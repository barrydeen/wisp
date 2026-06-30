package cooking.zap.app.repo

import android.content.Context
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/**
 * Memories — "On this day" service. Mirrors the web client's
 * `src/lib/memories.ts`: finds the logged-in user's OWN kind-1 (text note)
 * posts from this same calendar day 1, 2, and 3 years ago, fetched from relays,
 * with a per-day cache so relays are queried at most once per day per user.
 *
 * Read/derive only — no new event kind, no publishing. Pure helpers
 * ([getMemoryWindows], [isReplyNote], [shouldCacheMemories]) are top-level so
 * they're unit-testable on the JVM without Android.
 */

enum class MemoryResolved { EOSE, TIMEOUT }

data class MemoryWindow(
    val yearsAgo: Int,
    /** Unix seconds, 00:00:00 local time on the target day. */
    val since: Long,
    /** Unix seconds, 23:59:59 local time on the target day. */
    val until: Long,
)

data class MemoryGroup(
    val yearsAgo: Int,
    /** Start of the target day (unix seconds, local midnight). */
    val dateSec: Long,
    val events: List<NostrEvent>,
    /**
     * How the window's fetch resolved. TIMEOUT means relays never sent EOSE, so
     * an empty result may just mean "relays didn't answer" — used by
     * [shouldCacheMemories] to decide whether an empty day is cacheable.
     */
    val resolvedVia: MemoryResolved,
)

// ─── DATE WINDOWS (pure) ──────────────────────────────────────────────────

internal fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

/**
 * Local-time day windows for 1, 2, and 3 years before [now]. Feb 29 falls back
 * to Feb 28 in non-leap target years (mirrors the web's getMemoryWindows). The
 * windows are computed in [now]'s time zone, so "this day" means the user's day.
 */
internal fun getMemoryWindows(now: Calendar): List<MemoryWindow> {
    val zone = now.timeZone
    val month = now.get(Calendar.MONTH)
    val dayOfMonth = now.get(Calendar.DAY_OF_MONTH)
    val nowYear = now.get(Calendar.YEAR)

    return intArrayOf(1, 2, 3).map { yearsAgo ->
        val year = nowYear - yearsAgo
        var day = dayOfMonth
        if (month == Calendar.FEBRUARY && day == 29 && !isLeapYear(year)) day = 28
        val start = Calendar.getInstance(zone).apply { clear(); set(year, month, day, 0, 0, 0) }
        val end = Calendar.getInstance(zone).apply { clear(); set(year, month, day, 23, 59, 59) }
        MemoryWindow(
            yearsAgo = yearsAgo,
            since = start.timeInMillis / 1000,
            until = end.timeInMillis / 1000,
        )
    }
}

// ─── REPLY PREDICATE (pure, NIP-10 aware) ─────────────────────────────────

/**
 * True if the event is a reply per NIP-10: it has an `e` tag marked
 * `root`/`reply`, an unmarked `e` tag (legacy positional reply), or an `e` tag
 * with an unknown marker. Mention-only `e` tags and `q`-tag quotes are NOT
 * replies. Marker comparison is case-insensitive. Ports the web's `isReplyNote`
 * verbatim (the app's [cooking.zap.app.nostr.Nip10.isReply] is case-sensitive on
 * the `mention` marker and counts empty-id `e` tags, so we don't reuse it here).
 */
internal fun isReplyNote(event: NostrEvent): Boolean {
    val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" && it[1].isNotEmpty() }
    if (eTags.isEmpty()) return false
    return eTags.any { tag -> tag.getOrNull(3)?.lowercase() != "mention" }
}

// ─── CACHE GATING (pure) ──────────────────────────────────────────────────

/**
 * A result is durably cacheable only when COMPLETE: EVERY window resolved via
 * EOSE — i.e. at least one queried relay sent EOSE for that window, so the query
 * reached a relay that finished its stored-events scan. A window that resolved via
 * TIMEOUT saw NO EOSE at all, so there's no evidence any relay finished — its
 * emptiness may be a transient miss (especially the deep 3-year window, whose
 * notes live on slower/fewer archive relays), so a PARTIAL result is not cached;
 * it re-fetches next open and self-heals.
 *
 * An EOSE'd window with genuinely zero notes IS cacheable ("nothing that day"); a
 * TIMEOUT window is not — that's the distinction. Stricter than the web's
 * any-EOSE rule on purpose: caching a partial (1-year had notes, 3-year timed
 * out) froze the 3-year window empty even though those notes exist.
 */
