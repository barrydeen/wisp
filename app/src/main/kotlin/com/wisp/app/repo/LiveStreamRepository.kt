package com.wisp.app.repo

import com.wisp.app.nostr.Nip53
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

data class LiveChatMessage(
    val id: String,
    val senderPubkey: String,
    val content: String,
    val createdAt: Long,
    val replyToId: String? = null,
    val reactions: Map<String, List<String>> = emptyMap(),
    val emojiTags: Map<String, String> = emptyMap(),
    val isZapAnnouncement: Boolean = false,
    val zapAmountSats: Long = 0
)

data class LiveStream(
    val activity: Nip53.LiveActivity,
    val aTagValue: String,
    val chatters: Set<String> = emptySet(),
    val lastChatAt: Long = 0L
)

class LiveStreamRepository {
    companion object {
        /**
         * Chat + zap-announcement rows kept per stream. Long-running radio
         * streams carry months of kind-1311; history pages in on demand and
         * stops here.
         */
        const val MAX_CHAT_MESSAGES = 1000
    }

    private val streams = ConcurrentHashMap<String, LiveStream>()
    private val seenChatIds = ConcurrentHashMap.newKeySet<String>()
    private var currentStreamKey: String? = null

    private val _liveStreams = MutableStateFlow<Map<String, LiveStream>>(emptyMap())
    val liveStreams: StateFlow<Map<String, LiveStream>> = _liveStreams

    private val _currentChatMessages = MutableStateFlow<List<LiveChatMessage>>(emptyList())
    val currentChatMessages: StateFlow<List<LiveChatMessage>> = _currentChatMessages

    private val _streamZapTotal = MutableStateFlow(0L)
    val streamZapTotal: StateFlow<Long> = _streamZapTotal

    /**
     * The current stream's zap total came from a bounded relay scan that may
     * have missed older receipts — rendered with a trailing "+", sidecar-style.
     */
    private val _streamZapTotalUncertain = MutableStateFlow(false)
    val streamZapTotalUncertain: StateFlow<Boolean> = _streamZapTotalUncertain

    private val chatMessages = ConcurrentHashMap<String, MutableList<LiveChatMessage>>()
    private val streamZapTotals = ConcurrentHashMap<String, Long>()
    private val zapTotalsUncertain = ConcurrentHashMap.newKeySet<String>()
    private val pendingFollowChatters = ConcurrentHashMap<String, MutableSet<String>>()
    private val pendingFollowChatAt = ConcurrentHashMap<String, Long>()

    fun addActivity(event: NostrEvent) {
        val activity = Nip53.parseLiveActivity(event) ?: return
        val key = Nip53.aTagValue(activity.hostPubkey, activity.dTag)
        val existing = streams[key]
        // Drop streams that ended, went stale, or never carried a streaming
        // URL — status "live" alone is not enough (sidecar's liveness rule).
        if (!Nip53.isLiveNow(activity)) {
            if (existing != null) {
                streams.remove(key)
                emitStreams()
            }
            return
        }
        // Update or insert — keep follow chatter data from existing entry
        if (existing != null) {
            streams[key] = existing.copy(activity = activity)
        } else {
            // Apply any pending follow chatter data from messages that arrived before the activity
            val pending = pendingFollowChatters.remove(key) ?: emptySet()
            val pendingAt = pendingFollowChatAt.remove(key) ?: 0L
            streams[key] = LiveStream(
                activity = activity,
                aTagValue = key,
                chatters = pending.toSet(),
                lastChatAt = pendingAt
            )
        }
        emitStreams()
    }

    /**
     * Discovery-only: track chatter counts for pill ranking without consuming events
     * from the RelayPool dedup set. Does NOT add messages to the chat list.
     */
    fun trackChatter(event: NostrEvent) {
        val aTagValue = Nip53.getChatActivityRef(event) ?: return
        val stream = streams[aTagValue]
        if (stream != null) {
            if (event.pubkey !in stream.chatters) {
                streams[aTagValue] = stream.copy(
                    chatters = stream.chatters + event.pubkey,
                    lastChatAt = maxOf(stream.lastChatAt, event.created_at)
                )
                emitStreams()
            }
        } else {
            pendingFollowChatters.getOrPut(aTagValue) { mutableSetOf() }.add(event.pubkey)
            pendingFollowChatAt[aTagValue] = maxOf(
                pendingFollowChatAt[aTagValue] ?: 0L, event.created_at
            )
        }
    }

    /**
     * Full chat processing: add message to the chat list for display.
     * Used by the dedicated per-stream subscription.
     */
    fun addChatMessage(event: NostrEvent) {
        if (!seenChatIds.add(event.id)) return
        val aTagValue = Nip53.getChatActivityRef(event)
        if (aTagValue == null) return
        val replyToId = event.tags.firstOrNull {
            it.size >= 2 && it[0] == "e" && (it.size < 4 || it[3] == "reply")
        }?.get(1)
        val emojiTags = com.wisp.app.nostr.Nip30.parseEmojiTags(event.tags)
        val msg = LiveChatMessage(
            id = event.id,
            senderPubkey = event.pubkey,
            content = event.content,
            createdAt = event.created_at,
            replyToId = replyToId,
            emojiTags = emojiTags
        )

        // Also update chatter counts
        val stream = streams[aTagValue]
        if (stream != null && event.pubkey !in stream.chatters) {
            streams[aTagValue] = stream.copy(
                chatters = stream.chatters + event.pubkey,
                lastChatAt = maxOf(stream.lastChatAt, event.created_at)
            )
            emitStreams()
        }

        // Add to chat messages for the stream
        val msgs = chatMessages.getOrPut(aTagValue) { mutableListOf() }
        synchronized(msgs) {
            msgs.add(msg)
            msgs.sortBy { it.createdAt }
            trimToChatCap(msgs)
        }

        // Update current view if this is the active stream
        if (aTagValue == currentStreamKey) {
            emitCurrentChat()
        }
    }

