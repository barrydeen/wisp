package cooking.zap.app.repo

import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.RecipeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function gate for [RecipeRepository]'s dedup/merge. The live
 * subscription path needs Android ([RelayPool] -> SharedPreferences) and is
 * exercised on-device; the addressable-coordinate newest-wins merge — the
 * part that turns an uneven multi-relay union into one clean feed — is pure
 * and tested here against the REAL "Tuscan Peposo" event (the same fixture
 * 1.1 froze) plus synthetic older/newer revisions of it.
 */
class RecipeRepositoryTest {

    private fun realTuscan(): NostrEvent {
        val json = javaClass.getResource("/recipes/tuscan_peposo.json")!!.readText()
        return NostrEvent.fromJson(json)
    }

    /** A revision of [base] keeping its coordinate (pubkey + d-tag) but new id/time/content. */
    private fun revision(base: NostrEvent, id: String, createdAt: Long, title: String): NostrEvent =
        base.copy(id = id, created_at = createdAt, content = "## Directions\n\n1. $title\n")

    // ---- preferNewer (the NIP-01 rule) -----------------------------------

    @Test
    fun preferNewer_higherCreatedAtWins() {
        val older = stub(id = "aaaa", createdAt = 100)
        val newer = stub(id = "bbbb", createdAt = 200)
        assertSame(newer, preferNewer(older, newer))
        assertSame(newer, preferNewer(newer, older)) // order-independent
    }

    @Test
    fun preferNewer_equalCreatedAt_lowerIdWins() {
        // NIP-01: on a created_at tie, keep the lexicographically lower id.
        val lower = stub(id = "00ff", createdAt = 100)
        val higher = stub(id = "ff00", createdAt = 100)
        assertSame(lower, preferNewer(lower, higher))
        assertSame(lower, preferNewer(higher, lower)) // order-independent
    }

    // ---- coordinate keying -----------------------------------------------

    @Test
    fun recipeCoordinate_isKindAuthorDTag() {
        val e = realTuscan()
        assertEquals(
            "30023:1852d83e2b9d12fa561071bfe159ff5ae510af1fc9b51b85539cb6a81486f207:" +
                "tuscan-peposo-(black-pepper-beef-stew)",
            recipeCoordinate(e)
        )
    }

    // ---- dedupeNewestPerCoordinate ---------------------------------------

    @Test
    fun dedupe_collapsesSameEventArrivingFromMultipleRelays() {
        val real = realTuscan()
        // primal + nos.lol + eden all return the identical event.
        val deduped = dedupeNewestPerCoordinate(listOf(real, real, real))
        assertEquals(1, deduped.size)
        assertSame(real, deduped.single())
    }

    @Test
    fun dedupe_newerRevisionWinsOverReal_olderRevisionLoses() {
        val real = realTuscan() // created_at = 1776632470
        val older = revision(real, id = "0".repeat(64), createdAt = real.created_at - 1000, title = "OLD")
        val newer = revision(real, id = "f".repeat(64), createdAt = real.created_at + 1000, title = "NEW")

        // Whatever order the relays deliver them in, the newest revision wins.
        val deduped = dedupeNewestPerCoordinate(listOf(older, real, newer))
        assertEquals(1, deduped.size)
        assertSame(newer, deduped.single())

        // And an older revision never displaces the real one.
        assertSame(real, dedupeNewestPerCoordinate(listOf(real, older)).single())
        assertSame(real, dedupeNewestPerCoordinate(listOf(older, real)).single())
    }

    @Test
    fun dedupe_keepsDistinctCoordinatesSeparate() {
        val real = realTuscan()
        val otherAuthor = real.copy(
            id = "a".repeat(64),
            pubkey = "b".repeat(64), // different author -> different coordinate
        )
        val deduped = dedupeNewestPerCoordinate(listOf(real, otherAuthor))
        assertEquals(2, deduped.size)
    }

    @Test
    fun dedupe_outputStillParsesAsRecipe() {
        // Sanity: the survivor of a merge is still a real, parseable recipe.
        val real = realTuscan()
        val survivor = dedupeNewestPerCoordinate(listOf(real, real)).single()
        assertTrue(RecipeParser.isRecipe(survivor))
        assertEquals("Tuscan Peposo (Black Pepper Beef Stew)", RecipeParser.parse(survivor).title)
    }

    private fun stub(id: String, createdAt: Long): NostrEvent = NostrEvent(
        id = id,
        pubkey = "p".repeat(64),
        created_at = createdAt,
        kind = RecipeParser.RECIPE_KIND,
        tags = listOf(listOf("d", "x"), listOf("t", "zapcooking")),
        content = "",
        sig = "0".repeat(128),
    )
}
