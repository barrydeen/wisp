package com.antz.app.repo

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zero-cost diagnostic file logger. All hot-path calls check [isEnabled] first —
 * when disabled, no string formatting, object allocation, or disk I/O occurs.
 *
 * Activated by long-pressing the version string in Interface settings 5 times.
 * Logs are written to app-internal storage and can be shared via ACTION_SEND.
 */
object DiagnosticLogger {
    @Volatile var isEnabled: Boolean = false

    private var writer: BufferedWriter? = null
    private val lock = Any()
    private const val MAX_SIZE = 512 * 1024L  // 512KB
    private const val LOG_FILE_NAME = "antz_diagnostic.log"
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        isEnabled = context.getSharedPreferences("antz_settings", Context.MODE_PRIVATE)
            .getBoolean("diagnostic_mode", false)
        if (isEnabled) openWriter(context)
    }

    fun log(tag: String, msg: String) {
        if (!isEnabled) return
        synchronized(lock) {
            try {
                writer?.appendLine("[${dateFormat.format(Date())}] $tag: $msg")
                writer?.flush()
            } catch (e: Exception) {
                Log.w("DiagLogger", "Write failed: ${e.message}")
            }
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("antz_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("diagnostic_mode", enabled).apply()
        isEnabled = enabled
        if (enabled) {
            openWriter(context)
            log("DIAG", "Diagnostic logging enabled")
        } else {
            synchronized(lock) {
                try { writer?.close() } catch (_: Exception) {}
                writer = null
            }
        }
    }

    fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILE_NAME)
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try { writer?.close() } catch (_: Exception) {}
            writer = null
            getLogFile(context).delete()
            if (isEnabled) openWriter(context)
        }
    }

    private fun openWriter(context: Context) {
        synchronized(lock) {
            try {
                val file = getLogFile(context)
                // Rotate if over max size
                if (file.exists() && file.length() > MAX_SIZE) {
                    val backup = File(context.filesDir, "$LOG_FILE_NAME.old")
                    file.renameTo(backup)
                }
                writer = BufferedWriter(FileWriter(getLogFile(context), true))
            } catch (e: Exception) {
                Log.w("DiagLogger", "Failed to open log file: ${e.message}")
            }
        }
    }
}
