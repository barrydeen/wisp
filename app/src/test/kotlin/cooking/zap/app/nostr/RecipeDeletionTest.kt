package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for recipe deletion ([RecipeDeletion]) — the tag shape of the two events
 * the web publishes to delete a recipe, plus the two properties that decide
 * whether a delete actually lands:
 *
 *  - the tombstone addresses **the event being deleted** (its kind, its `d`),
 *    never a re-derived or hardcoded one;
 *  - the blanked replacement is **not a recipe** to any reader here, so a
 *    deleted recipe leaves no "[Deleted]" ghost in the feeds.
 */
class RecipeDeletionTest {

    private val author = "ab".repeat(32)

    // Smallest valid recipe template (see RecipeFormatTest) — needed so the
    // "the original IS a recipe" half of the ghost test isn't vacuous.
    private val RECIPE_BODY = "## Ingredients\n\n- flour\n- water\n\n## Directions\n\n1. Mix.\n2. Bake."

    private fun recipe(
        kind: Int = RecipeParser.RECIPE_KIND,
        d: String? = "tuscan-peposo",
        createdAt: Long = 1_700_000_000,
        pubkey: String = author,
    ): NostrEvent = NostrEvent(
        id = "ee".repeat(32),
        pubkey = pubkey,
        created_at = createdAt,
        kind = kind,
        tags = buildList {
            d?.let { add(listOf("d", it)) }
            add(listOf("t", "zapcooking"))
            add(listOf("title", "Tuscan Peposo"))
        },
        content = RECIPE_BODY,
        sig = "0".repeat(128),
    )

    // ---- blanked replacement ------------------------------------------------

    @Test
    fun blankedReplacement_carriesTheSameDTagAndTheWebsMarkers() {
        val tags = RecipeDeletion.blankedReplacementTags(recipe())
        assertEquals(
            listOf(
                listOf("d", "tuscan-peposo"),
                listOf("deleted", "true"),
                listOf("title", "[Deleted]"),
            ),
            tags,
        )
    }

    @Test
    fun blankedReplacement_omitsDTagWhenTheRecipeHasNone() {
        assertEquals(
            listOf(listOf("deleted", "true"), listOf("title", "[Deleted]")),
            RecipeDeletion.blankedReplacementTags(recipe(d = null)),
        )
        // A blank `d` is the same case — there is nothing to address with.
        assertEquals(
            listOf(listOf("deleted", "true"), listOf("title", "[Deleted]")),
            RecipeDeletion.blankedReplacementTags(recipe(d = "   ")),
        )
    }

    /**
     * The load-bearing property: a blanked replacement is not a recipe, so it
     * resolves to no format and never renders. Without this, deleting would
     * leave a "[Deleted]" card in every recipe feed.
     */
    @Test
    fun blankedReplacement_isNotARecipe() {
        val original = recipe()
        assertTrue("fixture must be a recipe or the assertion below is vacuous", RecipeParser.isRecipe(original))

        val blanked = original.copy(
            content = RecipeDeletion.TOMBSTONE_CONTENT,
            tags = RecipeDeletion.blankedReplacementTags(original),
            created_at = RecipeDeletion.deletionTimestamp(original),
        )
        assertFalse(RecipeParser.isRecipe(blanked))
        assertNull(RecipeFormats.forEvent(blanked))
    }

    /**
     * The recipe gate is not the whole story: the article surfaces
     * (`FeedScreen`, `UserProfileScreen`) route on `kind == 30023` alone and
     * never ask [RecipeParser.isRecipe], so the blanked replacement has to be
     * recognisable *as a tombstone* — that is the guard `EventRepository`
     * applies before the feed insert. Without it, deleting a recipe puts a
     * "[Deleted]" article card in every follower's feed.
     */
    @Test
    fun blankedReplacement_isRecognisableAsATombstone() {
        val original = recipe()
        assertFalse("a live recipe must not look like a tombstone", RecipeDeletion.isBlankedReplacement(original))

        val blanked = original.copy(
            content = RecipeDeletion.TOMBSTONE_CONTENT,
            tags = RecipeDeletion.blankedReplacementTags(original),
        )
        assertTrue(RecipeDeletion.isBlankedReplacement(blanked))

        // Also when the recipe had no `d` tag — the marker is not carried by
        // the coordinate, so that case must be recognisable too.
        assertTrue(
            RecipeDeletion.isBlankedReplacement(
                original.copy(tags = RecipeDeletion.blankedReplacementTags(recipe(d = null)))
            )
        )
    }

