package com.wisp.app.viewmodel

import com.wisp.app.nostr.NostrEvent

sealed interface FeedRow {
    data class Event(val event: NostrEvent) : FeedRow
    data class Gap(val gap: FeedGap) : FeedRow
}

data class FeedGap(
    val id: String,          // "gap-${newerTs}-${olderTs}"
    val newerSideTs: Long,   // created_at of note above the gap → becomes `until`
    val olderSideTs: Long,   // created_at of note below the gap → becomes `since`
    val state: GapState = GapState.IDLE
) {
    fun isFillable(): Boolean {
        val now = System.currentTimeMillis() / 1000
        return (now - olderSideTs) <= GAP_FILLABLE_WINDOW_SECONDS
    }
}

enum class GapState { IDLE, LOADING, EMPTY }

const val GAP_THRESHOLD_SECONDS = 1800L  // 30 minutes — minimum delta to show a gap marker
const val GAP_FILLABLE_WINDOW_SECONDS = 48L * 3600  // only attempt fills for gaps within 48h
