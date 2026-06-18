package cooking.zap.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import cooking.zap.app.db.WispObjectBox
import cooking.zap.app.relay.HttpClientFactory
import cooking.zap.app.repo.DiagnosticLogger
import cooking.zap.app.repo.ExchangeRateRepository
import cooking.zap.app.repo.ZapSender

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
        return ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
                add(OkHttpNetworkFetcherFactory(callFactory = { HttpClientFactory.getImageClient() }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.15)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
