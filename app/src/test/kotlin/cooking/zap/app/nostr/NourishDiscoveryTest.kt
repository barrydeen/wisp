package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic coverage for Nourish Explore discovery helpers — ports frontend
 * `nourishDiscovery.test.ts` (Phase 3b + #532 resilience).
 *
 * Device verification still needed (not covered here):
 * - Chip toggle / return-to-page no longer flashes empty when pantry is slow
 * - Cached paint then quiet revalidate ("Updating…")
 * - Filter isolation: chips A results never flash under chips B
 * - Unauthenticated pantry `#l` REQs return service-key 30078s
 * - Rough recipes absent under quantity chips, present under flag-only
 * - Persistent pantry connection reuse across Explore fetches
 */
class NourishDiscoveryTest {

    @Before
    fun setUp() {
        NourishDiscovery.resetSessionCaches()
    }

    // ─── Chips / labels ───────────────────────────────────────────

    @Test
    fun filterChips_mapToPlanLabels() {
        val byId = NourishDiscovery.FILTER_CHIPS.associate { it.id to it.nourishLabel }
        assertEquals("protein:30plus", byId["high-protein"])
        assertEquals("kcal:under600", byId["under-600"])
        assertEquals("carbs:under40", byId["low-carb"])
        assertEquals("seedoil:free", byId["no-seed-oils"])
        assertEquals("addedsugar:free", byId["no-added-sugar"])
        assertEquals("redmeat:free", byId["no-red-meat"])
        assertEquals(6, NourishDiscovery.FILTER_CHIPS.size)
    }

    @Test
    fun labelsFromChipIds_chipOrderAndCombos() {
        assertEquals(emptyList<String>(), NourishDiscovery.labelsFromChipIds(emptyList()))
        assertEquals(listOf("protein:30plus"), NourishDiscovery.labelsFromChipIds(listOf("high-protein")))
        assertEquals(
            listOf("protein:30plus", "seedoil:free"),
            NourishDiscovery.labelsFromChipIds(listOf("no-seed-oils", "high-protein")),
        )
        assertEquals(
            listOf("kcal:under600", "carbs:under40", "addedsugar:free", "redmeat:free"),
            NourishDiscovery.labelsFromChipIds(
                listOf("no-red-meat", "under-600", "low-carb", "no-added-sugar"),
            ),
        )
    }

    @Test
    fun labelsFromChipIds_ignoresUnknown() {
        assertEquals(
            listOf("protein:30plus"),
            NourishDiscovery.labelsFromChipIds(listOf("not-a-chip", "high-protein")),
        )
    }

    // ─── Selectivity ──────────────────────────────────────────────

    @Test
    fun selectivityOrder_proteinBeforeKcalBeforeSeedoil() {
        val p = NourishDiscovery.LABEL_SELECTIVITY_ASC.indexOf("protein:30plus")
        val k = NourishDiscovery.LABEL_SELECTIVITY_ASC.indexOf("kcal:under600")
        val s = NourishDiscovery.LABEL_SELECTIVITY_ASC.indexOf("seedoil:free")
        assertTrue(p >= 0)
        assertTrue(k > p)
        assertTrue(s > k)
    }

    @Test
    fun pickMostSelectiveLabel_andPrimary() {
        assertEquals(
            "protein:30plus",
            NourishDiscovery.pickMostSelectiveLabel(listOf("seedoil:free", "protein:30plus")),
        )
        assertEquals(
            "kcal:under600",
            NourishDiscovery.pickMostSelectiveLabel(listOf("kcal:under600", "seedoil:free")),
        )
        assertEquals(
            "protein:30plus",
            NourishDiscovery.pickMostSelectiveLabel(
                listOf("seedoil:free", "kcal:under600", "protein:30plus"),
            ),
        )
        assertEquals(
            "carbs:under40",
            NourishDiscovery.pickMostSelectiveLabel(listOf("carbs:under40", "addedsugar:free")),
        )
        assertEquals("redmeat:free", NourishDiscovery.pickMostSelectiveLabel(listOf("redmeat:free")))
    }

    // ─── Filter construction ──────────────────────────────────────

    @Test
    fun buildFilter_noLabelOmitsHashL() {
        val filter = NourishDiscovery.buildNourishAnalysisFilter()
        assertEquals(listOf(30078), filter.kinds)
        assertEquals(listOf(NourishParser.SERVICE_PUBKEY), filter.authors)
        assertEquals(200, filter.limit)
        assertNull(filter.lTags)
        val json = filter.toJsonObject()
        assertFalse(json.containsKey("#l"))
    }

    @Test
    fun buildFilter_singleLabelAddsExactHashL() {
        val filter = NourishDiscovery.buildNourishAnalysisFilter("protein:30plus")
        assertEquals(listOf("protein:30plus"), filter.lTags)
        assertEquals(
            """["protein:30plus"]""",
            filter.toJsonObject()["#l"].toString(),
        )
        assertEquals(
            listOf("seedoil:free"),
            NourishDiscovery.buildNourishAnalysisFilter("seedoil:free").lTags,
        )
    }

    @Test
    fun servicePubkey_matchesFrontend() {
        assertEquals(
            "fdd263f69f9e95a2a0a58ec3e7e8053011214fa66007d93b26d2f4717d31917b",
            NourishParser.SERVICE_PUBKEY,
        )
    }

    // ─── Intersection ─────────────────────────────────────────────

    @Test
    fun eventHasAllLabels_and() {
        val tags = fakeTags(listOf("protein:30plus", "seedoil:free", "kcal:under600"))
        assertTrue(NourishDiscovery.eventHasAllLabels(tags, listOf("protein:30plus", "seedoil:free")))
        assertFalse(
            NourishDiscovery.eventHasAllLabels(tags, listOf("protein:30plus", "addedsugar:free")),
        )
        assertTrue(NourishDiscovery.eventHasAllLabels(tags, emptyList()))
    }

    @Test
    fun eventHasAllLabels_rejectsForeignNamespace() {
        val tags = listOf(
            listOf("l", "protein:30plus", "cooking.zap.nourish-flag"),
            listOf("l", "seedoil:free", NourishDiscovery.LABEL_NAMESPACE),
        )
        assertFalse(NourishDiscovery.eventHasAllLabels(tags, listOf("protein:30plus")))
        assertTrue(NourishDiscovery.eventHasAllLabels(tags, listOf("seedoil:free")))
    }

    @Test
    fun eventHasAllLabels_acceptsTwoElementTags() {
        val tags = listOf(listOf("l", "protein:30plus"))
        assertTrue(NourishDiscovery.eventHasAllLabels(tags, listOf("protein:30plus")))
    }

    @Test
    fun intersectEventsByLabels_remainingAnd() {
        val events = listOf(
            fakeEvent(listOf("protein:30plus", "seedoil:free")),
            fakeEvent(listOf("protein:30plus")),
            fakeEvent(listOf("protein:30plus", "seedoil:free", "kcal:under600")),
            fakeEvent(listOf("protein:30plus", "addedsugar:free")),
        )
        assertEquals(2, NourishDiscovery.intersectEventsByLabels(events, listOf("seedoil:free")).size)
        assertEquals(
            1,
            NourishDiscovery.intersectEventsByLabels(
                events,
                listOf("seedoil:free", "kcal:under600"),
            ).size,
        )
        assertEquals(4, NourishDiscovery.intersectEventsByLabels(events, emptyList()).size)
    }

    // ─── Degrade / never-clobber ───────────────────────────────────

    @Test
    fun shouldDegrade_onlyOnEmpty() {
        assertTrue(NourishDiscovery.shouldDegradeFilteredResults(0))
        assertFalse(NourishDiscovery.shouldDegradeFilteredResults(1))
        assertFalse(NourishDiscovery.shouldDegradeFilteredResults(3))
    }

    @Test
    fun authreadAndNourishPub_areDedupBypassPrefixes() {
        // Explore `#l` REQs reuse event ids from the unfiltered load; without
        // these prefixes the pool dedup empties the collector (PR #161).
        assertTrue(
            cooking.zap.app.relay.RelayPool.DEFAULT_DEDUP_BYPASS_PREFIXES.contains("authread-"),
        )
        assertTrue(
            cooking.zap.app.relay.RelayPool.DEFAULT_DEDUP_BYPASS_PREFIXES.contains("nourish-pub-"),
        )
    }

    @Test
    fun shouldPreservePreviousOnEmpty_silentRevalidate() {
        assertTrue(
            NourishDiscovery.shouldPreservePreviousOnEmpty(previousCount = 12, freshCount = 0),
        )
        assertFalse(
            NourishDiscovery.shouldPreservePreviousOnEmpty(previousCount = 12, freshCount = 10),
        )
        assertFalse(
            NourishDiscovery.shouldPreservePreviousOnEmpty(previousCount = 0, freshCount = 0),
        )
        assertFalse(
            NourishDiscovery.shouldPreservePreviousOnEmpty(
                previousCount = 12,
                freshCount = 0,
                legitimateEmpty = true,
            ),
        )
    }

    // ─── Dedup newest ─────────────────────────────────────────────

    @Test
    fun parseAnalyses_dedupByCoordKeepsNewest() {
        val older = nourishEvent(
            id = "old",
            createdAt = 100,
            recipePubkey = "pk1",
            recipeDTag = "pasta",
            overall = 5,
        )
        val newer = nourishEvent(
            id = "new",
            createdAt = 200,
            recipePubkey = "pk1",
            recipeDTag = "pasta",
            overall = 8,
        )
        val other = nourishEvent(
            id = "other",
            createdAt = 150,
            recipePubkey = "pk2",
            recipeDTag = "soup",
            overall = 6,
        )
        val rows = NourishDiscovery.parseAnalyses(listOf(older, newer, other))
        assertEquals(2, rows.size)
        val pasta = rows.find { it.recipeDTag == "pasta" }!!
        assertEquals(8, pasta.score.overall)
        assertEquals(200, pasta.createdAt)
        assertEquals("new", pasta.eventId)
    }

    // ─── Session cache ────────────────────────────────────────────

    @Test
    fun filterCacheKey_orderIndependent() {
        assertEquals(
            NourishDiscovery.filterCacheKey(listOf("seedoil:free", "protein:30plus")),
            NourishDiscovery.filterCacheKey(listOf("protein:30plus", "seedoil:free")),
        )
        assertEquals("", NourishDiscovery.filterCacheKey(emptyList()))
    }

    @Test
    fun peekDiscoveryCache_hitAndIsolation() {
        val protein = listOf(fakeRanked("protein-only"))
        val seedoil = listOf(fakeRanked("seedoil-only"))
        NourishDiscovery.putDiscoveryCache(listOf("protein:30plus"), protein, degraded = false)
        NourishDiscovery.putDiscoveryCache(listOf("seedoil:free"), seedoil, degraded = false)

        val a = NourishDiscovery.peekDiscoveryCache(listOf("protein:30plus"))
        val b = NourishDiscovery.peekDiscoveryCache(listOf("seedoil:free"))
        val none = NourishDiscovery.peekDiscoveryCache(emptyList())

        assertNotNull(a)
        assertTrue(a!!.fromCache)
        assertEquals("protein-only", a.recipes.first().recipeDTag)
        assertEquals("seedoil-only", b!!.recipes.first().recipeDTag)
        assertNull(none)
    }

    @Test
    fun peekDiscoveryCache_expiredTtlDropped() {
        NourishDiscovery.putDiscoveryCache(
            emptyList(),
            listOf(fakeRanked("stale")),
            degraded = false,
            fetchedAt = System.currentTimeMillis() - 10 * 60 * 1000L,
        )
        assertNull(NourishDiscovery.peekDiscoveryCache(emptyList()))
    }

    @Test
    fun recipeMap_reusesAcrossFilters_keepsNewest() {
        val older = recipeEvent("evt1", "pk1", "pasta", createdAt = 100)
        val newer = recipeEvent("evt2", "pk1", "pasta", createdAt = 200)
        NourishDiscovery.putCachedRecipeEvent("pk1", "pasta", older)
        assertEquals("evt1", NourishDiscovery.getCachedRecipeEvent("pk1", "pasta")?.id)

        NourishDiscovery.putCachedRecipeEvent("pk1", "pasta", newer)
        assertEquals("evt2", NourishDiscovery.getCachedRecipeEvent("pk1", "pasta")?.id)

        // Older write does not clobber
        NourishDiscovery.putCachedRecipeEvent("pk1", "pasta", older)
        assertEquals("evt2", NourishDiscovery.getCachedRecipeEvent("pk1", "pasta")?.id)
    }

    // ─── Fixtures ─────────────────────────────────────────────────

    private fun fakeTags(labels: List<String>): List<List<String>> {
        val tags = mutableListOf(listOf("d", "nourish:30023:pk:recipe"))
        if (labels.isNotEmpty()) {
            tags.add(listOf("L", NourishDiscovery.LABEL_NAMESPACE))
            for (label in labels) {
                tags.add(listOf("l", label, NourishDiscovery.LABEL_NAMESPACE))
            }
        }
        return tags
    }

    private fun fakeEvent(labels: List<String>): NostrEvent =
        NostrEvent(
            id = labels.joinToString("-").ifEmpty { "empty" },
            pubkey = NourishParser.SERVICE_PUBKEY,
            created_at = 1,
            kind = NourishParser.KIND,
            tags = fakeTags(labels),
            content = "",
            sig = "0".repeat(128),
        )

    private fun nourishEvent(
        id: String,
        createdAt: Long,
        recipePubkey: String,
        recipeDTag: String,
        overall: Int,
    ): NostrEvent {
        val content = """
            {
              "realFood": {"score": 8, "label": "Strong"},
              "gut": {"score": 7, "label": "Strong"},
              "protein": {"score": 6, "label": "Moderate"},
              "antiInflammatory": {"score": 5, "label": "Moderate"},
              "bloodSugar": {"score": 4, "label": "Fair"},
              "immuneSupportive": {"score": 7, "label": "Strong"},
              "brainHealth": {"score": 6, "label": "Moderate"},
              "heartHealth": {"score": 9, "label": "Excellent"},
              "overall": {"score": $overall, "label": "Strong"},
              "improvements": []
            }
        """.trimIndent()
        return NostrEvent(
            id = id,
            pubkey = NourishParser.SERVICE_PUBKEY,
            created_at = createdAt,
            kind = NourishParser.KIND,
            tags = listOf(
                listOf("d", "nourish:30023:$recipePubkey:$recipeDTag"),
                listOf("a", "30023:$recipePubkey:$recipeDTag"),
            ),
            content = content,
            sig = "0".repeat(128),
        )
    }

    private fun recipeEvent(
        id: String,
        pubkey: String,
        dTag: String,
        createdAt: Long,
    ): NostrEvent = NostrEvent(
        id = id,
        pubkey = pubkey,
        created_at = createdAt,
        kind = RecipeParser.RECIPE_KIND,
        tags = listOf(listOf("d", dTag), listOf("title", dTag), listOf("t", "zapcooking")),
        content = "",
        sig = "0".repeat(128),
    )

    private fun fakeRanked(dTag: String): NourishDiscovery.RankedRecipe {
        val recipe = RecipeParser.Recipe(
            id = dTag,
            author = "pk",
            dTag = dTag,
            title = dTag,
            images = emptyList(),
            summary = null,
            publishedAt = 1,
            hashtags = emptyList(),
            categories = emptyList(),
            content = RecipeParser.RecipeContent(),
        )
        val score = NourishScore(
            overall = 8,
            overallLabel = "Strong",
            dimensions = listOf(
                NourishDimension("Real Food", 8),
                NourishDimension("Gut", 7),
                NourishDimension("Protein", 7),
            ),
            improvements = emptyList(),
        )
        return NourishDiscovery.RankedRecipe(
            recipe = recipe,
            score = score,
            createdAt = 1,
            authorPubkey = "pk",
            recipeDTag = dTag,
        )
    }
}
