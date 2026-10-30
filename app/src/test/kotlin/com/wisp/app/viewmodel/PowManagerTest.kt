package com.wisp.app.viewmodel

import android.content.SharedPreferences
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.RelayListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Failed / Stopped PoW pill outlives an account switch, so [PowManager.retry]
 * must refuse a retained note whose signer no longer matches the signed-in
 * account — publishing it would go out as the previous account.
 */
class PowManagerTest {

    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
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
            override fun putString(key: String, value: String?) = also { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = also { pending[key] = values }
            override fun putInt(key: String, value: Int) = also { pending[key] = value }
            override fun putLong(key: String, value: Long) = also { pending[key] = value }
            override fun putFloat(key: String, value: Float) = also { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = also { pending[key] = value }
            override fun remove(key: String) = also { pending[key] = null }
            override fun clear() = also { pending.clear() }
            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
        }
    }

    /** Signing is unavailable in tests: every attempt counts, then fails. */
    private class FakeSigner(override val pubkeyHex: String) : NostrSigner {
        val signAttempts = AtomicInteger(0)
        override suspend fun signEvent(
            kind: Int,
            content: String,
            tags: List<List<String>>,
            createdAt: Long
        ): NostrEvent {
            signAttempts.incrementAndGet()
            throw IllegalStateException("no signing in test")
        }

        override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String =
            throw UnsupportedOperationException()

        override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String =
            throw UnsupportedOperationException()
    }

    private fun manager(currentPubkey: () -> String?): PowManager =
        PowManager(
            noteDifficulty = { 0 },
            relayPool = RelayPool(),
            outboxRouter = OutboxRouter(RelayPool(), RelayListRepository(FakePrefs())),
            eventRepo = EventRepository(),
            draftStore = ComposeDraftStore(FakePrefs()),
            scope = CoroutineScope(Dispatchers.Unconfined),
            currentPubkey = currentPubkey
        )

    private fun awaitStatus(manager: PowManager, timeoutMs: Long = 5000, pred: (PowStatus) -> Boolean) {
        val start = System.currentTimeMillis()
        while (!pred(manager.status.value)) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("timed out waiting for status, last=${manager.status.value}")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun `retry drops the retained note after an account switch`() {
        val signerA = FakeSigner("a".repeat(64))
        var currentPubkey: String? = signerA.pubkeyHex
        val manager = manager { currentPubkey }

        manager.submitNote(
            signer = signerA,
            content = "hello",
            tags = emptyList(),
            draftRestoreKey = "bucket",
            draftRestorePayload = DraftPayload(content = "hello", explicit = false, powEnabled = false)
        )
        awaitStatus(manager) { it is PowStatus.Failed }
        assertEquals(1, signerA.signAttempts.get())

        currentPubkey = "b".repeat(64)
        manager.retry()

        assertEquals(PowStatus.Idle, manager.status.value)
        assertEquals(1, signerA.signAttempts.get())
        Thread.sleep(200) // an unguarded retry would re-mine and fail again
        assertEquals(PowStatus.Idle, manager.status.value)
    }

    @Test
    fun `retry drops the retained note when no account is signed in`() {
        val signerA = FakeSigner("a".repeat(64))
        var currentPubkey: String? = signerA.pubkeyHex
        val manager = manager { currentPubkey }

        manager.submitNote(signer = signerA, content = "hello", tags = emptyList())
        awaitStatus(manager) { it is PowStatus.Failed }

        currentPubkey = null
        manager.retry()

        assertEquals(PowStatus.Idle, manager.status.value)
        assertEquals(1, signerA.signAttempts.get())
    }

    @Test
    fun `retry still re-runs the note for the same account`() {
        val signerA = FakeSigner("a".repeat(64))
        val manager = manager { signerA.pubkeyHex }

        manager.submitNote(signer = signerA, content = "hello", tags = emptyList())
        awaitStatus(manager) { it is PowStatus.Failed }
        assertEquals(1, signerA.signAttempts.get())

        manager.retry()
        awaitStatus(manager) { signerA.signAttempts.get() >= 2 }
        assertTrue(manager.status.value is PowStatus.Failed)
    }
}
