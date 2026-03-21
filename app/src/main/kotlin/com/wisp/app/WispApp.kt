package com.wisp.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.wisp.app.db.WispObjectBox
import com.wisp.app.relay.HttpClientFactory
import com.wisp.app.relay.TorManager
import com.wisp.app.repo.DiagnosticLogger
import com.wisp.app.repo.ZapSender
import okhttp3.Call

class WispApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        DiagnosticLogger.init(this)
        WispObjectBox.init(this)
        TorManager.initialize(this)
        ZapSender.init(this)
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        val torAwareCallFactory = Call.Factory { request ->
            HttpClientFactory.getImageClient().newCall(request)
        }
        return ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
                add(OkHttpNetworkFetcherFactory(callFactory = { torAwareCallFactory }))
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
