package cooking.zap.app.viewmodel

import cooking.zap.app.nostr.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * PR 1 of 2 — proves the extracted [ingestEvent] path is correct UNDER single-thread
 * confinement before PR 2 actually moves the live collector off Main onto a serial
 * background dispatcher.
 *
 * [ingestEvent] is the suspension-free per-event body the live collector now calls.
 * Here we hammer it from many concurrent coroutines confined to a real
 * `Dispatchers.Default.limitedParallelism(1)` — the exact confinement PR 2 will use —
 * and assert the load-bearing guarantees:
 *  - dedup is exact (no dupes, no drops) even when ids repeat and arrival order is
 *    shuffled relative to `created_at`,
 *  - the [accept] gate keeps rejected events out of the source of truth,
 *  - the emitted display list (via [mergeFeedOrder]) has no duplicate ids, is
 *    strictly descending by `created_at`, and is stable across repeated flushes.
 *
 * The second test walks the real lifecycle — seed → live burst → EOSE freeze →
 * straggler — and asserts a late straggler appends without reordering rendered rows.
 *
 * The collector itself still runs on Main.immediate in PR 1; this test deliberately
 * exercises the background confinement so the move in PR 2 lands on a green guardrail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlyFoodIngestTest {

    private fun ev(id: String, createdAt: Long, pubkey: String = "pk") = NostrEvent(
        id = id,
        pubkey = pubkey,
        created_at = createdAt,
        kind = 1,
        tags = emptyList(),
        content = "",
        sig = "",
    )

    // ---- concurrency hammer: no dupes, no drops, stable descending order ------

    @Test
    fun hammer_concurrentIngest_noDupesNoDrops_stableDescendingOrder() = runBlocking {
        // The serial confinement PR 2 will move the collector to. With plain
        // (non-thread-safe) collections, width-1 is the ONLY thing that makes the
        // dedup-then-insert atomic — this test fails loudly if that ever regresses.
        @OptIn(ExperimentalCoroutinesApi::class)
        val confinement = Dispatchers.Default.limitedParallelism(1)

        // 600 accepted events with UNIQUE created_at (so "strictly descending" is
        // well-defined), plus 60 events from a blocked author the accept gate drops.
        val accepted = (0 until 600).map { ev(id = "e$it", createdAt = 1_000L + it) }
        val blocked = (0 until 60).map { ev(id = "b$it", createdAt = 5_000L + it, pubkey = "blocked") }

        // Each event submitted 3x (duplicate ids) and arrival order shuffled, so the
        // ingest sees ids out of created_at order and must dedup across submissions.
        val submissions = (accepted + accepted + accepted + blocked + blocked + blocked)
            .shuffled(Random(42))

        val seen = LinkedHashMap<String, NostrEvent>()
        val flushes = AtomicInteger(0)
        val acceptCalls = AtomicInteger(0)

        val jobs = submissions.map { event ->
            launch(confinement) {
                ingestEvent(
                    event = event,
                    seen = seen,
                    accept = {
                        acceptCalls.incrementAndGet()
                        it.pubkey != "blocked"
                    },
                    onAccepted = { /* cache/profile side-effects are Android-bound; no-op here */ },
                    signalFlush = { flushes.incrementAndGet() },
                )
            }
        }
        jobs.joinAll()

        // (a) exact dedup: every unique accepted id present once, no drops, no dupes,
        // and not a single blocked event leaked into the source of truth.
        assertEquals("seen must hold exactly the unique accepted events", accepted.size, seen.size)
        assertTrue("no blocked author in seen", seen.values.none { it.pubkey == "blocked" })
        // The flush is signaled exactly once per NEW insert — never per submission.
        assertEquals("one flush signal per newly inserted event", accepted.size, flushes.get())
        // Dedup is by `seen` membership, which only accepted events ever enter. So an
        // accepted id is evaluated once (its 2 dupes short-circuit on `id in seen`),
        // but a BLOCKED id is re-evaluated on every submission — rejected events are
        // never memoized. This mirrors the live collector exactly.
        val blockedSubmissions = blocked.size * 3
        assertEquals(
            "accept runs once per accepted id but once per blocked submission",
            accepted.size + blockedSubmissions, acceptCalls.get(),
        )

        // (b) emitted display list has no duplicate ids.
        val ordered = arrayListOf<NostrEvent>()
        val placed = hashSetOf<String>()
        val notes = mergeFeedOrder(ordered, placed, seen.values, settled = false)
        assertEquals("emitted notes must contain no duplicate ids", notes.size, notes.map { it.id }.toSet().size)
        assertEquals(accepted.size, notes.size)

        // (c) strictly descending by created_at...
        val times = notes.map { it.created_at }
        assertEquals("notes must be strictly descending by created_at", times.sortedDescending(), times)
        assertEquals("strictly descending implies all-distinct here", times.size, times.toSet().size)

        // ...and stable across repeated flushes: a second from-scratch rebuild matches,
        // and once settled, re-flushes append nothing and never reorder.
        val rebuiltAgain = mergeFeedOrder(arrayListOf(), hashSetOf(), seen.values, settled = false)
        assertEquals("rebuild is deterministic", notes.map { it.id }, rebuiltAgain.map { it.id })
        val settled1 = mergeFeedOrder(ordered, placed, seen.values, settled = true)
        val settled2 = mergeFeedOrder(ordered, placed, seen.values, settled = true)
        assertEquals("settled re-flush keeps order", notes.map { it.id }, settled1.map { it.id })
        assertEquals("settled re-flush is idempotent", settled1.map { it.id }, settled2.map { it.id })
    }

    // ---- lifecycle: seed → burst → EOSE → straggler appends, no reorder -------

    @Test
    fun lifecycle_seedThenBurstThenEose_stragglerAppendsWithoutReordering() = runBlocking {
        @OptIn(ExperimentalCoroutinesApi::class)
        val confinement = Dispatchers.Default.limitedParallelism(1)

        val seen = LinkedHashMap<String, NostrEvent>()
        val ordered = arrayListOf<NostrEvent>()
        val placed = hashSetOf<String>()

        // Ingest through the same extracted path, confined to the serial dispatcher.
        suspend fun ingest(e: NostrEvent) = withContext(confinement) {
            ingestEvent(e, seen, accept = { true }, onAccepted = {}, signalFlush = {})
        }

        // Seed from cache (unsettled), then a live burst arriving out of order.
        listOf(ev("s1", 100), ev("s2", 200)).forEach { ingest(it) }
        listOf(ev("b1", 300), ev("b2", 150)).forEach { ingest(it) }

        // EOSE freeze (mirrors submit(): rebuild from `seen`, THEN flip settled).
        val atEose = mergeFeedOrder(ordered, placed, seen.values, settled = false)
        assertEquals("post-EOSE order is full desc sort", listOf("b1", "s2", "b2", "s1"), atEose.map { it.id })

        // A late straggler NEWER than everything on screen.
        ingest(ev("straggler", 999))
        val afterStraggler = mergeFeedOrder(ordered, placed, seen.values, settled = true)

        // Rendered rows keep their positions; the newer straggler appends at the TAIL
        // instead of jumping to the top — no reshuffle of what's already on screen.
        assertEquals(
            listOf("b1", "s2", "b2", "s1", "straggler"),
            afterStraggler.map { it.id },
        )
        // And it really did land in the source of truth.
        assertTrue("straggler ingested", "straggler" in seen)
    }
}
