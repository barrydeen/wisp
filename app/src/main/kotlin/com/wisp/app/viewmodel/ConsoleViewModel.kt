package com.wisp.app.viewmodel

import androidx.lifecycle.ViewModel
import com.wisp.app.relay.ConsoleLogEntry
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ConsoleViewModel : ViewModel() {
    private var relayPool: RelayPool? = null

    private val empty = MutableStateFlow<List<ConsoleLogEntry>>(emptyList())

    val consoleLog: StateFlow<List<ConsoleLogEntry>>
        get() = relayPool?.consoleLog ?: empty

    fun init(relayPool: RelayPool) {
        this.relayPool = relayPool
    }

    fun clear() {
        relayPool?.clearConsoleLog()
    }
}
