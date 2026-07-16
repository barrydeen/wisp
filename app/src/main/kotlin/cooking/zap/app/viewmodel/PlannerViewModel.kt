package cooking.zap.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.mealplan.PlannerLogic
import cooking.zap.app.mealplan.PlannerMutations
import cooking.zap.app.mealplan.PlannerWeekState
import cooking.zap.app.mealplan.Schema
import cooking.zap.app.mealplan.Week
import cooking.zap.app.repo.PlannerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Meal planner store — Android port of web `plannerStore.ts`. Behaviors are
 * pinned by that file's tests: neighborhood batch fetch, no-refetch of loaded
 * weeks, 2000ms per-week debounced publish (job-cancel), mutation rejection on
 * unloaded / DecryptFailed / readOnly (overwrite guard), dirty-week guard on
 * prefetch, refresh flushes pending saves first, logout clears timers + state.
 *
 * Sealed week states ([PlannerWeekState]) are the investigation's new-pattern
 * flag — Loading | Empty | Loaded(plan, readOnly) | DecryptFailed.
 */
class PlannerViewModel : ViewModel() {

    companion object {
        const val SAVE_DEBOUNCE_MS = 2_000L
    }

    private val _weeks = MutableStateFlow<Map<String, PlannerWeekState>>(emptyMap())
    val weeks: StateFlow<Map<String, PlannerWeekState>> = _weeks.asStateFlow()

    private val _currentWeekId = MutableStateFlow(Week.currentWeekId())
    val currentWeekId: StateFlow<String> = _currentWeekId.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastSaved = MutableStateFlow<Long?>(null)
    val lastSaved: StateFlow<Long?> = _lastSaved.asStateFlow()

    private val _canWrite = MutableStateFlow(false)
    val canWrite: StateFlow<Boolean> = _canWrite.asStateFlow()

    private var repo: PlannerRepository? = null
    private var canWriteProvider: () -> Boolean = { false }
    private var bound = false
    /** Last [bind] account key — identity change clears timers + sealed weeks. */
    private var boundAccountKey: String? = null
    /** Last [bind] write capability — READ_ONLY→signing must refetch/decrypt. */
    private var boundCanWrite: Boolean? = null
    private var hasBoundIdentity = false

    private var loadJob: Job? = null
    private val saveJobs = HashMap<String, Job>()
    private val pendingSaves = HashSet<String>()
    private val saveLock = Any()
    private val loadMutex = Mutex()

    val writeResults: SharedFlow<PlannerRepository.WriteResult>
        get() = requireNotNull(repo).writeResults

    /**
     * Bind the active [PlannerRepository]. Pass [accountKey] (active pubkey or
     * null when logged out) so logout / account switch clears state without
     * wiping on every Recipes-tab re-entry. Write-capability flips (e.g.
     * READ_ONLY → signing) also clear so [load] refetches instead of treating
     * prior Empty/Loaded weeks as already loaded.
     */
    fun bind(
        repo: PlannerRepository,
        canWriteProvider: () -> Boolean,
        accountKey: String? = null,
    ) {
        val canWriteNow = canWriteProvider()
        val identityChanged = hasBoundIdentity &&
            (boundAccountKey != accountKey || boundCanWrite != canWriteNow)
        if (identityChanged) {
            clear()
        }
        hasBoundIdentity = true
        boundAccountKey = accountKey
        boundCanWrite = canWriteNow
        this.repo = repo
        this.canWriteProvider = canWriteProvider
        _canWrite.value = canWriteNow
        bound = true
    }

    // ---- navigation / load --------------------------------------------------

    /** Load current week + neighbors (one multi-#d fetch). */
    fun load() {
        _canWrite.value = canWriteProvider()
        launchLoad {
            loadWeeks(PlannerLogic.neighborhood(_currentWeekId.value))
        }
    }

    /** Force-refetch neighborhood — flushes pending saves first (dirty-week). */
    fun refresh() {
        launchLoad {
            flushPendingSaves()
            loadWeeks(PlannerLogic.neighborhood(_currentWeekId.value), force = true)
        }
    }

    fun goToWeek(weekId: String) {
        if (!Week.isValidWeekId(weekId)) return
        _currentWeekId.value = weekId
        launchLoad {
            loadWeeks(PlannerLogic.neighborhood(weekId))
        }
    }

