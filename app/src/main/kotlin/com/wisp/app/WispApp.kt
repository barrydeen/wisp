package com.wisp.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ImageResult
import coil3.video.VideoFrameDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.wisp.app.db.WispObjectBox
import com.wisp.app.nostr.BlossomUri
import com.wisp.app.relay.HttpClientFactory
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.BlossomUriResolver
import com.wisp.app.repo.DiagnosticLogger
import com.wisp.app.repo.ExchangeRateRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.ZapSender
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class WispApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        DiagnosticLogger.init(this)
        WispObjectBox.init(this)
        ZapSender.init(this)
        ExchangeRateRepository.init(this)
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        // Resolves BUD-10 `blossom:` URIs before the network fetcher sees the
        // request. One KeyRepository, read per resolution, so the account
        // switcher is picked up without rebuilding the loader.
        val keyRepo = KeyRepository(context)
        val resolver = BlossomUriResolver(
            httpClient = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                // GET-and-verify downloads whole blobs (hash-checked before
                // use), so the per-attempt budget has room for a multi-MB
                // image on a slow server.
                .callTimeout(20, TimeUnit.SECONDS)
                .build(),
            accountServers = {
                BlossomRepository.readServersFromPrefs(context, keyRepo.getPubkeyHex())
            },
        )
        return ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
                add(OkHttpNetworkFetcherFactory(callFactory = { HttpClientFactory.getImageClient() }))
                add(BlossomUriInterceptor(resolver))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.15)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    /** Rewrites `blossom:` model strings to their resolved https URL; every other request passes through. */
    private class BlossomUriInterceptor(private val resolver: BlossomUriResolver) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val data = chain.request.data as? String
            if (data == null || !data.startsWith(BlossomUri.SCHEME, ignoreCase = true)) {
                return chain.proceed()
            }
            val resolved = resolver.resolve(data)
                // No server answered — proceed and let the fetch fail as a normal image error.
                ?: return chain.proceed()
            return chain.withRequest(chain.request.newBuilder().data(resolved).build()).proceed()
        }
    }
}
