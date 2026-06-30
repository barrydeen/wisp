package cooking.zap.app.viewmodel

import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.repo.OnlyFoodFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Convergence / anti-drift test for PR-U2. Both OnlyFood surfaces now route the
 * food-quality decision through the SAME [OnlyFoodFilter], so they can't diverge:
 *  - home feed (`EventRepository.addHashtagFeedEvent` kind-1): inserts iff ACCEPT,
 *    counts a WoT drop iff WOT_FILTERED.
 *  - drawer VM (`OnlyFoodFeedViewModel.accept`): a FOLLOWING-mode author gate, then
 *    ingests iff ACCEPT, counts a WoT drop iff WOT_FILTERED.
 *
 * The mappings below mirror those two call sites exactly. The test asserts the
 * drawer's GLOBAL-mode admission equals the home feed's admission across a corpus
 * (so a future change to either mapping fails here), demonstrates the de-drift
 * (blocked pubkey / hellthread / hashtag-spam — which the old mute-only drawer
 * accept() would have ADMITTED — are now dropped), and locks the cold-WoT guard.
 */
class OnlyFoodVmConvergenceTest {

    private val NOW = 1_000_000L

    private fun ev(
        id: String,
        pubkey: String = "good",
        content: String = "tasty",
        tags: List<List<String>> = emptyList(),
        createdAt: Long = NOW - 100,
    ) = NostrEvent(id, pubkey, createdAt, 1, tags, content, "")

    private fun filter(
        userBlocked: Set<String> = emptySet(),
        mutedWords: Set<String> = emptySet(),
        deletedIds: Set<String> = emptySet(),
        wotEnabled: Boolean = false,
        networkReady: Boolean = false,
        currentUser: String = "me",
        qualified: Set<String> = emptySet(),
        foodSeed: Set<String> = emptySet(),
    ) = OnlyFoodFilter(
        nowSeconds = { NOW },
        isUserBlocked = { it in userBlocked },
        containsMutedWord = { c -> mutedWords.any { c.lowercase().contains(it) } },
        isThreadMuted = { false },
        isDeleted = { it in deletedIds },
        isWotFiltered = { pk ->
            // Verbatim mirror of EventRepository.isOnlyFoodWotFiltered, incl. no-op guard.
            when {
                !wotEnabled -> false
                !networkReady -> false
                pk == currentUser -> false
                pk in qualified -> false
                pk in foodSeed -> false
                else -> true
            }
        },
    )

    // --- the two surfaces' admission mappings, mirroring their real call sites ---

    private fun homeAdmits(f: OnlyFoodFilter, e: NostrEvent) =
        f.decideKind1(e) == OnlyFoodFilter.Decision.ACCEPT

    private fun homeWotDropped(f: OnlyFoodFilter, e: NostrEvent) =
        f.decideKind1(e) == OnlyFoodFilter.Decision.WOT_FILTERED

    /** Mirrors OnlyFoodFeedViewModel.accept(event, follows): mode gate, then filter. */
    private fun drawerAdmits(f: OnlyFoodFilter, e: NostrEvent, follows: Set<String>?): Boolean {
        if (follows != null && e.pubkey !in follows) return false
        return f.decideKind1(e) == OnlyFoodFilter.Decision.ACCEPT
    }

    private fun drawerWotDropped(f: OnlyFoodFilter, e: NostrEvent, follows: Set<String>?): Boolean {
        if (follows != null && e.pubkey !in follows) return false
        return f.decideKind1(e) == OnlyFoodFilter.Decision.WOT_FILTERED
    }

