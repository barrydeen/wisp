package com.wisp.app.repo

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PersistedMetadataCacheTest {
    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) { }
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) { }

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var cleared = false

            override fun putString(key: String, value: String?) = also { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = also { pending[key] = values }
            override fun putInt(key: String, value: Int) = also { pending[key] = value }
            override fun putLong(key: String, value: Long) = also { pending[key] = value }
            override fun putFloat(key: String, value: Float) = also { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = also { pending[key] = value }
            override fun remove(key: String) = also { removed += key }
            override fun clear() = also { cleared = true }
            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (cleared) values.clear()
                removed.forEach { values.remove(it) }
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
        }
    }

    private fun newCache(prefs: SharedPreferences, capacity: Int = 2000) = PersistedMetadataCache<String>(
        prefs, "m_", { it }, { it }, { }, capacity
    )

    @Test
    fun flushCommitsDirtyRecordsToPrefs() = runBlocking {
        val prefs = FakePrefs()
        val cache = newCache(prefs)
        assertTrue(cache.update("a", "value-a", 1L))
        assertEquals("value-a", cache.get("a"))
        withTimeout(2_000) { cache.flush() }
        assertEquals("value-a", prefs.values["m_a"])
        assertEquals(1L, prefs.values["m_ts_a"])
        cache.shutdown()
    }

    @Test
    fun staleTimestampsAreRejected() = runBlocking {
        val prefs = FakePrefs()
        val cache = newCache(prefs)
        assertTrue(cache.update("a", "v1", 10))
        assertFalse(cache.update("a", "v2", 10))
        assertFalse(cache.update("a", "v3", 9))
        assertTrue(cache.update("a", "v4", 11))
        assertEquals("v4", cache.get("a"))
        cache.shutdown()
    }

    @Test
    fun valuesSurviveMemoryEvictionAfterFlush() = runBlocking {
        val prefs = FakePrefs()
        val cache = newCache(prefs, capacity = 2)
        cache.update("a", "v-a", 1)
        cache.update("b", "v-b", 1)
        cache.update("c", "v-c", 1)
        withTimeout(2_000) { cache.flush() }
        cache.clearMemory()
        assertEquals("v-a", cache.get("a"))
        cache.shutdown()
    }

    @Test
    fun shutdownDrainsPendingWrites() = runBlocking {
        val prefs = FakePrefs()
        val cache = newCache(prefs)
        cache.update("a", "value-a", 5)
        withTimeout(2_000) { cache.shutdown() }
        assertEquals("value-a", prefs.values["m_a"])
        assertEquals(5L, prefs.values["m_ts_a"])
    }

    @Test
    fun updateAfterCloseIsRejected() = runBlocking {
        val prefs = FakePrefs()
        val cache = newCache(prefs)
        withTimeout(2_000) { cache.shutdown() }
        val thrown = assertThrows(IllegalStateException::class.java) { cache.update("a", "v", 1) }
        assertEquals("Metadata cache is closed", thrown.message)
    }
}
