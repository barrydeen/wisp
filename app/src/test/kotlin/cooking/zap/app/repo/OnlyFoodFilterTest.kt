package cooking.zap.app.repo

import cooking.zap.app.nostr.Nip10
import cooking.zap.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterization test for PR-U1's [OnlyFoodFilter] extraction.
 *
 * The load-bearing guarantee is "byte-for-byte identical home-feed behavior". To
 * prove it, [reference] re-implements the PRE-extraction kind-1 chain of
 * `EventRepository.addHashtagFeedEvent` VERBATIM (same conditions, same order, its
 * own copy of the structural-spam logic). The test then runs both [reference] and
 * [OnlyFoodFilter.decideKind1] over a representative corpus with the SAME injected
 * deps and asserts an identical accept/reject + WoT-drop classification for every
 * event — so the extraction can't have changed any decision.
 *
 * The critical WoT no-op (drawer-goes-blank failure mode) is covered explicitly:
 * when the social graph isn't ready, the WoT predicate is a no-op and nothing is
 * dropped for WoT reasons.
 */
class OnlyFoodFilterTest {

    private val NOW = 1_000_000L

    private fun ev(
        id: String,
        pubkey: String = "good",
        createdAt: Long = NOW - 100,
        content: String = "",
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        created_at = createdAt,
        kind = 1,
        tags = tags,
        content = content,
        sig = "",
    )

    /** Injected world state shared by [reference] and the [OnlyFoodFilter] under test. */
    private class Config(
        val blocked: Set<String> = OnlyFoodFilter.BLOCKED_PUBKEYS,
        val userBlocked: Set<String> = emptySet(),
        val mutedWords: Set<String> = emptySet(),
        val mutedThreads: Set<String> = emptySet(),
        val deletedIds: Set<String> = emptySet(),
        val wotEnabled: Boolean = false,
        val networkReady: Boolean = false,
        val currentUser: String = "me",
        val qualified: Set<String> = emptySet(),
        val foodSeed: Set<String> = emptySet(),
    ) {
        fun isUserBlocked(pk: String) = pk in userBlocked
        fun containsMutedWord(content: String): Boolean {
            if (mutedWords.isEmpty()) return false
            val lower = content.lowercase()
            return mutedWords.any { lower.contains(it) }
        }
        fun isThreadMuted(root: String) = root in mutedThreads
        fun isDeleted(id: String) = id in deletedIds

        /** Mirrors EventRepository.isOnlyFoodWotFiltered verbatim, including the no-op guard. */
        fun isWotFiltered(pk: String): Boolean {
            if (!wotEnabled) return false
            if (!networkReady) return false          // empty-feed guard (the critical no-op)
            if (pk == currentUser) return false
            if (pk in qualified) return false
            if (pk in foodSeed) return false
            return true
        }
    }

    private fun filterFor(cfg: Config) = OnlyFoodFilter(
        nowSeconds = { NOW },
        blockedPubkeys = cfg.blocked,
        isUserBlocked = cfg::isUserBlocked,
        containsMutedWord = cfg::containsMutedWord,
        isThreadMuted = cfg::isThreadMuted,
        isDeleted = cfg::isDeleted,
        isWotFiltered = cfg::isWotFiltered,
    )

    /** Accept vs reject vs WoT-drop — the only distinctions the original chain made. */
    private data class Outcome(val accepted: Boolean, val wotDropped: Boolean)

    private fun OnlyFoodFilter.Decision.toOutcome() = when (this) {
        OnlyFoodFilter.Decision.ACCEPT -> Outcome(accepted = true, wotDropped = false)
        OnlyFoodFilter.Decision.WOT_FILTERED -> Outcome(accepted = false, wotDropped = true)
        else -> Outcome(accepted = false, wotDropped = false)
    }

    // ---- the PRE-extraction kind-1 chain, copied verbatim ---------------------

