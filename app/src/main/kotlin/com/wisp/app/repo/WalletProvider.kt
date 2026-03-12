package com.wisp.app.repo

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface WalletProvider {
    val balance: StateFlow<Long?>
    val isConnected: StateFlow<Boolean>
    val statusLog: SharedFlow<String>

    /** Emits the amount in msats whenever an incoming payment is received. */
    val paymentReceived: SharedFlow<Long>

    fun hasConnection(): Boolean
    fun connect()
    fun disconnect()
    suspend fun fetchBalance(): Result<Long>
    suspend fun payInvoice(bolt11: String): Result<String>
    suspend fun makeInvoice(amountMsats: Long, description: String): Result<String>
    suspend fun listTransactions(limit: Int = 50): Result<List<WalletTransaction>>
}

data class WalletTransaction(
    val type: String,
    val description: String?,
    val paymentHash: String,
    val amountMsats: Long,
    val createdAt: Long,
    val settledAt: Long?
)