    fun addReaction(aTagValue: String, messageId: String, reactorPubkey: String, emoji: String) {
        val msgs = chatMessages[aTagValue] ?: return
        synchronized(msgs) {
            val idx = msgs.indexOfFirst { it.id == messageId }
            if (idx < 0) return
            val msg = msgs[idx]
            val current = msg.reactions[emoji] ?: emptyList()
            if (reactorPubkey in current) return
            msgs[idx] = msg.copy(reactions = msg.reactions + (emoji to (current + reactorPubkey)))
        }
        if (aTagValue == currentStreamKey) {
            emitCurrentChat()
        }
    }

    /**
     * Handle a stream-level zap receipt (kind 9735 with a-tag).
     * Inserts a zap announcement message into the chat list.
     */
    fun addStreamZap(event: NostrEvent, aTagValue: String) {
        if (!seenChatIds.add("zap-${event.id}")) return
        val sats = Nip57.getZapAmountSats(event)
        if (sats <= 0) return
        val zapperPubkey = Nip57.getZapperPubkey(event) ?: return
        val zapMessage = Nip57.getZapMessage(event)

        val msg = LiveChatMessage(
            id = "zap-${event.id}",
            senderPubkey = zapperPubkey,
            content = zapMessage,
            createdAt = event.created_at,
            isZapAnnouncement = true,
            zapAmountSats = sats
        )

        val msgs = chatMessages.getOrPut(aTagValue) { mutableListOf() }
        synchronized(msgs) {
            msgs.add(msg)
            msgs.sortBy { it.createdAt }
            trimToChatCap(msgs)
        }

        // Totals accumulate per stream across visits so re-opening a stream
        // doesn't shrink the number back to whatever survived the chat cap.
        streamZapTotals[aTagValue] = (streamZapTotals[aTagValue] ?: 0L) + sats
        if (aTagValue == currentStreamKey) {
            _streamZapTotal.value = streamZapTotals[aTagValue] ?: 0L
            emitCurrentChat()
        }
    }

    /** Mark a stream's zap total as a bounded sample that may be short (renders "+"). */
    fun markZapTotalUncertain(aTagValue: String) {
        zapTotalsUncertain.add(aTagValue)
        if (aTagValue == currentStreamKey) _streamZapTotalUncertain.value = true
    }

    fun setCurrentStream(aTagValue: String?) {
        currentStreamKey = aTagValue
        if (aTagValue != null) {
            // Prefer the accumulated total; fall back to what the retained
            // chat rows say (first visit to a stream discovered before opening).
            val known = streamZapTotals[aTagValue]
            _streamZapTotal.value = known ?: run {
                val msgs = chatMessages[aTagValue]
                if (msgs != null) {
                    synchronized(msgs) { msgs.filter { it.isZapAnnouncement }.sumOf { it.zapAmountSats } }
                } else 0L
            }
            _streamZapTotalUncertain.value = aTagValue in zapTotalsUncertain
            emitCurrentChat()
        } else {
            _streamZapTotal.value = 0L
            _streamZapTotalUncertain.value = false
            _currentChatMessages.value = emptyList()
        }
    }

    fun getActivity(aTagValue: String): Nip53.LiveActivity? = streams[aTagValue]?.activity

    fun getChatRelayHint(aTagValue: String): String? =
        streams[aTagValue]?.activity?.relayHints?.firstOrNull()

    /** Rows (chat + zap announcements) currently retained for a stream. */
    fun chatMessageCount(aTagValue: String): Int {
        val msgs = chatMessages[aTagValue] ?: return 0
        return synchronized(msgs) { msgs.size }
    }

    /**
     * Created-at of the oldest retained chat row (zap announcements excluded —
     * they don't come from the chat feed and can predate all messages).
     */
    fun oldestChatMessageCreatedAt(aTagValue: String): Long? {
        val msgs = chatMessages[aTagValue] ?: return null
        return synchronized(msgs) { msgs.firstOrNull { !it.isZapAnnouncement }?.createdAt }
    }

    private fun trimToChatCap(msgs: MutableList<LiveChatMessage>) {
        if (msgs.size > MAX_CHAT_MESSAGES) {
            msgs.subList(0, msgs.size - MAX_CHAT_MESSAGES).clear()
        }
    }

    private fun emitStreams() {
        _liveStreams.value = streams.toMap()
    }

    private fun emitCurrentChat() {
        val key = currentStreamKey ?: return
        val msgs = chatMessages[key]
        _currentChatMessages.value = if (msgs != null) {
            synchronized(msgs) { msgs.toList() }
        } else emptyList()
    }

    /**
     * Drop discovery state (live pills + pending chatter) without touching an
     * open stream's chat. Called when the user hides live content so nothing
     * reappears if discovery is later re-enabled from stale data.
     */
    fun clearDiscovered() {
        streams.clear()
        pendingFollowChatters.clear()
        pendingFollowChatAt.clear()
        emitStreams()
    }

    fun clear() {
        streams.clear()
        chatMessages.clear()
        seenChatIds.clear()
        streamZapTotals.clear()
        zapTotalsUncertain.clear()
        pendingFollowChatters.clear()
        pendingFollowChatAt.clear()
        currentStreamKey = null
        _liveStreams.value = emptyMap()
        _currentChatMessages.value = emptyList()
        _streamZapTotal.value = 0L
        _streamZapTotalUncertain.value = false
    }
}