    fun nextWeek() = goToWeek(Week.nextWeekId(_currentWeekId.value))
    fun prevWeek() = goToWeek(Week.prevWeekId(_currentWeekId.value))
    fun goToCurrentWeek() = goToWeek(Week.currentWeekId())

    private suspend fun loadWeeks(weekIds: List<String>, force: Boolean = false) {
        val r = repo ?: return
        val dirty = synchronized(saveLock) {
            saveJobs.keys.toSet() + pendingSaves
        }
        // PR 8 one-line accommodation (callout): a week left in Loading by a
        // load that was cancelled mid-fetch (rapid prev/next navigation) must
        // NOT count as loaded, or fetchTargets skips it and it hangs on the
        // spinner forever. Loads are serialized (loadMutex) + the prior job is
        // cancelled, so re-fetching a stale-Loading week can't double-fetch.
        val loaded = _weeks.value.filterValues { it !is PlannerWeekState.Loading }.keys
        val targets = PlannerLogic.fetchTargets(weekIds, loaded, dirty, force)
        if (targets.isEmpty()) {
            _initialized.value = true
            return
        }
        loadMutex.withLock {
            _loading.value = true
            try {
                // Mark targets Loading so the UI doesn't flash Empty mid-fetch.
                patchWeeks { map ->
                    val next = map.toMutableMap()
                    for (id in targets) {
                        if (force || id !in next) next[id] = PlannerWeekState.Loading
                    }
                    next
                }
                val results = r.fetchMealPlans(targets)
                patchWeeks { map ->
                    val next = map.toMutableMap()
                    for (id in targets) {
                        // Re-check dirty: a mutation may have landed during fetch.
                        val stillDirty = synchronized(saveLock) {
                            saveJobs.containsKey(id) || pendingSaves.contains(id)
                        }
                        if (stillDirty) continue
                        next[id] = PlannerLogic.toWeekState(id, results[id])
                    }
                    next
                }
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load meal plans"
            } finally {
                _loading.value = false
                _initialized.value = true
            }
        }
    }

    // ---- mutations (return false when rejected — overwrite guard) -----------

    fun setSlot(weekId: String, day: String, slot: String, entry: JsonObject): Boolean =
        mutatePlan(weekId) { PlannerMutations.setSlot(it, day, slot, entry) }

    fun clearSlot(weekId: String, day: String, slot: String): Boolean =
        mutatePlan(weekId) { PlannerMutations.clearSlot(it, day, slot) }

    fun setDayNotes(weekId: String, day: String, notes: String): Boolean =
        mutatePlan(weekId) { PlannerMutations.setDayNotes(it, day, notes) }

    fun setWeekNotes(weekId: String, notes: String): Boolean =
        mutatePlan(weekId) { PlannerMutations.setWeekNotes(it, notes) }

    private fun mutatePlan(weekId: String, transform: (Schema.MealPlan) -> Schema.MealPlan): Boolean {
        val state = _weeks.value[weekId]
        if (!PlannerLogic.canMutate(state)) return false
        val plan = PlannerLogic.planOf(state!!) ?: return false
        val next = PlannerLogic.afterMutation(transform(plan))
        patchWeeks { it + (weekId to next) }
        scheduleSave(weekId)
        return true
    }

    fun deleteWeek(weekId: String) {
        cancelSave(weekId)
        val priorUpdated = PlannerLogic.planOf(_weeks.value[weekId])?.updatedAt ?: 0L
        viewModelScope.launch(Dispatchers.Default) {
            // Drain any in-flight performSave for this week before delete —
            // otherwise delete can win the repo writeMutex and the later save
            // republishes, resurrecting the plan on relays.
            while (synchronized(saveLock) { weekId in pendingSaves }) {
                delay(25)
            }
            val r = repo ?: return@launch
            when (val result = r.deleteMealPlan(weekId, lastKnownUpdatedAt = priorUpdated)) {
                is PlannerRepository.WriteResult.Ok,
                is PlannerRepository.WriteResult.NoRelays,
                -> {
                    // Even NoRelays: local reset (web resets after service call;
                    // surface NoRelays via writeResults).
                    patchWeeks {
                        it + (weekId to PlannerWeekState.Empty(Schema.createEmptyMealPlan(weekId)))
                    }
                    if (result is PlannerRepository.WriteResult.NoRelays) {
                        _error.value = "Meal plan delete reached 0 relays"
                    } else {
                        _error.value = null
                    }
                }
                is PlannerRepository.WriteResult.SignerMissing ->
                    _error.value = "Cannot delete — no signer"
                is PlannerRepository.WriteResult.SignerError ->
                    _error.value = result.message
            }
        }
    }

