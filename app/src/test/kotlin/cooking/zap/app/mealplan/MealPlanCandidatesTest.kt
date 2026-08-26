package cooking.zap.app.mealplan

import cooking.zap.app.nostr.HiddenRecipes
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.repo.RecipeBookmarkRepository.CookbookList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure Cheffy candidate discovery — event mapper, merge, and saved fill.
 * No relay integration. Port of frontend recipeDiscoveryService.ts (8caef33)
 * for the pieces that determine prompt quality.
 */
class MealPlanCandidatesTest {

    private val author = "cd".repeat(32)

    // --- tags ---

    @Test
    fun tags_dropExactZapcookingAndNostrcooking_keepPrefixed() {
        val event = event(
            tTags = listOf(
                "zapcooking",
                "nostrcooking",
                "zapcooking-breakfast",
                "zapcooking-italian",
                "dinner",
            ),
        )
        val tags = MealPlanCandidates.candidateFromEvent(event)!!.tags
        assertFalse("zapcooking" in tags)
        assertFalse("nostrcooking" in tags)
        assertTrue("zapcooking-breakfast" in tags)
        assertTrue("zapcooking-italian" in tags)
        assertTrue("dinner" in tags)
    }

    @Test
    fun tags_dedupeCaseAndCap8And80() {
        val tTags = buildList {
            add("zapcooking")
            add("Breakfast")
            add("breakfast")
            add("Dinner")
            addAll((1..10).map { "tag$it" })
        }
        val tags = MealPlanCandidates.candidateFromEvent(event(tTags = tTags))!!.tags
        assertEquals(8, tags.size)
        assertEquals("breakfast", tags[0])
        assertFalse("Breakfast" in tags)
        assertEquals(listOf("breakfast", "dinner", "tag1", "tag2", "tag3", "tag4", "tag5", "tag6"), tags)

        val long = MealPlanCandidates.candidateFromEvent(
            event(tTags = listOf("zapcooking", "x".repeat(100))),
        )!!.tags
        assertEquals(listOf("x".repeat(80)), long)
    }

    @Test
    fun tags_zapcookingBreakfast_isEligibleForBreakfast() {
        val candidate = MealPlanCandidates.candidateFromEvent(
            event(tTags = listOf("zapcooking", "zapcooking-breakfast")),
        )!!
        assertTrue(
            "prefix-stripping zapcooking-* would silently drop breakfast eligibility",
            SlotEligibility.isRecipeEligibleForSlot(candidate, "breakfast"),
        )
        val stripped = SlotEligibility.SlotCandidate(
            title = candidate.title,
            tags = listOf("breakfast"), // what categories would have produced
        )
        assertTrue(SlotEligibility.isRecipeEligibleForSlot(stripped, "breakfast"))
        val wronglyStripped = SlotEligibility.SlotCandidate(
            title = "Weeknight Pasta",
            tags = emptyList(),
        )
        assertFalse(SlotEligibility.isRecipeEligibleForSlot(wronglyStripped, "breakfast"))
    }

    // --- title ---

    @Test
    fun title_usesTitleTag() {
        val c = MealPlanCandidates.candidateFromEvent(event(title = "Spaghetti"))!!
        assertEquals("Spaghetti", c.title)
    }

    @Test
    fun title_fallsBackToDTagThenRecipe() {
        val fromD = MealPlanCandidates.candidateFromEvent(event(title = null, dTag = "weeknight-pasta"))!!
        assertEquals("weeknight-pasta", fromD.title)

        val longTitle = "T".repeat(200)
        val capped = MealPlanCandidates.candidateFromEvent(event(title = longTitle))!!
        assertEquals(MealPlanGeneration.MAX_TITLE_CHARS, capped.title.length)
        assertEquals(120, capped.title.length)
    }

    // --- ingredients ---

    @Test
    fun ingredients_tagIndex3PreferredOverIndex1_andQuantityStripped() {
        val event = event(
            extraTags = listOf(
                listOf("ingredient", "ignored-name", "2", "2 lbs chicken"),
                listOf("ingredient", "1 cup flour"),
            ),
        )
        val ingredients = MealPlanCandidates.candidateFromEvent(event)!!.recipe.ingredients
        assertEquals(listOf("chicken", "flour"), ingredients)
    }

