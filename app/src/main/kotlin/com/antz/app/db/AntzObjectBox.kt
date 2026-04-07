package com.antz.app.db

import android.content.Context
import io.objectbox.BoxStore

object AntzObjectBox {
    lateinit var store: BoxStore
        private set

    val isInitialized: Boolean
        get() = ::store.isInitialized

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = MyObjectBox.builder()
            .androidContext(context.applicationContext)
            .build()
    }
}
