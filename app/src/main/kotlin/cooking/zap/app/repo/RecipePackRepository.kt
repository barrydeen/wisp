package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.Nip51
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.PackFormats
import cooking.zap.app.nostr.packKey
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.relay.SubscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

/**
 * Read-only pack listing repository for PR A. Owns Discover / Mine / Saved fetches.
 */
class RecipePackRepository(
    private val relayPool: RelayPool,
    private val eventRepo: EventRepository,
    private val subManager: SubscriptionManager,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext = Dispatchers.Default,
    private val userPubkeyProvider: () -> String? = { null },
) {
    companion object {
        private const val PAGE_LIMIT = 60
        private const val SAVED_PACKS_DTAG = "zapcooking-saved-packs"
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

    fun loadDiscover(limit: Int = PAGE_LIMIT) {
        discoverJob?.cancel()
        discoverJob = scope.launch(processingContext) {
            _isDiscoverLoading.value = true
            try {
                val format = PackFormats.primary
                val events = queryAcrossStandardRelays(
                    filters = listOf(format.packDiscoverFilter(limit = limit)),
                    subPrefix = "pack-discover"
                )
                _discoverPacks.value = sanitizeAndSort(events)
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
                val events = queryAcrossStandardRelays(
                    filters = listOf(format.packMineFilter(author = author, limit = limit)),
                    subPrefix = "pack-mine"
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
                val savedListEvents = queryAcrossStandardRelays(
                    filters = listOf(
                        Filter(
                            kinds = listOf(Nip51.KIND_BOOKMARK_SET),
                            authors = listOf(author),
                            dTags = listOf(SAVED_PACKS_DTAG),
                            limit = 1,
                        )
                    ),
                    subPrefix = "pack-saved-list"
                )
                val newestSavedList = dedupeNewestPerPackCoordinate(savedListEvents).firstOrNull()
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
                val savedPackEvents = queryAcrossStandardRelays(
                    filters = byAuthorFilters,
                    subPrefix = "pack-saved-resolve"
                )
                _savedPacks.value = sanitizeAndSort(savedPackEvents)
            } finally {
                _isSavedLoading.value = false
            }
        }
    }

    private suspend fun queryAcrossStandardRelays(
        filters: List<Filter>,
        subPrefix: String,
    ): List<NostrEvent> {
        if (filters.isEmpty()) return emptyList()
        val subId = "$subPrefix-${subCounter.getAndIncrement()}"
        val req = ClientMessage.req(subId, filters)
        val collected = mutableListOf<NostrEvent>()
        val collector = scope.launch(processingContext) {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != subId) return@collect
                collected.add(relayEvent.event)
                eventRepo.cacheEvent(relayEvent.event)
                eventRepo.requestProfileIfMissing(relayEvent.event.pubkey)
            }
        }
        var sent = 0
        for (url in RelayConfig.PACK_STANDARD_RELAYS) {
            if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
        }
        try {
            if (sent > 0) {
                subManager.awaitEoseCount(subId, expectedCount = sent, timeoutMs = 8_000)
            }
        } finally {
            collector.cancel()
            subManager.closeSubscription(subId)
        }
        return collected
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
            .filter { parseSavedPackCoordinate(it) != null }
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

