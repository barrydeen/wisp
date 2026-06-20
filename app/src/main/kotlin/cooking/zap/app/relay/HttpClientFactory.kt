package cooking.zap.app.relay

import android.content.Context
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientFactory {
    @Volatile private var imageClient: OkHttpClient? = null
    @Volatile private var generalClient: OkHttpClient? = null
    @Volatile private var shortTimeoutClient: OkHttpClient? = null
    @Volatile private var mediaClient: OkHttpClient? = null
    @Volatile private var nip05Client: OkHttpClient? = null
    @Volatile private var downloadClient: OkHttpClient? = null

    fun createRelayClient(): OkHttpClient {
        // OkHttp's default Dispatcher.maxRequests is 64, which caps concurrent
        // WebSocket upgrade requests. With outbox routing creating 50+ ephemeral
        // connections, new user-initiated connections get queued and time out.
        val dispatcher = Dispatcher().apply {
            maxRequests = 256
            maxRequestsPerHost = 10
        }

        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(10, TimeUnit.SECONDS)
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
            .build()
    }

    fun getImageClient(): OkHttpClient {
        imageClient?.let { return it }
        return synchronized(this) {
            imageClient ?: createHttpClient(
                connectTimeoutSeconds = 10,
                readTimeoutSeconds = 30
            ).also { imageClient = it }
        }
    }

    fun getGeneralClient(): OkHttpClient {
        generalClient?.let { return it }
        return synchronized(this) {
            generalClient ?: createHttpClient(
                connectTimeoutSeconds = 10,
                readTimeoutSeconds = 15
            ).also { generalClient = it }
        }
    }

    fun getShortTimeoutClient(): OkHttpClient {
        shortTimeoutClient?.let { return it }
        return synchronized(this) {
            shortTimeoutClient ?: createHttpClient(
                connectTimeoutSeconds = 5,
                readTimeoutSeconds = 5
            ).also { shortTimeoutClient = it }
        }
    }

    // NIP-05 verification fans out to many distinct hosts; failing fast on
    // unreachable .well-known/nostr.json endpoints keeps the badge responsive.
    fun getNip05Client(): OkHttpClient {
        nip05Client?.let { return it }
        return synchronized(this) {
            nip05Client ?: createHttpClient(
                connectTimeoutSeconds = 5,
                readTimeoutSeconds = 10
            ).also { nip05Client = it }
        }
    }

    fun getMediaClient(): OkHttpClient {
        mediaClient?.let { return it }
        return synchronized(this) {
            mediaClient ?: createHttpClient(
                connectTimeoutSeconds = 10,
                readTimeoutSeconds = 30
            ).also { mediaClient = it }
        }
    }

    // Full-file downloads need longer read timeouts than streaming —
    // a stalled chunk on a flaky connection shouldn't kill the download.
    fun getDownloadClient(): OkHttpClient {
        downloadClient?.let { return it }
        return synchronized(this) {
            downloadClient ?: createHttpClient(
                connectTimeoutSeconds = 30,
                readTimeoutSeconds = 60
            ).also { downloadClient = it }
        }
    }

    // Server-side AI compute (e.g. POST /api/nourish) runs an LLM over 8
    // dimensions AND awaits a pantry publish — routinely 20–60s. The general
    // client's 15s read timeout would abort mid-compute and surface a bogus
    // error while the server is still working.
    @Volatile private var computeClient: OkHttpClient? = null
    fun getComputeClient(): OkHttpClient {
        computeClient?.let { return it }
        return synchronized(this) {
            computeClient ?: createHttpClient(
                connectTimeoutSeconds = 10,
                readTimeoutSeconds = 75
            ).also { computeClient = it }
        }
    }

    fun createExoPlayer(context: Context): ExoPlayer {
        val client = getMediaClient()
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
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(followRedirects)

        if (writeTimeoutSeconds > 0) {
            builder.writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        }

        return builder.build()
    }
}
