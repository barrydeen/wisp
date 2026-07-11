package cooking.zap.app.repo

import android.util.Log
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NourishDiscovery
import cooking.zap.app.nostr.NourishParser
import cooking.zap.app.nostr.NourishScore
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.relay.AuthedRelayReader
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads Nourish health scores (kind 30078) from the Pantry relay
 * (`wss://pantry.zap.cooking` = [RelayConfig.MEMBERS_RELAY]).
 *
 * **Single-recipe path** ([fetchScore]): anonymous `#d`-addressed REQ.
 *
 * **Explore path** ([fetchRankedRecipes]): ports web `nourishDiscovery.ts` —
 * `#l`-filtered REQ (most selective label first), client-side AND intersect,
 * dedup by recipe coordinate, batch-resolve kind-30023 recipes, session SWR
 * cache with never-clobber empty revalidate, one pantry retry before fallback
 * to public relays, persistent pantry via [RelayPool.ensureGroupRelay].
 */
class NourishRepository(private val relayPool: RelayPool) {

    private val reader = AuthedRelayReader(relayPool)
    private val scoreCache = ConcurrentHashMap<String, NourishScore>()

    /**
     * @param hasSigningKey retained for call-site compatibility; no longer
     *   gates the pantry read (anonymous service-key 30078 REQs are public).
     *   Compute remains signing-key gated in the ViewModel.
     */
    suspend fun fetchScore(
        recipeAuthor: String,
        recipeDTag: String,
        @Suppress("UNUSED_PARAMETER") hasSigningKey: Boolean = true,
    ): NourishScore? {
        if (recipeAuthor.isBlank() || recipeDTag.isBlank()) return null
        val key = "$recipeAuthor:$recipeDTag"
        scoreCache[key]?.let { return it }

        val filter = Filter(
            kinds = listOf(NourishParser.KIND),
            authors = listOf(NourishParser.SERVICE_PUBKEY),
            dTags = listOf(NourishParser.dTag(recipeAuthor, recipeDTag)),
            limit = 1,
        )
        ensurePantryConnected()
        val event = reader.read(RelayConfig.MEMBERS_RELAY, filter)
            .firstOrNull { it.kind == NourishParser.KIND } ?: return null
        val parsed = NourishParser.parse(event.content) ?: return null
        scoreCache[key] = parsed
        return parsed
    }

    /**
     * Fetch analyzed recipes ranked by [sortBy]. [filters] are Nourish `l`
     * label values (AND). Empty filtered results degrade to the unfiltered
     * ranked list (`degraded = true`).
     */
    suspend fun fetchRankedRecipes(
        sortBy: NourishDiscovery.SortDimension = NourishDiscovery.SortDimension.OVERALL,
        limit: Int = 40,
        filters: List<String> = emptyList(),
    ): NourishDiscovery.DiscoveryResult {
        val uniqueFilters = filters.distinct()
        val key = NourishDiscovery.filterCacheKey(uniqueFilters)
        val previous = NourishDiscovery.getCachedDiscoveryEntry(uniqueFilters)

        val fresh = fetchFreshRanked(sortBy, limit, uniqueFilters)

        if (
            NourishDiscovery.shouldPreservePreviousOnEmpty(
                previousCount = previous?.first?.size ?: 0,
                freshCount = fresh.recipes.size,
            )
        ) {
            Log.w(
                TAG,
                "[nourish.explore.refresh-miss] filterKey=${key.ifEmpty { "(none)" }} " +
                    "previousCount=${previous!!.first.size} degraded=${previous.second}",
            )
            return NourishDiscovery.DiscoveryResult(
                recipes = previous.first,
                degraded = previous.second,
                refreshMiss = true,
            )
        }

        if (fresh.legitimateEmpty && fresh.recipes.isEmpty()) {
            val unfiltered = NourishDiscovery.getCachedDiscoveryEntry(emptyList())
            if (unfiltered != null && unfiltered.first.isNotEmpty()) {
                Log.w(
                    TAG,
                    "[nourish.explore.refresh-miss] filterKey=${key.ifEmpty { "(none)" }} " +
                        "reason=degrade-unfiltered-empty previousCount=${unfiltered.first.size}",
                )
                val degradedResult = NourishDiscovery.DiscoveryResult(
                    recipes = unfiltered.first,
                    degraded = true,
                    refreshMiss = true,
                )
                NourishDiscovery.putDiscoveryCache(uniqueFilters, degradedResult.recipes, degraded = true)
                return degradedResult
            }
        }

        if (fresh.recipes.isNotEmpty() || previous == null) {
            NourishDiscovery.putDiscoveryCache(uniqueFilters, fresh.recipes, fresh.degraded)
        }

        return NourishDiscovery.DiscoveryResult(
            recipes = fresh.recipes,
            degraded = fresh.degraded,
        )
    }

