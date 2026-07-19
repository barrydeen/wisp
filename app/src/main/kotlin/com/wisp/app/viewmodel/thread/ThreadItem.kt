package com.wisp.app.viewmodel.thread

import com.wisp.app.nostr.NostrEvent

/**
 * One row in a flattened reply thread. Replaces the old `Pair<NostrEvent, Int>` so the list
 * can also carry synthetic progressive-disclosure rows (a folded subtree that expands inline)
 * with stable LazyColumn keys.
 *
 * Each variant carries its own [depth] so the UI's indent + connector math is uniform, plus
 * a stable [key] and a distinct [contentType] (so Compose pools synthetic rows separately
 * from full PostCard rows).
 */
sealed interface ThreadItem {
    val depth: Int
    val key: Any
    val contentType: String

    /** A real note row.
     *  - [descendantCount]: full subtree size under this note (used for "+N replies" affordances).
     *  - [connectorStartsMidAir]: true when this note's depth-guide rail has no same-depth Post
     *    immediately above it in the rendered list — i.e. the rail's top would "start in mid-air"
     *    rather than continuing a visible spine. The UI dashes the top of the rail in that case. */
    data class Post(
        val event: NostrEvent,
        override val depth: Int,
        val descendantCount: Int,
        val collapsed: Boolean,
        val connectorStartsMidAir: Boolean = false
    ) : ThreadItem {
        override val key: Any get() = event.id
        override val contentType: String get() = "post"
    }

    /** A folded subtree under [anchor] that the user can expand inline, keeping scroll
     *  position anchored on the note above. [hiddenCount] is the subtree size. */
    data class CollapsedReplies(
        val anchor: NostrEvent,
        override val depth: Int,
        val hiddenCount: Int
    ) : ThreadItem {
        override val key: Any get() = "collapsed_${anchor.id}"
        override val contentType: String get() = "collapsed"
    }

    /** High-fan-out cap: [hiddenCount] sibling replies under [parent] are folded away. Tapping
     *  expands them inline. */
    data class ShowMoreReplies(
        val parent: NostrEvent,
        override val depth: Int,
        val hiddenCount: Int
    ) : ThreadItem {
        override val key: Any get() = "more_${parent.id}"
        override val contentType: String get() = "more"
    }
}