    @Test
    fun ingredients_markdownFallback_viaIngredientParser() {
        val md = """
            ## Ingredients
            - 2 lbs chicken
            - flour

            ## Directions
            1. Mix.
        """.trimIndent()
        val ingredients = MealPlanCandidates.candidateFromEvent(event(content = md))!!.recipe.ingredients
        assertTrue(ingredients.any { it.contains("chicken", ignoreCase = true) })
        assertTrue(ingredients.any { it.contains("flour", ignoreCase = true) })
        assertFalse(ingredients.any { it.startsWith("2 ") })
    }

    @Test
    fun ingredients_cap12_andNeitherPathYieldsEmpty() {
        val extra = (1..20).map { listOf("ingredient", "item$it") }
        val capped = MealPlanCandidates.candidateFromEvent(event(extraTags = extra))!!.recipe.ingredients
        assertEquals(12, capped.size)
        assertEquals("item1", capped.first())

        val empty = MealPlanCandidates.candidateFromEvent(event())!!.recipe.ingredients
        assertTrue(empty.isEmpty())
    }

    // --- time / servings ---

    @Test
    fun times_tagFirstThenMarkdownThenNeither() {
        val tagged = event(
            extraTags = listOf(
                listOf("prep_time", "10 min"),
                listOf("cook_time", "20 min"),
                listOf("servings", "4"),
            ),
            content = detailsMarkdown(prep = "99", cook = "99", servings = "99"),
        )
        val fromTags = MealPlanCandidates.candidateFromEvent(tagged)!!
        assertEquals("10 min", fromTags.recipe.prepTime)
        assertEquals("20 min", fromTags.recipe.cookTime)
        assertEquals("4", fromTags.recipe.servings)

        val fromMd = MealPlanCandidates.candidateFromEvent(
            event(content = detailsMarkdown(prep = "15 min", cook = "30 min", servings = "2")),
        )!!
        assertEquals("15 min", fromMd.recipe.prepTime)
        assertEquals("30 min", fromMd.recipe.cookTime)
        assertEquals("2", fromMd.recipe.servings)

        val neither = MealPlanCandidates.candidateFromEvent(event())!!
        assertNull(neither.recipe.prepTime)
        assertNull(neither.recipe.cookTime)
        assertNull(neither.recipe.servings)
    }

    // --- coordinate ---

    @Test
    fun coordinate_constructedAndMalformedRejected() {
        val ok = MealPlanCandidates.candidateFromEvent(event(dTag = "pasta"))!!
        assertEquals("30023:$author:pasta", ok.a)

        assertNull(MealPlanCandidates.candidateFromEvent(event(kind = 1, dTag = "pasta")))
        assertNull(
            MealPlanCandidates.candidateFromEvent(
                NostrEvent.createUnsigned(
                    pubkeyHex = author,
                    kind = 30023,
                    content = "",
                    tags = listOf(listOf("title", "No d")),
                ),
            ),
        )
    }

    @Test
    fun hiddenCoordinate_isSkipped() {
        val hidden = HiddenRecipes.COORDINATES.first()
        val parts = hidden.split(":", limit = 3)
        val event = event(dTag = parts[2], pubkey = parts[1], kind = parts[0].toInt())
        assertNull(MealPlanCandidates.candidateFromEvent(event))
    }

    @Test
    fun image_staysOnWrapper_notOnRecipeCandidate() {
        val c = MealPlanCandidates.candidateFromEvent(
            event(extraTags = listOf(listOf("image", "https://img.example/cover.jpg"))),
        )!!
        assertEquals("https://img.example/cover.jpg", c.image)
        // MealPlanGeneration.RecipeCandidate has no image field — the wrapper
        // is the only place the preview URL lives.
        assertEquals("https://img.example/cover.jpg", c.image)
        assertEquals("30023:$author:pasta", c.recipe.a)
    }

    // --- merge / all ---

