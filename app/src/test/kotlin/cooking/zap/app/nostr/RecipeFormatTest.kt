package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for the recipe-format seam (refactor only — no behavior change). Proves:
 *  - the registry resolves real recipes to [Nip23RecipeFormat] and the adapter
 *    is identity with the retained [RecipeParser]/[RecipeSerializer] objects;
 *  - [dedupeAcrossFormats] is a **true pass-through while one format is active**;
 *  - the cross-format canonical-pick order `(rank desc, created_at desc, id asc)`
 *    is correct (simulated via the `rankOf` lambda — no second format registered);
 *  - the [Nip333RecipeFormat] stub is NOT in the runtime registry (its TODO
 *    bodies can never fire).
 */
class RecipeFormatTest {

    private fun realTuscan(): NostrEvent {
        val json = javaClass.getResource("/recipes/tuscan_peposo.json")!!.readText()
        return NostrEvent.fromJson(json)
    }

    private fun stub(id: String, createdAt: Long, author: String = "p".repeat(64), d: String = "x"): NostrEvent =
        NostrEvent(
            id = id,
            pubkey = author,
            created_at = createdAt,
            kind = RecipeParser.RECIPE_KIND,
            tags = listOf(listOf("d", d), listOf("t", "zapcooking")),
            content = "",
            sig = "0".repeat(128),
        )

    // ---- registry + adapter ----------------------------------------------

    @Test
    fun registry_resolvesRealRecipeToNip23() {
        val e = realTuscan()
        assertSame(Nip23RecipeFormat, RecipeFormats.forEvent(e))
        assertEquals(0, RecipeFormats.rankOf(e))
    }

    @Test
    fun nip23Adapter_isIdentityWithRetainedObjects() {
        val e = realTuscan()
        // parse delegates verbatim → same structured recipe.
        assertEquals(RecipeParser.parse(e), Nip23RecipeFormat.parse(e))
        // serialize delegates verbatim → byte-identical content + tags.
        val recipe = RecipeParser.parse(e)
        val unsigned = Nip23RecipeFormat.serialize(
            recipe, recipe.title!!, listOf(recipe.image!!), recipe.categories,
        )
        assertEquals(RecipeParser.RECIPE_KIND, unsigned.kind)
        assertEquals(RecipeSerializer.toContent(recipe), unsigned.content)
        assertEquals(
            RecipeSerializer.toTags(recipe.title!!, recipe.summary, listOf(recipe.image!!), recipe.categories),
            unsigned.tags,
        )
        assertEquals(RecipeSerializer.slug(recipe.title!!), Nip23RecipeFormat.slug(recipe.title!!))
    }

    // ---- RecipeKey -------------------------------------------------------

    @Test
    fun recipeKey_isAuthorPlusDTag_kindIndependent() {
        val e = realTuscan()
        assertEquals(
            RecipeKey(
                "1852d83e2b9d12fa561071bfe159ff5ae510af1fc9b51b85539cb6a81486f207",
                "tuscan-peposo-(black-pepper-beef-stew)",
            ),
            recipeKey(e),
        )
    }

    // ---- dedupeAcrossFormats: single-format pass-through -----------------

    @Test
    fun dedupe_singleFormat_isPassThrough() {
        // Distinct recipes (different d-tags) → all survive, none collapsed.
        val a = stub(id = "aa", createdAt = 100, d = "recipe-a")
        val b = stub(id = "bb", createdAt = 100, d = "recipe-b")
        val out = dedupeAcrossFormats(listOf(a, b)) { RecipeFormats.rankOf(it) }
        assertEquals(2, out.size)
        assertTrue(out.containsAll(listOf(a, b)))
    }

    @Test
    fun dedupe_dropsEventsWithNoActiveFormat() {
        val recipe = stub(id = "aa", createdAt = 100)
        val notARecipe = recipe.copy(id = "bb", kind = 1) // rankOf → null
        val out = dedupeAcrossFormats(listOf(recipe, notARecipe)) { RecipeFormats.rankOf(it) }
        assertEquals(listOf(recipe), out)
    }

    // ---- dedupeAcrossFormats: cross-format canonical-pick ----------------
    // No second format is registered, so rank is simulated via the lambda.

    @Test
    fun dedupe_crossFormat_higherRankWins_evenIfOlder() {
        // Same RecipeKey (same author + d), different "formats".
        val lowRankNewer = stub(id = "aa", createdAt = 200) // rank 0
        val highRankOlder = stub(id = "bb", createdAt = 100) // rank 1
        val rank = { e: NostrEvent -> if (e.id == "bb") 1 else 0 }
        val out = dedupeAcrossFormats(listOf(lowRankNewer, highRankOlder), rank)
        // Rank beats recency: the higher-rank (migration-target) event wins.
        assertEquals(listOf(highRankOlder), out)
    }

    @Test
    fun dedupe_sameRank_newerWins_thenLowerId() {
        val older = stub(id = "ff", createdAt = 100)
        val newer = stub(id = "aa", createdAt = 200)
        assertEquals(listOf(newer), dedupeAcrossFormats(listOf(older, newer)) { 0 })

        // created_at tie → lexicographically lower id wins.
        val lowId = stub(id = "00aa", createdAt = 100)
        val highId = stub(id = "ffbb", createdAt = 100)
        assertEquals(listOf(lowId), dedupeAcrossFormats(listOf(highId, lowId)) { 0 })
    }

    // ---- stub guard ------------------------------------------------------

    @Test
    fun nip333Stub_isNotRegistered() {
        // The stub must never be iterated at runtime — its TODO() bodies can't fire.
        assertFalse(RecipeFormats.active.any { it === Nip333RecipeFormat })
        assertFalse(RecipeFormats.primary === Nip333RecipeFormat)
    }
}