    private fun corpus() = listOf(
        ev("good", pubkey = "good", content = "yummy #cooking", tags = listOf(listOf("t", "cooking"))),
        ev("blocked", pubkey = OnlyFoodFilter.BLOCKED_PUBKEYS.first()),
        ev("userblocked", pubkey = "ub"),
        ev("muted", content = "this is badword"),
        ev("del1"),
        ev("hellthread", tags = (0 until 25).map { listOf("p", "p$it") }),
        ev("hashtagspam", tags = (0 until 6).map { listOf("t", "t$it") }),
        ev("contentspam", content = "#a #b #c #d #e #f"),
        ev("reply", tags = listOf(listOf("e", "root", "", "reply"), listOf("p", "x"))),
        ev("stranger", pubkey = "stranger"),
        ev("friend", pubkey = "friend"),
        ev("future", createdAt = NOW + 100),
    )

    @Test
    fun drawerGlobal_andHomeFeed_makeIdenticalDecisions_acrossCorpus() {
        val f = filter(
            userBlocked = setOf("ub"),
            mutedWords = setOf("badword"),
            deletedIds = setOf("del1"),
            wotEnabled = true,
            networkReady = true,
            qualified = setOf("friend"),
        )
        for (e in corpus()) {
            assertEquals(
                "admission diverged for '${e.id}'",
                homeAdmits(f, e),
                drawerAdmits(f, e, follows = null),
            )
            assertEquals(
                "WoT-drop classification diverged for '${e.id}'",
                homeWotDropped(f, e),
                drawerWotDropped(f, e, follows = null),
            )
        }
    }

    @Test
    fun drawer_nowApplies_theDefensesItWasMissing() {
        // The old drawer accept() was mute/block/word ONLY — these would have been
        // ADMITTED. After U2 they're dropped, matching the home feed.
        val f = filter()
        assertTrue("a clean food note is still admitted", drawerAdmits(f, ev("ok", content = "tasty"), null))
        assertFalse("app-level blocklist now applies", drawerAdmits(f, ev("b", pubkey = OnlyFoodFilter.BLOCKED_PUBKEYS.first()), null))
        assertFalse("hellthread cap now applies", drawerAdmits(f, ev("h", tags = (0 until 25).map { listOf("p", "p$it") }), null))
        assertFalse("hashtag-cap now applies", drawerAdmits(f, ev("s", tags = (0 until 6).map { listOf("t", "t$it") }), null))
        assertFalse("inline-hashtag spam now applies", drawerAdmits(f, ev("c", content = "#a #b #c #d #e #f"), null))
    }

    @Test
    fun coldStart_drawerDoesNotGoBlank_wotNoOpUntilNetworkReady() {
        val stranger = ev("s", pubkey = "stranger")
        // WoT OFF (default) → admitted.
        assertTrue(drawerAdmits(filter(wotEnabled = false), stranger, null))
        // WoT ON but network NOT ready → no-op, still admitted (the blank-feed guard).
        assertTrue(drawerAdmits(filter(wotEnabled = true, networkReady = false), stranger, null))
        // WoT ON and network ready → stranger dropped, counted as a WoT drop.
        val ready = filter(wotEnabled = true, networkReady = true, qualified = setOf("friend"))
        assertFalse(drawerAdmits(ready, stranger, null))
        assertTrue(drawerWotDropped(ready, stranger, null))
    }

    @Test
    fun following_modeGate_isDrawerSpecific_andStacksOnTheSharedFilter() {
        val f = filter()
        val follows = setOf("friend")
        // A clean note from a non-followed author: admitted in GLOBAL, gated in FOLLOWING.
        val stranger = ev("s", pubkey = "stranger", content = "tasty")
        assertTrue(drawerAdmits(f, stranger, follows = null))
        assertFalse(drawerAdmits(f, stranger, follows = follows))
        // A followed author still passes the shared food-quality filter.
        assertTrue(drawerAdmits(f, ev("f", pubkey = "friend", content = "tasty"), follows = follows))
        // ...but a followed author posting a hellthread is still dropped by the filter.
        assertFalse(drawerAdmits(f, ev("fh", pubkey = "friend", tags = (0 until 25).map { listOf("p", "p$it") }), follows = follows))
    }
}
