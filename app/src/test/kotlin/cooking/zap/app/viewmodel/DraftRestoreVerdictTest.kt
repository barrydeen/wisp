package cooking.zap.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure gate for slow-path draft restore ([draftRestoreVerdict] / [draftRestoreSkipsBeforeDecrypt] /
 * [restoreFastPathShouldDrop]). The live path (raw relay collect, nip44Decrypt, prefs, registry
 * lookups) needs Android and is exercised on-device; the DECISIONS that stop a lagging relay's
 * older copy — or a deleted draft — from being restored are pure and pinned here.
 *
 * Note the deliberate restore-vs-list asymmetry these encode: restore consults the deletion
 * registry (never auto-load a deleted draft), whereas DraftsViewModel.loadDrafts intentionally
 * still lists kind-5-deleted drafts for iOS-Wisp parity.
 */
class DraftRestoreVerdictTest {

    // ---- pre-decrypt short-circuit (avoids the RemoteSigner IPC) ---------

    @Test
    fun `older copy than newest seen is stale, before decrypt`() {
        // A lagging relay sends created_at=100 after we already saw 150 for this coord.
        assertEquals(
            DraftRestoreVerdict.SkipStale,
            draftRestoreSkipsBeforeDecrypt(wrapperCreatedAt = 100, newestSeenForCoord = 150, registryDeletionTime = null)
        )
    }

    @Test
    fun `coord tombstoned at or after this copy is registry-deleted, before decrypt`() {
        assertEquals(
            DraftRestoreVerdict.RegistryDeleted,
            draftRestoreSkipsBeforeDecrypt(wrapperCreatedAt = 100, newestSeenForCoord = null, registryDeletionTime = 100)
        )
        // Permanent tombstone (local delete) always suppresses.
        assertEquals(
            DraftRestoreVerdict.RegistryDeleted,
            draftRestoreSkipsBeforeDecrypt(wrapperCreatedAt = 100, newestSeenForCoord = null, registryDeletionTime = Long.MAX_VALUE)
        )
    }

    @Test
    fun `copy newer than the deletion time needs a decrypt (not short-circuited)`() {
        // Deleted at 100, but this copy is 120 — a re-edit after the delete; must be evaluated.
        assertNull(
            draftRestoreSkipsBeforeDecrypt(wrapperCreatedAt = 120, newestSeenForCoord = null, registryDeletionTime = 100)
        )
    }

    @Test
    fun `first sight of a live coord needs a decrypt`() {
        assertNull(
            draftRestoreSkipsBeforeDecrypt(wrapperCreatedAt = 100, newestSeenForCoord = null, registryDeletionTime = null)
        )
    }

    // ---- full verdict (post-decrypt) -------------------------------------

    private fun verdict(
        ts: Long = 100,
        newestSeen: Long? = null,
        regTime: Long? = null,
        decryptedBlank: Boolean = false,
        contentBlank: Boolean = false,
        isReplyOrQuote: Boolean = false
    ) = draftRestoreVerdict(ts, newestSeen, regTime, decryptedBlank, contentBlank, isReplyOrQuote)

    @Test
    fun `empty decrypt is a tombstone`() {
        assertEquals(DraftRestoreVerdict.Tombstone, verdict(decryptedBlank = true))
    }

    @Test
    fun `blank content or reply-quote is unrestorable`() {
        assertEquals(DraftRestoreVerdict.SkipUnrestorable, verdict(contentBlank = true))
        assertEquals(DraftRestoreVerdict.SkipUnrestorable, verdict(isReplyOrQuote = true))
    }

    @Test
    fun `a fresh non-empty top-level draft is a candidate`() {
        assertEquals(DraftRestoreVerdict.Candidate, verdict())
    }

    @Test
    fun `newer empty copy of a coord tombstones over an older non-empty copy`() {
        // The lagging older non-empty copy (ts=100) would be a Candidate on its own...
        assertEquals(DraftRestoreVerdict.Candidate, verdict(ts = 100))
        // ...but once the newer empty copy (ts=150) advanced the marker, the old one is stale.
        assertEquals(DraftRestoreVerdict.SkipStale, verdict(ts = 100, newestSeen = 150))
    }

    @Test
    fun `pre-decrypt verdict wins even when decrypt info is supplied`() {
        // Registry-deleted takes precedence over what the (hypothetical) decrypt said.
        assertEquals(
            DraftRestoreVerdict.RegistryDeleted,
            verdict(ts = 100, regTime = 100, decryptedBlank = false, contentBlank = false)
        )
    }

    // ---- fast-path drop gate --------------------------------------------

    @Test
    fun `fast path drops only when a cached id has a registry tombstone`() {
        assertTrue(restoreFastPathShouldDrop(cachedId = "draft-abc", registryDeletionTime = 100L))
        assertTrue(restoreFastPathShouldDrop(cachedId = "draft-abc", registryDeletionTime = Long.MAX_VALUE))
        assertFalse(restoreFastPathShouldDrop(cachedId = "draft-abc", registryDeletionTime = null))
        assertFalse(restoreFastPathShouldDrop(cachedId = null, registryDeletionTime = 100L))
    }
}
