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
 * A14 recipe-bookmark interop. Reads AND writes the **same canonical lists the
 * Zap Cooking web app uses** so recipe collections round-trip cross-client.
 *
 * Web contract (src/lib/stores/cookbookStore.ts): every recipe list is a NIP-51
 * generic list of **kind 30001**, with each saved recipe referenced by its
 * **a-tag coordinate** (`kind:pubkey:dTag`). There are two flavours:
 *  - the **default Saved list**: `d`-tag [DEFAULT_LIST_DTAG], with **no `t` tag**
 *    (the web reads it by `#d`); and
 *  - **named collections**: a slug `d`-tag plus a recipe `t` tag
 *    ([COLLECTION_TAG]) so the web can enumerate them by `#t`.
 *
 * Each list carries cookbook metadata (title/summary/image/cover) that must
 * survive every republish — alongside any unknown tags.
 *
 * Deliberately NOT the kind-10003/30003 note-bookmark path (left untouched) and
 * NOT the kind-30003 `zapcooking-saved-packs` list (a separate concern). The
 * only hardcoded constant is the list kind ([LIST_KIND]); recipe coordinates
 * are built format-agnostically via [RecipeFormats] so there's no hardcoded
 * 30023.
 *
 * Mirrors the RecipePackRepository relay strategy: cache-first paint, then a
 * broadened read union + OutboxRouter author-scoped routing to the user's write
 * relays with an EOSE grace window.
 *
 * Scope (PR 3a): read all of the user's recipe lists, single-tap toggle of the
 * default list, multi-membership add/remove against any list, and create-by-name.
 * Rename / delete / cover editing live with the Cookbook screen (PR 3b).
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
        /** The web's canonical recipe-list kind (NIP-51 generic list). */
        const val LIST_KIND = 30001
        /** NIP-09 deletion event kind (used to delete a named collection). */
        const val DELETE_KIND = 5
        /** Default Saved list `d`-tag (the list has NO `t` tag — read by `#d`). */
        const val DEFAULT_LIST_DTAG = "nostrcooking-bookmarks"
        /** Seed title when creating the default list fresh. */
        const val DEFAULT_LIST_TITLE = "Saved"
        /** `t` tag stamped on named collections so the web enumerates them by `#t`. */
        const val COLLECTION_TAG = "zapcooking"
        /** `t` values that mark a kind-30001 list as a Zap Cooking recipe collection. */
        val RECIPE_T_TAGS = setOf("zapcooking", "nostrcooking")
        private const val EOSE_GRACE_MS = 2_000L
        private const val CACHE_LIMIT = 2_000
        private const val QUERY_LIMIT = 256
    }

    /**
     * A single recipe list (the default Saved list or a named collection),
     * projected from its newest kind-30001 event for the UI.
     */
    data class CookbookList(
        val dTag: String,
        val title: String,
        val summary: String?,
        val image: String?,
        /** Recipe a-coordinate whose image is the collection cover (web `cover` tag). */
        val coverCoord: String?,
        val coordinates: Set<String>,
        val isDefault: Boolean,
        val event: NostrEvent,
    )

    private val _bookmarkedCoordinates = MutableStateFlow<Set<String>>(emptySet())
    /** The recipe a-coordinates currently in the canonical **default** Saved list. */
    val bookmarkedCoordinates: StateFlow<Set<String>> = _bookmarkedCoordinates.asStateFlow()

    private val _lists = MutableStateFlow<List<CookbookList>>(emptyList())
    /** All of the user's recipe lists — default first, then newest-first. */
    val lists: StateFlow<List<CookbookList>> = _lists.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Newest known event per list `d`-tag — the carry-forward source on republish.
    private val listsLock = Any()
    private val listsByDTag = HashMap<String, NostrEvent>()

    private val subCounter = AtomicInteger(0)
    private var loadJob: Job? = null
    private val writeMutex = Mutex()

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

    /** True iff [event]'s coordinate is in the canonical default Saved list. */
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
            cachedRecipeLists(author).forEach { applyEvent(it) }
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
                cachedRecipeLists(author).forEach { applyEvent(it) }
                queryRecipeLists(author)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Apply a cached or inbound (streamed) list event, newest-wins per `d`-tag.
     * Called by the EventRouter when any of the user's own kind-30001 recipe
     * lists arrives. Non-recipe kind-30001 lists are ignored.
     */
    fun applyEvent(event: NostrEvent) {
        if (event.kind != LIST_KIND) return
        if (!isRecipeList(event)) return
        val dTag = dTagOf(event) ?: return
        val changed = synchronized(listsLock) {
            val current = listsByDTag[dTag]
            if (current != null && event.created_at <= current.created_at) {
                false
            } else {
                listsByDTag[dTag] = event
                true
            }
        }
        if (!changed) return
        eventRepo.cacheEvent(event)
        publishListsState()
    }

    /**
     * Single-tap toggle of [event]'s coordinate in the **default** Saved list and
     * publish the replaceable kind-30001 to the user's write relays. Carries
     * forward the existing list's title/summary/image/cover and ALL unknown tags
     * so web-set cookbook metadata is never clobbered. Returns the new bookmarked
     * state (true = now saved), or the current state unchanged when the event
     * isn't a recipe or there's no signing key.
     */
    suspend fun toggle(event: NostrEvent): Boolean {
        val coord = coordinateForEvent(event) ?: return isRecipeBookmarked(event)
        if (signerProvider() == null) return _bookmarkedCoordinates.value.contains(coord)
        return mutateList(DEFAULT_LIST_DTAG, coord, desired = null, seedTitleIfNew = DEFAULT_LIST_TITLE)
    }

    /**
     * Toggle [event]'s coordinate in an arbitrary recipe list [dTag]
     * (multi-membership — a recipe can be in several lists at once). Returns the
     * resulting membership for that list.
     */
    suspend fun toggleRecipeInList(dTag: String, event: NostrEvent): Boolean {
        val coord = coordinateForEvent(event) ?: return false
        val seed = if (dTag == DEFAULT_LIST_DTAG) DEFAULT_LIST_TITLE else null
        return mutateList(dTag, coord, desired = null, seedTitleIfNew = seed)
    }

    /**
     * Force [event]'s coordinate present/absent in recipe list [dTag]. Returns
     * the resulting membership.
     */
    suspend fun setRecipeInList(dTag: String, event: NostrEvent, add: Boolean): Boolean {
        val coord = coordinateForEvent(event) ?: return false
        val seed = if (dTag == DEFAULT_LIST_DTAG) DEFAULT_LIST_TITLE else null
        return mutateList(dTag, coord, desired = add, seedTitleIfNew = seed)
    }

    /**
     * Create a new **named collection** from [title] (slug `d`-tag, stamped with
     * the [COLLECTION_TAG] `t` tag), optionally seeding it with [seedEvent]'s
     * recipe in the same publish. If a collection with the same slug already
     * exists, this adds to it (preserving its metadata). Returns the list's
     * `d`-tag, or null for a blank title / read-only account.
     */
    suspend fun createList(title: String, seedEvent: NostrEvent? = null): String? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        val signer = signerProvider() ?: return null
        val dTag = slugify(trimmed)
        // Never collide with the default list; the default is single-tap territory.
        if (dTag.isBlank() || dTag == DEFAULT_LIST_DTAG) return null
        return writeMutex.withLock {
            val author = signer.pubkeyHex
            val base = listEventFor(dTag) ?: cachedListEvent(author, dTag)
            val nextCoords = LinkedHashSet(base?.let { parseCoordinates(it) } ?: emptySet())
            seedEvent?.let { ev -> coordinateForEvent(ev)?.let { nextCoords.add(it) } }
            val tags = buildListTags(base, dTag, isDefault = false, seedTitleIfNew = trimmed, nextCoords = nextCoords)
            val content = base?.content.orEmpty()
            val signed = signer.signEvent(kind = LIST_KIND, content = content, tags = tags)
            applyEvent(signed)
            relayPool.sendToWriteRelays(ClientMessage.event(signed))
            dTag
        }
    }

    /**
     * Batch-add recipe coordinates to the **default** list in a **single**
     * republish (used by the one-time legacy migration — never republish per
     * bookmark). Carries forward title/summary/image/cover + unknown tags like
     * [toggle]. Returns the coordinates that were newly added; empty for a
     * read-only account or when every coordinate is already saved.
     */
    suspend fun addCoordinates(coordinates: Collection<String>): Set<String> {
        if (coordinates.isEmpty()) return emptySet()
        val signer = signerProvider() ?: return emptySet()
        return writeMutex.withLock {
            val author = signer.pubkeyHex
            val base = listEventFor(DEFAULT_LIST_DTAG) ?: cachedListEvent(author, DEFAULT_LIST_DTAG)
            val currentCoords = base?.let { parseCoordinates(it) } ?: _bookmarkedCoordinates.value
            val nextCoords = LinkedHashSet(currentCoords)
            val added = coordinates.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && nextCoords.add(it) }
                .toCollection(LinkedHashSet())
            if (added.isEmpty()) return@withLock emptySet()
            val tags = buildListTags(base, DEFAULT_LIST_DTAG, isDefault = true, seedTitleIfNew = DEFAULT_LIST_TITLE, nextCoords = nextCoords)
            val content = base?.content.orEmpty()
            val signed = signer.signEvent(kind = LIST_KIND, content = content, tags = tags)
            applyEvent(signed)
            relayPool.sendToWriteRelays(ClientMessage.event(signed))
            added
        }
    }

    // ---- Collection management (PR 3b-iii) --------------------------------
    // All edits reuse the all-tags-preserving carry-forward republish: the
    // a-coords, image, the untouched metadata, and any unknown tags ride
    // through [buildListTags] unchanged; only the targeted tag is rewritten.

    /**
     * Rename a **named collection** — republish its kind-30001 with an updated
     * `title`, carrying everything else (d-tag, a-coords, t, cover, image,
     * summary, unknown tags) forward unchanged. The `d`-tag is the stable
     * identity and is **never** changed (a new `d` = a different list = orphaned
     * data). The **default Saved list cannot be renamed** (mirrors web). Returns
     * false for a blank title, the default list, an unknown list, or read-only.
     */
    suspend fun renameList(dTag: String, newTitle: String): Boolean {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty() || dTag == DEFAULT_LIST_DTAG) return false
        return editListMetadata(dTag) { base, coords ->
            buildListTags(base, dTag, isDefault = false, seedTitleIfNew = trimmed, nextCoords = coords, newTitle = trimmed)
        }
    }

    /**
     * Set (or clear, when [summary] is blank) a list's `summary`/description,
     * carrying the rest forward. Allowed on the default list too (web only locks
     * the default's title). Returns false for an unknown list or read-only.
     */
    suspend fun setListDescription(dTag: String, summary: String): Boolean {
        val isDefault = dTag == DEFAULT_LIST_DTAG
        return editListMetadata(dTag) { base, coords ->
            buildListTags(base, dTag, isDefault, seedTitleIfNew = null, nextCoords = coords, summaryEdit = MetaEdit(summary))
        }
    }

    /**
     * Set the `cover` tag to a **member** recipe's a-coordinate (mirrors the
     * web cover-picker — the cover must already be in the list). Carries the
     * rest forward. Returns false when [coverCoord] isn't a member, or for an
     * unknown list / read-only.
     */
    suspend fun setListCover(dTag: String, coverCoord: String): Boolean {
        val coord = coverCoord.trim()
        if (coord.isEmpty()) return false
        val isDefault = dTag == DEFAULT_LIST_DTAG
        return editListMetadata(dTag) { base, coords ->
            // Web guard: a cover can only be a recipe already in the collection.
            if (coord !in coords) null
            else buildListTags(base, dTag, isDefault, seedTitleIfNew = null, nextCoords = coords, coverEdit = MetaEdit(coord))
        }
    }

    /**
     * Delete a **named collection** via a NIP-09 kind-5 event tagging both the
     * list event id (`e`) and its address (`a` = `30001:<pubkey>:<dTag>`), exactly
     * like the web. The **default Saved list is never deletable**. Optimistically
     * removes the list locally. Returns false for the default list, an unknown
     * list, or read-only.
     */
    suspend fun deleteList(dTag: String): Boolean {
        if (dTag == DEFAULT_LIST_DTAG) return false
        val signer = signerProvider() ?: return false
        return writeMutex.withLock {
            val author = signer.pubkeyHex
            val base = listEventFor(dTag) ?: cachedListEvent(author, dTag) ?: return@withLock false
            val deleteTags = listOf(
                listOf("e", base.id),
                listOf("a", "$LIST_KIND:$author:$dTag"),
            )
            val signed = signer.signEvent(kind = DELETE_KIND, content = "", tags = deleteTags)
            // Optimistic local removal so the Saved grid updates immediately.
            removeListLocally(dTag)
            relayPool.sendToWriteRelays(ClientMessage.event(signed))
            true
        }
    }

    /**
     * Shared metadata-edit republish: load the newest known list event for
     * [dTag] (cache included), let [buildTags] produce the new tag set (returning
     * null to abort, e.g. a non-member cover), then sign + apply + publish to the
     * user's write relays. Returns false when read-only, the list is unknown, or
     * [buildTags] aborts.
     */
    private suspend fun editListMetadata(
        dTag: String,
        buildTags: (base: NostrEvent, coords: Set<String>) -> List<List<String>>?,
    ): Boolean {
        val signer = signerProvider() ?: return false
        return writeMutex.withLock {
            val author = signer.pubkeyHex
            val base = listEventFor(dTag) ?: cachedListEvent(author, dTag) ?: return@withLock false
            val tags = buildTags(base, parseCoordinates(base)) ?: return@withLock false
            val signed = signer.signEvent(kind = LIST_KIND, content = base.content, tags = tags)
            applyEvent(signed)
            relayPool.sendToWriteRelays(ClientMessage.event(signed))
            true
        }
    }

    /** Drop [dTag] from the in-memory list set and republish state (optimistic delete). */
    private fun removeListLocally(dTag: String) {
        synchronized(listsLock) { listsByDTag.remove(dTag) }
        publishListsState()
    }

    /**
     * One-time legacy migration (A14 PR 2). Resolves [legacyEventIds] (the old
     * kind-10003 note-bookmark e-ids), keeps the ones that resolve to a
     * recognized recipe ([RecipeFormats]), and batch-adds their a-coordinates to
     * the canonical default list in a single republish. **ADD-ONLY** — does not
     * touch the legacy 10003 list (PR 1's read-union dedups by coordinate, so
     * nothing double-displays and no bookmark can be stranded).
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
        synchronized(listsLock) { listsByDTag.clear() }
        _isLoading.value = false
        _lists.value = emptyList()
        _bookmarkedCoordinates.value = emptySet()
    }

    /**
     * Add/remove [coord] in recipe list [dTag] and publish. [desired] forces
     * presence (true) / absence (false); null toggles based on the current
     * (cache-refreshed) membership. Returns the resulting membership.
     */
    private suspend fun mutateList(
        dTag: String,
        coord: String,
        desired: Boolean?,
        seedTitleIfNew: String?,
    ): Boolean {
        val signer = signerProvider()
        if (signer == null) return isCoordInList(dTag, coord)
        return writeMutex.withLock {
            val author = signer.pubkeyHex
            val isDefault = dTag == DEFAULT_LIST_DTAG
            // Refresh the carry-forward base from the newest we know (cache included).
            val base = listEventFor(dTag) ?: cachedListEvent(author, dTag)
            val nextCoords = LinkedHashSet(base?.let { parseCoordinates(it) } ?: emptySet())
            val add = desired ?: !nextCoords.contains(coord)
            val changed = if (add) nextCoords.add(coord) else nextCoords.remove(coord)
            if (!changed) return@withLock add
            val tags = buildListTags(base, dTag, isDefault, seedTitleIfNew, nextCoords)
            val content = base?.content.orEmpty()
            val signed = signer.signEvent(kind = LIST_KIND, content = content, tags = tags)
            // Optimistic local apply (created_at is now, so it wins).
            applyEvent(signed)
            relayPool.sendToWriteRelays(ClientMessage.event(signed))
            add
        }
    }

    /** Result of [migrateLegacyBookmarks]: coordinates added + whether the run was exhaustive. */
    data class MigrationOutcome(val added: Set<String>, val complete: Boolean)

    private data class ResolveResult(val events: List<NostrEvent>, val complete: Boolean)

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

    /**
     * Stream the user's recipe lists into [applyEvent]. Uses one author-scoped
     * kind-30001 REQ (broad on purpose) sent to the broadened read union plus the
     * user's write relays via [OutboxRouter] — which only accepts a single filter
     * and counts EOSE per sub-id, so a single broad filter keeps that path intact.
     * [isRecipeList] discards any non-recipe kind-30001 list, so the materialized
     * set is exactly {default Saved by #d} ∪ {collections by #t}.
     */
    private suspend fun queryRecipeLists(author: String): Unit = withContext(processingContext) {
        val subId = "recipe-bookmarks-${subCounter.getAndIncrement()}"
        val seenIds = mutableSetOf<String>()
        val targetedRelays = mutableSetOf<String>()
        val collector = scope.launch(processingContext) {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != subId) return@collect
                val event = relayEvent.event
                if (event.id in seenIds) return@collect
                if (event.kind != LIST_KIND || event.pubkey != author) return@collect
                if (!isRecipeList(event)) return@collect
                seenIds.add(event.id)
                applyEvent(event)
            }
        }

        val filter = listFilter(author)
        val req = ClientMessage.req(subId, filter)
        for (url in readRelays()) {
            if (relayPool.sendToRelayOrEphemeral(url, req)) targetedRelays.add(url)
        }
        // Author-scoped outbox routing — the user's write relays are authoritative
        // for their own self-lists (matches the web's publish target).
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
    }

    private fun listFilter(author: String): Filter = Filter(
        kinds = listOf(LIST_KIND),
        authors = listOf(author),
        limit = QUERY_LIMIT,
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

    /** All cached recipe lists for [author], newest-per-`d`-tag. */
    private fun cachedRecipeLists(author: String): List<NostrEvent> {
        val byDTag = LinkedHashMap<String, NostrEvent>()
        fun consider(event: NostrEvent) {
            if (event.kind != LIST_KIND || event.pubkey != author) return
            if (!isRecipeList(event)) return
            val dTag = dTagOf(event) ?: return
            val current = byDTag[dTag]
            if (current == null || event.created_at > current.created_at) byDTag[dTag] = event
        }
        eventRepo.findAddressableEvent(LIST_KIND, author, DEFAULT_LIST_DTAG)?.let(::consider)
        eventRepo.eventPersistence
            ?.getEventsByAuthorAndKind(author, LIST_KIND, limit = CACHE_LIMIT)
            ?.forEach(::consider)
        return byDTag.values.toList()
    }

    private fun cachedListEvent(author: String, dTag: String): NostrEvent? {
        eventRepo.findAddressableEvent(LIST_KIND, author, dTag)?.let { return it }
        val persistence = eventRepo.eventPersistence ?: return null
        return persistence.getEventsByAuthorAndKind(author, LIST_KIND, limit = CACHE_LIMIT)
            .filter { hasDTag(it, dTag) }
            .maxByOrNull { it.created_at }
    }

    private fun publishListsState() {
        val snapshot = synchronized(listsLock) { listsByDTag.values.toList() }
        val models = snapshot
            .map(::toModel)
            .sortedWith(
                compareByDescending<CookbookList> { it.isDefault }
                    .thenByDescending { it.event.created_at },
            )
        _lists.value = models
        _bookmarkedCoordinates.value = models.firstOrNull { it.isDefault }?.coordinates ?: emptySet()
    }

    private fun toModel(event: NostrEvent): CookbookList {
        val dTag = dTagOf(event).orEmpty()
        val isDefault = dTag == DEFAULT_LIST_DTAG
        fun tagValue(name: String): String? = event.tags
            .firstOrNull { it.size >= 2 && it[0] == name }
            ?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        val title = if (isDefault) DEFAULT_LIST_TITLE else (tagValue("title") ?: dTag)
        return CookbookList(
            dTag = dTag,
            title = title,
            summary = tagValue("summary"),
            image = tagValue("image"),
            coverCoord = tagValue("cover"),
            coordinates = parseCoordinates(event),
            isDefault = isDefault,
            event = event,
        )
    }

    private fun isRecipeList(event: NostrEvent): Boolean {
        if (event.kind != LIST_KIND) return false
        if (hasDTag(event, DEFAULT_LIST_DTAG)) return true
        return event.tags.any { it.size >= 2 && it[0] == "t" && it[1].trim() in RECIPE_T_TAGS }
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
     * Rebuild list tags from [existing], preserving everything except the `a`
     * tags (rewritten to [nextCoords]) and the misattributing `client` tag.
     * Guarantees a `d` tag of [dTag] and a `title` (seeding [seedTitleIfNew] /
     * "Saved" / the slug when fresh). Named collections ([isDefault] false) are
     * stamped with the recipe [COLLECTION_TAG] `t` tag when they lack one; the
     * default list is never given a `t` tag.
     */
    /**
     * A single-value metadata-tag edit applied on republish (PR 3b-iii). A
     * non-blank [value] sets the tag; a blank/null [value] removes it. Passing
     * the edit as `null` to [buildListTags] leaves the existing tag untouched
     * (carried forward) — distinct from `MetaEdit(null)` which removes it.
     */
    private class MetaEdit(val value: String?)

    private fun buildListTags(
        existing: NostrEvent?,
        dTag: String,
        isDefault: Boolean,
        seedTitleIfNew: String?,
        nextCoords: Set<String>,
        // PR 3b-iii metadata edits: null = carry the existing tag forward unchanged.
        newTitle: String? = null,
        summaryEdit: MetaEdit? = null,
        coverEdit: MetaEdit? = null,
    ): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        var hasD = false
        var hasTitle = false
        var hasRecipeT = false
        existing?.tags?.forEach { tag ->
            if (tag.isEmpty()) return@forEach
            when (tag[0]) {
                "a" -> Unit // dropped; the full coordinate set is re-added below
                "client" -> Unit // dropped to avoid misattribution on republish
                "d" -> {
                    if (!hasD) {
                        tags.add(listOf("d", dTag))
                        hasD = true
                    }
                }
                "title" -> {
                    if (!hasTitle) {
                        // A rename replaces the title; otherwise carry it forward.
                        tags.add(if (newTitle != null) listOf("title", newTitle) else tag.toList())
                        hasTitle = true
                    }
                }
                // summary/cover are carried forward in place UNLESS an edit is
                // pending — then the in-loop tag is dropped and the new value
                // (or nothing, when removed) is appended below.
                "summary" -> { if (summaryEdit == null) tags.add(tag.toList()) }
                "cover" -> { if (coverEdit == null) tags.add(tag.toList()) }
                "t" -> {
                    // The default Saved list must never carry a recipe `t` tag — the
                    // web enumerates collections by `#t` and reads Saved by `#d`.
                    if (!isDefault) {
                        tags.add(tag.toList())
                        if (tag.size >= 2 && tag[1].trim() in RECIPE_T_TAGS) hasRecipeT = true
                    }
                }
                else -> tags.add(tag.toList()) // carry forward image/unknown
            }
        }
        if (!hasD) tags.add(0, listOf("d", dTag))
        if (!hasTitle) {
            tags.add(listOf("title", newTitle ?: seedTitleIfNew ?: if (isDefault) DEFAULT_LIST_TITLE else dTag))
        }
        // Apply pending summary/cover edits (a blank value clears the tag).
        summaryEdit?.value?.trim()?.takeIf { it.isNotBlank() }?.let { tags.add(listOf("summary", it)) }
        coverEdit?.value?.trim()?.takeIf { it.isNotBlank() }?.let { tags.add(listOf("cover", it)) }
        if (!isDefault && !hasRecipeT) tags.add(listOf("t", COLLECTION_TAG))
        nextCoords.forEach { tags.add(listOf("a", it)) }
        return tags
    }

    private fun listEventFor(dTag: String): NostrEvent? =
        synchronized(listsLock) { listsByDTag[dTag] }

    /** Current membership of [coord] in list [dTag] (in-memory, no network). */
    private fun isCoordInList(dTag: String, coord: String): Boolean {
        listEventFor(dTag)?.let { return parseCoordinates(it).contains(coord) }
        if (dTag == DEFAULT_LIST_DTAG) return _bookmarkedCoordinates.value.contains(coord)
        return _lists.value.firstOrNull { it.dTag == dTag }?.coordinates?.contains(coord) == true
    }

    /**
     * Canonical `d`-tag for a recipe list event. Prefers [DEFAULT_LIST_DTAG] when
     * present so [isRecipeList] (any matching `d`) and map keying stay aligned
     * even on malformed multi-`d` events.
     */
    private fun dTagOf(event: NostrEvent): String? {
        if (hasDTag(event, DEFAULT_LIST_DTAG)) return DEFAULT_LIST_DTAG
        return event.tags
            .firstOrNull { it.size >= 2 && it[0] == "d" }
            ?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun hasDTag(event: NostrEvent, dTag: String): Boolean {
        val needle = dTag.trim()
        return event.tags.any { it.size >= 2 && it[0] == "d" && it[1].trim() == needle }
    }

    /** Web-compatible slug (cookbookStore.createList): lower, spaces→`-`, strip non `[a-z0-9-]`. */
    private fun slugify(title: String): String =
        title.lowercase().replace(' ', '-').replace(Regex("[^a-z0-9-]"), "")
}
