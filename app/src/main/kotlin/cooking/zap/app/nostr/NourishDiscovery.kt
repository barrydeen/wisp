package cooking.zap.app.nostr

/**
 * Nourish Explore discovery helpers — port of frontend
 * `src/lib/nourish/nourishDiscovery.ts` (Phase 3b + #532 resilience).
 *
 * Pure logic + session caches. Networking lives in
 * [cooking.zap.app.repo.NourishRepository].
 */
object NourishDiscovery {

    /** NIP-32 self-label namespace on kind-30078 events. */
    const val LABEL_NAMESPACE = "cooking.zap.nourish"

    /** Session cache TTL — corpus changes slowly; 5 min matches web. */
    const val DISCOVERY_CACHE_TTL_MS = 5 * 60 * 1000L

    enum class SortDimension { OVERALL, REAL_FOOD, GUT, PROTEIN }

    data class FilterChip(
        val id: String,
        val label: String,
        val nourishLabel: String,
    )

    /**
     * Tappable filter chips (AND composition). Quantity chips never match
     * `confidence: "rough"` recipes — those carry no threshold labels by design.
     */
    val FILTER_CHIPS: List<FilterChip> = listOf(
        FilterChip("high-protein", "High protein (30g+)", "protein:30plus"),
        FilterChip("under-600", "Under 600 kcal", "kcal:under600"),
        FilterChip("low-carb", "Low carb (under 40g)", "carbs:under40"),
        FilterChip("no-seed-oils", "No seed oils", "seedoil:free"),
        FilterChip("no-added-sugar", "No added sugar", "addedsugar:free"),
        FilterChip("no-red-meat", "No red meat", "redmeat:free"),
    )

    /**
     * Static selectivity order from the Phase 2 backfill census (2026-07-10).
     * Lower index = more selective. Used to pick the primary `#l` REQ for AND.
     */
    val LABEL_SELECTIVITY_ASC: List<String> = listOf(
        "protein:40plus",
        "kcal:under400",
        "protein:30plus",
        "carbs:under20",
        "protein:20plus",
        "carbs:under40",
        "kcal:under600",
        "addedsugar:free",
        "kcal:under800",
        "redmeat:free",
        "seedoil:free",
    )

    data class AnalysisRow(
        val score: NourishScore,
        val createdAt: Long,
        val recipePubkey: String,
        val recipeDTag: String,
        val eventId: String,
    )

    data class RankedRecipe(
        val recipe: RecipeParser.Recipe,
        val score: NourishScore,
        val createdAt: Long,
        val authorPubkey: String,
        val recipeDTag: String,
    )

    data class DiscoveryResult(
        val recipes: List<RankedRecipe>,
        val degraded: Boolean,
        val fromCache: Boolean = false,
        val refreshMiss: Boolean = false,
    )

    private data class CacheEntry(
        val recipes: List<RankedRecipe>,
        val degraded: Boolean,
        val fetchedAt: Long,
    )

    private val discoveryCache = LinkedHashMap<String, CacheEntry>()
    private val recipeEventCache = LinkedHashMap<String, NostrEvent>()

    fun labelsFromChipIds(chipIds: Collection<String>): List<String> {
        val wanted = chipIds.toSet()
        return FILTER_CHIPS.mapNotNull { chip ->
            if (chip.id in wanted) chip.nourishLabel else null
        }
    }

    fun pickMostSelectiveLabel(labels: List<String>): String {
        require(labels.isNotEmpty()) { "pickMostSelectiveLabel requires at least one label" }
        var best = labels.first()
        var bestRank = selectivityRank(best)
        for (i in 1 until labels.size) {
            val rank = selectivityRank(labels[i])
            if (rank < bestRank) {
                best = labels[i]
                bestRank = rank
            }
        }
        return best
    }

    private fun selectivityRank(label: String): Int {
        val idx = LABEL_SELECTIVITY_ASC.indexOf(label)
        return if (idx < 0) Int.MAX_VALUE else idx
    }

    /**
     * Whether an event carries every required `l` label. When a namespace is
     * present on the tag, it must match [LABEL_NAMESPACE]; two-element tags
     * are accepted (relay `#l` match).
     */
    fun eventHasAllLabels(tags: List<List<String>>, required: List<String>): Boolean {
        if (required.isEmpty()) return true
        val present = HashSet<String>()
        for (t in tags) {
            if (t.isEmpty() || t[0] != "l" || t.size < 2 || t[1].isBlank()) continue
            if (t.size >= 3 && t[2].isNotEmpty() && t[2] != LABEL_NAMESPACE) continue
            present.add(t[1])
        }
        return required.all { it in present }
    }

    fun intersectEventsByLabels(
        events: Iterable<NostrEvent>,
        remainingLabels: List<String>,
    ): List<NostrEvent> {
        if (remainingLabels.isEmpty()) return events.toList()
        return events.filter { eventHasAllLabels(it.tags, remainingLabels) }
    }

    /** Empty filtered set → degrade to unfiltered ranked view. */
    fun shouldDegradeFilteredResults(filteredCount: Int): Boolean = filteredCount == 0

