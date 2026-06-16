package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ZapPreset(
    val amountSats: Long,
    val message: String = ""
)

class ZapPreferences(private val context: Context, pubkeyHex: String? = null) {
    private var prefs: SharedPreferences =
        context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
        .also { migrateFromGlobalIfNeeded(context, it, pubkeyHex) }

    fun reload(pubkeyHex: String?) {
        prefs = context.getSharedPreferences(prefsName(pubkeyHex), Context.MODE_PRIVATE)
            .also { migrateFromGlobalIfNeeded(context, it, pubkeyHex) }
    }

    companion object {
        private const val KEY_ZAP_PRESETS = "zap_presets"
        private const val KEY_MIGRATED_FROM_GLOBAL = "migrated_from_global_v1"

        /**
         * Before the per-account fix, the in-sheet "+" button wrote
         * presets to the un-scoped `zap_prefs` file. Copy them into the
         * per-account file on first read so users who saved presets
         * pre-fix don't see them disappear.
         */
        private fun migrateFromGlobalIfNeeded(
            context: Context,
            perAccount: SharedPreferences,
            pubkeyHex: String?
        ) {
            if (pubkeyHex == null) return
            if (perAccount.getBoolean(KEY_MIGRATED_FROM_GLOBAL, false)) return
            if (perAccount.contains(KEY_ZAP_PRESETS)) {
                perAccount.edit().putBoolean(KEY_MIGRATED_FROM_GLOBAL, true).apply()
                return
            }
            val global = context.getSharedPreferences("zap_prefs", Context.MODE_PRIVATE)
            val globalJson = global.getString(KEY_ZAP_PRESETS, null)
            val edit = perAccount.edit().putBoolean(KEY_MIGRATED_FROM_GLOBAL, true)
            if (globalJson != null) edit.putString(KEY_ZAP_PRESETS, globalJson)
            edit.apply()
        }

        val DEFAULT_PRESETS = listOf(
            ZapPreset(21),
            ZapPreset(100),
            ZapPreset(500),
            ZapPreset(1000),
            ZapPreset(5000)
        )

        private fun prefsName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "zap_prefs_$pubkeyHex" else "zap_prefs"
    }

    fun getPresets(): List<ZapPreset> {
        val json = prefs.getString(KEY_ZAP_PRESETS, null) ?: return DEFAULT_PRESETS
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ZapPreset(
                    amountSats = obj.getLong("amount"),
                    message = obj.optString("message", "")
                )
            }
        } catch (_: Exception) {
            DEFAULT_PRESETS
        }
    }

    fun setPresets(presets: List<ZapPreset>) {
        val arr = JSONArray()
        presets.forEach { preset ->
            val obj = JSONObject()
            obj.put("amount", preset.amountSats)
            if (preset.message.isNotEmpty()) obj.put("message", preset.message)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_ZAP_PRESETS, arr.toString()).apply()
    }

    fun addPreset(preset: ZapPreset): List<ZapPreset> {
        val current = getPresets()
        if (current.any { it.amountSats == preset.amountSats && it.message == preset.message }) return current
        val updated = (current + preset).sortedBy { it.amountSats }
        setPresets(updated)
        return updated
    }

    fun removePreset(preset: ZapPreset): List<ZapPreset> {
        val current = getPresets()
        val updated = current.filter { it.amountSats != preset.amountSats || it.message != preset.message }
        setPresets(updated)
        return updated
    }

}
