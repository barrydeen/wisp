package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.RecipeFormats
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * A14 recipe-bookmark interop. Reads AND writes the **same canonical list the
 * Zap Cooking web app uses** so recipe bookmarks round-trip cross-client.
 *
 * Web contract (src/lib/stores/cookbookStore.ts): a NIP-51 generic list of
 * **kind 30001**, default `d`-tag `"nostrcooking-bookmarks"`, with each saved
 * recipe referenced by its **a-tag coordinate** (`kind:pubkey:dTag`). The list
 * carries cookbook metadata (title/summary/image/cover) that must survive every
 * republish.
 *
 * Deliberately NOT the kind-10003/30003 note-bookmark path (left untouched) and
 * NOT the kind-30003 `zapcooking-saved-packs` list (a separate concern). The one
 * hardcoded constant here is the list kind ([LIST_KIND]); recipe coordinates are
 * built format-agnostically via [RecipeFormats] so there's no hardcoded 30023.
 *
 * Mirrors the RecipePackRepository relay strategy: cache-first paint, then a
 * broadened read union + OutboxRouter author-scoped routing to the user's write
 * relays with an EOSE grace window.
 */
class RecipeBookmarkRepository(
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val eventRepo: EventRepository,
    private val subManager: SubscriptionManager,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext = Dispatchers.Default,
    private val userReadRelaysProvider: () -> List<String> = { emptyList() },
    private val userPubkeyProvider: () -> String? = { null },
    private val signerProvider: () -> NostrSigner? = { null },
) {
    companion object {
        /** The web's canonical recipe-bookmark list kind (NIP-51 generic list). */
        const val LIST_KIND = 30001
        /** Default Saved list `d`-tag (the list has NO `t` tag — key reads on this). */
        const val DEFAULT_LIST_DTAG = "nostrcooking-bookmarks"
        /** Seed title when creating the list fresh. */
        const val DEFAULT_LIST_TITLE = "Saved"
        private const val EOSE_GRACE_MS = 2_000L
        private const val CACHE_LIMIT = 2_000
    }

    private val _bookmarkedCoordinates = MutableStateFlow<Set<String>>(emptySet())
    /** The recipe a-coordinates currently in the canonical Saved list. */
    val bookmarkedCoordinates: StateFlow<Set<String>> = _bookmarkedCoordinates.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Newest known canonical list event — the carry-forward source on republish.
    @Volatile
    private var listEvent: NostrEvent? = null

    private val subCounter = AtomicInteger(0)
    private var loadJob: Job? = null
    private val toggleMutex = Mutex()

    /**
     * Build the format-agnostic addressable coordinate (`kind:pubkey:dTag`) for a
     * recipe event, or null if the event isn't a recognized recipe / has no `d`.
     */
    fun coordinateForEvent(event: NostrEvent): String? {
        val format = RecipeFormats.forEvent(event) ?: return null
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)?.trim()
        if (dTag.isNullOrBlank()) return null
        return "${format.kind}:${event.pubkey}:$dTag"
    }

    /** True iff [event]'s coordinate is in the canonical Saved list. */
    fun isRecipeBookmarked(event: NostrEvent): Boolean {
        val coord = coordinateForEvent(event) ?: return false
        return _bookmarkedCoordinates.value.contains(coord)
    }

    /** Always-on cache paint (instant cold-start before relay-fill). */
    fun paintFromCache(pubkey: String? = userPubkeyProvider()) {
        val author = pubkey?.trim().orEmpty()
        if (author.isBlank()) {
            reset()
            return
        }
        scope.launch(processingContext) {
            cachedListEvent(author)?.let { applyEvent(it) }
        }
    }

    /** Cache-first paint then broadened-union + outbox + EOSE-grace relay fill. */
    fun load(pubkey: String? = userPubkeyProvider()) {
        val author = pubkey?.trim().orEmpty()
        loadJob?.cancel()
        if (author.isBlank()) {
            reset()
            return
        }
        loadJob = scope.launch(processingContext) {
            _isLoading.value = true
            try {
                cachedListEvent(author)?.let { applyEvent(it) }
                val filter = listFilter(author)
                val events = queryList(subPrefix = "recipe-bookmarks", author = author, filter = filter)
                val newest = buildList {
                    listEvent?.let(::add)
                    addAll(events)
                }
                    .filter { it.kind == LIST_KIND && it.pubkey == author && hasDTag(it, DEFAULT_LIST_DTAG) }
                    .maxByOrNull { it.created_at }
                if (newest != null) applyEvent(newest)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Apply a cached or inbound (streamed) list event, newest-wins. Called by the
     * EventRouter when the user's own kind-30001 Saved list arrives.
     */
    fun applyEvent(event: NostrEvent) {
        if (event.kind != LIST_KIND) return
        if (!hasDTag(event, DEFAULT_LIST_DTAG)) return
        val current = listEvent
        if (current != null && event.created_at <= current.created_at) return
        listEvent = event
        eventRepo.cacheEvent(event)
        _bookmarkedCoordinates.value = parseCoordinates(event)
    }

    /**
     * Toggle [event]'s recipe coordinate in the canonical Saved list and publish
     * the replaceable kind-30001 to the user's write relays.
     *
     * Carries forward the existing list's title/summary/image/cover and ALL
     * unknown tags so web-set cookbook metadata is never clobbered. Returns the
     * new bookmarked state (true = now saved), or the current state unchanged if
     * the event isn't a recipe or there's no signing key.
     */
    suspend fun toggle(event: NostrEvent): Boolean {
        val coord = coordinateForEvent(event) ?: return isRecipeBookmarked(event)
        val signer = signerProvider() ?: return _bookmarkedCoordinates.value.contains(coord)
        return toggleMutex.withLock {
            val author = signer.pubkeyHex
            // Refresh the carry-forward base from the newest we know (cache included).
            val base = listEvent ?: cachedListEvent(author)
            val currentCoords = base?.let { parseCoordinates(it) } ?: _bookmarkedCoordinates.value
            val nextCoords = LinkedHashSet(currentCoords)
            val nowBookmarked: Boolean
            if (nextCoords.contains(coord)) {
                nextCoords.remove(coord)
                nowBookmarked = false
            } else {
                nextCoords.add(coord)
                nowBookmarked = true
            }
            val tags = buildToggledTags(base, nextCoords)
            val content = base?.content.orEmpty()
            val signed = signer.signEvent(kind = LIST_KIND, content = content, tags = tags)
            // Optimistic local apply (created_at is now, so it wins).
            applyEvent(signed)
            relayPool.sendToWriteRelays(ClientMessage.event(signed))
            nowBookmarked
        }
    }

    /**
     * Batch-add recipe coordinates to the canonical list in a **single**
     * republish (used by the one-time legacy migration — never republish per
     * bookmark). Carries forward title/summary/image/cover + unknown tags like
     * [toggle]. Returns the coordinates that were newly added; empty for a
     * read-only account or when every coordinate is already saved.
     */
    suspend fun addCoordinates(coordinates: Collection<String>): Set<String> {
        if (coordinates.isEmpty()) return emptySet()
        val signer = signerProvider() ?: return emptySet()
        return toggleMutex.withLock {
            val author = signer.pubkeyHex
            val base = listEvent ?: cachedListEvent(author)
            val currentCoords = base?.let { parseCoordinates(it) } ?: _bookmarkedCoordinates.value
            val nextCoords = LinkedHashSet(currentCoords)
            val added = coordinates.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && nextCoords.add(it) }
                .toCollection(LinkedHashSet())
            if (added.isEmpty()) return@withLock emptySet()
            val tags = buildToggledTags(base, nextCoords)
            val content = base?.content.orEmpty()
            val signed = signer.signEvent(kind = LIST_KIND, content = content, tags = tags)
            applyEvent(signed)
            relayPool.sendToWriteRelays(ClientMessage.event(signed))
            added
        }
    }

    /**
     * One-time legacy migration (A14 PR 2). Resolves [legacyEventIds] (the old
     * kind-10003 note-bookmark e-ids), keeps the ones that resolve to a
     * recognized recipe ([RecipeFormats]), and batch-adds their a-coordinates to
     * the canonical list in a single republish. **ADD-ONLY** — does not touch the
     * legacy 10003 list (PR 1's read-union dedups by coordinate, so nothing
     * double-displays and no bookmark can be stranded).
     *
     * Returns a [MigrationOutcome]. **`complete` is false when relays were
     * unreachable for ids that weren't already cached** — the caller must NOT
     * persist the one-shot flag in that case, so the migration retries on a
     * later launch instead of permanently stranding un-backfilled bookmarks.
     */
    suspend fun migrateLegacyBookmarks(legacyEventIds: Set<String>): MigrationOutcome {
        if (legacyEventIds.isEmpty()) return MigrationOutcome(added = emptySet(), complete = true)
        if (signerProvider() == null) return MigrationOutcome(added = emptySet(), complete = false)
        val resolution = resolveEventsByIds(legacyEventIds)
        // coordinateForEvent returns null for non-recipe events, so non-recipe
        // and unresolved e-ids are naturally skipped (never lost — they stay in
        // the legacy 10003 list untouched).
        val coords = resolution.events
            .mapNotNull { coordinateForEvent(it) }
            .toCollection(LinkedHashSet())
        // Publish whatever we could resolve now (idempotent); if relays were
        // unreachable for the still-missing ids, complete=false so the caller
        // leaves the flag unset and retries on a later launch.
        val added = if (coords.isEmpty()) emptySet() else addCoordinates(coords)
        return MigrationOutcome(added = added, complete = resolution.complete)
    }

    fun reset() {
        // Cancel any in-flight load so a stale fetch can't repopulate state after
        // an account switch.
        loadJob?.cancel()
        loadJob = null
        listEvent = null
        _isLoading.value = false
        _bookmarkedCoordinates.value = emptySet()
    }

    /**
     * Resolve events by id, cache-first then a single broadened-union relay REQ.
     * [ResolveResult.complete] is false when there were uncached ids but no relay
     * acknowledged the query (EOSE), i.e. the network was effectively unreachable
     * and the result can't be trusted as exhaustive.
     */
    private suspend fun resolveEventsByIds(ids: Set<String>): ResolveResult = withContext(processingContext) {
        if (ids.isEmpty()) return@withContext ResolveResult(events = emptyList(), complete = true)
        val resolved = LinkedHashMap<String, NostrEvent>()
        ids.forEach { id -> eventRepo.getEvent(id)?.let { resolved[id] = it } }
        val missing = ids.filterNot { it in resolved }.toSet()
        if (missing.isEmpty()) return@withContext ResolveResult(events = resolved.values.toList(), complete = true)

        val subId = "recipe-bm-migrate-${subCounter.getAndIncrement()}"
        val collector = scope.launch(processingContext) {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != subId) return@collect
                val event = relayEvent.event
                if (event.id !in missing || event.id in resolved) return@collect
                resolved[event.id] = event
                eventRepo.cacheEvent(event)
            }
        }
        val req = ClientMessage.req(subId, Filter(ids = missing.toList()))
        val targetedRelays = mutableSetOf<String>()
        for (url in readRelays()) {
            if (relayPool.sendToRelayOrEphemeral(url, req)) targetedRelays.add(url)
        }
        val expectedEose = targetedRelays.count { relayPool.healthTracker?.isBad(it) != true }
        var eoseCount = 0
        try {
            if (expectedEose > 0) {
                eoseCount = subManager.awaitEoseCount(subId, expectedCount = expectedEose, timeoutMs = 8_000)
                delay(EOSE_GRACE_MS)
            }
        } finally {
            collector.cancelAndJoin()
            subManager.closeSubscription(subId)
        }
        // A fair shot at the network requires at least one relay to have answered.
        // No EOSE (offline / all targets timed out) => not exhaustive => retry later.
        val complete = resolved.keys.containsAll(missing) || eoseCount > 0
        ResolveResult(events = resolved.values.toList(), complete = complete)
    }

    /** Result of [migrateLegacyBookmarks]: coordinates added + whether the run was exhaustive. */
    data class MigrationOutcome(val added: Set<String>, val complete: Boolean)

    private data class ResolveResult(val events: List<NostrEvent>, val complete: Boolean)

    private suspend fun queryList(
        subPrefix: String,
        author: String,
        filter: Filter,
    ): List<NostrEvent> = withContext(processingContext) {
        val subId = "$subPrefix-${subCounter.getAndIncrement()}"
        val collected = mutableListOf<NostrEvent>()
        val seenIds = mutableSetOf<String>()
        val targetedRelays = mutableSetOf<String>()
        val collector = scope.launch(processingContext) {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != subId) return@collect
                val event = relayEvent.event
                if (event.id in seenIds) return@collect
                if (event.kind != LIST_KIND) return@collect
                if (event.pubkey != author) return@collect
                if (!hasDTag(event, DEFAULT_LIST_DTAG)) return@collect
                seenIds.add(event.id)
                collected.add(event)
                eventRepo.cacheEvent(event)
            }
        }

        val req = ClientMessage.req(subId, listOf(filter))
        for (url in readRelays()) {
            if (relayPool.sendToRelayOrEphemeral(url, req)) targetedRelays.add(url)
        }
        // Author-scoped outbox routing — the user's write relays are authoritative
        // for their own self-list (matches the web's publish target).
        targetedRelays.addAll(outboxRouter.subscribeToUserWriteRelays(subId, author, filter))

        val expectedEose = targetedRelays.count { relayPool.healthTracker?.isBad(it) != true }
        try {
            if (expectedEose > 0) {
                subManager.awaitEoseCount(subId, expectedCount = expectedEose, timeoutMs = 8_000)
                delay(EOSE_GRACE_MS)
            }
        } finally {
            collector.cancelAndJoin()
            subManager.closeSubscription(subId)
        }
        return@withContext collected.toList()
    }

    private fun listFilter(author: String): Filter = Filter(
        kinds = listOf(LIST_KIND),
        authors = listOf(author),
        dTags = listOf(DEFAULT_LIST_DTAG),
        limit = 1,
    )

    private fun readRelays(): List<String> {
        val bad = relayPool.healthTracker?.getBadRelays().orEmpty()
        val union = LinkedHashSet<String>()
        fun add(url: String) {
            val normalized = url.trim().trimEnd('/')
            if (normalized.isBlank()) return
            if (normalized in bad) return
            union.add(normalized)
        }
        RelayConfig.DEFAULT_INDEXER_RELAYS.forEach(::add)
        RelayConfig.DEFAULTS.filter { it.read }.forEach { add(it.url) }
        userReadRelaysProvider().forEach(::add)
        return union.toList()
    }

    private fun cachedListEvent(author: String): NostrEvent? {
        eventRepo.findAddressableEvent(LIST_KIND, author, DEFAULT_LIST_DTAG)?.let { return it }
        val persistence = eventRepo.eventPersistence ?: return null
        return persistence.getEventsByAuthorAndKind(author, LIST_KIND, limit = CACHE_LIMIT)
            .filter { hasDTag(it, DEFAULT_LIST_DTAG) }
            .maxByOrNull { it.created_at }
    }

    private fun parseCoordinates(event: NostrEvent): Set<String> {
        return event.tags
            .asSequence()
            .filter { it.size >= 2 && it[0] == "a" }
            .map { it[1].trim() }
            .filter { it.isNotBlank() }
            .toCollection(LinkedHashSet())
    }

    /**
     * Rebuild the list tags from [existing], preserving everything except the
     * `a` tags (rewritten to [nextCoords]) and the misattributing `client` tag.
     * Guarantees a canonical `d` tag and a `title` (seeding "Saved" when fresh).
     */
    private fun buildToggledTags(existing: NostrEvent?, nextCoords: Set<String>): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        var hasD = false
        var hasTitle = false
        existing?.tags?.forEach { tag ->
            if (tag.isEmpty()) return@forEach
            when (tag[0]) {
                "a" -> Unit // dropped; the full coordinate set is re-added below
                "client" -> Unit // dropped to avoid misattribution on republish
                "d" -> {
                    if (!hasD) {
                        tags.add(listOf("d", DEFAULT_LIST_DTAG))
                        hasD = true
                    }
                }
                "title" -> {
                    if (!hasTitle) {
                        tags.add(tag.toList())
                        hasTitle = true
                    }
                }
                else -> tags.add(tag.toList()) // carry forward summary/image/cover/t/unknown
            }
        }
        if (!hasD) tags.add(0, listOf("d", DEFAULT_LIST_DTAG))
        if (!hasTitle) tags.add(listOf("title", DEFAULT_LIST_TITLE))
        nextCoords.forEach { tags.add(listOf("a", it)) }
        return tags
    }

    private fun hasDTag(event: NostrEvent, dTag: String): Boolean {
        val needle = dTag.trim()
        return event.tags.any { it.size >= 2 && it[0] == "d" && it[1].trim() == needle }
    }
}
