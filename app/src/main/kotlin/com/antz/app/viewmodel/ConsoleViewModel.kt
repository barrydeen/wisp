package com.antz.app.viewmodel

import androidx.lifecycle.ViewModel
import com.antz.app.relay.ConsoleLogEntry
import com.antz.app.relay.RelayPool
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
