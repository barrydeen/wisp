package cooking.zap.app.cheffy

import cooking.zap.app.api.NoteReviewResult
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.hexToByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NoteReview.phaseForResult] — the pure core of the modal's state
 * machine, mirroring the web `noteReview.ts` `phaseForResult` tests and
 * pinning the copy pools verbatim.
 */
class NoteReviewTest {

    @Test
    fun deadEndLinesAreTheWebPoolVerbatim() {
        assertEquals(
            listOf(
                "Cheffy couldn't get a good look at that photo. It might be a broken link — or just not clearly a dish.",
                "That photo's playing hard to get — Cheffy can't quite make out a dish in it.",
                "Cheffy squinted, but couldn't spot a dish in there. The photo may not have come through.",
                "Hmm — Cheffy couldn't see this one clearly. The link may be stale, or the dish is camera-shy.",
            ),
            NoteReview.DEAD_END_LINES,
        )
    }

    @Test
    fun success_mapsToDraft() {
        val next = NoteReview.phaseForResult(NoteReviewResult.Success("A draft", 2))
        assertEquals(NoteReview.Phase.DRAFT, next.phase)
        assertEquals("", next.message)
    }

    @Test
    fun notMember_mapsToUpsell() {
        assertEquals(
            NoteReview.PhaseAndMessage(NoteReview.Phase.UPSELL, ""),
            NoteReview.phaseForResult(NoteReviewResult.NotMember),
        )
    }

    @Test
    fun deadEnd_mapsToDeadEnd_withALineFromThePool() {
        val next = NoteReview.phaseForResult(NoteReviewResult.DeadEnd)
        assertEquals(NoteReview.Phase.DEAD_END, next.phase)
        assertTrue(next.message in NoteReview.DEAD_END_LINES)
    }

    @Test
    fun deadEnd_rotatesAwayFromThePreviousLine() {
        // pickLine guarantees the avoided line is never returned for
        // pools with more than one entry — try every line as "previous".
        for (previous in NoteReview.DEAD_END_LINES) {
            repeat(20) {
                val next = NoteReview.phaseForResult(NoteReviewResult.DeadEnd, avoidDeadEndLine = previous)
                assertNotEquals(previous, next.message)
                assertTrue(next.message in NoteReview.DEAD_END_LINES)
            }
        }
    }

    @Test
    fun signFailed_mapsToError_withTheSignFailedLine() {
        assertEquals(
            NoteReview.PhaseAndMessage(NoteReview.Phase.ERROR, NoteReview.SIGN_FAILED_LINE),
            NoteReview.phaseForResult(NoteReviewResult.SignFailed),
        )
    }

    @Test
    fun membershipUnavailable_isRetryableError_neverUpsell() {
        // Deviation D5: the endpoint fails closed on a membership-service
        // outage — an upsell here would dun a member over our outage.
        val next = NoteReview.phaseForResult(NoteReviewResult.MembershipUnavailable)
        assertEquals(NoteReview.Phase.ERROR, next.phase)
        assertNotEquals(NoteReview.Phase.UPSELL, next.phase)
        assertEquals(NoteReview.MEMBERSHIP_UNAVAILABLE_LINE, next.message)
    }

    @Test
    fun rateLimited_mapsToError_withTheBreatherLine() {
        assertEquals(
            NoteReview.PhaseAndMessage(NoteReview.Phase.ERROR, NoteReview.RATE_LIMITED_LINE),
            NoteReview.phaseForResult(NoteReviewResult.RateLimited(1800)),
        )
    }

    @Test
    fun error_mapsToError_passingTheMessageThrough() {
        assertEquals(
            NoteReview.PhaseAndMessage(NoteReview.Phase.ERROR, "Network error"),
            NoteReview.phaseForResult(NoteReviewResult.Error("Network error")),
        )
    }

    @Test
    fun blankErrorMessage_fallsBackToTheGenericLine() {
        assertEquals(
            NoteReview.PhaseAndMessage(NoteReview.Phase.ERROR, NoteReview.GENERIC_ERROR_LINE),
            NoteReview.phaseForResult(NoteReviewResult.Error("  ")),
        )
    }

    // --- Phase 3: publish ---

