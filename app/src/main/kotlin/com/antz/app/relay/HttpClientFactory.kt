package com.antz.app.relay

import android.content.Context
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object HttpClientFactory {

    private val TOR_TIMEOUT_MULTIPLIER = 3L

    /**
     * DNS resolver that forces all hostname resolution through the SOCKS5 proxy.
     * When Tor is active, returns an unresolved InetAddress so OkHttp sends the
     * hostname through the SOCKS tunnel and the Tor exit node resolves DNS.
     * This prevents DNS leaks.
     */
    private val torSafeDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
        }
    }

    fun createRelayClient(): OkHttpClient {
        val isTor = TorManager.isEnabled()
        val connectTimeout = if (isTor) 30L else 10L

        // OkHttp's default Dispatcher.maxRequests is 64, which caps concurrent
        // WebSocket upgrade requests. With outbox routing creating 50+ ephemeral
        // connections, new user-initiated connections get queued and time out.
        val dispatcher = Dispatcher().apply {
            maxRequests = 256
            maxRequestsPerHost = 10
        }

        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // Strip permessage-deflate from the REQUEST so the server never negotiates
            // compression. The previous approach (network interceptor stripping the
            // response header) left the request header intact — servers that support
            // deflate would negotiate it, then send compressed frames to a client with
            // no inflater, causing ProtocolException and a reconnect loop.
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .removeHeader("Sec-WebSocket-Extensions")
                    .build()
                chain.proceed(request)
            }

        if (isTor) {
            TorManager.proxy?.let { builder.proxy(it) }
            builder.dns(torSafeDns)
        }

        return builder.build()
    }

    private var imageClient: OkHttpClient? = null
    private var imageClientBuiltWithTor: Boolean = false

    fun getImageClient(): OkHttpClient {
        val torNow = TorManager.isEnabled()
        val client = imageClient
        if (client != null && imageClientBuiltWithTor == torNow) return client
        return createHttpClient(
            connectTimeoutSeconds = 10,
            readTimeoutSeconds = 30
        ).also {
            imageClient = it
            imageClientBuiltWithTor = torNow
        }
    }

    fun createExoPlayer(context: Context): ExoPlayer {
        val client = createHttpClient(connectTimeoutSeconds = 10, readTimeoutSeconds = 30)
        val dataSourceFactory = OkHttpDataSource.Factory(client)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    fun safeShutdownClient(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        try {
            if (!client.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                client.dispatcher.executorService.shutdownNow()
            }
        } catch (_: InterruptedException) {
            client.dispatcher.executorService.shutdownNow()
        }
    }

    fun createHttpClient(
        connectTimeoutSeconds: Long = 10,
        readTimeoutSeconds: Long = 10,
        writeTimeoutSeconds: Long = 0,
        followRedirects: Boolean = true
    ): OkHttpClient {
        val isTor = TorManager.isEnabled()
        val multiplier = if (isTor) TOR_TIMEOUT_MULTIPLIER else 1L

        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds * multiplier, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds * multiplier, TimeUnit.SECONDS)
            .followRedirects(followRedirects)

        if (writeTimeoutSeconds > 0) {
            builder.writeTimeout(writeTimeoutSeconds * multiplier, TimeUnit.SECONDS)
        }

        if (isTor) {
            TorManager.proxy?.let { builder.proxy(it) }
            builder.dns(torSafeDns)
        }

        return builder.build()
    }
}
