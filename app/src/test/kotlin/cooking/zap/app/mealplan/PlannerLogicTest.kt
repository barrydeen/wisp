package cooking.zap.app.mealplan

import cooking.zap.app.nostr.Nip89
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.repo.PlannerRepository
import cooking.zap.app.viewmodel.PlannerViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic gate for planner repo/VM decision logic — mirrors web
 * `plannerStore.test.ts` plus #174 grocery mechanisms (supersedes,
 * monotonic stamp, normalized exclusion).
 *
 * Vectors inline (PR 9 will extract shared JSON fixtures).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerLogicTest {

    private val W28 = "2026-W28"
    private val W29 = "2026-W29"
    private val W30 = "2026-W30"
    private val W31 = "2026-W31"
    private val pk = "ab".repeat(32)

    // ---- neighborhood / fetch targets ---------------------------------------

    @Test
    fun neighborhood_isVisiblePlusBothNeighbors() {
        assertEquals(listOf(W29, W28, W30), PlannerLogic.neighborhood(W29))
    }

    @Test
    fun fetchTargets_skipsLoadedUnlessForce() {
        val loaded = setOf(W28, W29, W30)
        assertEquals(
            emptyList<String>(),
            PlannerLogic.fetchTargets(listOf(W28, W29, W30), loaded, dirty = emptySet(), force = false),
        )
        assertEquals(
            listOf(W31),
            PlannerLogic.fetchTargets(listOf(W29, W30, W31), loaded, dirty = emptySet(), force = false),
        )
        assertEquals(
            setOf(W28, W29, W30),
            PlannerLogic.fetchTargets(listOf(W28, W29, W30), loaded, dirty = emptySet(), force = true).toSet(),
        )
    }

    @Test
    fun fetchTargets_skipsDirtyWeeks_evenOnForce() {
        assertEquals(
            listOf(W31),
            PlannerLogic.fetchTargets(
                weekIds = listOf(W30, W31),
                loaded = setOf(W28, W29, W30),
                dirty = setOf(W30),
                force = false,
            ),
        )
        assertEquals(
            listOf(W28, W30),
            PlannerLogic.fetchTargets(
                weekIds = listOf(W28, W29, W30),
                loaded = emptySet(),
                dirty = setOf(W29),
                force = true,
            ),
        )
    }

    // ---- week-state transitions ---------------------------------------------

    @Test
    fun toWeekState_absentIsEmpty_decryptFailedPreserved_readOnlyLoaded() {
        val empty = PlannerLogic.toWeekState(W29, null)
        assertTrue(empty is PlannerWeekState.Empty)
        assertEquals(W29, (empty as PlannerWeekState.Empty).plan.week)

        val failed = PlannerLogic.toWeekState(
            W29,
            MealPlanFetchResult.DecryptFailed(W29, null, "denied"),
        )
        assertEquals(PlannerWeekState.DecryptFailed("denied"), failed)

        val plan = Schema.createEmptyMealPlan(W29)
        val loaded = PlannerLogic.toWeekState(
            W29,
            MealPlanFetchResult.Ok(W29, plan, readOnly = true, event = fakeEvent(W29)),
        )
        assertTrue(loaded is PlannerWeekState.Loaded)
        assertTrue((loaded as PlannerWeekState.Loaded).readOnly)
    }

    // ---- mutation rejection matrix ------------------------------------------

    @Test
    fun canMutate_rejectsUnloadedDecryptFailedReadOnly() {
        assertFalse(PlannerLogic.canMutate(null))
        assertFalse(PlannerLogic.canMutate(PlannerWeekState.Loading))
        assertFalse(PlannerLogic.canMutate(PlannerWeekState.DecryptFailed("x")))
        assertFalse(
            PlannerLogic.canMutate(
                PlannerWeekState.Loaded(Schema.createEmptyMealPlan(W29), readOnly = true),
            ),
        )
        assertTrue(PlannerLogic.canMutate(PlannerWeekState.Empty(Schema.createEmptyMealPlan(W29))))
        assertTrue(
            PlannerLogic.canMutate(
                PlannerWeekState.Loaded(Schema.createEmptyMealPlan(W29), readOnly = false),
            ),
        )
    }

    @Test
    fun mutations_setClearNotes_preserveUnknownFields() {
        val base = Schema.createEmptyMealPlan(W29)
        val withExtra = Schema.MealPlan(
            JsonObject(base.json + ("futureFeature" to buildJsonObject { put("some", "data") })),
        )
        var plan = PlannerMutations.setSlot(
            withExtra, "mon", "dinner", PlannerMutations.textSlot("Tacos"),
        )
        plan = PlannerMutations.setSlot(
            plan, "mon", "breakfast",
            PlannerMutations.recipeSlot("30023:abc:oats", title = "Oats"),
        )
        plan = PlannerMutations.setDayNotes(plan, "mon", "prep ahead")
        plan = PlannerMutations.setWeekNotes(plan, "busy week")
        plan = PlannerMutations.clearSlot(plan, "mon", "breakfast")

        assertEquals(
            buildJsonObject { put("type", "text"); put("text", "Tacos") },
            plan.slot("mon", "dinner"),
        )
        assertEquals(null, plan.slot("mon", "breakfast"))
        assertEquals("prep ahead", plan.day("mon")?.get("notes")?.let { (it as JsonPrimitive).content })
        assertEquals("busy week", plan.notes)
        assertEquals(
            buildJsonObject { put("some", "data") },
            plan.json["futureFeature"]?.jsonObject,
        )
    }

    // ---- supersedes / newest-per-week (NIP-01) ------------------------------

    @Test
    fun supersedes_matchesNip01ReplaceableRules() {
        assertTrue(PlannerLogic.supersedes(200, "ff", 100, "00"))
        assertFalse(PlannerLogic.supersedes(100, "00", 200, "ff"))
        // Same created_at: LOWEST id wins — the version relays retain.
        assertTrue(PlannerLogic.supersedes(100, "0a", 100, "0b"))
        assertFalse(PlannerLogic.supersedes(100, "0b", 100, "0a"))
        assertFalse(PlannerLogic.supersedes(100, "0a", 100, "0a"))
    }

    @Test
    fun newestPerWeek_sameSecondTieBreak_keepsLowestId() {
        // Release-blocking for weekly d-tags: two same-second publishes.
        val higherId = fakeEvent(W29, createdAt = 100, id = "bb".repeat(32))
        val lowerId = fakeEvent(W29, createdAt = 100, id = "aa".repeat(32))
        val newest = PlannerLogic.newestPerWeek(listOf(higherId, lowerId))
        assertEquals("aa".repeat(32), newest[W29]?.id)
    }

    @Test
    fun newestPerWeek_keepsHighestCreatedAt() {
        val older = fakeEvent(W29, createdAt = 100, id = "aa".repeat(32))
        val newer = fakeEvent(W29, createdAt = 200, id = "bb".repeat(32))
        val other = fakeEvent(W30, createdAt = 150, id = "cc".repeat(32))
        val newest = PlannerLogic.newestPerWeek(listOf(older, newer, other))
        assertEquals(2, newest.size)
        assertEquals("bb".repeat(32), newest[W29]?.id)
        assertEquals("cc".repeat(32), newest[W30]?.id)
    }

    // ---- monotonic stamp ----------------------------------------------------

    @Test
    fun stampForPublish_isStrictlyMonotonic_sameSecondRepublish() {
        val plan = Schema.createEmptyMealPlan(W29).let {
            // Force updatedAt=100 for the regression.
            Schema.MealPlan(
                JsonObject(it.json.toMutableMap().apply {
                    put("updatedAt", JsonPrimitive(100L))
                    put("createdAt", JsonPrimitive(50L))
                }),
            )
        }
        assertEquals(200, PlannerMutations.stampForPublish(plan, 200).updatedAt)
        // Same-second (or clock-skew) republish must bump past prior stamp or
        // NIP-01 same-created_at tie-break can keep the older event.
        assertEquals(101, PlannerMutations.stampForPublish(plan, 100).updatedAt)
        assertEquals(101, PlannerMutations.stampForPublish(plan, 50).updatedAt)
        assertEquals(50, PlannerMutations.stampForPublish(plan, 100).createdAt)
    }

    // ---- tag-array structural parity ----------------------------------------

    @Test
    fun buildTags_isExactlyDAndClient_nothingElse() {
        assertEquals(
            listOf(
                listOf("d", "mealplan-2026-W29"),
                listOf("client", Nip89.CLIENT_NAME),
            ),
            MealPlanEvents.buildTags(W29),
        )
        assertEquals(2, MealPlanEvents.buildTags(W29).size)
        assertTrue(MealPlanEvents.buildTags(W29).none { it.firstOrNull() == "a" })
    }

    // ---- pantry exclusion (normalized) --------------------------------------

    @Test
    fun writeTargets_excludeMembersRelay_normalized() {
        val pool = listOf(
            "wss://nos.lol",
            "wss://relay.damus.io",
            RelayConfig.MEMBERS_RELAY,
            "WSS://Pantry.Zap.Cooking/", // casing + trailing slash must still match
            "wss://relay.primal.net",
        )
        val targets = PlannerLogic.writeTargets(pool, PlannerRepository.WRITE_EXCLUDE)
        assertFalse(targets.any { RelayConfig.normalizeForCompare(it) == RelayConfig.normalizeForCompare(RelayConfig.MEMBERS_RELAY) })
        assertEquals(
            listOf("wss://nos.lol", "wss://relay.damus.io", "wss://relay.primal.net"),
            targets,
        )
        assertEquals(setOf("wss://pantry.zap.cooking"), PlannerRepository.WRITE_EXCLUDE)
    }

    // ---- debounce coalescing (job-cancel, virtual time) ---------------------

    @Test
    fun debounce_coalescesBurstIntoOneSave() = runTest {
        val saved = mutableListOf<String>()
        val jobs = HashMap<String, Job>()
        fun schedule(key: String) {
            jobs.remove(key)?.cancel()
            jobs[key] = launch {
                delay(PlannerViewModel.SAVE_DEBOUNCE_MS)
                jobs.remove(key)
                saved.add(key)
            }
        }
        schedule(W29)
        advanceTimeBy(500); schedule(W29)
        advanceTimeBy(500); schedule(W29)
        assertTrue(saved.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(W29), saved)
    }

    @Test
    fun logoutClear_cancelsPendingDebounce() = runTest {
        val saved = mutableListOf<String>()
        val jobs = HashMap<String, Job>()
        fun schedule(key: String) {
            jobs.remove(key)?.cancel()
            jobs[key] = launch {
                delay(PlannerViewModel.SAVE_DEBOUNCE_MS)
                jobs.remove(key)
                saved.add(key)
            }
        }
        schedule(W29)
        // clearAllTimers equivalent:
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        advanceUntilIdle()
        assertTrue(saved.isEmpty())
    }

    private fun fakeEvent(weekId: String, createdAt: Long = 1, id: String = "ee".repeat(32)) =
        NostrEvent(
            id = id,
            pubkey = pk,
            created_at = createdAt,
            kind = MealPlanEvents.KIND,
            tags = MealPlanEvents.buildTags(weekId),
            content = "ciphertext",
            sig = "0".repeat(128),
        )
}
