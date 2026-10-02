package com.wisp.app.viewmodel

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the draft-recovery rules a rejected / stopped publish applies (port
 * of wisp-ios PostPublisherDraftRecoveryTests): the composer's draft bucket is
 * refilled from the publish's snapshot, a reopen consumes it, and a later
 * success only clears the bucket while it still holds that same draft.
 */
class ComposeDraftStoreTest {

    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = defValue
        override fun getLong(key: String, defValue: Long): Long = defValue
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

            override fun putString(key: String, value: String?) = also { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = also { pending[key] = values }
            override fun putInt(key: String, value: Int) = also { pending[key] = value }
            override fun putLong(key: String, value: Long) = also { pending[key] = value }
            override fun putFloat(key: String, value: Float) = also { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = also { pending[key] = value }
            override fun remove(key: String) = also { removed += key }
            override fun clear() = also { }
            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                removed.forEach { values.remove(it) }
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
        }
    }

    private val key = "compose_draft_new_testpubkey"

    private fun store() = ComposeDraftStore(FakePrefs())

    private fun payload(content: String) = DraftPayload(
        content = content,
        explicit = false,
        powEnabled = false,
        mentions = listOf(DraftMention(0, 5, "aaaa")),
        attachments = listOf(DraftAttachment("https://example.com/a.jpg", "image/jpeg", 100, 200))
    )

    // --- Restore ---

    @Test
    fun `restore refills an empty bucket`() {
        val store = store()
        assertTrue(store.restoreIfAbsent(key, payload("the post that never landed")))
        assertEquals("the post that never landed", store.take(key)?.content)
    }

    @Test
    fun `take also restores mentions attachments and toggles`() {
        val store = store()
        store.restoreIfAbsent(key, payload("hello @aaaa"))

        val restored = store.take(key)!!
        assertEquals(1, restored.mentions.size)
        assertEquals(0, restored.mentions[0].start)
        assertEquals("aaaa", restored.mentions[0].pubkey)
        assertEquals(1, restored.attachments.size)
        assertEquals("https://example.com/a.jpg", restored.attachments[0].url)
        assertEquals(200, restored.attachments[0].height)
    }

    /**
     * The pill can sit on a failure long enough for the user to start typing
     * something else in the same composer slot. Restoring over that would trade
     * one lost draft for another — Retry still holds the publisher's own copy.
     */
    @Test
    fun `restore leaves a newer draft alone`() {
        val store = store()
        store.restoreIfAbsent(key, payload("something else entirely"))

        assertFalse(store.restoreIfAbsent(key, payload("old")))
        assertEquals("something else entirely", store.take(key)?.content)
    }

    /** Composers that never hand over a snapshot (private replies, draft-backed
     *  composers) pass a null payload and must not materialize a bucket. */
    @Test
    fun `restore is a no-op without a snapshot`() {
        val store = store()
        assertFalse(store.restoreIfAbsent(key, null))
        assertNull(store.take(key))
    }

    @Test
    fun `restore without a key is skipped`() {
        val store = store()
        assertFalse(store.restoreIfAbsent(null, payload("x")))
    }

    // --- Clear on success ---

    @Test
    fun `success clears the restored draft`() {
        val store = store()
        store.restoreIfAbsent(key, payload("retried and landed"))

        assertTrue(store.clearIfStillThisDraft(key, "retried and landed"))
        assertNull(store.take(key))
    }

    /** A retry that succeeds after the user has moved on must leave their newer
     *  draft in place. */
    @Test
    fun `success keeps a newer draft`() {
        val store = store()
        store.restoreIfAbsent(key, payload("a different post"))

        assertFalse(store.clearIfStillThisDraft(key, "old"))
        assertEquals("a different post", store.take(key)?.content)
    }

    /** The common path: the composer already took the bucket at reopen, so
     *  there is nothing left to clear. */
    @Test
    fun `success on an empty bucket is a no-op`() {
        val store = store()
        assertFalse(store.clearIfStillThisDraft(key, "x"))
    }

    @Test
    fun `success without a key is skipped`() {
        val store = store()
        assertFalse(store.clearIfStillThisDraft(null, "x"))
    }

    // --- Keys ---

    @Test
    fun `keys are per pubkey and per parent`() {
        val store = store()
        assertEquals("compose_draft_new_pk", store.keyFor("pk", null, null))
        assertEquals("compose_draft_reply_pk_parent", store.keyFor("pk", "parent", null))
        assertEquals("compose_draft_quote_pk_quoted", store.keyFor("pk", null, "quoted"))
        // A reply wins when both are set — it is the tighter slot.
        assertEquals("compose_draft_reply_pk_parent", store.keyFor("pk", "parent", "quoted"))
    }
}
