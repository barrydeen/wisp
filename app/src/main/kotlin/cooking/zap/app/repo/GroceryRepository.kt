package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.GroceryEvents
import cooking.zap.app.nostr.GroceryEvents.GroceryItem
import cooking.zap.app.nostr.GroceryEvents.GroceryList
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.SignerDecryptGate
import cooking.zap.app.relay.OutboxRouter
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.relay.SubscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Encrypted grocery lists (kind 30078) — read/decrypt/write, mirroring web's
 * `groceryStore.ts` behavior on top of the [GroceryEvents] contract layer (#172).
 *
 * **Pantry-excluded write set (approved stop-gate decision):** grocery events
 * publish to the user's write relays + default pool via
 * [RelayPool.sendToWriteRelaysExcluding], EXCLUDING [RelayConfig.MEMBERS_RELAY].
 * The pantry gates 30078 behind NIP-42 membership ("restricted: membership
 * required") and personal encrypted data doesn't belong on the shared recipe
 * relay. The planner port (PR 7) reuses the same exclusion.
 *
 * **No relay-confirmed-absence guard (unlike [RecipeBookmarkRepository]):** the
 * cold-cache overwrite hazard (#169) came from a DETERMINISTIC d-tag
 * (`nostrcooking-bookmarks`) two devices both write to. Grocery d-tags are
 * random (`GroceryEvents.generateListId`), so a create can't collide with an
 * unfetched list by construction — the guard would protect against nothing.
 * Edits target an id already in state, and merge()'s newest-`updatedAt`-wins
 * keeps a relay-fetched copy from clobbering a locally-dirty one.
 *
 * **No decrypt-failed state (grocery contract):** a list that won't decrypt is
 * skipped per-list ([GroceryEvents.decryptListEvent] returns null). Bulk
 * decrypts route through a session [SignerDecryptGate] so repeated Amber
 * denials trip the breaker instead of storming the user.
 *
 * Cache-first paint (ObjectBox persists self-authored 30078 like any own event)
 * then relay fill, matching the bookmark repo. Debounced saves (2 s per list)
 * via [DebouncedSaver]; new lists and deletes publish immediately.
 */
class GroceryRepository(
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
        const val KIND = GroceryEvents.KIND
        private const val EOSE_GRACE_MS = 2_000L
        private const val FETCH_TIMEOUT_MS = 8_000L
        private const val SAVE_DEBOUNCE_MS = 2_000L
        private const val CACHE_LIMIT = 500

        /** Relays a grocery event must never reach — see the class doc. */
        val WRITE_EXCLUDE = setOf(RelayConfig.MEMBERS_RELAY)

        /**
         * Pure write-target computation (tested): the pool's write relays minus
         * [exclude], compared via [RelayConfig.normalizeForCompare] so casing /
         * trailing-slash variants can't dodge the filter. Returned for
         * visibility/tests; the actual send filters the live pool the same way
         * inside [RelayPool.sendToWriteRelaysExcluding].
         */
        fun writeTargets(poolWriteUrls: List<String>, exclude: Set<String>): List<String> {
            val excludeNormalized = exclude.mapTo(HashSet()) { RelayConfig.normalizeForCompare(it) }
            return poolWriteUrls.filterNot { RelayConfig.normalizeForCompare(it) in excludeNormalized }
        }

        /**
         * NIP-01 replaceable supersession (tested): newer `created_at` wins;
         * same-`created_at` ties keep the LOWEST event id — matching what
         * relays retain, so local state can't diverge from relay state.
         */
        fun supersedes(candidateCreatedAt: Long, candidateId: String, incumbentCreatedAt: Long, incumbentId: String): Boolean =
            candidateCreatedAt > incumbentCreatedAt ||
                (candidateCreatedAt == incumbentCreatedAt && candidateId < incumbentId)
    }

    /** Outcome of a publish attempt, surfaced for the UI (investigation gap 4). */
    sealed interface WriteResult {
        val listId: String
        /** Published to [relayCount] relays (>0). */
        data class Ok(override val listId: String, val relayCount: Int) : WriteResult
        /** Signed but reached ZERO relays — a visible failure, not silence. */
        data class NoRelays(override val listId: String) : WriteResult
        /** No signer (READ_ONLY / signed-out) — nothing was signed. */
        data class SignerMissing(override val listId: String) : WriteResult
        /** Signer threw (e.g. Amber denial) — nothing was published. */
        data class SignerError(override val listId: String, val message: String) : WriteResult
    }

    private val _lists = MutableStateFlow<List<GroceryList>>(emptyList())
    /** All of the user's grocery lists, newest-updated first (web ordering). */
    val lists: StateFlow<List<GroceryList>> = _lists.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _writeResults = MutableSharedFlow<WriteResult>(extraBufferCapacity = 16)
    /** Emitted after every publish attempt so the UI can show sent-count / failure. */
    val writeResults: SharedFlow<WriteResult> = _writeResults

    private val stateLock = Any()
    private val subCounter = AtomicInteger(0)
    private val writeMutex = Mutex()
    private var loadJob: Job? = null

    // Recreated on reset() so a denial streak never crosses account boundaries.
    @Volatile private var decryptGate = SignerDecryptGate()

    private val saver = DebouncedSaver(scope, SAVE_DEBOUNCE_MS) { listId -> publishList(listId) }

    // ---- state helpers ------------------------------------------------------

    private fun currentById(id: String): GroceryList? =
        synchronized(stateLock) { _lists.value.firstOrNull { it.id == id } }

    private fun upsert(list: GroceryList) {
        synchronized(stateLock) {
            _lists.value = GroceryMutations.merge(_lists.value.filterNot { it.id == list.id }, listOf(list))
        }
    }

    private fun removeLocally(id: String) {
        synchronized(stateLock) { _lists.value = _lists.value.filterNot { it.id == id } }
    }

    // ---- load ---------------------------------------------------------------

    /** Cache-first paint then relay fill. Cancels any in-flight load first. */
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
                paintFromCache(author)
                fetchFromRelays(author)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun paintFromCache(author: String) {
        val signer = signerProvider() ?: return
        val cached = eventRepo.eventPersistence
            ?.getEventsByAuthorAndKind(author, KIND, limit = CACHE_LIMIT)
            ?.filter { GroceryEvents.isGroceryCandidate(it) }
            ?: return
        val decoded = decryptNewestPerDTag(cached, signer)
        if (decoded.isNotEmpty()) mergeIntoState(decoded)
    }

    private suspend fun fetchFromRelays(author: String): Unit = withContext(processingContext) {
        val signer = signerProvider() ?: return@withContext
        val subId = "grocery-${subCounter.getAndIncrement()}"
        val collected = LinkedHashMap<String, NostrEvent>() // by event id
        val collector = scope.launch(processingContext) {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != subId) return@collect
                val event = relayEvent.event
                if (event.kind != KIND || event.pubkey != author) return@collect
                if (!GroceryEvents.isGroceryCandidate(event)) return@collect
                collected[event.id] = event
                eventRepo.cacheEvent(event) // persist for cold start
            }
        }
        val filter = Filter(kinds = listOf(KIND), authors = listOf(author), limit = 100)
        val req = ClientMessage.req(subId, filter)
        val targeted = mutableSetOf<String>()
        for (url in readRelays()) {
            if (relayPool.sendToRelayOrEphemeral(url, req)) targeted.add(url)
        }
        targeted.addAll(outboxRouter.subscribeToUserWriteRelays(subId, author, filter, exclude = WRITE_EXCLUDE))
        val expectedEose = targeted.count { relayPool.healthTracker?.isBad(it) != true }
        try {
            if (expectedEose > 0) {
                subManager.awaitEoseCount(subId, expectedCount = expectedEose, timeoutMs = FETCH_TIMEOUT_MS)
                delay(EOSE_GRACE_MS)
            }
        } finally {
            collector.cancelAndJoin()
            subManager.closeSubscription(subId)
        }
        // Bulk-decrypt AFTER the fetch window (web's fetch-then-decrypt-loop) so
        // Amber prompts don't serialize inside the event stream.
        val decoded = decryptNewestPerDTag(collected.values, signer)
        mergeIntoState(decoded)
    }

    /**
     * Decrypt a batch, keeping the newest event per d-tag first (replaceable —
     * relays may return several versions), skipping per-list failures. Routes
     * through the session decrypt gate. Same-`created_at` ties break to the
     * LOWEST event id — the NIP-01 supersession rule — so the version we keep
     * matches the one relays retain (our own writes can't tie thanks to
     * [GroceryMutations.stampForPublish], but older clients / cross-device
     * races can).
     */
    private suspend fun decryptNewestPerDTag(
        events: Collection<NostrEvent>,
        signer: NostrSigner,
    ): List<GroceryList> {
        val newestByDTag = LinkedHashMap<String, NostrEvent>()
        for (event in events) {
            val id = GroceryEvents.listIdFrom(event) ?: continue
            val existing = newestByDTag[id]
            if (existing == null || supersedes(event.created_at, event.id, existing.created_at, existing.id)) {
                newestByDTag[id] = event
            }
        }
        return newestByDTag.values.mapNotNull { event ->
            GroceryEvents.decryptListEvent(event, signer, decryptGate)
        }
    }

    private fun mergeIntoState(decoded: List<GroceryList>) {
        if (decoded.isEmpty()) return
        synchronized(stateLock) {
            // Don't let a relay copy overwrite a list with a pending local save.
            val protectedIds = decoded.mapNotNull { it.id.takeIf { id -> saver.isPending(id) } }.toSet()
            val incoming = decoded.filterNot { it.id in protectedIds }
            _lists.value = GroceryMutations.merge(_lists.value, incoming)
        }
    }

    // ---- mutations (optimistic + debounced publish) -------------------------

    /** Create a new list; publishes IMMEDIATELY (web addList). Returns the id, or null when signed-out. */
    suspend fun createList(title: String): String? {
        signerProvider() ?: return null
        val id = GroceryEvents.generateListId()
        val list = GroceryMutations.createEmptyList(id, title, nowSeconds())
        upsert(list)
        publishList(id)
        return id
    }

    fun renameList(id: String, title: String) = mutate(id) { GroceryMutations.rename(it, title) }
    fun setNotes(id: String, notes: String) = mutate(id) { GroceryMutations.setNotes(it, notes) }
    fun addItem(id: String, item: GroceryItem) = mutate(id) { GroceryMutations.addItem(it, item) }
    fun editItem(id: String, item: GroceryItem) = mutate(id) { GroceryMutations.editItem(it, item) }
    fun setChecked(id: String, itemId: String, checked: Boolean) =
        mutate(id) { GroceryMutations.setChecked(it, itemId, checked) }
    fun removeItem(id: String, itemId: String) = mutate(id) { GroceryMutations.removeItem(it, itemId) }
    fun addRecipeLink(id: String, recipeATag: String) = mutate(id) { GroceryMutations.addRecipeLink(it, recipeATag) }

    /** Apply a pure transform optimistically, then schedule a debounced save. */
    private inline fun mutate(id: String, transform: (GroceryList) -> GroceryList) {
        val current = currentById(id) ?: return
        upsert(transform(current))
        saver.schedule(id)
    }

    /** Flush a list's pending debounced save now (e.g. leaving the screen). */
    suspend fun saveNow(id: String) = saver.flush(id)

    /**
     * Delete a list: cancel any pending save, remove locally, publish NIP-09
     * kind-5 immediately (excluding pantry). No-op when signed-out.
     */
    suspend fun deleteList(id: String) {
        val signer = signerProvider() ?: run {
            _writeResults.tryEmit(WriteResult.SignerMissing(id))
            return
        }
        saver.cancel(id)
        val eventId = latestEventIdFor(signer.pubkeyHex, id)
        // Deletion must not be OLDER than the list's last (monotonic) stamp, or
        // NIP-09's up-to-created_at rule leaves the newest version alive.
        val deleteStamp = maxOf(nowSeconds(), (currentById(id)?.updatedAt ?: 0) + 1)
        removeLocally(id)
        writeMutex.withLock {
            try {
                val deletion = GroceryEvents.createDeletionEvent(signer, id, eventId, createdAt = deleteStamp)
                val count = relayPool.sendToWriteRelaysExcluding(ClientMessage.event(deletion), WRITE_EXCLUDE)
                _writeResults.tryEmit(
                    if (count > 0) WriteResult.Ok(id, count) else WriteResult.NoRelays(id)
                )
            } catch (e: Exception) {
                _writeResults.tryEmit(WriteResult.SignerError(id, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    // ---- publish ------------------------------------------------------------

    /** Sign + cache + publish the current state of [id], stamping a monotonic updatedAt. */
    private suspend fun publishList(id: String) {
        val signer = signerProvider() ?: run {
            _writeResults.tryEmit(WriteResult.SignerMissing(id))
            return
        }
        val list = currentById(id)?.let { GroceryMutations.stampForPublish(it, nowSeconds()) } ?: return
        upsert(list) // reflect the stamped updatedAt in state
        writeMutex.withLock {
            try {
                val event = GroceryEvents.createListEvent(signer, list)
                eventRepo.cacheEvent(event) // persist for cold start
                val count = relayPool.sendToWriteRelaysExcluding(ClientMessage.event(event), WRITE_EXCLUDE)
                _writeResults.tryEmit(
                    if (count > 0) WriteResult.Ok(id, count) else WriteResult.NoRelays(id)
                )
            } catch (e: Exception) {
                _writeResults.tryEmit(WriteResult.SignerError(id, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun latestEventIdFor(author: String, listId: String): String? =
        eventRepo.eventPersistence
            ?.getEventsByAuthorAndKind(author, KIND, limit = CACHE_LIMIT)
            ?.filter { GroceryEvents.listIdFrom(it) == listId }
            ?.maxByOrNull { it.created_at }
            ?.id

    // ---- lifecycle ----------------------------------------------------------

    /** Logout / account switch: cancel timers, clear state, reset the decrypt gate. */
    fun reset() {
        loadJob?.cancel()
        loadJob = null
        saver.cancelAll()
        synchronized(stateLock) { _lists.value = emptyList() }
        _isLoading.value = false
        decryptGate = SignerDecryptGate()
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun readRelays(): List<String> {
        val bad = relayPool.healthTracker?.getBadRelays().orEmpty()
        val excludeNormalized = WRITE_EXCLUDE.mapTo(HashSet()) { RelayConfig.normalizeForCompare(it) }
        val union = LinkedHashSet<String>()
        fun add(url: String) {
            val normalized = url.trim().trimEnd('/')
            if (normalized.isBlank() || normalized in bad) return
            // Never read grocery from the members relay (symmetry with the write exclusion).
            if (RelayConfig.normalizeForCompare(normalized) in excludeNormalized) return
            union.add(normalized)
        }
        RelayConfig.DEFAULTS.filter { it.read }.forEach { add(it.url) }
        userReadRelaysProvider().forEach(::add)
        return union.toList()
    }
}
