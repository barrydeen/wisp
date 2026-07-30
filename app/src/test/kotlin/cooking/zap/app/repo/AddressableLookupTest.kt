package cooking.zap.app.repo

import cooking.zap.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [newestAddressable] — the coordinate lookup behind
 * `EventRepository.findAddressableEvent`.
 *
 * The case that matters is a cache holding **two versions of one coordinate**.
 * That could not happen before recipe edit: the event cache is keyed by event
 * id, so an edit republishing the same address under a new id leaves both
 * versions in it. Picking the first match would then be picking an arbitrary
 * version — which the member reads as "my edit didn't save", because their own
 * recipe grid already applies newest-wins and would show the new one.
 */
class AddressableLookupTest {

    private val author = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun event(
        id: String,
        createdAt: Long,
        dTag: String = "ragu",
        pubkey: String = author,
        kind: Int = 30023,
    ) = NostrEvent(
        id = id.padEnd(64, '0'),
        pubkey = pubkey,
        created_at = createdAt,
        kind = kind,
        tags = listOf(listOf("d", dTag), listOf("title", "Ragu")),
        content = "",
        sig = "0".repeat(128),
    )

    @Test
    fun picksTheNewestVersionOfACoordinate() {
        val old = event("1", 1_700_000_000L)
        val new = event("2", 1_700_000_100L)
        // Both orderings: the cache's iteration order is not something we control.
        assertEquals(new, newestAddressable(listOf(old, new), 30023, author, "ragu"))
        assertEquals(new, newestAddressable(listOf(new, old), 30023, author, "ragu"))
    }

    @Test
    fun picksTheNewestAcrossThreeVersions() {
        val a = event("1", 1_700_000_000L)
        val b = event("2", 1_700_000_300L)
        val c = event("3", 1_700_000_100L)
        assertEquals(b, newestAddressable(listOf(a, b, c), 30023, author, "ragu"))
    }

    @Test
    fun breaksACreatedAtTieOnTheLowerId() {
        // NIP-01's tiebreak, and the same one dedupeAcrossFormats uses — so two
        // readers can never disagree about a tie.
        val lower = event("1", 1_700_000_000L)
        val higher = event("9", 1_700_000_000L)
        assertEquals(lower, newestAddressable(listOf(higher, lower), 30023, author, "ragu"))
        assertEquals(lower, newestAddressable(listOf(lower, higher), 30023, author, "ragu"))
    }

    @Test
    fun ignoresOtherCoordinates() {
        val target = event("1", 1_700_000_000L)
        val otherD = event("2", 1_800_000_000L, dTag = "carbonara")
        val otherAuthor = event("3", 1_800_000_000L, pubkey = other)
        val otherKind = event("4", 1_800_000_000L, kind = 30818)
        assertEquals(
            target,
            newestAddressable(listOf(otherD, otherAuthor, otherKind, target), 30023, author, "ragu"),
        )
    }

    @Test
    fun returnsNullWhenNothingMatches() {
        assertNull(newestAddressable(listOf(event("1", 1L, dTag = "x")), 30023, author, "ragu"))
        assertNull(newestAddressable(emptyList(), 30023, author, "ragu"))
    }

    @Test
    fun toleratesMalformedDTags() {
        // A `["d"]` with no value must not match, and must not throw.
        val malformed = event("1", 1_800_000_000L).copy(tags = listOf(listOf("d")))
        val good = event("2", 1_700_000_000L)
        assertEquals(good, newestAddressable(listOf(malformed, good), 30023, author, "ragu"))
    }
}
