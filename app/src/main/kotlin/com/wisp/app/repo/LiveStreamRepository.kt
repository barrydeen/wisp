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
    private val streams = ConcurrentHashMap<String, LiveStream>()
    private val seenChatIds = ConcurrentHashMap.newKeySet<String>()
    private var currentStreamKey: String? = null

    private val _liveStreams = MutableStateFlow<Map<String, LiveStream>>(emptyMap())
    val liveStreams: StateFlow<Map<String, LiveStream>> = _liveStreams

    private val _currentChatMessages = MutableStateFlow<List<LiveChatMessage>>(emptyList())
    val currentChatMessages: StateFlow<List<LiveChatMessage>> = _currentChatMessages

    private val _streamZapTotal = MutableStateFlow(0L)
    val streamZapTotal: StateFlow<Long> = _streamZapTotal

    private val chatMessages = ConcurrentHashMap<String, MutableList<LiveChatMessage>>()
    private val pendingFollowChatters = ConcurrentHashMap<String, MutableSet<String>>()
    private val pendingFollowChatAt = ConcurrentHashMap<String, Long>()

    fun addActivity(event: NostrEvent) {
        val activity = Nip53.parseLiveActivity(event) ?: return
        val key = Nip53.aTagValue(activity.hostPubkey, activity.dTag)
        val existing = streams[key]
        // Only keep if status is "live", remove if ended
        if (activity.status != "live") {
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
        }

        if (aTagValue == currentStreamKey) {
            _streamZapTotal.value += sats
            emitCurrentChat()
        }
    }

    fun setCurrentStream(aTagValue: String?) {
        currentStreamKey = aTagValue
        if (aTagValue != null) {
            // Recalculate stream zap total from existing messages
            val msgs = chatMessages[aTagValue]
            _streamZapTotal.value = if (msgs != null) {
                synchronized(msgs) { msgs.filter { it.isZapAnnouncement }.sumOf { it.zapAmountSats } }
            } else 0L
            emitCurrentChat()
        } else {
            _streamZapTotal.value = 0L
            _currentChatMessages.value = emptyList()
        }
    }

    fun getActivity(aTagValue: String): Nip53.LiveActivity? = streams[aTagValue]?.activity

    fun getChatRelayHint(aTagValue: String): String? =
        streams[aTagValue]?.activity?.relayHints?.firstOrNull()

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

    fun clear() {
        streams.clear()
        chatMessages.clear()
        seenChatIds.clear()
        pendingFollowChatters.clear()
        pendingFollowChatAt.clear()
        currentStreamKey = null
        _liveStreams.value = emptyMap()
        _currentChatMessages.value = emptyList()
        _streamZapTotal.value = 0L
    }
}