    /**
     * ...and it is not a recipe for **two independent** reasons, so the
     * property survives a change to either half. The test above passes on the
     * dropped `#t` tag alone — it would still pass if the tombstone content
     * became a valid template. These two pin each half on its own.
     */
    @Test
    fun blankedReplacement_failsBothHalvesOfTheRecipeGate() {
        val original = recipe()

        // Half 1 — no recipe #t tag, even with the original's content.
        assertFalse(RecipeParser.isRecipe(original.copy(tags = RecipeDeletion.blankedReplacementTags(original))))

        // Half 2 — blank content, even if the recipe #t tag were carried over.
        assertFalse(
            RecipeParser.isRecipe(
                original.copy(
                    content = RecipeDeletion.TOMBSTONE_CONTENT,
                    tags = RecipeDeletion.blankedReplacementTags(original) + listOf(listOf("t", "zapcooking")),
                )
            )
        )
    }

    // ---- kind-5 deletion request --------------------------------------------

    @Test
    fun deletionRequest_addressesTheCoordinateTheKindAndTheEventId() {
        val event = recipe()
        assertEquals(
            listOf(
                listOf("a", "30023:$author:tuscan-peposo"),
                listOf("k", "30023"),
                listOf("e", event.id),
            ),
            RecipeDeletion.deletionRequestTags(event),
        )
    }

    /**
     * Kind comes off the event. A hardcoded 30023 is the web defect that points
     * a premium recipe's tombstone at the author's public recipe of the same
     * name — this repo has one recipe kind today, so the test is the guard.
     */
    @Test
    fun deletionRequest_usesTheEventsOwnKindNotTheDefault() {
        val tags = RecipeDeletion.deletionRequestTags(recipe(kind = 35000))
        assertEquals(listOf("a", "35000:$author:tuscan-peposo"), tags[0])
        assertEquals(listOf("k", "35000"), tags[1])
    }

    @Test
    fun deletionRequest_dropsTheCoordinateButKeepsTheIdWhenThereIsNoDTag() {
        val event = recipe(d = null)
        assertEquals(
            listOf(listOf("k", "30023"), listOf("e", event.id)),
            RecipeDeletion.deletionRequestTags(event),
        )
    }

    // ---- timestamp -----------------------------------------------------------

    @Test
    fun deletionTimestamp_isNowWhenTheClockIsAheadOfTheRecipe() {
        val now = 1_800_000_000L
        assertEquals(now, RecipeDeletion.deletionTimestamp(recipe(createdAt = 1_700_000_000), now = now))
    }

    /**
     * NIP-09 voids only `created_at <= deletion`, and a replacement must be
     * newer to supersede — so a device clock at or behind the recipe's stamp
     * must still produce a strictly newer tombstone, not a no-op.
     */
    @Test
    fun deletionTimestamp_stepsPastARecipeDatedAtOrAfterNow() {
        val stamp = 1_900_000_000L
        assertEquals(stamp + 1, RecipeDeletion.deletionTimestamp(recipe(createdAt = stamp), now = stamp))
        assertEquals(stamp + 1, RecipeDeletion.deletionTimestamp(recipe(createdAt = stamp), now = stamp - 500))
    }

    // ---- future-date ceiling ---------------------------------------------------

    /**
     * The clamp and the ceiling meet: past `now + FUTURE_DATE_GRACE_SECONDS`
     * the tombstone [RecipeDeletion.deletionTimestamp] produces is itself
     * future-dated, so `EventRepository.addEvent` drops it locally and relays
     * drop it remotely. [RecipeDeletion.isDeletableNow] is what turns that into
     * a refusal instead of a delete that reports success and evicts the recipe
     * from this device while it stays live everywhere else.
     */
    @Test
    fun isDeletableNow_refusesARecipeDatedPastTheFutureCeiling() {
        val now = 1_800_000_000L
        val grace = RecipeDeletion.FUTURE_DATE_GRACE_SECONDS

        // The exact boundary: created_at + 1 == now + grace still lands.
        assertTrue(RecipeDeletion.isDeletableNow(recipe(createdAt = now + grace - 1), now = now))
        // One second further and the tombstone is itself dropped as future-dated.
        assertFalse(RecipeDeletion.isDeletableNow(recipe(createdAt = now + grace), now = now))
        assertFalse(RecipeDeletion.isDeletableNow(recipe(createdAt = now + 86_400), now = now))
    }

    @Test
    fun isDeletableNow_allowsAnOrdinaryRecipe() {
        val now = 1_800_000_000L
        assertTrue(RecipeDeletion.isDeletableNow(recipe(createdAt = 1_700_000_000), now = now))
        // A recipe stamped exactly now clamps to now + 1, well inside the grace.
        assertTrue(RecipeDeletion.isDeletableNow(recipe(createdAt = now), now = now))
    }

    // ---- d-tag extraction -----------------------------------------------------

    @Test
    fun dTagOf_readsTheFirstDTagAndTreatsBlankAsAbsent() {
        assertEquals("tuscan-peposo", RecipeDeletion.dTagOf(recipe()))
        assertNull(RecipeDeletion.dTagOf(recipe(d = null)))
        assertNull(RecipeDeletion.dTagOf(recipe(d = "")))
    }
}
