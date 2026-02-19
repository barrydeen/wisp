package com.wisp.app.repo

import android.util.LruCache
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NotificationItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NotificationRepository {
    private val seenEvents = LruCache<String, Boolean>(2000)

    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications

    private val _hasUnread = MutableStateFlow(false)
    val hasUnread: StateFlow<Boolean> = _hasUnread

    fun addEvent(event: NostrEvent, myPubkey: String) {
        // Filter out own activity
        if (event.pubkey == myPubkey) return
        // Dedup
        if (seenEvents.get(event.id) != null) return
        // Validate p-tag references myPubkey
        val hasPTag = event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == myPubkey }
        if (!hasPTag) return

        seenEvents.put(event.id, true)

        val item = when (event.kind) {
            7 -> buildReaction(event)
            1 -> buildKind1(event)
            9735 -> buildZap(event, myPubkey)
            else -> null
        } ?: return

        _hasUnread.value = true
        val current = _notifications.value.toMutableList()
        current.add(0, item)
        if (current.size > 200) {
            _notifications.value = current.take(200)
        } else {
            _notifications.value = current
        }
    }

    fun markRead() {
        _hasUnread.value = false
    }

    fun clear() {
        seenEvents.evictAll()
        _notifications.value = emptyList()
        _hasUnread.value = false
    }

    fun purgeUser(pubkey: String) {
        _notifications.value = _notifications.value.filter { it.senderPubkey != pubkey }
    }

    private fun buildReaction(event: NostrEvent): NotificationItem.Reaction {
        val emoji = event.content.ifBlank { "+" }
        val referencedId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        return NotificationItem.Reaction(
            id = event.id,
            senderPubkey = event.pubkey,
            createdAt = event.created_at,
            referencedEventId = referencedId,
            emoji = emoji
        )
    }

    private fun buildKind1(event: NostrEvent): NotificationItem? {
        // Check for quote (q tag) first
        val quotedId = event.tags.firstOrNull { it.size >= 2 && it[0] == "q" }?.get(1)
        if (quotedId != null) return buildQuote(event, quotedId)

        // Check for reply
        val replyTarget = Nip10.getReplyTarget(event)
        if (replyTarget != null) return buildReply(event, replyTarget)

        // Otherwise it's a mention
        return buildMention(event)
    }

    private fun buildReply(event: NostrEvent, replyTarget: String): NotificationItem.Reply {
        return NotificationItem.Reply(
            id = event.id,
            senderPubkey = event.pubkey,
            createdAt = event.created_at,
            referencedEventId = replyTarget,
            replyEventId = event.id,
            contentPreview = event.content.take(120)
        )
    }

    private fun buildQuote(event: NostrEvent, quotedEventId: String): NotificationItem.Quote {
        return NotificationItem.Quote(
            id = event.id,
            senderPubkey = event.pubkey,
            createdAt = event.created_at,
            referencedEventId = quotedEventId,
            contentPreview = event.content.take(120),
            quotedEventId = quotedEventId
        )
    }

    private fun buildMention(event: NostrEvent): NotificationItem.Mention {
        return NotificationItem.Mention(
            id = event.id,
            senderPubkey = event.pubkey,
            createdAt = event.created_at,
            referencedEventId = null,
            contentPreview = event.content.take(120),
            eventId = event.id
        )
    }

    private fun buildZap(event: NostrEvent, myPubkey: String): NotificationItem.Zap? {
        val amount = Nip57.getZapAmountSats(event)
        if (amount <= 0) return null
        // Zapper pubkey from uppercase P tag
        val zapperPubkey = event.tags.firstOrNull { it.size >= 2 && it[0] == "P" }?.get(1)
            ?: event.pubkey
        val referencedId = Nip57.getZappedEventId(event)
        return NotificationItem.Zap(
            id = event.id,
            senderPubkey = zapperPubkey,
            createdAt = event.created_at,
            referencedEventId = referencedId,
            amountSats = amount
        )
    }
}