internal fun shouldCacheMemories(groups: List<MemoryGroup>): Boolean =
    groups.isNotEmpty() && groups.all { it.resolvedVia == MemoryResolved.EOSE }

/** Local-time YYYY-MM-DD key for [now]. */
internal fun localDateKey(now: Calendar): String {
    val y = now.get(Calendar.YEAR)
    val m = (now.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
    val d = now.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
    return "$y-$m-$d"
}

@Serializable
private data class StoredMemoryGroup(
    val yearsAgo: Int,
    val dateSec: Long,
    val events: List<NostrEvent>,
    val resolvedVia: MemoryResolved = MemoryResolved.EOSE,
)

/**
 * Memories service. Holds the read-only relay/cache plumbing; constructed once
 * (e.g. in FeedViewModel) and shared by the teaser card and the full screen.
 */
class MemoriesRepository(
    private val context: Context,
    private val relayPool: RelayPool,
    private val eventRepo: EventRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val subSeq = AtomicInteger(0)

    // ─── relay fetch ──────────────────────────────────────────────────────

    /**
     * Memory relay union: the default READ relays plus archive-friendly relays
     * that keep old notes around (mirrors the web's standardRelays ∪ ARCHIVE).
     */
    private fun memoryRelays(): List<String> {
        val urls = LinkedHashSet<String>()
        RelayConfig.DEFAULTS.filter { it.read }.forEach { urls.add(it.url) }
        ARCHIVE_RELAYS.forEach { urls.add(it) }
        return urls.toList()
    }

    /**
     * Fetch one window's events: open a sub on the memory relay union, collect
     * the author's kind-1 notes in [window], await EOSE (count-based, with a
     * timeout), then close. Never throws — resolves to an empty TIMEOUT group on
     * trouble. Excludes replies and empty-content notes; sorts ascending by
     * created_at (oldest first), matching the web.
     */
    private suspend fun fetchWindow(pubkey: String, window: MemoryWindow, eoseTimeoutMs: Long): MemoryGroup = coroutineScope {
        val subId = "memories-${window.yearsAgo}-${subSeq.incrementAndGet()}"
        val collected = LinkedHashMap<String, NostrEvent>()

        // relayEvents has replay=0 — gate the REQ on the event collector being live
        // so events aren't dropped before subscription (mirrors OnlyFood's gate).
        val collectorReady = CompletableDeferred<Unit>()
        val collector = launch {
            relayPool.relayEvents
                .onSubscription { collectorReady.complete(Unit) }
                .collect { relayEvent ->
                    if (relayEvent.subscriptionId != subId) return@collect
                    val ev = relayEvent.event
                    if (ev.kind != 1 || ev.pubkey != pubkey) return@collect
                    if (ev.id !in collected) collected[ev.id] = ev
                }
        }

        val resolvedVia = try {
            collectorReady.await()
            // CONNECT FIRST. sendToRelayOrEphemeral returns true on SEND, not on
            // CONNECT — sending to a still-handshaking relay makes the EOSE wait time
            // out before slow archive relays (damus/nostr.wine/primal — where old notes
            // live) deliver. Gate the REQ on a live socket (connect budget is separate
            // from the EOSE budget); connect in parallel and send only to those.
            val connected = memoryRelays()
                .map { url -> async { url to relayPool.awaitRelayConnected(url, CONNECT_TIMEOUT_MS) } }
                .awaitAll()
                .filter { it.second }
                .map { it.first }

            if (connected.isEmpty()) {
                MemoryResolved.TIMEOUT
            } else {
                // EOSE awaiter, subscribed BEFORE the REQ goes out — eoseSignals is
                // replay=0, so a not-yet-subscribed awaiter would miss an EOSE emitted
                // right after send and read a false TIMEOUT (mirrors OnlyFood's gate).
                // Counts up to one EOSE per connected relay, capped by the EOSE budget.
                val eoseReady = CompletableDeferred<Unit>()
                val eoseAwaiter = async {
                    var count = 0
                    withTimeoutOrNull(eoseTimeoutMs) {
                        relayPool.eoseSignals
                            .onSubscription { eoseReady.complete(Unit) }
                            .filter { it == subId }
                            .take(connected.size)
                            .collect { count++ }
                    }
                    count
                }
                eoseReady.await()

                val filter = Filter(
                    kinds = listOf(1),
                    authors = listOf(pubkey),
                    since = window.since,
                    until = window.until,
                    limit = WINDOW_LIMIT,
                )
                val req = ClientMessage.req(subId, filter)
                var sent = 0
                for (url in connected) {
                    if (relayPool.sendToRelayOrEphemeral(url, req)) sent++
                }
                if (sent == 0) {
                    eoseAwaiter.cancel()
                    MemoryResolved.TIMEOUT
                } else {
                    val eoseCount = eoseAwaiter.await()
                    if (eoseCount > 0) {
                        // Stragglers drip in after EOSE — keep collecting for a grace
                        // window before teardown. Only after a REAL EOSE: an all-timeout
                        // window has no evidence anyone will deliver, so don't add the 4s.
                        delay(EOSE_GRACE_MS)
                        MemoryResolved.EOSE
                    } else {
                        MemoryResolved.TIMEOUT
                    }
                }
            }
        } catch (_: Exception) {
            MemoryResolved.TIMEOUT
        } finally {
            collector.cancel()
            relayPool.closeOnAllRelays(subId)
        }

        val events = collected.values
            .filter { !isReplyNote(it) && it.content.trim().isNotEmpty() }
            .sortedBy { it.created_at }
        // Cache into the shared event store so PostCard can resolve profiles.
        for (ev in events) {
            eventRepo.cacheEvent(ev)
            eventRepo.requestProfileIfMissing(ev.pubkey)
        }
        MemoryGroup(window.yearsAgo, window.since, events, resolvedVia)
    }

    /** Fetch all three windows in parallel. Empty groups are normal. */
    suspend fun fetchMemories(pubkey: String, now: Calendar): List<MemoryGroup> = coroutineScope {
        val relays = memoryRelays()
        // Pin ONCE for the whole multi-window fetch. RelayPool pinning is a plain Set
        // (not ref-counted), so pinning per-window would let the first window to finish
        // unpin the archive relays while the other two are still mid-fetch — re-opening
        // the LRU-eviction window this fix closes. Pin here; unpin after all settle.
        relays.forEach { relayPool.pinEphemeral(it) }
        try {
            getMemoryWindows(now).map { window ->
                // Deeper windows get more EOSE budget: their notes live on slower/fewer
                // archive relays (nostr.wine/primal) that take longer to dig out cold
                // events, so the 3-year window isn't torn down before it can deliver.
                val eoseTimeoutMs = EOSE_TIMEOUT_MS + (window.yearsAgo - 1).toLong() * EOSE_TIMEOUT_PER_YEAR_MS
                async { fetchWindow(pubkey, window, eoseTimeoutMs) }
            }.awaitAll()
        } finally {
            relays.forEach { relayPool.unpinEphemeral(it) }
        }
    }

    // ─── per-day cache (SharedPreferences, account-scoped) ────────────────

    private fun prefs(pubkey: String) =
        context.getSharedPreferences("wisp_memories_$pubkey", Context.MODE_PRIVATE)

    private fun groupsKey(dateKey: String) = "groups_${CACHE_VERSION}_$dateKey"

    private fun readCache(pubkey: String, now: Calendar): List<MemoryGroup>? {
        val raw = prefs(pubkey).getString(groupsKey(localDateKey(now)), null) ?: return null
        val groups = try {
            json.decodeFromString<List<StoredMemoryGroup>>(raw).map {
                MemoryGroup(it.yearsAgo, it.dateSec, it.events, it.resolvedVia)
            }
        } catch (_: Exception) {
            return null
        }
        // Treat a cached PARTIAL (any TIMEOUT/incomplete window) as a MISS so it
        // re-fetches and self-heals — only all-EOSE results are written by the
        // current code, but this also abandons any pre-fix poisoned partial.
        if (groups.any { it.resolvedVia != MemoryResolved.EOSE }) return null
        return groups
    }

    /** Write today's groups and prune any prior-day group entries. */
    private fun writeCache(pubkey: String, now: Calendar, groups: List<MemoryGroup>) {
        val key = groupsKey(localDateKey(now))
        val stored = groups.map { StoredMemoryGroup(it.yearsAgo, it.dateSec, it.events, it.resolvedVia) }
        val editor = prefs(pubkey).edit()
        // Prune stale prior-day group keys so the file doesn't grow unbounded.
        for (k in prefs(pubkey).all.keys) {
            if (k.startsWith("groups_") && k != key) editor.remove(k)
        }
        try {
            editor.putString(key, json.encodeToString(stored)).apply()
        } catch (_: Exception) {
            // Best-effort only.
        }
    }

    /**
     * Today's cached memories if present AND complete, otherwise fetch from relays
     * and cache only a COMPLETE result (every window EOSE'd — see
     * [shouldCacheMemories]). A cached partial reads as a miss ([readCache]) so a
     * transient single-window timeout self-heals on the next open instead of
     * freezing that window empty for the rest of the day. The fetched groups are
     * returned regardless of caching, so the user still sees whatever arrived.
     */
    suspend fun getMemoriesCached(pubkey: String, now: Calendar = Calendar.getInstance()): List<MemoryGroup> {
        readCache(pubkey, now)?.let { cached ->
            // The live fetch primes EventRepository per event; the cache-read path must
            // do the same so cache-first PostCards resolve profiles (and other
            // eventRepo-backed lookups) instead of rendering with missing author data.
            primeEventCache(cached)
            return cached
        }
        val groups = fetchMemories(pubkey, now)
        if (shouldCacheMemories(groups)) writeCache(pubkey, now, groups)
        return groups
    }

    /** Seed the shared event store from cached memory events (cache-first parity). */
    private fun primeEventCache(groups: List<MemoryGroup>) {
        for (group in groups) {
            for (ev in group.events) {
                eventRepo.cacheEvent(ev)
                eventRepo.requestProfileIfMissing(ev.pubkey)
            }
        }
    }

    /**
     * Overwrite today's cache entry with [groups], but only when authoritative
     * per [shouldCacheMemories]. Returns whether the write happened.
     */
    fun overwriteMemoriesCache(pubkey: String, groups: List<MemoryGroup>, now: Calendar = Calendar.getInstance()): Boolean {
        if (!shouldCacheMemories(groups)) return false
        writeCache(pubkey, now, groups)
        return true
    }

    /**
     * Cache-bypassing refresh: always hits relays, then overwrites today's cache
     * only when the result is authoritative. `refreshed == false` means the
     * caller should keep showing its current (cached) data.
     */
    suspend fun refreshMemories(pubkey: String, now: Calendar = Calendar.getInstance()): Pair<List<MemoryGroup>, Boolean> {
        val groups = fetchMemories(pubkey, now)
        return groups to overwriteMemoriesCache(pubkey, groups, now)
    }

    // ─── per-day dismissal (the teaser card) ──────────────────────────────

    private fun dismissKey(dateKey: String) = "dismissed_$dateKey"

    fun dismissMemoriesCard(pubkey: String, now: Calendar = Calendar.getInstance()) {
        val key = dismissKey(localDateKey(now))
        val editor = prefs(pubkey).edit()
        for (k in prefs(pubkey).all.keys) {
            if (k.startsWith("dismissed_") && k != key) editor.remove(k)
        }
        editor.putBoolean(key, true).apply()
    }

    fun isMemoriesCardDismissed(pubkey: String, now: Calendar = Calendar.getInstance()): Boolean =
        prefs(pubkey).getBoolean(dismissKey(localDateKey(now)), false)

    /** Clear today's dismissal (the card's Undo affordance). */
    fun undismissMemoriesCard(pubkey: String, now: Calendar = Calendar.getInstance()) {
        prefs(pubkey).edit().remove(dismissKey(localDateKey(now))).apply()
    }

    companion object {
        /** Archive-friendly relays that keep old notes (mirrors the web's ARCHIVE_RELAYS). */
        private val ARCHIVE_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nostr.wine",
            "wss://relay.primal.net",
        )
        private const val WINDOW_LIMIT = 50
        /** Budget to bring an ephemeral archive relay's socket up before the REQ. */
        private const val CONNECT_TIMEOUT_MS = 8_000L
        /** Base EOSE budget for the shallowest (1-year) window, measured AFTER connect. */
        private const val EOSE_TIMEOUT_MS = 10_000L
        /** Extra EOSE budget per year of depth — the 3-year window gets +2×this. */
        private const val EOSE_TIMEOUT_PER_YEAR_MS = 2_000L
        /** Straggler window after a real EOSE before teardown (archive relays drip late). */
        private const val EOSE_GRACE_MS = 4_000L
        /** Cache schema version — bump to abandon old (e.g. pre-fix poisoned) caches. */
        private const val CACHE_VERSION = "v2"
    }
}