    @Test
    fun canPost_isTrueOnlyForDraft() {
        for (phase in NoteReview.Phase.entries) {
            assertEquals(phase == NoteReview.Phase.DRAFT, NoteReview.canPost(phase))
        }
    }

    @Test
    fun publishLinesAreTheWebCopyVerbatim() {
        assertEquals(
            "The relays are taking their time. Your reply is signed and may already be out there — give it another push, and Cheffy won't ask your signer twice.",
            NoteReview.POST_TIMEOUT_LINE,
        )
        assertEquals(
            "The relays didn't take that one. Your draft is safe — give it another go.",
            NoteReview.PUBLISH_FAILED_LINE,
        )
    }

    @Test
    fun noteLinkFor_encodesNeventWithAuthorAndKindHints() {
        val event = NostrEvent(
            id = "aa".repeat(32),
            pubkey = "bb".repeat(32),
            created_at = 1_700_000_000L,
            kind = 1,
            tags = emptyList(),
            content = "reply",
            sig = "0".repeat(128),
        )
        val link = NoteReview.noteLinkFor(event)
        assertTrue(link.startsWith("nevent1"))

        // Roundtrip: id + author survive (the decoder skips the kind TLV).
        val decoded = Nip19.neventDecode(link)
        assertEquals(event.id, decoded.eventId)
        assertEquals(event.pubkey, decoded.author)

        // The kind hint is really in there: dropping it changes the encoding.
        val withoutKind = Nip19.neventEncode(
            eventId = event.id.hexToByteArray(),
            author = event.pubkey.hexToByteArray(),
        )
        assertNotEquals(withoutKind, link)
    }

    // --- Phase 4: disclosure footer ---

    @Test
    fun disclosureFooterStringIsTheProductSpecVerbatim() {
        assertEquals("⚡🍳 via Cheffy · zap.cooking", NoteReview.DISCLOSURE_FOOTER)
    }

    @Test
    fun withDisclosureFooter_off_returnsTheDraftUntouched() {
        assertEquals("my words", NoteReview.withDisclosureFooter("my words", on = false))
        // Even trailing whitespace stays the member's own when off.
        assertEquals("my words  \n", NoteReview.withDisclosureFooter("my words  \n", on = false))
    }

    @Test
    fun withDisclosureFooter_on_appendsAfterExactlyOneBlankLine() {
        assertEquals(
            "Lovely crust!\n\n⚡🍳 via Cheffy · zap.cooking",
            NoteReview.withDisclosureFooter("Lovely crust!", on = true),
        )
    }

    @Test
    fun withDisclosureFooter_normalizesTrailingWhitespaceSoTheFooterNeverDrifts() {
        // Mixed trailing whitespace/newlines collapse to the single blank
        // line — the footer never sits more than one blank line away.
        assertEquals(
            "draft words\n\n⚡🍳 via Cheffy · zap.cooking",
            NoteReview.withDisclosureFooter("draft words \n\n\t ", on = true),
        )
    }

    @Test
    fun disclosureDefaultsArePerMode_commentOff_recipeOn() {
        assertEquals(false, NoteReview.defaultDisclosure(cooking.zap.app.api.NoteReviewMode.COMMENT))
        assertEquals(true, NoteReview.defaultDisclosure(cooking.zap.app.api.NoteReviewMode.RECIPE))
    }

    @Test
    fun disclosureSeedsFromThePrefOnlyInChoose() {
        for (phase in NoteReview.Phase.entries) {
            assertEquals(
                phase == NoteReview.Phase.CHOOSE,
                NoteReview.shouldSeedDisclosureFromPref(phase),
            )
        }
    }

    @Test
    fun noteLinkFor_fallsBackToNote1WhenTheNeventEncodingFails() {
        // Odd-length pubkey hex makes the author hint unencodable; the id
        // is still valid, so the fallback is a bare note1 (web parity).
        val event = NostrEvent(
            id = "aa".repeat(32),
            pubkey = "abc",
            created_at = 1_700_000_000L,
            kind = 1,
            tags = emptyList(),
            content = "reply",
            sig = "0".repeat(128),
        )
        val link = NoteReview.noteLinkFor(event)
        assertTrue(link.startsWith("note1"))
        assertEquals(event.id, Nip19.noteDecode(link))
    }
}
