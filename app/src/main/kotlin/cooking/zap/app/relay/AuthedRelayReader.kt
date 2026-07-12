package cooking.zap.app.relay

import android.util.Log
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads events from a NIP-42 AUTH-required relay, surviving reconnects and the
 * resync race (concern 2.4a). **Reusable** — Nourish read now, Nourish compute
 * (2.4b) and future members-only reads.
 *
 * NIP-42 auth is per-connection, so the read can't trust a stale URL-keyed
 * "authenticated" flag (and [RelayPool] now clears it on disconnect, so
 * `isAuthenticated` reflects the *live* socket). The read sends the REQ and
 * watches the relay's frames:
 *  - `EOSE` → done; collect the events.
 *  - `CLOSED <sub> auth-required` → the live socket wasn't authed yet (cold
 *    start, or a resync re-sent the REQ on a fresh socket before re-auth) →
 *    wait for the auto-AUTH to land (RelayPool auto-signs pre-approved relays)
 *    and re-send, bounded to [maxAttempts].
 *  - timeout while [RelayPool.reconnectGeneration] changed → a reconnect raced
 *    the query → retry; otherwise return what we have (genuine slow/no-score).
 *
 * The relay is pinned against ephemeral eviction for the read. Returns the
 * collected events (empty on exhausted retries / can't-auth / timeout). The
 * caller gates on a signing key — READ_ONLY can't auth.
 *
 * Subscription ids use the `authread-` prefix, which is on the pool's dedup
 * bypass list — required so a second REQ for overlapping event ids (e.g.
 * Nourish Explore `#l` after an unfiltered load) is not silently emptied by
 * global seen-event dedup.
 */
class AuthedRelayReader(private val relayPool: RelayPool) {

    suspend fun read(
        url: String,
        filter: Filter,
        authTimeoutMs: Long = 8_000,
        queryTimeoutMs: Long = 6_000,
        maxAttempts: Int = 3,
    ): List<NostrEvent> {
        relayPool.autoApproveRelayAuth(url)
        relayPool.pinEphemeral(url)
        try {
            repeat(maxAttempts) {
                val gen = relayPool.reconnectGeneration
                // Process-wide unique subId so concurrent reader instances can't collide.
                val sub = "authread-${SEQ.incrementAndGet()}"
                val events = ArrayList<NostrEvent>()
                var outcome: Outcome? = null
                try {
                    coroutineScope {
                        val collector = launch {
                            relayPool.relayEvents.collect { if (it.subscriptionId == sub) events.add(it.event) }
                        }
                        // Wait for the socket before REQ so we don't race connect/AUTH
                        // and spend the query timeout on a queued-but-undrained frame.
                        relayPool.awaitRelayConnected(url, timeoutMs = authTimeoutMs)

                        val frame = ClientMessage.req(sub, filter)
                        Log.d(TAG, "REQ frame relay=$url sub=$sub frame=$frame")
                        // autoReconnect=true: the read must survive a transient drop of the
                        // ephemeral socket (the whole point) — without it the socket would
                        // never come back and retries would stall.
                        relayPool.sendToRelayOrEphemeral(url, frame, autoReconnect = true)
                        outcome = withTimeoutOrNull(queryTimeoutMs) {
                            merge(
                                relayPool.eoseSignals.filter { it == sub }.map { Outcome.EOSE },
                                relayPool.closedSignals
                                    .filter { it.first == sub && isAuthRequired(it.second) }
                                    .map { Outcome.AUTH_CLOSED },
                            ).first()
                        }
                        // events and EOSE arrive on separate SharedFlows — a brief grace so
                        // the last event(s) land before we stop collecting (no dropped results).
                        if (outcome == Outcome.EOSE) delay(STRAGGLER_MS)
                        collector.cancelAndJoin()
                    }
                } finally {
                    relayPool.closeOnAllRelays(sub)
                }

                when (outcome) {
                    Outcome.EOSE -> {
                        Log.d(TAG, "EOSE relay=$url sub events=${events.size}")
                        return events
                    }
                    Outcome.AUTH_CLOSED -> {
                        // Wait for the (async) auto-AUTH on the live socket, then retry.
                        withTimeoutOrNull(authTimeoutMs) {
                            while (!relayPool.isAuthenticated(url)) delay(POLL_MS)
                        }
                    }
                    null -> {
                        // Genuine timeout vs a reconnect that raced the query.
                        if (relayPool.reconnectGeneration == gen) {
                            Log.d(TAG, "timeout relay=$url events=${events.size}")
                            return events
                        }
                        // else: socket reconnected mid-query → retry on the fresh one.
                    }
                }
            }
            return emptyList() // exhausted retries (kept auth-closing / reconnecting)
        } finally {
            relayPool.unpinEphemeral(url)
        }
    }

    private fun isAuthRequired(message: String): Boolean =
        message.contains("auth-required", ignoreCase = true) ||
            message.contains("restricted", ignoreCase = true)

    private enum class Outcome { EOSE, AUTH_CLOSED }

    companion object {
        private const val TAG = "AuthedRelayReader"
        private const val POLL_MS = 100L
        private const val STRAGGLER_MS = 300L
        /** Process-wide subId sequence — unique across all reader instances. */
        private val SEQ = AtomicLong(0)
    }
}