    private fun refStructuralSpam(event: NostrEvent): Boolean {
        var pCount = 0
        var tCount = 0
        for (tag in event.tags) {
            if (tag.isEmpty()) continue
            when (tag[0]) {
                "p" -> pCount++
                "t" -> tCount++
            }
        }
        val contentHashtags = Regex("""(^|\s)#([^\s#]+)""").findAll(event.content).count()
        val hashtagCount = maxOf(contentHashtags, tCount)
        return pCount >= 25 || hashtagCount > 5
    }

    private fun reference(event: NostrEvent, cfg: Config): Outcome {
        if (event.created_at > NOW + 30) return Outcome(false, false)
        if (event.pubkey in cfg.blocked) return Outcome(false, false)
        if (cfg.isUserBlocked(event.pubkey)) return Outcome(false, false)
        if (cfg.isDeleted(event.id)) return Outcome(false, false)
        // kind-1 branch
        if (cfg.containsMutedWord(event.content)) return Outcome(false, false)
        val threadRoot = Nip10.getRootId(event) ?: Nip10.getReplyTarget(event) ?: event.id
        if (cfg.isThreadMuted(threadRoot)) return Outcome(false, false)
        if (refStructuralSpam(event)) return Outcome(false, false)
        val isReply = Nip10.isReply(event)
        if (!isReply) {
            if (cfg.isWotFiltered(event.pubkey)) return Outcome(false, true)
            return Outcome(true, false)
        }
        return Outcome(false, false) // reply: dropped, not counted as a WoT drop
    }

    // ---- corpus equivalence: filter decision == verbatim reference -------------

    private fun corpus(): List<NostrEvent> {
        val pTags = { n: Int -> (0 until n).map { listOf("p", "p$it") } }
        val tTags = { n: Int -> (0 until n).map { listOf("t", "t$it") } }
        return listOf(
            ev("accept", pubkey = "good", content = "yummy #cooking", tags = listOf(listOf("t", "cooking"))),
            ev("blocked", pubkey = OnlyFoodFilter.BLOCKED_PUBKEYS.first(), content = "food"),
            ev("userblocked", pubkey = "muted-author", content = "food"),
            ev("mutedword", pubkey = "good", content = "this is spamword here"),
            ev("deleted", pubkey = "good", content = "food"),
            ev("threadmuted", pubkey = "good", content = "food"), // non-reply → threadRoot == id
            ev("hell24", pubkey = "good", content = "food", tags = pTags(24)),
            ev("hell25", pubkey = "good", content = "food", tags = pTags(25)),
            ev("tags5", pubkey = "good", content = "food", tags = tTags(5)),
            ev("tags6", pubkey = "good", content = "food", tags = tTags(6)),
            ev("contenttags6", pubkey = "good", content = "#a #b #c #d #e #f"),
            ev("reply", pubkey = "good", content = "food", tags = listOf(listOf("e", "root1", "", "reply"), listOf("p", "x"))),
            ev("stranger", pubkey = "stranger", content = "food"),
            ev("friend", pubkey = "friend", content = "food"),
            ev("seeded", pubkey = "seeded", content = "food"),
            ev("future", pubkey = "good", createdAt = NOW + 100, content = "food"),
            ev("me", pubkey = "me", content = "food"),
        )
    }

    @Test
    fun decision_matchesVerbatimReference_acrossCorpus_wotOn_networkReady() {
        val cfg = Config(
            userBlocked = setOf("muted-author"),
            mutedWords = setOf("spamword"),
            mutedThreads = setOf("threadmuted"),
            deletedIds = setOf("deleted"),
            wotEnabled = true,
            networkReady = true,
            currentUser = "me",
            qualified = setOf("friend"),
            foodSeed = setOf("seeded"),
        )
        val filter = filterFor(cfg)
        for (event in corpus()) {
            assertEquals(
                "decision diverged from reference for '${event.id}'",
                reference(event, cfg),
                filter.decideKind1(event).toOutcome(),
            )
        }
    }

    @Test
    fun decision_matchesVerbatimReference_acrossCorpus_wotOff() {
        val cfg = Config(wotEnabled = false, networkReady = true)
        val filter = filterFor(cfg)
        for (event in corpus()) {
            assertEquals(
                "decision diverged from reference for '${event.id}'",
                reference(event, cfg),
                filter.decideKind1(event).toOutcome(),
            )
        }
    }

