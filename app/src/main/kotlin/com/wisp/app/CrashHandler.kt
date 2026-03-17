package com.wisp.app

import android.content.Context
import android.os.Build
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Nip17
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.KeyRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {

    const val DEVELOPER_PUBKEY = "e2ccf7cf20403f3f2a4a55b328f0de3be38558a7d5f33632fdaaefc726c1c8eb"
    private const val CRASH_LOG_FILE = "crash_log.txt"

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrashLog(context, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val version = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                "${pInfo.versionName} (${pInfo.longVersionCode})"
            } catch (_: Exception) {
                "unknown"
            }

            val log = buildString {
                appendLine("Wisp Crash Report")
                appendLine("=================")
                appendLine("Time: $timestamp")
                appendLine("Version: $version")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.SDK_INT}")
                appendLine()
                appendLine("Stack trace:")
                appendLine(throwable.stackTraceToString())
            }

            File(context.filesDir, CRASH_LOG_FILE).writeText(log)
        } catch (_: Exception) {
            // Best-effort — don't crash the crash handler
        }
    }

    fun hasCrashLog(context: Context): Boolean =
        File(context.filesDir, CRASH_LOG_FILE).exists()

    fun getCrashLog(context: Context): String =
        File(context.filesDir, CRASH_LOG_FILE).readText()

    fun clearCrashLog(context: Context) {
        File(context.filesDir, CRASH_LOG_FILE).delete()
    }

    private val DEVELOPER_DM_RELAYS = listOf(
        "wss://relay.0xchat.com",
        "wss://relay.utxo.one/chat",
        "wss://relay.damus.io"
    )

    suspend fun sendCrashDm(keyRepo: KeyRepository, relayPool: RelayPool, crashLog: String) {
        val keypair = keyRepo.getKeypair() ?: return

        val recipientPubkey = DEVELOPER_PUBKEY.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val wrap = Nip17.createGiftWrap(
            senderPrivkey = keypair.privkey,
            senderPubkey = keypair.pubkey,
            recipientPubkey = recipientPubkey,
            message = crashLog
        )
        val msg = ClientMessage.event(wrap)

        for (url in DEVELOPER_DM_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, msg)
        }
    }
}
