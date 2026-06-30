package cooking.zap.app.repo

import cooking.zap.app.nostr.Nip10
import cooking.zap.app.nostr.NostrEvent

/**
 * Pure, dependency-injected OnlyFood food-quality filter — the single accept/reject
 * decision for a kind-1 food note. Extracted verbatim from the kind-1 chain of
 * [EventRepository.addHashtagFeedEvent] (PR-U1) so the home feed and (later, PR-U2)
 * the drawer OnlyFood feed share ONE decision and can't drift.
 *
 * **Pure**: no insertion, no caching, no counters, no I/O. The caller owns those
 * side effects — e.g. [EventRepository] increments `_onlyFoodWotDropped` on a
 * [Decision.WOT_FILTERED] result and inserts on [Decision.ACCEPT].
 *
 * **Self-contained**: [decideKind1] re-applies the future-dated / blocklist /
 * blocked / deleted checks that [addHashtagFeedEvent] also runs at its top level,
 * so the SAME filter can drive the drawer feed in U2 (which has no enclosing
 * pre-checks). For the home feed those few boolean reads are redundant but the
 * decision is identical.
 *
 * Dependencies are injected as plain lambdas/values so the module is JVM-testable
 * with no Android or repo wiring:
 * @param nowSeconds current unix time in seconds (injected for deterministic tests).
 * @param blockedPubkeys app-level OnlyFood curation blocklist ([BLOCKED_PUBKEYS]).
 * @param isUserBlocked the viewer's personal mute/block check (mute repo).
 * @param containsMutedWord the viewer's muted-word check (mute repo).
 * @param isThreadMuted the viewer's thread-mute check (mute repo).
 * @param isDeleted NIP-09 deletion check.
 * @param isWotFiltered web-of-trust gate. MUST already encapsulate the
 *   "no-op when the social graph isn't ready" guard (returns false until ready) —
 *   that is the drawer-goes-blank failure mode this filter must never trigger.
 */
class OnlyFoodFilter(
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val blockedPubkeys: Set<String> = BLOCKED_PUBKEYS,
    private val isUserBlocked: (String) -> Boolean,
    private val containsMutedWord: (String) -> Boolean,
    private val isThreadMuted: (String) -> Boolean,
    private val isDeleted: (String) -> Boolean,
    private val isWotFiltered: (String) -> Boolean,
) {
    /**
     * Why a kind-1 note was accepted or dropped. Only [ACCEPT] inserts; only
     * [WOT_FILTERED] increments the WoT-dropped counter. Every other value is a
     * silent drop (mirrors the original chain's bare `return`s). The order of the
     * checks in [decideKind1] matches the original execution order verbatim.
     */
    enum class Decision { ACCEPT, FUTURE_DATED, BLOCKED_PUBKEY, USER_BLOCKED, DELETED, MUTED_WORD, THREAD_MUTED, STRUCTURAL_SPAM, REPLY, WOT_FILTERED }

    /**
     * The OnlyFood accept/reject decision for a kind-1 note. Same conditions, same
     * order as the pre-extraction [addHashtagFeedEvent] kind-1 branch:
     * future-dated → blocklist → user-blocked → deleted → muted-word → thread-muted
     * → structural-spam → reply → web-of-trust.
     */
    fun decideKind1(event: NostrEvent): Decision {
        if (event.created_at > nowSeconds() + FUTURE_SKEW_SECONDS) return Decision.FUTURE_DATED
        if (event.pubkey in blockedPubkeys) return Decision.BLOCKED_PUBKEY
        if (isUserBlocked(event.pubkey)) return Decision.USER_BLOCKED
        if (isDeleted(event.id)) return Decision.DELETED
        if (containsMutedWord(event.content)) return Decision.MUTED_WORD
        val threadRoot = Nip10.getRootId(event) ?: Nip10.getReplyTarget(event) ?: event.id
        if (isThreadMuted(threadRoot)) return Decision.THREAD_MUTED
        if (isStructuralSpam(event)) return Decision.STRUCTURAL_SPAM
        if (Nip10.isReply(event)) return Decision.REPLY
        if (isWotFiltered(event.pubkey)) return Decision.WOT_FILTERED
        return Decision.ACCEPT
    }

    companion object {
        /** Clock skew tolerance for future-dated events (seconds). */
        const val FUTURE_SKEW_SECONDS = 30L
        /** OnlyFood structural spam caps — mirror the web client's FoodstrFeed thresholds. */
        const val HELLTHREAD_P_LIMIT = 25
        const val MAX_HASHTAGS = 5
        // Inline content hashtags, mirroring the web's HASHTAG_PATTERN = /(^|\s)#([^\s#]+)/g.
        private val CONTENT_HASHTAG_REGEX = Regex("""(^|\s)#([^\s#]+)""")

        /**
         * App-level OnlyFood blocklist (curation). Applies to ALL users' OnlyFood feed and
         * is SEPARATE from each user's personal mute list. Add a future spammer's hex
         * pubkey as a one-line entry, commented with its npub for readability.
         */
        val BLOCKED_PUBKEYS: Set<String> = setOf(
            // npub1m354es2t3hpx0wslegv7qrrpt4dmjyzh6feazktpuze0vnqw6jcqx5ps3x
            "dc695cc14b8dc267ba1fca19e00c615d5bb91057d273d15961e0b2f64c0ed4b0",
            // npub1qvv7xqpkeugn4qsa9lqjuypjttpx6gewk3gzz80mew07lgpw57sq2u5jtf
            "0319e30036cf113a821d2fc12e10325ac26d232eb450211dfbcb9fefa02ea7a0",
        )

        /** Count inline #hashtags in note content, mirroring the web's countContentHashtags. */
        private fun countContentHashtags(content: String): Int =
            CONTENT_HASHTAG_REGEX.findAll(content).count()

        /**
         * Mirror the web client's structural caps: hellthread p-tags and hashtag spam.
         * Pure and shared — the kind-1 path uses it via [decideKind1]; the poll/repost
         * branches of [addHashtagFeedEvent] call it directly.
         */
        fun isStructuralSpam(event: NostrEvent): Boolean {
            var pCount = 0
            var tCount = 0
            for (tag in event.tags) {
                if (tag.isEmpty()) continue
                when (tag[0]) {
                    "p" -> pCount++
                    "t" -> tCount++
                }
            }
            // Mirror the web's getHashtagCount = max(inline content #tags, t-tags), so
            // inline-hashtag spam is caught even when the author omits #t tags.
            val hashtagCount = maxOf(countContentHashtags(event.content), tCount)
            return pCount >= HELLTHREAD_P_LIMIT || hashtagCount > MAX_HASHTAGS
        }
    }
}