    /**
     * Empty revalidate after a successful non-empty fetch is almost certainly
     * transport failure — preserve previous. Legitimate filter-miss degrade
     * sets [legitimateEmpty] and keeps its own path.
     */
    fun shouldPreservePreviousOnEmpty(
        previousCount: Int,
        freshCount: Int,
        legitimateEmpty: Boolean = false,
    ): Boolean {
        if (legitimateEmpty) return false
        return previousCount > 0 && freshCount == 0
    }

    /** Stable cache key for a filter label set (order-independent). */
    fun filterCacheKey(filters: Collection<String>): String {
        if (filters.isEmpty()) return ""
        return filters.toSet().sorted().joinToString(",")
    }

    fun buildNourishAnalysisFilter(label: String? = null): Filter {
        return Filter(
            kinds = listOf(NourishParser.KIND),
            authors = listOf(NourishParser.SERVICE_PUBKEY),
            limit = 200,
            lTags = label?.let { listOf(it) },
        )
    }

    fun getDimensionScore(score: NourishScore, dim: SortDimension): Int = when (dim) {
        SortDimension.OVERALL -> score.overall
        SortDimension.REAL_FOOD -> score.dimensions.find { it.name == "Real Food" }?.score ?: 0
        SortDimension.GUT -> score.dimensions.find { it.name == "Gut" }?.score ?: 0
        SortDimension.PROTEIN -> score.dimensions.find { it.name == "Protein" }?.score ?: 0
    }

    /**
     * Parse 30078 events → analysis rows, dedup by recipe coordinate keeping
     * newest [createdAt]. Skips events without a usable `a` tag / parse.
     */
    fun parseAnalyses(nourishEvents: Iterable<NostrEvent>): List<AnalysisRow> {
        val byCoord = LinkedHashMap<String, AnalysisRow>()
        for (event in nourishEvents) {
            val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1).orEmpty()
            if (!dTag.startsWith("nourish:")) continue
            val parsed = NourishParser.parse(event.content) ?: continue
            val aTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "a" }?.get(1).orEmpty()
            val parts = aTag.split(":")
            if (parts.size < 3 || parts[0] != "30023") continue
            val recipePubkey = parts[1]
            val recipeDTag = parts.drop(2).joinToString(":")
            if (recipePubkey.isBlank() || recipeDTag.isBlank()) continue
            val key = recipeCoordKey(recipePubkey, recipeDTag)
            val row = AnalysisRow(
                score = parsed,
                createdAt = event.created_at,
                recipePubkey = recipePubkey,
                recipeDTag = recipeDTag,
                eventId = event.id,
            )
            val existing = byCoord[key]
            if (existing == null || row.createdAt > existing.createdAt) {
                byCoord[key] = row
            }
        }
        return byCoord.values.toList()
    }

    fun sortAnalyses(
        analyses: List<AnalysisRow>,
        sortBy: SortDimension,
    ): List<AnalysisRow> =
        analyses.sortedWith(
            compareByDescending<AnalysisRow> { getDimensionScore(it.score, sortBy) }
                .thenByDescending { it.createdAt },
        )

    fun recipeCoordKey(pubkey: String, dTag: String): String = "$pubkey:$dTag"

    // ─── Session caches ───────────────────────────────────────────

    fun peekDiscoveryCache(filters: Collection<String>): DiscoveryResult? {
        val key = filterCacheKey(filters)
        val entry = discoveryCache[key] ?: return null
        if (System.currentTimeMillis() - entry.fetchedAt > DISCOVERY_CACHE_TTL_MS) {
            discoveryCache.remove(key)
            return null
        }
        return DiscoveryResult(
            recipes = entry.recipes,
            degraded = entry.degraded,
            fromCache = true,
        )
    }

    fun putDiscoveryCache(
        filters: Collection<String>,
        recipes: List<RankedRecipe>,
        degraded: Boolean = false,
        fetchedAt: Long = System.currentTimeMillis(),
    ) {
        discoveryCache[filterCacheKey(filters)] = CacheEntry(recipes, degraded, fetchedAt)
    }

    fun getCachedDiscoveryEntry(filters: Collection<String>): Pair<List<RankedRecipe>, Boolean>? {
        val entry = discoveryCache[filterCacheKey(filters)] ?: return null
        return entry.recipes to entry.degraded
    }

    fun getCachedRecipeEvent(pubkey: String, dTag: String): NostrEvent? =
        recipeEventCache[recipeCoordKey(pubkey, dTag)]

    fun putCachedRecipeEvent(pubkey: String, dTag: String, event: NostrEvent) {
        val key = recipeCoordKey(pubkey, dTag)
        val existing = recipeEventCache[key]
        if (existing == null || event.created_at >= existing.created_at) {
            recipeEventCache[key] = event
        }
    }

    /** Clear session caches — tests / account switch. */
    fun resetSessionCaches() {
        discoveryCache.clear()
        recipeEventCache.clear()
    }
}
