package cooking.zap.app.debug

import android.util.Log
import cooking.zap.app.mealplan.MealPlanEvents
import cooking.zap.app.mealplan.PlannerMutations
import cooking.zap.app.mealplan.PlannerWeekState
import cooking.zap.app.mealplan.Schema
import cooking.zap.app.mealplan.Week
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.PlannerRepository
import cooking.zap.app.relay.RelayPool
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

    // ---- DecryptFailed injection (PR 8 UI verification) ---------------------

    /**
     * Base64 that is NOT a valid NIP-44 payload: decodes to bytes whose first
     * (version) byte is not 2, so [MealPlanEvents.decryptPlanEvent] throws and
     * maps to DecryptFailed — the whole point of the plant.
     */
    private const val UNDECRYPTABLE_CONTENT = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5"

    // Remembered so the plant can be cleaned up (process-global; debug only).
    @Volatile private var plantedWeek: String? = null
    @Volatile private var plantedEventId: String? = null
    @Volatile private var plantedStamp: Long = 0L

    /**
     * Plant an undecryptable meal-plan event for the CURRENT week so the week
     * view renders [PlannerWeekState.DecryptFailed]. Signed with the real
     * contract tags but junk content; `created_at` is stamped a few minutes
     * ahead so it supersedes any real plan (NIP-01 newest-wins). Published to
     * write relays (pantry-excluded) AND cached, then the VM refreshes.
     *
     * Reversible via [cleanupPlanted] — the deletion is stamped past this event.
     */
    fun plantUndecryptable(
        signer: NostrSigner,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        vm: PlannerViewModel,
        scope: CoroutineScope,
    ) {
        scope.launch(Dispatchers.Default) {
            try {
                val week = vm.currentWeekId.value
                val stamp = System.currentTimeMillis() / 1000 + 300
                val event = signer.signEvent(
                    kind = MealPlanEvents.KIND,
                    content = UNDECRYPTABLE_CONTENT,
                    tags = MealPlanEvents.buildTags(week),
                    createdAt = stamp,
                )
                eventRepo.cacheEvent(event)
                val count = relayPool.sendToWriteRelaysExcluding(
                    ClientMessage.event(event), PlannerRepository.WRITE_EXCLUDE,
                )
                plantedWeek = week
                plantedEventId = event.id
                plantedStamp = stamp
                Log.i(TAG, "planted undecryptable week=$week id=${event.id.take(8)} relays=$count stamp=$stamp")
                vm.refresh()
                delay(4_000)
                Log.i(TAG, "after plant, state=${vm.weeks.value[week]} (expect DecryptFailed)")
            } catch (e: Exception) {
                Log.e(TAG, "plant FAILED: ${e.message}", e)
            }
        }
    }

    /**
     * Plant a VALID but schemaVersion-2 plan for the current week so the week
     * view renders Loaded(readOnly=true) — banner + disabled editing. Encrypted
     * to self (decrypts fine), so the read-only comes from the schema version,
     * not a crypto failure. Reversible via [cleanupPlanted].
     */
    fun plantReadOnly(
        signer: NostrSigner,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        vm: PlannerViewModel,
        scope: CoroutineScope,
    ) {
        scope.launch(Dispatchers.Default) {
            try {
                val week = vm.currentWeekId.value
                val stamp = System.currentTimeMillis() / 1000 + 300
                val payload = """{"schemaVersion":2,"week":"$week","days":{"mon":{"slots":{"dinner":{"type":"text","text":"From a newer app"}}}},"createdAt":$stamp,"updatedAt":$stamp}"""
                val content = signer.nip44Encrypt(payload, signer.pubkeyHex)
                val event = signer.signEvent(
                    kind = MealPlanEvents.KIND,
                    content = content,
                    tags = MealPlanEvents.buildTags(week),
                    createdAt = stamp,
                )
                eventRepo.cacheEvent(event)
                val count = relayPool.sendToWriteRelaysExcluding(
                    ClientMessage.event(event), PlannerRepository.WRITE_EXCLUDE,
                )
                plantedWeek = week
                plantedEventId = event.id
                plantedStamp = stamp
                Log.i(TAG, "planted read-only(v2) week=$week id=${event.id.take(8)} relays=$count")
                vm.refresh()
                delay(4_000)
                Log.i(TAG, "after read-only plant, state=${vm.weeks.value[week]} (expect Loaded readOnly=true)")
            } catch (e: Exception) {
                Log.e(TAG, "plant read-only FAILED: ${e.message}", e)
            }
        }
    }

    /** Undo [plantUndecryptable]/[plantReadOnly]: NIP-09 delete stamped past the planted event, then refresh. */
    fun cleanupPlanted(
        signer: NostrSigner,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        vm: PlannerViewModel,
        scope: CoroutineScope,
    ) {
        scope.launch(Dispatchers.Default) {
            try {
                val week = plantedWeek ?: run { Log.i(TAG, "nothing planted"); return@launch }
                val deleteStamp = plantedStamp + 1
                val deletion = MealPlanEvents.createDeletionEvent(
                    signer, week, eventId = plantedEventId, createdAt = deleteStamp,
                )
                eventRepo.deletedEventsRepo?.markDeletedAddress(
                    MealPlanEvents.KIND, signer.pubkeyHex, Week.dTagForWeek(week), deleteStamp,
                )
                val count = relayPool.sendToWriteRelaysExcluding(
                    ClientMessage.event(deletion), PlannerRepository.WRITE_EXCLUDE,
                )
                Log.i(TAG, "cleaned planted week=$week relays=$count")
                plantedWeek = null
                plantedEventId = null
                plantedStamp = 0L
                vm.refresh()
            } catch (e: Exception) {
                Log.e(TAG, "cleanup FAILED: ${e.message}", e)
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
