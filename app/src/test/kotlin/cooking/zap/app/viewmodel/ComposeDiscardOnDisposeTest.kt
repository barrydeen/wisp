package cooking.zap.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure gate for the composer's discard-on-dispose decision ([shouldDiscardOnDispose]). This is
 * the crux of the phantom-draft fix's PR 2: emptying a restored draft and leaving must discard it,
 * but the trigger has to be narrow — a blank reply composer or an un-restored composer must never
 * wipe the cache. The live path (LastDraftCache prefs + relay replacement) needs Android and is
 * exercised on-device; the DECISION is pure and tested here.
 */
class ComposeDiscardOnDisposeTest {

    @Test
    fun `emptied restored top-level draft discards`() {
        // User cleared the text they could see; the editor was showing the cached draft.
        assertTrue(
            shouldDiscardOnDispose(
                isTopLevel = true,
                currentDraftId = "draft-abc",
                cachedId = "draft-abc",
                textIsBlank = true
            )
        )
    }

    @Test
    fun `blank reply composer does not discard cached top-level draft`() {
        // A reply composer may sit over an unrelated cached top-level draft the user never opened;
        // leaving it blank must not destroy that draft.
        assertFalse(
            shouldDiscardOnDispose(
                isTopLevel = false,
                currentDraftId = "draft-abc",
                cachedId = "draft-abc",
                textIsBlank = true
            )
        )
    }

    @Test
    fun `blank top-level composer with nothing restored does not discard`() {
        // Nothing was restored this session (currentDraftId null) — an empty composer the user
        // opened and closed must leave any cached draft alone.
        assertFalse(
            shouldDiscardOnDispose(
                isTopLevel = true,
                currentDraftId = null,
                cachedId = "draft-abc",
                textIsBlank = true
            )
        )
    }

    @Test
    fun `restored draft id not matching cache does not discard`() {
        // The editor isn't showing the cached draft (ids diverged) — don't clear the cache.
        assertFalse(
            shouldDiscardOnDispose(
                isTopLevel = true,
                currentDraftId = "draft-abc",
                cachedId = "draft-XYZ",
                textIsBlank = true
            )
        )
    }

    @Test
    fun `non-blank text does not discard`() {
        // The user still has content; the non-blank auto-save path owns this, not discard.
        assertFalse(
            shouldDiscardOnDispose(
                isTopLevel = true,
                currentDraftId = "draft-abc",
                cachedId = "draft-abc",
                textIsBlank = false
            )
        )
    }
}
