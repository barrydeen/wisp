package cooking.zap.app.repo

import cooking.zap.app.repo.LastDraftCache.Companion.matchesCachedDraft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure gate for the clear-on-delete decision in [LastDraftCache] (the live prefs store needs an
 * Android Context and is exercised on-device). This predicate is the crux of the phantom-draft
 * fix: deleting a draft from the Drafts screen must drop the compose fast-path cache — but ONLY
 * when the cache points at that exact draft, or an unrelated draft/account gets clobbered.
 */
class LastDraftCacheTest {

    @Test
    fun `matching cached id clears`() {
        assertTrue(matchesCachedDraft(cachedId = "draft-abc", dTag = "draft-abc"))
    }

    @Test
    fun `different cached id does not clear`() {
        // Deleting draft B must leave draft A (the one the composer would continue) cached.
        assertFalse(matchesCachedDraft(cachedId = "draft-A", dTag = "draft-B"))
    }

    @Test
    fun `null cached id never clears`() {
        // Nothing cached (or a foreign-account entry this pubkey lookup didn't find) — a delete
        // must be a no-op, never a blanket wipe.
        assertFalse(matchesCachedDraft(cachedId = null, dTag = "draft-abc"))
    }
}