    // ---- explicit coverage of the documented-critical cases --------------------

    @Test
    fun wot_isNoOp_whenNetworkNotReady_soNothingDroppedForWot() {
        // WoT enabled but the social graph isn't ready: a stranger (outside qualified
        // network AND food seed) must NOT be dropped for WoT — this is the drawer-feed-
        // goes-blank failure mode the guard prevents.
        val cfg = Config(wotEnabled = true, networkReady = false, qualified = emptySet(), foodSeed = emptySet())
        val decision = filterFor(cfg).decideKind1(ev("stranger", pubkey = "stranger", content = "food"))
        assertEquals(OnlyFoodFilter.Decision.ACCEPT, decision)
    }

    @Test
    fun wot_dropsStranger_onlyWhenEnabledAndReady() {
        val ready = Config(wotEnabled = true, networkReady = true, qualified = setOf("friend"), foodSeed = setOf("seeded"))
        assertEquals(
            OnlyFoodFilter.Decision.WOT_FILTERED,
            filterFor(ready).decideKind1(ev("s", pubkey = "stranger", content = "food")),
        )
        assertEquals(
            OnlyFoodFilter.Decision.ACCEPT,
            filterFor(ready).decideKind1(ev("f", pubkey = "friend", content = "food")),
        )
        assertEquals(
            OnlyFoodFilter.Decision.ACCEPT,
            filterFor(ready).decideKind1(ev("seed", pubkey = "seeded", content = "food")),
        )
    }

    @Test
    fun reject_reasons_areCorrectAndOrdered() {
        val cfg = Config(
            userBlocked = setOf("ub"),
            mutedWords = setOf("bad"),
            mutedThreads = setOf("root-muted"),
            deletedIds = setOf("del"),
        )
        val f = filterFor(cfg)
        assertEquals(OnlyFoodFilter.Decision.FUTURE_DATED, f.decideKind1(ev("fut", createdAt = NOW + 100)))
        assertEquals(OnlyFoodFilter.Decision.BLOCKED_PUBKEY, f.decideKind1(ev("b", pubkey = OnlyFoodFilter.BLOCKED_PUBKEYS.first())))
        assertEquals(OnlyFoodFilter.Decision.USER_BLOCKED, f.decideKind1(ev("u", pubkey = "ub")))
        assertEquals(OnlyFoodFilter.Decision.DELETED, f.decideKind1(ev("del")))
        assertEquals(OnlyFoodFilter.Decision.MUTED_WORD, f.decideKind1(ev("w", content = "so bad")))
        assertEquals(OnlyFoodFilter.Decision.THREAD_MUTED, f.decideKind1(ev("root-muted")))
        assertEquals(OnlyFoodFilter.Decision.ACCEPT, f.decideKind1(ev("ok", content = "tasty")))
    }

    @Test
    fun structuralSpam_boundaries() {
        val f = filterFor(Config())
        val p = { n: Int -> (0 until n).map { listOf("p", "p$it") } }
        val t = { n: Int -> (0 until n).map { listOf("t", "t$it") } }
        assertEquals(OnlyFoodFilter.Decision.ACCEPT, f.decideKind1(ev("p24", tags = p(24))))
        assertEquals(OnlyFoodFilter.Decision.STRUCTURAL_SPAM, f.decideKind1(ev("p25", tags = p(25))))
        assertEquals(OnlyFoodFilter.Decision.ACCEPT, f.decideKind1(ev("t5", tags = t(5))))
        assertEquals(OnlyFoodFilter.Decision.STRUCTURAL_SPAM, f.decideKind1(ev("t6", tags = t(6))))
        // max(inline content #tags, t-tags): 6 inline hashtags, 0 t-tags → spam.
        assertEquals(OnlyFoodFilter.Decision.STRUCTURAL_SPAM, f.decideKind1(ev("c6", content = "#a #b #c #d #e #f")))
    }
}
