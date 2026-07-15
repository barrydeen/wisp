package cooking.zap.app.repo

import cooking.zap.app.mealplan.MealPlanEvents
import cooking.zap.app.mealplan.MealPlanFetchResult
import cooking.zap.app.mealplan.PlannerLogic
import cooking.zap.app.mealplan.PlannerMutations
import cooking.zap.app.mealplan.Schema
import cooking.zap.app.mealplan.Week
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.SignerDecryptGate
import cooking.zap.app.relay.OutboxRouter
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.relay.SubscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Encrypted meal plans (kind 30078) — fetch/decrypt/publish/delete, mirroring
 * web `plannerService.ts` on top of [Week]/[Schema]/[MealPlanEvents] (#173).
 *
 * **Pantry exclusion (adopted from #174 grocery):** publishes via
 * [RelayPool.sendToWriteRelaysExcluding] with [WRITE_EXCLUDE]; fetches pass
 * the same exclude into [OutboxRouter.subscribeToUserWriteRelays] (pantry
 * answers CLOSED not EOSE and would stall EOSE-counted waits). URL compares
 * use [RelayConfig.normalizeForCompare].
 *
 * Reads use exact multi-value `#d` ([Filter.dTags]) for a batch of week ids
 * (current + neighbors in one REQ). Decrypt failures surface as
 * [MealPlanFetchResult.DecryptFailed], never as "no plan". Newest-per-week
 * via [PlannerLogic.supersedes] (NIP-01). ObjectBox cache via [EventRepository].
 */
class PlannerRepository(
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
        const val KIND = MealPlanEvents.KIND
        private const val EOSE_GRACE_MS = 2_000L
        private const val FETCH_TIMEOUT_MS = 8_000L
        private const val CACHE_LIMIT = 200

        /** Relays a meal-plan event must never reach — same exclusion as grocery (#174). */
        val WRITE_EXCLUDE = setOf(RelayConfig.MEMBERS_RELAY)
    }

    /** Outcome of a publish attempt — sent-relay count surfaced for UI. */
    sealed interface WriteResult {
        val weekId: String
        data class Ok(override val weekId: String, val relayCount: Int) : WriteResult
        data class NoRelays(override val weekId: String) : WriteResult
        data class SignerMissing(override val weekId: String) : WriteResult
        data class SignerError(override val weekId: String, val message: String) : WriteResult
    }

    private val _writeResults = MutableSharedFlow<WriteResult>(extraBufferCapacity = 16)
    val writeResults: SharedFlow<WriteResult> = _writeResults

    private val subCounter = AtomicInteger(0)
    private val writeMutex = Mutex()

    /** Last monotonic publish stamp per weekId — deletions must exceed this. */
    private val lastPublishStamp = HashMap<String, Long>()

    @Volatile private var decryptGate = SignerDecryptGate()

    // ---- fetch --------------------------------------------------------------

    /**
     * Exact multi-#d fetch for [weekIds]. Returns a map keyed by weekId —
     * absent key = no plan on relays; present = ok or decrypt-failed.
     * Cache-first paint for matching d-tags, then relay fill.
     */
    suspend fun fetchMealPlans(weekIds: List<String>): Map<String, MealPlanFetchResult> =
        withContext(processingContext) {
            val author = userPubkeyProvider()?.trim().orEmpty()
            val signer = signerProvider()
            if (author.isBlank() || signer == null || weekIds.isEmpty()) return@withContext emptyMap()

            val dTags = weekIds.map { Week.dTagForWeek(it) }
            val fromCache = decryptCached(author, weekIds, signer)
            val fromRelays = fetchFromRelays(author, dTags, signer)

            val merged = LinkedHashMap<String, MealPlanFetchResult>()
            merged.putAll(fromCache)
            merged.putAll(fromRelays)
            merged
        }

    private suspend fun decryptCached(
        author: String,
        weekIds: List<String>,
        signer: NostrSigner,
    ): Map<String, MealPlanFetchResult> {
        val wanted = weekIds.toSet()
        val cached = eventRepo.eventPersistence
            ?.getEventsByAuthorAndKind(author, KIND, limit = CACHE_LIMIT)
            ?.filter { MealPlanEvents.weekIdFrom(it) in wanted }
            ?: return emptyMap()
        return decryptNewest(cached, signer)
    }

    private suspend fun fetchFromRelays(
        author: String,
        dTags: List<String>,
        signer: NostrSigner,
    ): Map<String, MealPlanFetchResult> {
        val subId = "mealplan-${subCounter.getAndIncrement()}"
        val collected = LinkedHashMap<String, NostrEvent>()
        val collector = scope.launch(processingContext) {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId != subId) return@collect
                val event = relayEvent.event
                if (event.kind != KIND || event.pubkey != author) return@collect
                if (MealPlanEvents.weekIdFrom(event) == null) return@collect
                collected[event.id] = event
                eventRepo.cacheEvent(event)
            }
        }
        val filter = Filter(
            kinds = listOf(KIND),
            authors = listOf(author),
            dTags = dTags,
            limit = dTags.size * 3,
        )
        val req = ClientMessage.req(subId, filter)
        val targeted = mutableSetOf<String>()
        for (url in readRelays()) {
            if (relayPool.sendToRelayOrEphemeral(url, req)) targeted.add(url)
        }
        // #174: exclude pantry — CLOSED instead of EOSE stalls the wait.
        targeted.addAll(
            outboxRouter.subscribeToUserWriteRelays(subId, author, filter, exclude = WRITE_EXCLUDE),
        )
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
        return decryptNewest(collected.values, signer)
    }

    private suspend fun decryptNewest(
        events: Collection<NostrEvent>,
        signer: NostrSigner,
    ): Map<String, MealPlanFetchResult> {
        val newest = PlannerLogic.newestPerWeek(events)
        val out = LinkedHashMap<String, MealPlanFetchResult>()
        for ((weekId, event) in newest) {
            out[weekId] = when (val parsed = MealPlanEvents.decryptPlanEvent(event, weekId, signer, decryptGate)) {
                is Schema.MealPlanPayloadResult.Ok ->
                    MealPlanFetchResult.Ok(weekId, parsed.plan, parsed.readOnly, event)
                is Schema.MealPlanPayloadResult.DecryptFailed ->
                    MealPlanFetchResult.DecryptFailed(weekId, event, parsed.error)
            }
        }
        return out
    }

    // ---- publish / delete ---------------------------------------------------

    /** Sign + cache + publish [plan] (monotonic stamp), pantry-excluded. */
    suspend fun saveMealPlan(plan: Schema.MealPlan): WriteResult {
        val signer = signerProvider() ?: return WriteResult.SignerMissing(plan.week)
        val stamped = PlannerMutations.stampForPublish(plan, nowSeconds())
        return writeMutex.withLock {
            try {
                val event = MealPlanEvents.createPlanEvent(signer, stamped)
                eventRepo.cacheEvent(event)
                lastPublishStamp[stamped.week] = stamped.updatedAt
                val count = relayPool.sendToWriteRelaysExcluding(ClientMessage.event(event), WRITE_EXCLUDE)
                if (count > 0) WriteResult.Ok(stamped.week, count)
                else WriteResult.NoRelays(stamped.week)
            } catch (e: Exception) {
                WriteResult.SignerError(stamped.week, e.message ?: e.javaClass.simpleName)
            }
        }.also { _writeResults.tryEmit(it) }
    }

    /**
     * NIP-09 delete for [weekId]. Deletion `created_at` is stamped past the
     * last publish so NIP-09's up-to-created_at rule doesn't leave the newest
     * plan alive (grocery #174 precedent).
     */
    suspend fun deleteMealPlan(weekId: String, lastKnownUpdatedAt: Long = 0L): WriteResult {
        val signer = signerProvider() ?: return WriteResult.SignerMissing(weekId)
        val eventId = latestEventIdFor(signer.pubkeyHex, weekId)
        val prior = maxOf(lastKnownUpdatedAt, lastPublishStamp[weekId] ?: 0L)
        val deleteStamp = maxOf(nowSeconds(), prior + 1)
        return writeMutex.withLock {
            try {
                val deletion = MealPlanEvents.createDeletionEvent(
                    signer, weekId, eventId, createdAt = deleteStamp,
                )
                lastPublishStamp.remove(weekId)
                val count = relayPool.sendToWriteRelaysExcluding(ClientMessage.event(deletion), WRITE_EXCLUDE)
                if (count > 0) WriteResult.Ok(weekId, count)
                else WriteResult.NoRelays(weekId)
            } catch (e: Exception) {
                WriteResult.SignerError(weekId, e.message ?: e.javaClass.simpleName)
            }
        }.also { _writeResults.tryEmit(it) }
    }

    private fun latestEventIdFor(author: String, weekId: String): String? =
        eventRepo.eventPersistence
            ?.getEventsByAuthorAndKind(author, KIND, limit = CACHE_LIMIT)
            ?.filter { MealPlanEvents.weekIdFrom(it) == weekId }
            ?.maxByOrNull { it.created_at }
            ?.id

    /** Logout / account switch: reset the decrypt gate and publish stamps. */
    fun reset() {
        decryptGate = SignerDecryptGate()
        synchronized(lastPublishStamp) { lastPublishStamp.clear() }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun readRelays(): List<String> {
        val bad = relayPool.healthTracker?.getBadRelays().orEmpty()
        val excludeNormalized = WRITE_EXCLUDE.mapTo(HashSet()) { RelayConfig.normalizeForCompare(it) }
        val union = LinkedHashSet<String>()
        fun add(url: String) {
            val normalized = url.trim().trimEnd('/')
            if (normalized.isBlank() || normalized in bad) return
            if (RelayConfig.normalizeForCompare(normalized) in excludeNormalized) return
            union.add(normalized)
        }
        RelayConfig.DEFAULTS.filter { it.read }.forEach { add(it.url) }
        userReadRelaysProvider().forEach(::add)
        return union.toList()
    }
}
