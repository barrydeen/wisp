package cooking.zap.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.api.MealPlanResult
import cooking.zap.app.cheffy.Cheffy
import cooking.zap.app.mealplan.MealPlanCandidates
import cooking.zap.app.mealplan.MealPlanGeneration
import cooking.zap.app.mealplan.Schema
import cooking.zap.app.mealplan.SlotEligibility
import cooking.zap.app.nostr.NostrSigner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plan with Cheffy — sheet phase machine (Phase 5).
 *
 * Signing is a real phase: every meal-plan body is unique, so
 * [cooking.zap.app.nostr.Nip98HeaderCache] never hits this endpoint.
 * [MealPlanResult] mapping and discovery live here; [mutatePlan] stays
 * on [PlannerViewModel].
 */
class CheffyPlanViewModel : ViewModel() {

    enum class Phase {
        FORM, SIGNING, WORKING, PREVIEW, UPSELL, READ_ONLY, ERROR, SIGN_IN,
    }

    data class UiState(
        val phase: Phase = Phase.FORM,
        val weekId: String = "",
        val mealSlots: List<String> = listOf("dinner"),
        val days: List<String> = Schema.DAY_KEYS,
        val styles: List<MealPlanGeneration.PreferenceStyleId> = emptyList(),
        val maxMinutesText: String = "",
        val servingsText: String = "",
        val excludeText: String = "",
        val notes: String = "",
        val source: MealPlanGeneration.RecipeSource = MealPlanGeneration.RecipeSource.ALL,
        val strategy: MealPlanGeneration.MealPlanStrategy =
            MealPlanGeneration.MealPlanStrategy.FILL_EMPTY,
        val meals: List<MealPlanGeneration.GeneratedMeal> = emptyList(),
        val coverageNote: String? = null,
        val message: String = "",
        val thinkingLine: String = "",
        val applying: Boolean = false,
    ) {
        val canSubmit: Boolean
            get() = mealSlots.isNotEmpty() && days.isNotEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var generateJob: Job? = null
    private var thinkingJob: Job? = null

    fun open(weekId: String, signedIn: Boolean, readOnly: Boolean) {
        generateJob?.cancel()
        stopThinking()
        _state.value = when {
            !signedIn -> UiState(phase = Phase.SIGN_IN, weekId = weekId)
            readOnly -> UiState(phase = Phase.READ_ONLY, weekId = weekId)
            else -> UiState(weekId = weekId)
        }
    }

    fun onSignedOut() {
        generateJob?.cancel()
        stopThinking()
        _state.update { it.copy(phase = Phase.SIGN_IN, applying = false) }
    }

    fun toggleSlot(slot: String) {
        if (slot !in Schema.SLOT_KEYS) return
        _state.update { s ->
            if (s.phase != Phase.FORM) return@update s
            val next = toggleKeepingAtLeastOne(s.mealSlots, slot, Schema.SLOT_KEYS)
            s.copy(mealSlots = next)
        }
    }

    fun toggleDay(day: String) {
        if (day !in Schema.DAY_KEYS) return
        _state.update { s ->
            if (s.phase != Phase.FORM) return@update s
            val next = toggleKeepingAtLeastOne(s.days, day, Schema.DAY_KEYS)
            s.copy(days = next)
        }
    }

    fun toggleStyle(id: MealPlanGeneration.PreferenceStyleId) {
        _state.update { s ->
            if (s.phase != Phase.FORM) return@update s
            val next = if (id in s.styles) s.styles - id else s.styles + id
            s.copy(styles = next)
        }
    }

    fun setMaxMinutes(text: String) {
        _state.update { s -> if (s.phase == Phase.FORM) s.copy(maxMinutesText = text) else s }
    }

    fun setServings(text: String) {
        _state.update { s -> if (s.phase == Phase.FORM) s.copy(servingsText = text) else s }
    }

    fun setExcludeText(text: String) {
        _state.update { s -> if (s.phase == Phase.FORM) s.copy(excludeText = text) else s }
    }

    fun setNotes(text: String) {
        _state.update { s ->
            if (s.phase != Phase.FORM) return@update s
            s.copy(notes = text.take(MealPlanGeneration.MAX_NOTES_CHARS))
        }
    }

    fun setSource(source: MealPlanGeneration.RecipeSource) {
        _state.update { s -> if (s.phase == Phase.FORM) s.copy(source = source) else s }
    }

    fun setStrategy(strategy: MealPlanGeneration.MealPlanStrategy) {
        _state.update { s -> if (s.phase == Phase.FORM) s.copy(strategy = strategy) else s }
    }

    fun cancelInFlight() {
        generateJob?.cancel()
        stopThinking()
    }

    fun backToForm() {
        generateJob?.cancel()
        stopThinking()
        _state.update {
            it.copy(
                phase = Phase.FORM,
                meals = emptyList(),
                coverageNote = null,
                message = "",
                thinkingLine = "",
                applying = false,
            )
        }
    }

    fun removeMeal(meal: MealPlanGeneration.GeneratedMeal) {
        _state.update { s ->
            if (s.phase != Phase.PREVIEW) return@update s
            val next = s.meals.filterNot {
                MealPlanGeneration.slotKey(it.day, it.slot) ==
                    MealPlanGeneration.slotKey(meal.day, meal.slot)
            }
            s.copy(meals = next)
        }
    }

    /**
     * Discover → filter → request. Discovery and filtering run before the
     * signer prompt so a no-candidates outcome never costs an Amber tap.
     * The sheet shows [Phase.WORKING] while discovering, then [Phase.SIGNING]
     * before the NIP-98 round trip, then [Phase.WORKING] again once
     * [onAuthHeaderReady] fires — signing is never a Cheffy spinner.
     */
    fun generate(
        plan: Schema.MealPlan,
        pubkey: String?,
        signer: NostrSigner?,
        discover: suspend (MealPlanGeneration.RecipeSource, String) -> List<MealPlanCandidates.Candidate>,
        request: suspend (
            MealPlanGeneration.MealPlanGenerationRequest,
            NostrSigner,
            (() -> Unit)?,
        ) -> MealPlanResult,
    ) {
        val s = _state.value
        if (s.phase != Phase.FORM && s.phase != Phase.ERROR) return
        if (!s.canSubmit) return
        generateJob?.cancel()
        generateJob = viewModelScope.launch { generateOnce(plan, pubkey, signer, discover, request) }
    }

    /** Body of [generate], callable from tests without a [viewModelScope] hop. */
    internal suspend fun generateOnce(
        plan: Schema.MealPlan,
        pubkey: String?,
        signer: NostrSigner?,
        discover: suspend (MealPlanGeneration.RecipeSource, String) -> List<MealPlanCandidates.Candidate>,
        request: suspend (
            MealPlanGeneration.MealPlanGenerationRequest,
            NostrSigner,
            (() -> Unit)?,
        ) -> MealPlanResult,
    ) {
        val s = _state.value
        if (s.phase != Phase.FORM && s.phase != Phase.ERROR) return
        if (!s.canSubmit) return
        val pk = pubkey?.trim().orEmpty()
        if (pk.isEmpty() || signer == null) {
            _state.update { it.copy(phase = Phase.SIGN_IN) }
            return
        }

        val occupied = MealPlanGeneration.occupiedSlotsFromPlan(plan, s.days, s.mealSlots)
        val occupiedKeys = MealPlanGeneration.occupiedSlotSet(occupied)
        val targetCount = MealPlanGeneration.cartesianSlots(s.days, s.mealSlots)
            .count { ref ->
                s.strategy != MealPlanGeneration.MealPlanStrategy.FILL_EMPTY ||
                    MealPlanGeneration.slotKey(ref.day, ref.slot) !in occupiedKeys
            }
        if (targetCount == 0) {
            _state.update { it.copy(phase = Phase.FORM, message = ALL_OCCUPIED_LINE) }
            return
        }

        enterWorking()
        val discovered = try {
            discover(s.source, pk)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            applyResult(MealPlanResult.Failed(NETWORK_ERROR_FALLBACK), s.mealSlots, requested = 0)
            return
        }

        val maxMinutes = s.maxMinutesText.trim().toIntOrNull()?.takeIf { it > 0 }
        val servings = s.servingsText.trim().toIntOrNull()?.takeIf { it > 0 }
        val exclude = MealPlanGeneration.parseExcludeIngredientsInput(s.excludeText)
        val filtered = MealPlanGeneration.filterRecipeCandidates(
            discovered.map { it.recipe },
            MealPlanGeneration.FilterCandidatesOptions(
                maxMinutes = maxMinutes,
                excludeIngredients = exclude,
                styles = s.styles,
                mealSlots = s.mealSlots,
            ),
        )
        runCatching {
            Log.i(
                TAG,
                "discover source=${s.source.id} before=${discovered.size} afterFilter=${filtered.size}",
            )
        }
        if (filtered.isEmpty()) {
            applyResult(
                MealPlanResult.NoCandidates(SlotEligibility.noEligibleRecipesMessage(s.mealSlots)),
                s.mealSlots,
                requested = 0,
            )
            return
        }

        val generationRequest = MealPlanGeneration.MealPlanGenerationRequest(
            weekId = s.weekId.ifBlank { plan.week },
            days = s.days,
            mealSlots = s.mealSlots,
            preferences = MealPlanGeneration.MealPlanPreferences(
                styles = s.styles,
                maxMinutes = maxMinutes,
                servings = servings,
                excludeIngredients = exclude.takeIf { it.isNotEmpty() },
                notes = s.notes.trim().ifEmpty { null },
            ),
            strategy = s.strategy,
            candidates = filtered,
            occupiedSlots = occupied,
        )
        val requested = MealPlanGeneration.resolveTargetSlots(generationRequest).size
        _state.update { it.copy(phase = Phase.SIGNING, message = "") }
        stopThinking()

        val imagesByA = discovered.associate { it.a to it.image }
        val result = try {
            request(generationRequest, signer) {
                _state.update { cur ->
                    if (cur.phase == Phase.SIGNING) {
                        cur.copy(
                            phase = Phase.WORKING,
                            thinkingLine = Cheffy.pickLine(Cheffy.THINKING_LINES, cur.thinkingLine),
                        )
                    } else cur
                }
                startThinkingTicker()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            MealPlanResult.Failed(NETWORK_ERROR_FALLBACK)
        }
        val withImages = when (result) {
            is MealPlanResult.Ok -> MealPlanResult.Ok(
                result.meals.map { meal -> meal.copy(image = imagesByA[meal.a] ?: meal.image) },
            )
            else -> result
        }
        applyResult(withImages, s.mealSlots, requested)
    }

    /**
     * One [PlannerViewModel.applyGeneratedPlan] write. Returns that result
     * so the sheet dismisses only on true.
     */
    fun apply(planner: PlannerViewModel): Boolean {
        val s = _state.value
        if (s.phase != Phase.PREVIEW || s.meals.isEmpty() || s.applying) return false
        _state.update { it.copy(applying = true) }
        val ok = planner.applyGeneratedPlan(s.weekId, s.meals, s.strategy)
        if (!ok) {
            _state.update {
                it.copy(
                    applying = false,
                    message = APPLY_FAILED_LINE,
                )
            }
        }
        return ok
    }

    /** Result → phase mapping, testable without HTTP. */
    internal fun applyResult(
        result: MealPlanResult,
        mealSlots: List<String> = _state.value.mealSlots,
        requested: Int = 0,
    ) {
        stopThinking()
        when (result) {
            is MealPlanResult.Ok -> {
                val coverage = SlotEligibility.insufficientSlotCoverageMessage(
                    mealSlots, result.meals.size, requested,
                )
                _state.update {
                    it.copy(
                        phase = Phase.PREVIEW,
                        meals = result.meals,
                        coverageNote = coverage,
                        message = "",
                        thinkingLine = "",
                        applying = false,
                    )
                }
            }
            is MealPlanResult.SignInRequired ->
                _state.update { it.copy(phase = Phase.SIGN_IN, thinkingLine = "", applying = false) }
            is MealPlanResult.MembersOnly ->
                _state.update {
                    it.copy(
                        phase = Phase.UPSELL,
                        message = result.message,
                        thinkingLine = "",
                        applying = false,
                    )
                }
            is MealPlanResult.RateLimited ->
                _state.update {
                    it.copy(
                        phase = Phase.ERROR,
                        message = RATE_LIMITED_LINE,
                        thinkingLine = "",
                        applying = false,
                    )
                }
            is MealPlanResult.NoCandidates ->
                _state.update {
                    it.copy(
                        phase = Phase.ERROR,
                        message = result.message.ifBlank {
                            SlotEligibility.noEligibleRecipesMessage(mealSlots)
                        },
                        thinkingLine = "",
                        applying = false,
                    )
                }
            is MealPlanResult.InvalidRequest ->
                _state.update {
                    it.copy(
                        phase = Phase.ERROR,
                        message = result.message,
                        thinkingLine = "",
                        applying = false,
                    )
                }
            is MealPlanResult.Rejected ->
                _state.update {
                    it.copy(
                        phase = Phase.ERROR,
                        message = result.message,
                        thinkingLine = "",
                        applying = false,
                    )
                }
            is MealPlanResult.SignFailed ->
                _state.update {
                    it.copy(
                        phase = Phase.ERROR,
                        message = SIGN_FAILED_LINE,
                        thinkingLine = "",
                        applying = false,
                    )
                }
            is MealPlanResult.Failed ->
                _state.update {
                    it.copy(
                        phase = Phase.ERROR,
                        message = result.message,
                        thinkingLine = "",
                        applying = false,
                    )
                }
        }
    }

    private fun enterWorking() {
        _state.update {
            it.copy(
                phase = Phase.WORKING,
                message = "",
                thinkingLine = Cheffy.pickLine(Cheffy.THINKING_LINES, it.thinkingLine),
            )
        }
        startThinkingTicker()
    }

    private fun startThinkingTicker() {
        thinkingJob?.cancel()
        thinkingJob = viewModelScope.launch {
            while (isActive) {
                delay(THINKING_ROTATE_MS)
                _state.update { s ->
                    if (s.phase != Phase.WORKING) return@update s
                    s.copy(thinkingLine = Cheffy.pickLine(Cheffy.THINKING_LINES, s.thinkingLine))
                }
            }
        }
    }

    private fun stopThinking() {
        thinkingJob?.cancel()
        thinkingJob = null
    }

    override fun onCleared() {
        generateJob?.cancel()
        stopThinking()
        super.onCleared()
    }

    companion object {
        const val TAG = "CheffyPlan"
        const val RATE_LIMITED_LINE = "Cheffy is a little busy. Try again in a bit."
        const val SIGN_FAILED_LINE = "Signing was cancelled. Try again when you're ready."
        const val APPLY_FAILED_LINE = "Could not add those meals. This week may be read-only."
        const val ALL_OCCUPIED_LINE =
            "Those slots already have meals. Switch to replace, or pick empty days."
        const val NETWORK_ERROR_FALLBACK =
            "Network error — check your connection and try again."
        private const val THINKING_ROTATE_MS = 2_500L

        private fun toggleKeepingAtLeastOne(
            current: List<String>,
            value: String,
            order: List<String>,
        ): List<String> {
            val next = if (value in current) current - value else current + value
            if (next.isEmpty()) return current
            return order.filter { it in next }
        }
    }
}
