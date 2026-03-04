package com.wisp.app.relay

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
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                response.newBuilder()
                    .removeHeader("Sec-WebSocket-Extensions")
                    .build()
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
