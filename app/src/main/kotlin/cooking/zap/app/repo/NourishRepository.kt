package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NourishParser
import cooking.zap.app.nostr.NourishScore
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads a recipe's Nourish health score (kind 30078) from the Pantry relay
 * (concern 2.4a). Pantry requires **NIP-42 AUTH** on every read, so this needs
 * a signing key (READ_ONLY can't auth → returns null, quiet absence).
 *
 * Flow: pre-approve Pantry for silent auto-AUTH → open the connection and wait
 * for the post-AUTH signal → REQ the service-published event for this recipe →
 * parse. Results are cached per recipe key. Any miss/timeout/no-key returns
 * null; the detail screen renders the section only when a score comes back.
 */
class NourishRepository(private val relayPool: RelayPool) {

    private val cache = ConcurrentHashMap<String, NourishScore>()
    private val subSeq = AtomicLong(0)

    /**
     * @param hasSigningKey false for READ_ONLY accounts — they can't NIP-42
     *   auth to Pantry, so we don't even try (returns null).
     */
    suspend fun fetchScore(
        recipeAuthor: String,
        recipeDTag: String,
        hasSigningKey: Boolean,
    ): NourishScore? {
        if (!hasSigningKey || recipeAuthor.isBlank() || recipeDTag.isBlank()) return null
        val key = "$recipeAuthor:$recipeDTag"
        cache[key]?.let { return it }

        val dTag = NourishParser.dTag(recipeAuthor, recipeDTag)
        val filter = Filter(
            kinds = listOf(NourishParser.KIND),
            authors = listOf(NourishParser.SERVICE_PUBKEY),
            dTags = listOf(dTag),
            limit = 1,
        )

        if (!ensureAuthenticated(filter)) return null

        val sub = "nourish-${subSeq.incrementAndGet()}"
        var event: NostrEvent? = null
        try {
            coroutineScope {
                val collector = launch {
                    relayPool.relayEvents.collect { relayEvent ->
                        if (relayEvent.subscriptionId != sub) return@collect
                        val e = relayEvent.event
                        if (e.kind == NourishParser.KIND) event = e
                    }
                }
                relayPool.sendToRelayOrEphemeral(PANTRY, ClientMessage.req(sub, filter))
                withTimeoutOrNull(QUERY_TIMEOUT_MS) { relayPool.eoseSignals.first { it == sub } }
                delay(STRAGGLER_MS)
                // cancelAndJoin (not cancel) so the final emission is processed and
                // there's a happens-before edge for the `event` read below.
                collector.cancelAndJoin()
            }
        } finally {
            relayPool.closeOnAllRelays(sub)
        }

        val parsed = event?.let { NourishParser.parse(it.content) } ?: return null
        cache[key] = parsed
        return parsed
    }

    /**
     * Pre-approve Pantry and make sure the connection is NIP-42 authenticated
     * before the real query (an un-authed REQ is CLOSED with "auth-required").
     * The warm-up REQ opens the connection and triggers the AUTH handshake,
     * which RelayPool auto-signs (Pantry is first-party).
     */
    private suspend fun ensureAuthenticated(filter: Filter): Boolean {
        relayPool.autoApproveRelayAuth(PANTRY)
        if (relayPool.isAuthenticated(PANTRY)) return true
        val warm = "nourish-auth-${subSeq.incrementAndGet()}"
        try {
            relayPool.sendToRelayOrEphemeral(PANTRY, ClientMessage.req(warm, filter))
            // Poll isAuthenticated() rather than awaiting authCompleted: that's a
            // non-replay SharedFlow, so an AUTH that lands between the send and a
            // `.first` subscription would be missed and burn the full timeout.
            // Polling exits within ~POLL_MS of auth completing (fast-path).
            withTimeoutOrNull(AUTH_TIMEOUT_MS) {
                while (!relayPool.isAuthenticated(PANTRY)) delay(POLL_MS)
            }
        } finally {
            relayPool.closeOnAllRelays(warm)
        }
        return relayPool.isAuthenticated(PANTRY)
    }

    fun clear() = cache.clear()

    companion object {
        private const val PANTRY = RelayConfig.MEMBERS_RELAY // wss://pantry.zap.cooking
        private const val AUTH_TIMEOUT_MS = 8_000L
        private const val QUERY_TIMEOUT_MS = 6_000L
        private const val STRAGGLER_MS = 400L
        private const val POLL_MS = 100L
    }
}