    // ---- debounce -----------------------------------------------------------

    private fun scheduleSave(weekId: String) {
        synchronized(saveLock) {
            saveJobs.remove(weekId)?.cancel()
            saveJobs[weekId] = viewModelScope.launch(Dispatchers.Default) {
                delay(SAVE_DEBOUNCE_MS)
                synchronized(saveLock) { saveJobs.remove(weekId) }
                performSave(weekId)
            }
        }
    }

    private fun cancelSave(weekId: String) {
        synchronized(saveLock) { saveJobs.remove(weekId)?.cancel() }
    }

    private fun clearAllTimers() {
        synchronized(saveLock) {
            saveJobs.values.forEach { it.cancel() }
            saveJobs.clear()
            pendingSaves.clear()
        }
    }

    private suspend fun performSave(weekId: String) {
        val already = synchronized(saveLock) {
            if (weekId in pendingSaves) true
            else { pendingSaves.add(weekId); false }
        }
        if (already) return

        _saving.value = true
        try {
            val state = _weeks.value[weekId]
            if (state is PlannerWeekState.Loaded && !state.readOnly) {
                val r = repo ?: return
                when (val result = r.saveMealPlan(state.plan)) {
                    is PlannerRepository.WriteResult.Ok -> {
                        patchSavedPlan(weekId, result.plan)
                        _lastSaved.value = System.currentTimeMillis()
                        _error.value = null
                    }
                    is PlannerRepository.WriteResult.NoRelays -> {
                        patchSavedPlan(weekId, result.plan)
                        _error.value = "Meal plan save reached 0 relays"
                    }
                    is PlannerRepository.WriteResult.SignerMissing ->
                        _error.value = "Cannot save — no signer"
                    is PlannerRepository.WriteResult.SignerError ->
                        _error.value = result.message
                }
            }
        } finally {
            synchronized(saveLock) { pendingSaves.remove(weekId) }
            val anyPending = synchronized(saveLock) { pendingSaves.isNotEmpty() || saveJobs.isNotEmpty() }
            if (!anyPending) _saving.value = false
        }
    }

    /** Flush all pending debounced saves immediately. */
    suspend fun saveNow() = flushPendingSaves()

    private suspend fun flushPendingSaves() {
        val ids = synchronized(saveLock) {
            val keys = saveJobs.keys.toList()
            saveJobs.values.forEach { it.cancel() }
            saveJobs.clear()
            keys
        }
        for (id in ids) performSave(id)
    }

    /** True while [weekId] has a pending debounced or in-flight save. */
    fun isDirty(weekId: String): Boolean = synchronized(saveLock) {
        saveJobs.containsKey(weekId) || pendingSaves.contains(weekId)
    }

    // ---- logout -------------------------------------------------------------

    fun clear() {
        loadJob?.cancel()
        loadJob = null
        clearAllTimers()
        repo?.reset()
        _weeks.value = emptyMap()
        _currentWeekId.value = Week.currentWeekId()
        _loading.value = false
        _initialized.value = false
        _error.value = null
        _saving.value = false
        _lastSaved.value = null
        _canWrite.value = canWriteProvider()
        // Keep hasBoundIdentity / boundAccountKey / boundCanWrite — bind() owns identity tracking.
    }

    private fun patchWeeks(transform: (Map<String, PlannerWeekState>) -> Map<String, PlannerWeekState>) {
        _weeks.value = transform(_weeks.value)
    }

    private fun launchLoad(block: suspend () -> Unit) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.Default) { block() }
    }

    private fun patchSavedPlan(weekId: String, plan: Schema.MealPlan) {
        patchWeeks { weeks ->
            when (val state = weeks[weekId]) {
                is PlannerWeekState.Loaded -> weeks + (weekId to state.copy(plan = plan))
                is PlannerWeekState.Empty -> weeks + (weekId to PlannerWeekState.Loaded(plan, readOnly = false))
                else -> weeks
            }
        }
    }
}
