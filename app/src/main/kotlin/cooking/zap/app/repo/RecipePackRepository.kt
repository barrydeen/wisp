package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.Nip51
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.PackFormats
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.nostr.packKey
import cooking.zap.app.relay.OutboxRouter
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.relay.SubscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

data class RecipePackSummary(
    val event: NostrEvent,
    val author: String,
    val dTag: String,
    val title: String,
    val description: String,
    val image: String?,
    val recipeCount: Int,
)

data class PackRecipeCoordinate(
    val kind: Int,
    val author: String,
    val dTag: String,
)

/**
 * Read-only pack listing repository for PR A. Owns Discover / Mine / Saved fetches.
 */
class RecipePackRepository(
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val eventRepo: EventRepository,
    private val subManager: SubscriptionManager,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext = Dispatchers.Default,
    private val userReadRelaysProvider: () -> List<String> = { emptyList() },
    private val userPubkeyProvider: () -> String? = { null },
) {
    companion object {
        private const val PAGE_LIMIT = 60
        private const val SAVED_PACKS_DTAG = "zapcooking-saved-packs"
        const val OFFICIAL_PACKS_PUBKEY = "319ad3e790634dbe86f14db9c2995b26ee3c6228be55f89c4c7fea9acc01d50a"
        private const val EOSE_GRACE_MS = 2_000L
        private const val CACHE_LIMIT = 2_000
        private val RECIPE_KINDS = RecipeFormats.active.map { it.kind }.toSet()
    }

    private val _discoverPacks = MutableStateFlow<List<RecipePackSummary>>(emptyList())
    val discoverPacks: StateFlow<List<RecipePackSummary>> = _discoverPacks.asStateFlow()

    private val _minePacks = MutableStateFlow<List<RecipePackSummary>>(emptyList())
    val minePacks: StateFlow<List<RecipePackSummary>> = _minePacks.asStateFlow()

    private val _savedPacks = MutableStateFlow<List<RecipePackSummary>>(emptyList())
    val savedPacks: StateFlow<List<RecipePackSummary>> = _savedPacks.asStateFlow()

    private val _isDiscoverLoading = MutableStateFlow(false)
    val isDiscoverLoading: StateFlow<Boolean> = _isDiscoverLoading.asStateFlow()

    private val _isMineLoading = MutableStateFlow(false)
    val isMineLoading: StateFlow<Boolean> = _isMineLoading.asStateFlow()

    private val _isSavedLoading = MutableStateFlow(false)
    val isSavedLoading: StateFlow<Boolean> = _isSavedLoading.asStateFlow()

    private val subCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private var discoverJob: Job? = null
    private var mineJob: Job? = null
    private var savedJob: Job? = null

    /** Always-on cache paint for Discover tab activation/reselection. */
    fun paintDiscoverFromCache() {
        scope.launch(processingContext) {
            _discoverPacks.value = discoverPacksFromCache()
        }
    }

    /** Always-on cache paint for Mine tab activation/reselection. */
    fun paintMineFromCache(pubkey: String? = userPubkeyProvider()) {
        val author = pubkey?.trim().orEmpty()
        if (author.isBlank()) {
            _minePacks.value = emptyList()
            return
        }
        scope.launch(processingContext) {
            _minePacks.value = minePacksFromCache(author)
        }
    }

    /** Always-on cache paint for Saved tab activation/reselection. */
    fun paintSavedFromCache(pubkey: String? = userPubkeyProvider()) {
        val author = pubkey?.trim().orEmpty()
        if (author.isBlank()) {
            _savedPacks.value = emptyList()
            return
        }
        scope.launch(processingContext) {
            _savedPacks.value = savedPacksFromCache(author)
        }
    }

    fun loadDiscover(limit: Int = PAGE_LIMIT) {
        discoverJob?.cancel()
        discoverJob = scope.launch(processingContext) {
            _isDiscoverLoading.value = true
            try {
                val format = PackFormats.primary
                // Keep cache-first behavior in the fetch path too.
                _discoverPacks.value = discoverPacksFromCache()

                val events = queryPacks(
                    subPrefix = "pack-discover",
                    readFilters = listOf(format.packDiscoverFilter(limit = limit)),
                    authorScoped = listOf(
                        AuthorScopedFilter(
                            author = OFFICIAL_PACKS_PUBKEY,
                            filter = format.packMineFilter(author = OFFICIAL_PACKS_PUBKEY, limit = limit),
                        )
                    )
                )
                _discoverPacks.value = sanitizeAndSort(
                    events.filter { eventMatchesDiscoverTags(it) || it.pubkey == OFFICIAL_PACKS_PUBKEY }
                )
            } finally {
                _isDiscoverLoading.value = false
            }
        }
    }

    fun loadMine(pubkey: String? = userPubkeyProvider(), limit: Int = PAGE_LIMIT) {
        val author = pubkey?.trim().orEmpty()
        mineJob?.cancel()
        if (author.isBlank()) {
            _minePacks.value = emptyList()
            return
        }
        mineJob = scope.launch(processingContext) {
            _isMineLoading.value = true
            try {
                val format = PackFormats.primary
                // Cache-first paint for my packs.
                _minePacks.value = minePacksFromCache(author)
                val events = queryPacks(
                    subPrefix = "pack-mine",
                    readFilters = listOf(format.packMineFilter(author = author, limit = limit)),
                    authorScoped = listOf(
                        AuthorScopedFilter(
                            author = author,
                            filter = format.packMineFilter(author = author, limit = limit),
                        )
                    )
                )
                _minePacks.value = sanitizeAndSort(events)
            } finally {
                _isMineLoading.value = false
            }
        }
    }

    fun loadSaved(pubkey: String? = userPubkeyProvider()) {
        val author = pubkey?.trim().orEmpty()
        savedJob?.cancel()
        if (author.isBlank()) {
            _savedPacks.value = emptyList()
            return
        }
        savedJob = scope.launch(processingContext) {
            _isSavedLoading.value = true
            try {
                // Cache-first saved-list + saved-pack paint.
                val cachedSavedList = cachedSavedListEvent(author)
                val cachedCoordinates = cachedSavedList
                    ?.let { Nip51.parseBookmarkSet(it) }
                    ?.coordinates
                    ?.mapNotNull { parseSavedPackCoordinate(it) }
                    ?.groupBy({ it.first }, { it.second })
                    ?.mapValues { it.value.toSet() }
                    .orEmpty()
                _savedPacks.value = savedPacksFromCache(author, cachedCoordinates)

                val savedListFilter = Filter(
                    kinds = listOf(Nip51.KIND_BOOKMARK_SET),
                    authors = listOf(author),
                    dTags = listOf(SAVED_PACKS_DTAG),
                    limit = 1,
                )
                val savedListEvents = queryPacks(
                    subPrefix = "pack-saved-list",
                    readFilters = listOf(savedListFilter),
                    authorScoped = listOf(
                        AuthorScopedFilter(
                            author = author,
                            filter = savedListFilter,
                        )
                    )
                )
                val newestSavedList = dedupeNewestPerPackCoordinate(
                    buildList {
                        cachedSavedList?.let(::add)
                        addAll(savedListEvents)
                    }
                ).firstOrNull()
                val savedCoordinates = newestSavedList
                    ?.let { Nip51.parseBookmarkSet(it) }
                    ?.coordinates
                    ?.mapNotNull { parseSavedPackCoordinate(it) }
                    ?.groupBy({ it.first }, { it.second })
                    ?.mapValues { it.value.toSet() }
                    .orEmpty()
                if (savedCoordinates.isEmpty()) {
                    _savedPacks.value = emptyList()
                    return@launch
                }

                val byAuthorFilters = savedCoordinates.map { (packAuthor, dTags) ->
                    Filter(
                        kinds = listOf(PackFormats.primary.kind),
                        authors = listOf(packAuthor),
                        dTags = dTags.toList(),
                        limit = dTags.size,
                    )
                }
                val savedAuthorScoped = savedCoordinates.map { (packAuthor, dTags) ->
                    AuthorScopedFilter(
                        author = packAuthor,
                        filter = Filter(
                            kinds = listOf(PackFormats.primary.kind),
                            authors = listOf(packAuthor),
                            dTags = dTags.toList(),
                            limit = dTags.size,
                        )
                    )
                }
                val savedPackEvents = queryPacks(
                    subPrefix = "pack-saved-resolve",
                    readFilters = byAuthorFilters,
                    authorScoped = savedAuthorScoped,
                )
                _savedPacks.value = sanitizeAndSort(savedPackEvents)
            } finally {
                _isSavedLoading.value = false
            }
        }
    }

    /**
     * Resolve one pack event by addressable coordinate.
     * Cache-first via ObjectBox, then relay fill through the same union+outbox
     * query strategy used by listing coverage.
     */
    suspend fun requestPackEvent(author: String, dTag: String): NostrEvent? {
        val normalizedAuthor = author.trim()
        val normalizedDTag = dTag.trim()
        if (normalizedAuthor.isBlank() || normalizedDTag.isBlank()) return null

        val cached = cachedPackEventByCoordinate(normalizedAuthor, normalizedDTag)
        val queried = queryPacks(
            subPrefix = "pack-coordinate",
            readFilters = PackFormats.active.map { it.packByCoordinateFilter(normalizedAuthor, normalizedDTag) },
            authorScoped = PackFormats.active.map {
                AuthorScopedFilter(
                    author = normalizedAuthor,
                    filter = it.packByCoordinateFilter(normalizedAuthor, normalizedDTag),
                )
            }
        )
        return dedupeNewestPerPackCoordinate(
            buildList {
                cached?.let(::add)
                addAll(queried)
            }
        ).firstOrNull { event ->
            PackFormats.forEvent(event) != null &&
                event.pubkey == normalizedAuthor &&
                eventHasDTag(event, normalizedDTag)
        }
    }

    /** Extract de-duplicated recipe coordinates from one pack event, in tag order. */
    fun extractRecipeCoordinates(event: NostrEvent): List<PackRecipeCoordinate> {
        val seen = LinkedHashSet<String>()
        return event.tags
            .asSequence()
            .filter { it.size >= 2 && it[0] == "a" }
            .mapNotNull { parseRecipeCoordinate(it[1]) }
            .map { PackRecipeCoordinate(kind = it.first, author = it.second, dTag = it.third) }
            .filter { coord ->
                val key = "${coord.kind}:${coord.author}:${coord.dTag}"
                seen.add(key)
            }
            .toList()
    }

    /** Parse one pack summary for detail/listing surfaces. */
    fun parseSummary(event: NostrEvent): RecipePackSummary? = parsePackSummary(event)

    private data class AuthorScopedFilter(
        val author: String,
        val filter: Filter,
    )

    private suspend fun queryPacks(
        subPrefix: String,
        readFilters: List<Filter>,
        authorScoped: List<AuthorScopedFilter> = emptyList(),
    ): List<NostrEvent> = withContext(processingContext) {
        if (readFilters.isEmpty() && authorScoped.isEmpty()) return@withContext emptyList()
        val subId = "$subPrefix-${subCounter.getAndIncrement()}"
        val collected = mutableListOf<NostrEvent>()
        val seenIds = mutableSetOf<String>()
        val targetedRelays = mutableSetOf<String>()
        val collector = scope.launch(processingContext) {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != subId) return@collect
                val event = relayEvent.event
                if (event.id in seenIds) return@collect
                if (PackFormats.forEvent(event) == null && event.kind != Nip51.KIND_BOOKMARK_SET) return@collect
                seenIds.add(event.id)
                collected.add(event)
                eventRepo.cacheEvent(event)
                eventRepo.requestProfileIfMissing(event.pubkey)
            }
        }

        if (readFilters.isNotEmpty()) {
            val req = ClientMessage.req(subId, readFilters)
            for (url in discoverReadRelays()) {
                if (relayPool.sendToRelayOrEphemeral(url, req)) targetedRelays.add(url)
            }
        }
        for (scoped in authorScoped) {
            targetedRelays.addAll(
                outboxRouter.subscribeToUserWriteRelays(subId, scoped.author, scoped.filter)
            )
        }
        // Count each relay once per subId; overlapping author-scoped routes share EOSE.
        val expectedEose = targetedRelays.count { relayPool.healthTracker?.isBad(it) != true }

        try {
            if (expectedEose > 0) {
                subManager.awaitEoseCount(subId, expectedCount = expectedEose, timeoutMs = 8_000)
                // Mirror RecipeRepository's grace: don't drop just-arriving events from slow relays.
                delay(EOSE_GRACE_MS)
            }
        } finally {
            collector.cancelAndJoin()
            subManager.closeSubscription(subId)
        }
        return@withContext collected.toList()
    }

    private fun discoverReadRelays(): List<String> {
        val bad = relayPool.healthTracker?.getBadRelays().orEmpty()
        val union = LinkedHashSet<String>()
        fun add(url: String) {
            val normalized = url.trim().trimEnd('/')
            if (normalized.isBlank()) return
            if (normalized in bad) return
            union.add(normalized)
        }
        RelayConfig.PACK_STANDARD_RELAYS.forEach(::add)
        RelayConfig.DEFAULT_INDEXER_RELAYS.forEach(::add)
        RelayConfig.DEFAULTS.filter { it.read }.forEach { add(it.url) }
        userReadRelaysProvider().forEach(::add)
        return union.toList()
    }

    private fun cachedPackEvents(limit: Int = CACHE_LIMIT): List<NostrEvent> {
        val persistence = eventRepo.eventPersistence ?: return emptyList()
        val events = PackFormats.active.flatMap { persistence.getEventsByKind(it.kind, limit) }
        return dedupeNewestPerPackCoordinate(events)
    }

    private fun discoverPacksFromCache(): List<RecipePackSummary> {
        val cached = cachedPackEvents()
            .filter { eventMatchesDiscoverTags(it) || it.pubkey == OFFICIAL_PACKS_PUBKEY }
        return sanitizeAndSort(cached)
    }

    private fun minePacksFromCache(author: String): List<RecipePackSummary> {
        return sanitizeAndSort(cachedPackEvents().filter { it.pubkey == author })
    }

    private fun savedPacksFromCache(
        author: String,
        cachedCoordinatesOverride: Map<String, Set<String>>? = null,
    ): List<RecipePackSummary> {
        val cachedCoordinates = cachedCoordinatesOverride ?: cachedSavedListEvent(author)
            ?.let { Nip51.parseBookmarkSet(it) }
            ?.coordinates
            ?.mapNotNull { parseSavedPackCoordinate(it) }
            ?.groupBy({ it.first }, { it.second })
            ?.mapValues { it.value.toSet() }
            .orEmpty()
        if (cachedCoordinates.isEmpty()) return emptyList()
        val cachedSavedPacks = cachedPackEvents().filter { event ->
            val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)?.trim()
            val wanted = cachedCoordinates[event.pubkey]
            dTag != null && wanted != null && dTag in wanted
        }
        return sanitizeAndSort(cachedSavedPacks)
    }

    private fun cachedPackEventByCoordinate(author: String, dTag: String): NostrEvent? {
        val fromCache = PackFormats.active.asSequence()
            .mapNotNull { eventRepo.findAddressableEvent(it.kind, author, dTag) }
            .firstOrNull { eventHasDTag(it, dTag) }

        val fromDb = eventRepo.eventPersistence
            ?.let { persistence ->
                PackFormats.active.flatMap { persistence.getEventsByAuthorAndKind(author, it.kind, limit = 200) }
                    .filter { eventHasDTag(it, dTag) }
            }
            .orEmpty()

        return dedupeNewestPerPackCoordinate(
            buildList {
                fromCache?.let(::add)
                addAll(fromDb)
            }
        ).firstOrNull()
    }

    private fun cachedSavedListEvent(author: String, limit: Int = CACHE_LIMIT): NostrEvent? {
        eventRepo.findAddressableEvent(Nip51.KIND_BOOKMARK_SET, author, SAVED_PACKS_DTAG)?.let { return it }
        val persistence = eventRepo.eventPersistence ?: return null
        val events = persistence.getEventsByKind(Nip51.KIND_BOOKMARK_SET, limit)
            .filter { it.pubkey == author }
            .filter { e ->
                e.tags.any { it.size >= 2 && it[0] == "d" && it[1] == SAVED_PACKS_DTAG }
            }
        return dedupeNewestPerPackCoordinate(events).firstOrNull()
    }

    private fun eventMatchesDiscoverTags(event: NostrEvent): Boolean {
        val tags = event.tags.asSequence()
            .filter { it.size >= 2 && it[0] == "t" }
            .map { it[1].trim().lowercase() }
            .toSet()
        return "zap-cooking" in tags && "recipe-pack" in tags
    }

    private fun eventHasDTag(event: NostrEvent, dTag: String): Boolean {
        val needle = dTag.trim()
        return event.tags.any { it.size >= 2 && it[0] == "d" && it[1].trim() == needle }
    }

    private fun sanitizeAndSort(events: List<NostrEvent>): List<RecipePackSummary> {
        return dedupeNewestPerPackCoordinate(events)
            .mapNotNull { parsePackSummary(it) }
            .sortedByDescending { it.event.created_at }
    }

    private fun parsePackSummary(event: NostrEvent): RecipePackSummary? {
        if (PackFormats.forEvent(event) == null) return null
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)?.trim().orEmpty()
        if (dTag.isBlank()) return null
        val recipeATags = event.tags
            .asSequence()
            .filter { it.size >= 2 && it[0] == "a" }
            .map { it[1] }
            .filter { parseRecipeCoordinate(it) != null }
            .toList()
        if (recipeATags.isEmpty()) return null
        val title = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: dTag
        val description = event.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)?.trim().orEmpty()
        val image = event.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
        return RecipePackSummary(
            event = event,
            author = event.pubkey,
            dTag = dTag,
            title = title,
            description = description,
            image = image,
            recipeCount = recipeATags.size,
        )
    }

    private fun parseSavedPackCoordinate(raw: String): Pair<String, String>? {
        val parts = raw.split(":", limit = 3)
        if (parts.size != 3) return null
        val kind = parts[0].toIntOrNull() ?: return null
        if (kind != PackFormats.primary.kind) return null
        val pubkey = parts[1].trim()
        val dTag = parts[2].trim()
        if (pubkey.isBlank() || dTag.isBlank()) return null
        return pubkey to dTag
    }

    private fun parseRecipeCoordinate(raw: String): Triple<Int, String, String>? {
        val parts = raw.split(":", limit = 3)
        if (parts.size != 3) return null
        val kind = parts[0].toIntOrNull() ?: return null
        if (kind !in RECIPE_KINDS) return null
        val pubkey = parts[1].trim()
        val dTag = parts[2].trim()
        if (pubkey.isBlank() || dTag.isBlank()) return null
        return Triple(kind, pubkey, dTag)
    }
}

private fun dedupeNewestPerPackCoordinate(events: Iterable<NostrEvent>): List<NostrEvent> {
    val byCoord = LinkedHashMap<cooking.zap.app.nostr.PackKey, NostrEvent>()
    for (event in events) {
        val key = packKey(event)
        val existing = byCoord[key]
        byCoord[key] = if (existing == null) event else preferNewerPack(existing, event)
    }
    return byCoord.values.toList()
}

private fun preferNewerPack(a: NostrEvent, b: NostrEvent): NostrEvent = when {
    a.created_at != b.created_at -> if (a.created_at > b.created_at) a else b
    else -> if (a.id <= b.id) a else b
}

