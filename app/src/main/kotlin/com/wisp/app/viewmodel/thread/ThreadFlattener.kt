package com.wisp.app.viewmodel.thread

import com.wisp.app.nostr.NostrEvent

/**
 * Pure, side-effect-free flattener that turns a parent→children reply tree into a
 * [ThreadItem] list for the UI. Shared by [com.wisp.app.viewmodel.ThreadViewModel] and
 * [com.wisp.app.viewmodel.ArticleViewModel] so the two paths can't drift.
 *
 * Progressive disclosure (so long/deep threads stay legible — "who is replying to whom"):
 *  - **Depth cap**: replies deeper than [DEPTH_CAP] levels fold behind a [ThreadItem.CollapsedReplies]
 *    affordance that the user expands *inline* (scroll position preserved) rather than
 *    navigating away. Putting the anchor in [expandedIds] overrides the cap for that branch.
 *  - **Collapsible branches**: a node in [collapsedIds] emits only itself; its descendants fold
 *    into the "+N replies" affordance the UI renders from [ThreadItem.Post.descendantCount].
 *  - **High fan-out**: a parent with more than [maxSiblingsInline] direct replies shows the first
 *    few then a [ThreadItem.ShowMoreReplies] affordance (unless it is in [expandedFanOut]).
 *
 * The [scrollTargetId] path (the note to scroll to, plus all its ancestors) is exempt from
 * collapse and the depth cap, so a freshly-published reply is always reachable/visible.
 *
 * All inputs are assumed already cleaned (deletions/blocked/spam filtered) and chronologically
 * sorted within each sibling group — done by the caller's `rebuildTree`.
 */
object ThreadFlattener {
    /** Root is depth 0; replies at depths 1..DEPTH_CAP render as posts; deeper replies fold. */
    const val DEPTH_CAP = 3

    /** Default direct-replies shown inline before a "show more" affordance appears under a parent. */
    const val MAX_SIBLINGS_INLINE = 4

    fun flatten(
        rootId: String,
        rootEvent: NostrEvent?,
        parentToChildren: Map<String, List<NostrEvent>>,
        collapsedIds: Set<String> = emptySet(),
        expandedIds: Set<String> = emptySet(),
        expandedFanOut: Set<String> = emptySet(),
        scrollTargetId: String? = null,
        maxSiblingsInline: Int = MAX_SIBLINGS_INLINE,
        depthCap: Int = DEPTH_CAP
    ): List<ThreadItem> {
        val subtreeSizes = computeSubtreeSizes(parentToChildren)
        val pathToTarget = scrollTargetId?.let { ancestorsOf(it, parentToChildren) } ?: emptySet()
        val raw = mutableListOf<ThreadItem>()
        val visited = HashSet<String>()

        if (rootEvent != null) {
            visited.add(rootEvent.id)
            raw.add(
                ThreadItem.Post(
                    event = rootEvent,
                    depth = 0,
                    descendantCount = subtreeSizes[rootEvent.id] ?: 0,
                    collapsed = false
                )
            )
            walk(rootEvent, 0, parentToChildren, subtreeSizes, collapsedIds, expandedIds, expandedFanOut, pathToTarget, visited, raw, maxSiblingsInline, depthCap, false)
        } else {
            // Root not yet loaded — render the top-level replies we have, each as a depth-0 root.
            for (child in parentToChildren[rootId].orEmpty()) {
                if (child.id in visited) continue
                visited.add(child.id)
                val collapsed = child.id in collapsedIds && child.id !in pathToTarget
                raw.add(
                    ThreadItem.Post(
                        event = child,
                        depth = 0,
                        descendantCount = subtreeSizes[child.id] ?: 0,
                        collapsed = collapsed
                    )
                )
                walk(child, 0, parentToChildren, subtreeSizes, collapsedIds, expandedIds, expandedFanOut, pathToTarget, visited, raw, maxSiblingsInline, depthCap, false)
            }
        }
        return applyConnectorFlags(raw)
    }

