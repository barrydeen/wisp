package com.wisp.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.video.VideoFrameDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.wisp.app.relay.HttpClientFactory
import com.wisp.app.relay.TorManager
import okhttp3.Call

class WispApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        TorManager.initialize(this)
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
            .crossfade(true)
            .build()
    }
}