    private data class FreshRanked(
        val recipes: List<NourishDiscovery.RankedRecipe>,
        val degraded: Boolean,
        val legitimateEmpty: Boolean,
    )

    private suspend fun fetchFreshRanked(
        sortBy: NourishDiscovery.SortDimension,
        limit: Int,
        uniqueFilters: List<String>,
    ): FreshRanked {
        if (uniqueFilters.isEmpty()) {
            val events = fetchNourishEvents(label = null)
            Log.d(TAG, "[Nourish Explore] Found ${events.size} Nourish events")
            val analyses = NourishDiscovery.parseAnalyses(events)
            val recipes = resolveRecipesFromAnalyses(analyses, sortBy, limit)
            return FreshRanked(recipes, degraded = false, legitimateEmpty = false)
        }

        val primary = NourishDiscovery.pickMostSelectiveLabel(uniqueFilters)
        val remaining = uniqueFilters.filter { it != primary }

        val nourishEvents = fetchNourishEvents(label = primary)
        Log.d(
            TAG,
            "[Nourish Explore] Found ${nourishEvents.size} Nourish events for #l=$primary" +
                if (remaining.isNotEmpty()) " (intersect ${remaining.joinToString(",")})" else "",
        )

        // Transport miss on the primary `#l` REQ — not a confident filter miss.
        if (nourishEvents.isEmpty()) {
            return FreshRanked(emptyList(), degraded = false, legitimateEmpty = false)
        }

        val filteredList =
            if (remaining.isNotEmpty()) {
                NourishDiscovery.intersectEventsByLabels(nourishEvents, remaining)
            } else {
                nourishEvents
            }

        val analyses = NourishDiscovery.parseAnalyses(filteredList)

        if (NourishDiscovery.shouldDegradeFilteredResults(analyses.size)) {
            Log.d(TAG, "[Nourish Explore] Filtered set empty — degrading to ranked view")
            val allEvents = fetchNourishEvents(label = null)
            val allAnalyses = NourishDiscovery.parseAnalyses(allEvents)
            val recipes = resolveRecipesFromAnalyses(allAnalyses, sortBy, limit)
            return FreshRanked(recipes, degraded = true, legitimateEmpty = true)
        }

        val recipes = resolveRecipesFromAnalyses(analyses, sortBy, limit)
        return FreshRanked(recipes, degraded = false, legitimateEmpty = false)
    }

    /**
     * Pantry (persistent) → empty retry after ~1s → public-relay fallback.
     * Filter shape is always kinds=[30078] authors=[service] (+ optional #l)
     * so anonymous pantry reads are allowed by `isPublicNourishFilter`.
     */
    private suspend fun fetchNourishEvents(label: String?): List<NostrEvent> {
        val filter = NourishDiscovery.buildNourishAnalysisFilter(label)
        ensurePantryConnected()

        var events = reader.read(RelayConfig.MEMBERS_RELAY, filter, queryTimeoutMs = FETCH_TIMEOUT_MS)
            .filter { it.kind == NourishParser.KIND }

        if (events.isEmpty()) {
            delay(PANTRY_RETRY_DELAY_MS)
            ensurePantryConnected()
            events = reader.read(RelayConfig.MEMBERS_RELAY, filter, queryTimeoutMs = FETCH_TIMEOUT_MS)
                .filter { it.kind == NourishParser.KIND }
        }

        if (events.isEmpty()) {
            Log.d(TAG, "[Nourish Explore] No events on pantry after retry, trying public relays...")
            events = fetchFromPublicRelays(filter)
        }

        return events
    }