    private fun walk(
        parentEvent: NostrEvent,
        parentDepth: Int,
        parentToChildren: Map<String, List<NostrEvent>>,
        subtreeSizes: Map<String, Int>,
        collapsedIds: Set<String>,
        expandedIds: Set<String>,
        expandedFanOut: Set<String>,
        pathToTarget: Set<String>,
        visited: HashSet<String>,
        result: MutableList<ThreadItem>,
        maxSiblingsInline: Int,
        depthCap: Int,
        insideExpanded: Boolean
    ) {
        val children = parentToChildren[parentEvent.id] ?: return
        val childDepth = parentDepth + 1
        val fanOut = children.size > maxSiblingsInline && parentEvent.id !in expandedFanOut
        val visibleChildren = if (fanOut) children.take(maxSiblingsInline) else children

        for (child in visibleChildren) {
            if (child.id in visited) continue
            visited.add(child.id)
            val collapsed = child.id in collapsedIds && child.id !in pathToTarget
            val hasChildren = !parentToChildren[child.id].isNullOrEmpty()
            result.add(
                ThreadItem.Post(
                    event = child,
                    depth = childDepth,
                    descendantCount = subtreeSizes[child.id] ?: 0,
                    collapsed = collapsed
                )
            )
            // Fold the subtree when capped (unless the user expanded this branch, or it's on the
            // scroll-to-reply path). Expanded branches descend normally and the cap reapplies
            // one level deeper, so expansion is progressive.
            val capHere = childDepth >= depthCap &&
                child.id !in pathToTarget &&
                child.id !in expandedIds &&
                !insideExpanded &&
                hasChildren
            when {
                collapsed || (capHere && !hasChildren) -> {
                    // Descendants hidden — the "+N replies" affordance renders on the Post row.
                }
                capHere -> {
                    result.add(
                        ThreadItem.CollapsedReplies(
                            anchor = child,
                            depth = childDepth + 1,
                            hiddenCount = subtreeSizes[child.id] ?: 0
                        )
                    )
                }
                else -> walk(child, childDepth, parentToChildren, subtreeSizes, collapsedIds, expandedIds, expandedFanOut, pathToTarget, visited, result, maxSiblingsInline, depthCap, insideExpanded || child.id in expandedIds)
            }
        }

        if (fanOut) {
            result.add(
                ThreadItem.ShowMoreReplies(
                    parent = parentEvent,
                    depth = childDepth,
                    hiddenCount = children.size - maxSiblingsInline
                )
            )
        }
    }

    /**
     * Marks each [ThreadItem.Post]'s rail as "starting in mid-air" when the row immediately above
     * (ignoring affordance rows) is not a Post at the same depth — i.e. the depth-guide spine is
     * broken, so the rail's top would otherwise float. The UI dashes the top of such rails to
     * signal they continue upward to the parent.
     */
    private fun applyConnectorFlags(items: List<ThreadItem>): List<ThreadItem> {
        var prevDepth = -1
        return items.map { item ->
            if (item is ThreadItem.Post) {
                val midAir = item.depth > 0 && prevDepth != item.depth
                prevDepth = item.depth
                if (midAir == item.connectorStartsMidAir) item else item.copy(connectorStartsMidAir = midAir)
            } else item
        }
    }

    /** Total descendants of each node (excluding the node itself), cycle-guarded. */
    private fun computeSubtreeSizes(parentToChildren: Map<String, List<NostrEvent>>): Map<String, Int> {
        val sizes = HashMap<String, Int>()
        val visiting = HashSet<String>()

        fun sizeOf(id: String): Int {
            sizes[id]?.let { return it }
            visiting.add(id)
            var total = 0
            for (child in parentToChildren[id].orEmpty()) {
                if (child.id in visiting) continue // cycle guard
                total += 1 + sizeOf(child.id)
            }
            visiting.remove(id)
            sizes[id] = total
            return total
        }
        for (id in parentToChildren.keys) sizeOf(id)
        return sizes
    }

    /** [targetId] plus all of its ancestors (up to a missing parent or a cycle). Used to keep
     *  the scroll-to-reply path expanded through collapse/depth-cap. */
    private fun ancestorsOf(targetId: String, parentToChildren: Map<String, List<NostrEvent>>): Set<String> {
        val childToParent = HashMap<String, String>()
        for ((parentId, children) in parentToChildren) {
            for (child in children) {
                if (child.id !in childToParent) childToParent[child.id] = parentId
            }
        }
        val result = LinkedHashSet<String>()
        var cur: String? = targetId
        while (cur != null && result.add(cur)) {
            cur = childToParent[cur]
        }
        return result
    }
}