    @Test
    fun merge_firstSeenByCoordinate_laterDoesNotReplace() {
        val a = candidate("pasta", "Mine")
        val a2 = candidate("pasta", "Explore copy")
        val b = candidate("soup", "Saved")
        val merged = MealPlanCandidates.mergeFirstSeen(
            listOf(listOf(a), listOf(a2, b), listOf(b)),
        )
        assertEquals(listOf("Mine", "Saved"), merged.map { it.title })
    }

    @Test
    fun mergeLoaders_oneThrowingDoesNotFailTheRun() = runBlocking {
        val mine = candidate("pasta", "Mine")
        val explore = candidate("salad", "Explore")
        val merged = MealPlanCandidates.mergeLoaders(
            listOf(
                { listOf(mine) },
                { error("saved exploded") },
                { listOf(explore) },
            ),
        )
        assertEquals(setOf(mine.a, explore.a), merged.map { it.a }.toSet())
    }

    // --- saved fill ---

    @Test
    fun saved_chunkingRespectsCap_andDropsUnresolved() = runBlocking {
        val coords = (1..70).map { "30023:$author:r$it" }
        val lists = listOf(cookbookList(*coords.toTypedArray()))
        val networkCalls = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        val result = MealPlanCandidates.resolveSaved(
            lists = lists,
            cacheLookup = { _, _, _ -> null },
            networkFetch = { _, _, dTag ->
                val now = inFlight.incrementAndGet()
                maxInFlight.updateAndGet { maxOf(it, now) }
                networkCalls.incrementAndGet()
                delay(30)
                inFlight.decrementAndGet()
                if (dTag == "r1") event(dTag = dTag) else null
            },
            networkCap = 64,
            chunkSize = 8,
            timeoutMs = 5_000,
        )

        assertEquals(64, networkCalls.get())
        assertTrue("chunk size 8, saw ${maxInFlight.get()} in flight", maxInFlight.get() <= 8)
        assertEquals(1, result.size)
        assertEquals("30023:$author:r1", result[0].a)
    }

    @Test
    fun saved_cacheHit_skipsNetwork() = runBlocking {
        val a = "30023:$author:pasta"
        var network = 0
        val result = MealPlanCandidates.resolveSaved(
            lists = listOf(cookbookList(a)),
            cacheLookup = { _, _, _ -> event(dTag = "pasta") },
            networkFetch = { _, _, _ ->
                network++
                null
            },
        )
        assertEquals(0, network)
        assertEquals(1, result.size)
        assertEquals(a, result[0].a)
    }

    // --- helpers ---

    private fun coord(dTag: String) = "30023:$author:$dTag"

    private fun candidate(dTag: String, title: String) = MealPlanCandidates.Candidate(
        recipe = MealPlanGeneration.RecipeCandidate(
            a = coord(dTag),
            title = title,
            tags = emptyList(),
            ingredients = emptyList(),
        ),
    )

    private fun cookbookList(vararg coords: String) = CookbookList(
        dTag = "saved",
        title = "Saved",
        summary = null,
        image = null,
        coverCoord = null,
        coordinates = coords.toSet(),
        isDefault = true,
        event = NostrEvent(
            id = "id-saved",
            pubkey = author,
            created_at = 1L,
            kind = 30001,
            tags = emptyList(),
            content = "",
            sig = "sig",
        ),
    )

    private fun event(
        dTag: String = "pasta",
        title: String? = "Weeknight Pasta",
        tTags: List<String> = listOf("zapcooking"),
        extraTags: List<List<String>> = emptyList(),
        content: String = "",
        pubkey: String = author,
        kind: Int = 30023,
    ): NostrEvent {
        val tags = buildList {
            if (dTag.isNotEmpty()) add(listOf("d", dTag))
            if (title != null) add(listOf("title", title))
            for (t in tTags) add(listOf("t", t))
            addAll(extraTags)
        }
        return NostrEvent.createUnsigned(
            pubkeyHex = pubkey,
            kind = kind,
            content = content,
            tags = tags,
        )
    }

    private fun detailsMarkdown(prep: String, cook: String, servings: String) = """
        ## Details
        ⏲️ Prep time: $prep
        🍳 Cook time: $cook
        🍽️ Servings: $servings

        ## Ingredients
        - flour

        ## Directions
        1. Mix.
    """.trimIndent()
}
