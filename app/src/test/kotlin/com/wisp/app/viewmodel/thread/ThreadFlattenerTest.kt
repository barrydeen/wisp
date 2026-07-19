package com.wisp.app.viewmodel.thread

import com.wisp.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [ThreadFlattener] — the progressive-disclosure flattener that is now the
 * heart of the thread view (depth cap, inline expand, scroll-to-reply path exemption, cycle
 * safety, and the "starts in mid-air" connector flag). The flattener consumes an already-parsed
 * parent→children map, so these tests build synthetic trees directly — no NIP-10 wiring needed.
 */
class ThreadFlattenerTest {

    private fun ev(id: String) = NostrEvent(id, "pk", 0L, 1, emptyList(), "", "")

    private fun tree(vararg edges: Pair<String, List<String>>): Map<String, List<NostrEvent>> {
        val m = HashMap<String, List<NostrEvent>>()
        for ((parent, kids) in edges) m[parent] = kids.map(::ev)
        return m
    }

    private fun posts(items: List<ThreadItem>) = items.filterIsInstance<ThreadItem.Post>()
    private fun collapsed(items: List<ThreadItem>) = items.filterIsInstance<ThreadItem.CollapsedReplies>()

    private val deepChain = tree(
        "R" to listOf("A"), "A" to listOf("B"), "B" to listOf("C"),
        "C" to listOf("D"), "D" to listOf("E")
    )

    @Test
    fun depthCap_foldsDeepSubtree_behindCollapsedAffordance() {
        val out = ThreadFlattener.flatten("R", ev("R"), deepChain)
        val p = posts(out)
        assertEquals(listOf("R", "A", "B", "C"), p.map { it.event.id })
        assertEquals(listOf(0, 1, 2, 3), p.map { it.depth })
        val c = collapsed(out)
        assertEquals(1, c.size)
        assertEquals("C", c[0].anchor.id)
        assertEquals(2, c[0].hiddenCount) // D + E folded
        assertNull(p.firstOrNull { it.event.id == "D" || it.event.id == "E" })
    }

    @Test
    fun expand_revealsEntireSubtree_atOnce() {
        // Tapping "N more replies" must reveal the whole hidden subtree at once, not one level
        // at a time. C's subtree is D -> E (hiddenCount 2); expanding C shows both D and E.
        val out = ThreadFlattener.flatten("R", ev("R"), deepChain, expandedIds = setOf("C"))
        val p = posts(out)
        assertTrue(p.any { it.event.id == "D" })
        assertTrue(p.any { it.event.id == "E" })
        assertTrue(collapsed(out).isEmpty()) // no re-cap inside the expanded subtree
    }

    @Test
    fun scrollTargetPath_staysVisibleThroughCap() {
        // E is depth 5 — normally capped — but it (and its ancestors) are exempt so a freshly
        // published reply is always reachable/visible.
        val out = ThreadFlattener.flatten("R", ev("R"), deepChain, scrollTargetId = "E")
        val p = posts(out)
        assertTrue(p.any { it.event.id == "E" })
        assertEquals(5, p.last().depth)
        assertTrue(collapsed(out).isEmpty())
    }

    @Test
    fun cycle_doesNotInfiniteLoop() {
        val out = ThreadFlattener.flatten("A", ev("A"), tree("A" to listOf("B"), "B" to listOf("A")))
        assertEquals(listOf("A", "B"), posts(out).map { it.event.id })
    }

    @Test
    fun collapsedBranch_hidesSubtree_andFlagsThePost() {
        val out = ThreadFlattener.flatten(
            "R", ev("R"),
            tree("R" to listOf("A", "B"), "A" to listOf("A1")),
            collapsedIds = setOf("A")
        )
        val p = posts(out)
        assertTrue(p.any { it.event.id == "A" && it.collapsed })
        assertFalse(p.any { it.event.id == "A1" })
    }

    @Test
    fun connectorFlag_dashesOrphanedTops_butNotContinuedSiblingSpines() {
        val out = ThreadFlattener.flatten("R", ev("R"), tree("R" to listOf("A", "B")))
        val p = posts(out)
        val a = p.first { it.event.id == "A" }
        val b = p.first { it.event.id == "B" }
        assertTrue(a.connectorStartsMidAir)  // first reply: spine broken above
        assertFalse(b.connectorStartsMidAir) // sibling immediately below: spine continues
    }

    @Test
    fun rootNull_rendersTopLevelRepliesAsDepthZeroRoots() {
        val out = ThreadFlattener.flatten(
            "R", null,
            tree("R" to listOf("A", "B"), "A" to listOf("A1"))
        )
        val p = posts(out)
        assertEquals("A", p[0].event.id)
        assertEquals(0, p[0].depth)
        assertTrue(p.any { it.event.id == "A1" && it.depth == 1 })
    }
}