    private suspend fun fetchFromPublicRelays(filter: Filter): List<NostrEvent> = coroutineScope {
        val sub = "nourish-pub-${SEQ.incrementAndGet()}"
        val collected = ArrayList<NostrEvent>()
        val collector = launch {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId == sub && relayEvent.event.kind == NourishParser.KIND) {
                    collected.add(relayEvent.event)
                }
            }
        }
        try {
            val req = ClientMessage.req(sub, filter)
            var sent = 0
            for (url in PUBLIC_FALLBACK_RELAYS) {
                if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
            }
            if (sent > 0) {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    var remaining = sent
                    while (remaining > 0) {
                        relayPool.eoseSignals.filter { it == sub }.first()
                        remaining--
                    }
                }
                delay(STRAGGLER_MS)
            }
        } finally {
            collector.cancelAndJoin()
            relayPool.closeOnAllRelays(sub)
        }
        collected.distinctBy { it.id }
    }

    private suspend fun resolveRecipesFromAnalyses(
        analyses: List<NourishDiscovery.AnalysisRow>,
        sortBy: NourishDiscovery.SortDimension,
        limit: Int,
    ): List<NourishDiscovery.RankedRecipe> {
        if (analyses.isEmpty()) return emptyList()

        val sorted = NourishDiscovery.sortAnalyses(analyses, sortBy).take(limit)
        val missing = sorted.filter {
            NourishDiscovery.getCachedRecipeEvent(it.recipePubkey, it.recipeDTag) == null
        }

        if (missing.isNotEmpty()) {
            fetchAndCacheRecipes(missing)
            val stillMissing = missing.filter {
                NourishDiscovery.getCachedRecipeEvent(it.recipePubkey, it.recipeDTag) == null
            }
            if (stillMissing.isNotEmpty()) {
                delay(PANTRY_RETRY_DELAY_MS)
                fetchAndCacheRecipes(stillMissing)
            }
        }

        val results = ArrayList<NourishDiscovery.RankedRecipe>(sorted.size)
        for (analysis in sorted) {
            val event = NourishDiscovery.getCachedRecipeEvent(analysis.recipePubkey, analysis.recipeDTag)
                ?: continue
            val recipe = RecipeFormats.forEvent(event)?.parse(event)
                ?: runCatching { RecipeParser.parse(event) }.getOrNull()
                ?: continue
            results.add(
                NourishDiscovery.RankedRecipe(
                    recipe = recipe,
                    score = analysis.score,
                    createdAt = analysis.createdAt,
                    authorPubkey = analysis.recipePubkey,
                    recipeDTag = analysis.recipeDTag,
                ),
            )
        }
        return results
    }

    private suspend fun fetchAndCacheRecipes(rows: List<NourishDiscovery.AnalysisRow>) {
        if (rows.isEmpty()) return
        val authors = rows.map { it.recipePubkey }.distinct()
        val dTags = rows.map { it.recipeDTag }.distinct()
        val filter = Filter(
            kinds = listOf(RecipeParser.RECIPE_KIND),
            authors = authors,
            dTags = dTags,
            limit = (authors.size * 2).coerceAtLeast(50).coerceAtMost(200),
        )

        val events = fetchRecipeEvents(filter)
        for (event in events) {
            val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: continue
            if (d.isBlank()) continue
            NourishDiscovery.putCachedRecipeEvent(event.pubkey, d, event)
        }
    }

    private suspend fun fetchRecipeEvents(filter: Filter): List<NostrEvent> = coroutineScope {
        val sub = "nourish-recipes-${SEQ.incrementAndGet()}"
        val collected = ArrayList<NostrEvent>()
        val collector = launch {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != sub) return@collect
                val event = relayEvent.event
                if (event.kind != RecipeParser.RECIPE_KIND) return@collect
                collected.add(event)
            }
        }
        try {
            val req = ClientMessage.req(sub, filter)
            var sent = 0
            for (url in RelayConfig.ARTICLES_RELAYS) {
                if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
            }
            if (sent > 0) {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    var remaining = sent
                    while (remaining > 0) {
                        relayPool.eoseSignals.filter { it == sub }.first()
                        remaining--
                    }
                }
                delay(STRAGGLER_MS)
            }
        } finally {
            collector.cancelAndJoin()
            relayPool.closeOnAllRelays(sub)
        }
        collected.distinctBy { it.id }
    }

    /**
     * Persistent pantry membership — connect once, reuse across Explore
     * fetches instead of thrashing per-call ephemeral sockets (#532 lesson).
     */
    private fun ensurePantryConnected() {
        relayPool.ensureGroupRelay(RelayConfig.MEMBERS_RELAY)
        relayPool.autoApproveRelayAuth(RelayConfig.MEMBERS_RELAY)
    }

    fun clear() {
        scoreCache.clear()
        NourishDiscovery.resetSessionCaches()
    }

    companion object {
        private const val TAG = "NourishRepo"
        private const val FETCH_TIMEOUT_MS = 8_000L
        private const val PANTRY_RETRY_DELAY_MS = 1_000L
        private const val STRAGGLER_MS = 300L
        private val SEQ = AtomicLong(0)

        /** Public relays that historically mirrored Nourish 30078s (web fallback). */
        private val PUBLIC_FALLBACK_RELAYS = listOf(
            "wss://nos.lol",
            "wss://relay.damus.io",
            "wss://relay.primal.net",
        )
    }
}
