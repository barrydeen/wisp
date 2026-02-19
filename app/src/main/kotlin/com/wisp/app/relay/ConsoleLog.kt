package com.wisp.app.relay

data class ConsoleLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val relayUrl: String,
    val type: ConsoleLogType,
    val message: String
)

enum class ConsoleLogType { OK_REJECTED, NOTICE, CONN_FAILURE, CONN_CLOSED }
