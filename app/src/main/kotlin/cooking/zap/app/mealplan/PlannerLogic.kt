package cooking.zap.app.mealplan

import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.relay.RelayConfig

/**
 * Pure planner decision logic — hermetically testable core of
 * [cooking.zap.app.viewmodel.PlannerViewModel] / repository fetch selection.
 * Mirrors web `plannerStore.ts` behaviors pinned by `plannerStore.test.ts`.
 *
 * Newest-per-week and write-target helpers adopt the #174 grocery mechanisms:
 * NIP-01 [supersedes] (lowest-id same-`created_at` tie-break) and
 * [RelayConfig.normalizeForCompare] for pantry exclusion.
 */
object PlannerLogic {

    /** Visible week + both neighbors (one multi-#d fetch). */
    fun neighborhood(weekId: String): List<String> = listOf(
        weekId,
        Week.prevWeekId(weekId),
        Week.nextWeekId(weekId),
    )

    /**
     * Weeks to fetch: valid, missing (or force), and NOT dirty (pending save).
     * Dirty-week guard — web PR9 data-loss race fix.
     */
    fun fetchTargets(
        weekIds: List<String>,
        loaded: Set<String>,
        dirty: Set<String>,
        force: Boolean,
    ): List<String> = weekIds.filter { weekId ->
        Week.isValidWeekId(weekId) &&
            (force || weekId !in loaded) &&
            weekId !in dirty
    }

    /**
     * NIP-01 replaceable supersession (tested): newer `created_at` wins;
     * same-`created_at` ties keep the LOWEST event id — matching what relays
     * retain. Deterministic weekly d-tags make same-second ties more likely
     * than grocery's random ids — treat as release-blocking.
     */
    fun supersedes(
        candidateCreatedAt: Long,
        candidateId: String,
        incumbentCreatedAt: Long,
        incumbentId: String,
    ): Boolean =
        candidateCreatedAt > incumbentCreatedAt ||
            (candidateCreatedAt == incumbentCreatedAt && candidateId < incumbentId)

    /**
     * Keep the newest replaceable event per week id (relays may return several
     * versions of the same d-tag). Same-`created_at` → lowest id via [supersedes].
     */
    fun newestPerWeek(events: Collection<NostrEvent>): Map<String, NostrEvent> {
        val newest = LinkedHashMap<String, NostrEvent>()
        for (event in events) {
            val weekId = MealPlanEvents.weekIdFrom(event) ?: continue
            val existing = newest[weekId]
            if (existing == null ||
                supersedes(event.created_at, event.id, existing.created_at, existing.id)
            ) {
                newest[weekId] = event
            }
        }
        return newest
    }

    /**
     * Map a fetch result (or absence) to a sealed week state.
     * Absent → [PlannerWeekState.Empty]; never collapse decrypt-failed into empty.
     */
    fun toWeekState(
        weekId: String,
        result: MealPlanFetchResult?,
    ): PlannerWeekState = when (result) {
        null -> PlannerWeekState.Empty(Schema.createEmptyMealPlan(weekId))
        is MealPlanFetchResult.Ok ->
            PlannerWeekState.Loaded(result.plan, result.readOnly)
        is MealPlanFetchResult.DecryptFailed ->
            PlannerWeekState.DecryptFailed(result.error)
    }

    /** Mutation allowed only on Empty or Loaded(readOnly=false). */
    fun canMutate(state: PlannerWeekState?): Boolean = when (state) {
        is PlannerWeekState.Empty -> true
        is PlannerWeekState.Loaded -> !state.readOnly
        else -> false // null / Loading / DecryptFailed
    }

    fun planOf(state: PlannerWeekState?): Schema.MealPlan? = when (state) {
        is PlannerWeekState.Empty -> state.plan
        is PlannerWeekState.Loaded -> state.plan
        else -> null
    }

    /** After a successful mutation, always surface as Loaded (editable). */
    fun afterMutation(plan: Schema.MealPlan): PlannerWeekState =
        PlannerWeekState.Loaded(plan, readOnly = false)

    /**
     * Pure write-target filter — pantry excluded via
     * [RelayConfig.normalizeForCompare] (casing / trailing-slash variants).
     */
    fun writeTargets(poolWriteUrls: List<String>, exclude: Set<String>): List<String> {
        val excludeNormalized = exclude.mapTo(HashSet()) { RelayConfig.normalizeForCompare(it) }
        return poolWriteUrls.filterNot { RelayConfig.normalizeForCompare(it) in excludeNormalized }
    }
}

/**
 * Sealed per-week UI/VM state (investigation new-pattern flag). Distinct from
 * web's absent-key + ok/decrypt-failed: [Loading] and [Empty] are first-class.
 */
sealed class PlannerWeekState {
    data object Loading : PlannerWeekState()
    /** Relays had no event for this week — editable empty skeleton. */
    data class Empty(val plan: Schema.MealPlan) : PlannerWeekState()
    data class Loaded(val plan: Schema.MealPlan, val readOnly: Boolean) : PlannerWeekState()
    data class DecryptFailed(val error: String) : PlannerWeekState()
}

/** Repository fetch outcome for one week that had an event on relays. */
sealed class MealPlanFetchResult {
    data class Ok(
        val weekId: String,
        val plan: Schema.MealPlan,
        val readOnly: Boolean,
        val event: NostrEvent,
    ) : MealPlanFetchResult()

    data class DecryptFailed(
        val weekId: String,
        val event: NostrEvent?,
        val error: String,
    ) : MealPlanFetchResult()
}
