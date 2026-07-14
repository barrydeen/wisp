package cooking.zap.app.repo

import cooking.zap.app.nostr.GroceryEvents.GroceryItem
import cooking.zap.app.nostr.GroceryEvents.GroceryList
import cooking.zap.app.relay.RelayConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic gate for GroceryRepository's decision logic (the planMutation
 * precedent from #169): mutation transitions, decrypt-skip merge, pantry
 * exclusion in the write-target set, debounce coalescing, and logout cancel —
 * all without constructing the Android-dependent repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroceryRepositoryLogicTest {

    private val pk = "ab".repeat(32)

    private fun list(id: String = "l1", updatedAt: Long = 100, items: List<GroceryItem> = emptyList()) =
        GroceryList(
            id = id, title = "Shop", items = items, recipeLinks = emptyList(),
            notes = null, createdAt = 1, updatedAt = updatedAt,
        )

    private fun item(id: String, checked: Boolean = false) =
        GroceryItem(id = id, name = id, quantity = "1", category = "other", checked = checked, addedAt = 1)

    // ---- mutation state transitions ----------------------------------------

    @Test
    fun mutations_addEditCheckRemove_produceExpectedState() {
        var l = GroceryMutations.createEmptyList("l1", "Weekly", 500)
        assertEquals("Weekly", l.title)
        assertTrue(l.items.isEmpty())

        l = GroceryMutations.addItem(l, item("a"))
        l = GroceryMutations.addItem(l, item("b"))
        assertEquals(listOf("a", "b"), l.items.map { it.id })

        l = GroceryMutations.setChecked(l, "a", true)
        assertTrue(l.items.first { it.id == "a" }.checked)
        assertFalse(l.items.first { it.id == "b" }.checked)

        l = GroceryMutations.editItem(l, item("b").copy(name = "bread", quantity = "2"))
        assertEquals("bread", l.items.first { it.id == "b" }.name)

        l = GroceryMutations.removeItem(l, "a")
        assertEquals(listOf("b"), l.items.map { it.id })

        l = GroceryMutations.rename(l, "Groceries")
        assertEquals("Groceries", l.title)
        l = GroceryMutations.rename(l, "  ") // blank keeps the old title (web parity)
        assertEquals("Groceries", l.title)

        l = GroceryMutations.setNotes(l, "milk too")
        assertEquals("milk too", l.notes)
        l = GroceryMutations.setNotes(l, "") // blank clears
        assertEquals(null, l.notes)
    }

    @Test
    fun mutations_doNotBumpUpdatedAt_repoStampsAtSaveTime() {
        val l = list(updatedAt = 100)
        val after = GroceryMutations.addItem(l, item("x"))
        assertEquals(100, after.updatedAt) // caller stamps once per publish
    }

    @Test
    fun stampForPublish_isStrictlyMonotonic_sameSecondRepublish() {
        // Normal case: a later clock just stamps now.
        assertEquals(200, GroceryMutations.stampForPublish(list(updatedAt = 100), 200).updatedAt)
        // Same-second republish (create → immediate flush): must move FORWARD,
        // or NIP-01's same-created_at tie-break (lowest id wins) can make relays
        // keep the older version — the PR 4 smoke lost a list's items this way.
        assertEquals(101, GroceryMutations.stampForPublish(list(updatedAt = 100), 100).updatedAt)
        // Clock went backwards: still strictly greater than the last stamp.
        assertEquals(101, GroceryMutations.stampForPublish(list(updatedAt = 100), 50).updatedAt)
    }

    // ---- merge / decrypt-skip ----------------------------------------------

    @Test
    fun merge_newestUpdatedWins_sortedDescending() {
        val current = listOf(list(id = "a", updatedAt = 300), list(id = "b", updatedAt = 100))
        val incoming = listOf(
            list(id = "a", updatedAt = 50),   // older → ignored (protects a dirty local copy)
            list(id = "b", updatedAt = 250),  // newer → replaces
            list(id = "c", updatedAt = 400),  // new
        )
        val merged = GroceryMutations.merge(current, incoming)
        assertEquals(listOf("c", "a", "b"), merged.map { it.id }) // 400, 300, 250 desc
        assertEquals(250, merged.first { it.id == "b" }.updatedAt)
    }

    @Test
    fun merge_treatsNullDecryptsAsAbsent_byConstruction() {
        // decryptListEvent returns null for undecryptable events; the repo's
        // decryptNewestPerDTag uses mapNotNull, so nulls never reach merge.
        val decoded: List<GroceryList?> = listOf(list(id = "ok"), null, list(id = "ok2"))
        val skipped = decoded.filterNotNull()
        val merged = GroceryMutations.merge(emptyList(), skipped)
        assertEquals(setOf("ok", "ok2"), merged.map { it.id }.toSet())
    }

    // ---- pantry exclusion in the write-target set --------------------------

    @Test
    fun writeTargets_excludeMembersRelay() {
        val pool = listOf(
            "wss://nos.lol",
            "wss://relay.damus.io",
            RelayConfig.MEMBERS_RELAY,
            "wss://relay.primal.net",
        )
        val targets = GroceryRepository.writeTargets(pool, GroceryRepository.WRITE_EXCLUDE)
        assertFalse(RelayConfig.MEMBERS_RELAY in targets)
        assertEquals(
            listOf("wss://nos.lol", "wss://relay.damus.io", "wss://relay.primal.net"),
            targets,
        )
    }

    @Test
    fun writeExclude_isThePantryRelay() {
        assertEquals(setOf("wss://pantry.zap.cooking"), GroceryRepository.WRITE_EXCLUDE)
    }

    @Test
    fun writeTargets_excludeMatchesUrlVariants_caseAndTrailingSlash() {
        // A pool entry stored with different casing or a trailing slash must not
        // dodge the exclusion — a member's personal event WOULD be accepted by
        // pantry, so a variant slipping through is a real leak, not a no-op.
        val pool = listOf(
            "wss://nos.lol",
            "wss://PANTRY.zap.cooking",
            "wss://pantry.zap.cooking/",
            " wss://pantry.zap.cooking ",
        )
        val targets = GroceryRepository.writeTargets(pool, GroceryRepository.WRITE_EXCLUDE)
        assertEquals(listOf("wss://nos.lol"), targets)
    }

    @Test
    fun supersedes_matchesNip01ReplaceableRules() {
        // Newer created_at always wins, regardless of id ordering.
        assertTrue(GroceryRepository.supersedes(200, "ff", 100, "00"))
        assertFalse(GroceryRepository.supersedes(100, "00", 200, "ff"))
        // Same created_at: LOWEST id wins (NIP-01 tie-break) — the version the
        // relay retains must be the version we keep locally.
        assertTrue(GroceryRepository.supersedes(100, "0a", 100, "0b"))
        assertFalse(GroceryRepository.supersedes(100, "0b", 100, "0a"))
        assertFalse(GroceryRepository.supersedes(100, "0a", 100, "0a"))
    }

    // ---- debounce coalescing (virtual time) --------------------------------

    @Test
    fun debounce_coalescesBurstIntoOneSave() = runTest {
        val saved = mutableListOf<String>()
        val saver = DebouncedSaver(this, delayMs = 2_000L) { saved.add(it) }

        // Three rapid mutations of the same list within the window.
        saver.schedule("l1")
        advanceTimeBy(500); saver.schedule("l1")
        advanceTimeBy(500); saver.schedule("l1")
        assertTrue(saver.isPending("l1"))
        assertTrue(saved.isEmpty()) // nothing saved yet

        advanceUntilIdle()
        assertEquals(listOf("l1"), saved) // exactly one save
        assertFalse(saver.isPending("l1"))
    }

    @Test
    fun debounce_distinctKeysSaveIndependently() = runTest {
        val saved = mutableListOf<String>()
        val saver = DebouncedSaver(this, delayMs = 2_000L) { saved.add(it) }
        saver.schedule("a")
        saver.schedule("b")
        advanceUntilIdle()
        assertEquals(setOf("a", "b"), saved.toSet())
    }

    @Test
    fun flush_savesImmediatelyAndCancelsTimer() = runTest {
        val saved = mutableListOf<String>()
        val saver = DebouncedSaver(this, delayMs = 2_000L) { saved.add(it) }
        saver.schedule("l1")
        saver.flush("l1")
        assertEquals(listOf("l1"), saved)
        assertFalse(saver.isPending("l1"))
        advanceUntilIdle()
        assertEquals(listOf("l1"), saved) // the cancelled timer did NOT double-save
    }

    @Test
    fun cancel_dropsPendingSaveWithoutSaving() = runTest {
        val saved = mutableListOf<String>()
        val saver = DebouncedSaver(this, delayMs = 2_000L) { saved.add(it) }
        saver.schedule("l1")
        saver.cancel("l1") // e.g. list deleted
        advanceUntilIdle()
        assertTrue(saved.isEmpty())
    }

    // ---- logout clear -------------------------------------------------------

    @Test
    fun cancelAll_dropsEveryPendingSave_logout() = runTest {
        val saved = mutableListOf<String>()
        val saver = DebouncedSaver(this, delayMs = 2_000L) { saved.add(it) }
        saver.schedule("a")
        saver.schedule("b")
        saver.schedule("c")
        saver.cancelAll() // logout / account switch
        assertFalse(saver.isPending("a"))
        advanceUntilIdle()
        assertTrue(saved.isEmpty()) // nothing from the previous account leaks out
    }
}
