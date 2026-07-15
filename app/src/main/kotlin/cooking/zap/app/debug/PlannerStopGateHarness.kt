package cooking.zap.app.debug

import android.util.Log
import cooking.zap.app.mealplan.MealPlanEvents
import cooking.zap.app.mealplan.PlannerMutations
import cooking.zap.app.mealplan.PlannerWeekState
import cooking.zap.app.mealplan.Schema
import cooking.zap.app.mealplan.Week
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.repo.PlannerRepository
import cooking.zap.app.viewmodel.PlannerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * DEBUG-only planner stop-gate / smoke harness (arc PR 7). Long-press the
 * Planner teaser to open the dialog. Not shipped in release (call site is
 * BuildConfig.DEBUG-gated).
 */
object PlannerStopGateHarness {
    const val TAG = "PR7-PLANNER"

    fun tagShapeAudit() {
        val week = Week.currentWeekId()
        val tags = MealPlanEvents.buildTags(week)
        Log.i(TAG, "tags=$tags d=${Week.dTagForWeek(week)} pantryExcluded=${PlannerRepository.WRITE_EXCLUDE}")
        check(tags.size == 2 && tags[0][0] == "d" && tags[1][0] == "client")
        check(tags.none { it.firstOrNull() == "a" })
        Log.i(TAG, "tag-shape OK for $week")
    }

    fun smokeSaveAndReload(vm: PlannerViewModel, scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            try {
                val week = Week.currentWeekId()
                Log.i(TAG, "smoke: load neighborhood around $week")
                vm.goToWeek(week)
                delay(4_000)

                when (val state = vm.weeks.value[week]) {
                    is PlannerWeekState.DecryptFailed -> {
                        Log.e(TAG, "smoke ABORT — decrypt-failed: ${state.error}")
                        return@launch
                    }
                    else -> Log.i(TAG, "smoke: initial state=$state")
                }

                check(
                    vm.setSlot(
                        week, "mon", "dinner",
                        PlannerMutations.recipeSlot("30023:smoke:debug-recipe", "PR7 Smoke Recipe"),
                    ),
                )
                check(vm.setSlot(week, "mon", "lunch", PlannerMutations.textSlot("PR7 leftovers")))
                check(vm.setWeekNotes(week, "PR7 smoke week"))

                Log.i(TAG, "smoke: saveNow")
                vm.saveNow()
                delay(2_000)

                Log.i(TAG, "smoke: refresh")
                vm.refresh()
                delay(4_000)

                val plan = when (val state = vm.weeks.value[week]) {
                    is PlannerWeekState.Loaded -> state.plan
                    is PlannerWeekState.Empty -> state.plan
                    else -> {
                        Log.e(TAG, "smoke FAIL — got ${vm.weeks.value[week]}")
                        return@launch
                    }
                }
                Log.i(
                    TAG,
                    "smoke dinner=${plan.slot("mon", "dinner")} lunch=${plan.slot("mon", "lunch")} " +
                        "notes=${plan.notes} d=${Week.dTagForWeek(week)}",
                )
                Log.i(TAG, "smoke DONE — audit relays: NOT on pantry; cold-relaunch should reload")
            } catch (e: Exception) {
                Log.e(TAG, "smoke FAILED: ${e.message}", e)
            }
        }
    }

    fun decryptRoundTrip(signer: NostrSigner, scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            try {
                val week = Week.currentWeekId()
                var plan = Schema.createEmptyMealPlan(week)
                plan = PlannerMutations.setSlot(
                    plan, "tue", "breakfast",
                    PlannerMutations.recipeSlot("30023:pk:eggs", "Eggs"),
                )
                plan = PlannerMutations.setSlot(
                    plan, "tue", "snack", PlannerMutations.textSlot("Fruit"),
                )
                val breakfast = plan.slot("tue", "breakfast")!!.toMutableMap()
                breakfast["servings"] = JsonPrimitive(2)
                plan = PlannerMutations.setSlot(plan, "tue", "breakfast", JsonObject(breakfast))

                val event = MealPlanEvents.createPlanEvent(signer, plan)
                Log.i(TAG, "roundtrip tags=${event.tags}")
                check(event.tags == MealPlanEvents.buildTags(week))

                val parsed = MealPlanEvents.decryptPlanEvent(event, week, signer)
                check(parsed is Schema.MealPlanPayloadResult.Ok)
                val ok = parsed as Schema.MealPlanPayloadResult.Ok
                check(ok.plan.slot("tue", "breakfast")?.get("servings") != null) {
                    "unknown slot field lost"
                }
                val roundJson = Json.parseToJsonElement(Schema.serializeMealPlan(ok.plan)).jsonObject
                Log.i(TAG, "roundtrip OK keys=${roundJson.keys}")
            } catch (e: Exception) {
                Log.e(TAG, "roundtrip FAILED: ${e.message}", e)
            }
        }
    }
}
