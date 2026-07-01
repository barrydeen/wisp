package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table + edge tests for [FoodContentScorer] — the web client's real
 * `contentContainsFoodWords` edge cases must pass verbatim — and eviction/memoization
 * tests for [MemoizedFoodContentScorer].
 */
class FoodContentScorerTest {

    private data class Case(val content: String, val expected: Boolean, val why: String)

    private val table = listOf(
        // ── ≥1 HARD word includes ──────────────────────────────────────────────
        Case("I made fresh pasta tonight", true, "1 hard word (pasta) includes"),
        Case("slow cooked brisket all day", true, "multi-word hard phrase (slow cooked)"),
        // ── exactly 1 SOFT word excludes; ≥2 SOFT includes ─────────────────────
        Case("just thinking about food", false, "exactly 1 soft word (food) excludes"),
        Case("that take was sweet and spicy", true, "2 soft words (sweet, spicy) includes"),
        // ── food hashtag in the TEXT includes ──────────────────────────────────
        Case("loving the #foodstr community lately", true, "inline food hashtag includes"),
        Case("gm #zapcooking", true, "inline food hashtag at boundary includes"),
        // ── standalone `root` excludes (unconditionally) ───────────────────────
        Case("the root of the problem is trust", false, "standalone root excludes"),
        Case("root vegetable recipe night", false, "root excludes even past a hard word"),
        Case("uprooted plants and rooting hormone", false, "no standalone root, no food → excludes"),
        // ── macro "excluding food and energy" guard ────────────────────────────
        Case("core inflation excluding food and energy rose", false, "macro phrase, only soft food → excludes"),
        Case("cpi excluding food and energy, anyway I love pasta", true, "macro phrase but hard word overrides"),
        Case("sweet reads, excluding food and energy inflation", true, "macro phrase + 2 soft total (sweet + the phrase's own food) includes"),
        // ── word-boundary correctness (substrings must NOT match) ──────────────
        Case("piecemeal reforms passed today", false, "'meal' inside piecemeal must not match"),
        Case("a tiny kitchenette in the flat", false, "'kitchen' inside kitchenette must not match"),
        Case("the grille of the car is chrome", false, "'grill' inside grille must not match"),
        // ── multi-word phrase matching ─────────────────────────────────────────
        Case("a light drizzle of olive oil", true, "multi-word phrase olive oil matches"),
        Case("olive\n oil over the greens", true, "phrase across whitespace/newline still matches"),
        Case("an olive branch and some motor oil", false, "non-adjacent olive/oil must not match phrase"),
        // ── normalization / case ───────────────────────────────────────────────
        Case("RECIPE for disaster", true, "uppercase lowercases to a hard match"),
        Case("", false, "empty excludes"),
        Case("     ", false, "blank excludes"),
    )

    @Test
    fun scorer_table() {
        for (c in table) {
            assertEquals("[${c.why}] content=\"${c.content}\"", c.expected, FoodContentScorer.matches(c.content))
        }
    }

    @Test
    fun memoized_matches_agrees_with_pure_scorer() {
        val memo = MemoizedFoodContentScorer()
        for (c in table) {
            assertEquals(c.why, FoodContentScorer.matches(c.content), memo.matches(c.content))
        }
    }

    @Test
    fun memoized_caches_repeat_content() {
        var calls = 0
        val memo = MemoizedFoodContentScorer(score = { calls++; true })
        assertTrue(memo.matches("pasta"))
        assertTrue(memo.matches("pasta"))
        assertTrue(memo.matches("pasta"))
        assertEquals("repeat content is scored once", 1, calls)
    }

    @Test
    fun memoized_evicts_oldest_in_a_batch_and_rescoring_follows() {
        var calls = 0
        // max=4, target=2: crossing 4 entries evicts the 3 oldest down to 2.
        val memo = MemoizedFoodContentScorer(maxEntries = 4, targetEntries = 2, score = { calls++; true })
        for (k in listOf("a", "b", "c", "d", "e")) memo.matches(k) // 5 distinct, none over cap yet
        assertEquals(5, calls)
        memo.matches("f")            // size 5 > 4 → evict a, b, c (down to 2), then insert f
        assertEquals(6, calls)
        memo.matches("d")            // survived eviction → cached, no re-score
        memo.matches("e")            // survived eviction → cached, no re-score
        assertEquals("d and e stayed cached", 6, calls)
        memo.matches("a")            // was evicted → re-scored
        assertEquals("evicted content is re-scored", 7, calls)
    }

    @Test
    fun memoized_blank_is_not_cached_or_scored() {
        var calls = 0
        val memo = MemoizedFoodContentScorer(score = { calls++; true })
        assertFalse(memo.matches(""))
        assertFalse(memo.matches("   "))
        assertFalse(memo.matches("\n\t "))
        assertEquals("empty and whitespace-only short-circuit before scoring", 0, calls)
    }
}
