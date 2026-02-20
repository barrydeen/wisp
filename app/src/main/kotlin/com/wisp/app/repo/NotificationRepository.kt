package com.wisp.app.repo

import android.util.LruCache
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NotificationGroup
import com.wisp.app.nostr.ZapEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NotificationRepository {
    private val seenEvents = LruCache<String, Boolean>(2000)

    private val lock = Any()
    private val groupMap = mutableMapOf<String, NotificationGroup>()

    private val _notifications = MutableStateFlow<List<NotificationGroup>>(emptyList())
    val notifications: StateFlow<List<NotificationGroup>> = _notifications

    private val _hasUnread = MutableStateFlow(false)
    val hasUnread: StateFlow<Boolean> = _hasUnread

    fun addEvent(event: NostrEvent, myPubkey: String) {
        if (event.pubkey == myPubkey) return
        if (seenEvents.get(event.id) != null) return
        val hasPTag = event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == myPubkey }
        if (!hasPTag) return

        seenEvents.put(event.id, true)

        synchronized(lock) {
            val merged = when (event.kind) {
                7 -> mergeReaction(event)
                1 -> mergeKind1(event)
                9735 -> mergeZap(event)
                else -> false
            }
            if (!merged) return

            _hasUnread.value = true
            rebuildSortedList()
        }
    }

    fun markRead() {
        _hasUnread.value = false
    }

    fun clear() {
        synchronized(lock) {
            seenEvents.evictAll()
            groupMap.clear()
            _notifications.value = emptyList()
            _hasUnread.value = false
        }
    }

    fun purgeUser(pubkey: String) = synchronized(lock) {
        val toRemove = mutableListOf<String>()
        val toUpdate = mutableMapOf<String, NotificationGroup>()

        for ((key, group) in groupMap) {
            when (group) {
                is NotificationGroup.ReactionGroup -> {
                    val filtered = group.reactions.mapValues { (_, pks) -> pks.filter { it != pubkey } }
                        .filter { it.value.isNotEmpty() }
                    if (filtered.isEmpty()) toRemove.add(key)
                    else toUpdate[key] = group.copy(reactions = filtered)
                }
                is NotificationGroup.ZapGroup -> {
                    val filtered = group.zaps.filter { it.pubkey != pubkey }
                    if (filtered.isEmpty()) toRemove.add(key)
                    else toUpdate[key] = group.copy(
                        zaps = filtered,
                        totalSats = filtered.sumOf { it.sats }
                    )
                }
                is NotificationGroup.ReplyNotification -> {
                    if (group.senderPubkey == pubkey) toRemove.add(key)
                }
                is NotificationGroup.QuoteNotification -> {
                    if (group.senderPubkey == pubkey) toRemove.add(key)
                }
                is NotificationGroup.MentionNotification -> {
                    if (group.senderPubkey == pubkey) toRemove.add(key)
                }
            }
        }

        toRemove.forEach { groupMap.remove(it) }
        toUpdate.forEach { (k, v) -> groupMap[k] = v }
        if (toRemove.isNotEmpty() || toUpdate.isNotEmpty()) {
            rebuildSortedList()
        }
    }

    private fun rebuildSortedList() {
        val sorted = groupMap.values.sortedByDescending { it.latestTimestamp }
        _notifications.value = if (sorted.size > 200) sorted.take(200) else sorted
    }

    private fun mergeReaction(event: NostrEvent): Boolean {
        val emoji = event.content.ifBlank { "+" }
        val referencedId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
            ?: return false
        val key = "reactions:$referencedId"
        val existing = groupMap[key] as? NotificationGroup.ReactionGroup

        if (existing != null) {
            val currentPubkeys = existing.reactions[emoji] ?: emptyList()
            if (event.pubkey in currentPubkeys) return false
            val updatedReactions = existing.reactions.toMutableMap()
            updatedReactions[emoji] = currentPubkeys + event.pubkey
            groupMap[key] = existing.copy(
                reactions = updatedReactions,
                latestTimestamp = maxOf(existing.latestTimestamp, event.created_at)
            )
        } else {
            groupMap[key] = NotificationGroup.ReactionGroup(
                groupId = key,
                referencedEventId = referencedId,
                reactions = mapOf(emoji to listOf(event.pubkey)),
                latestTimestamp = event.created_at
            )
        }
        return true
    }

    private fun mergeZap(event: NostrEvent): Boolean {
        val amount = Nip57.getZapAmountSats(event)
        if (amount <= 0) return false
        val zapperPubkey = Nip57.getZapperPubkey(event) ?: return false
        val referencedId = Nip57.getZappedEventId(event) ?: return false
        val key = "zaps:$referencedId"
        val message = Nip57.getZapMessage(event)
        val entry = ZapEntry(pubkey = zapperPubkey, sats = amount, message = message, createdAt = event.created_at)
        val existing = groupMap[key] as? NotificationGroup.ZapGroup

        if (existing != null) {
            groupMap[key] = existing.copy(
                zaps = existing.zaps + entry,
                totalSats = existing.totalSats + amount,
                latestTimestamp = maxOf(existing.latestTimestamp, event.created_at)
            )
        } else {
            groupMap[key] = NotificationGroup.ZapGroup(
                groupId = key,
                referencedEventId = referencedId,
                zaps = listOf(entry),
                totalSats = amount,
                latestTimestamp = event.created_at
            )
        }
        return true
    }

    private fun mergeKind1(event: NostrEvent): Boolean {
        val quotedId = event.tags.firstOrNull { it.size >= 2 && it[0] == "q" }?.get(1)
        if (quotedId != null) return mergeQuote(event, quotedId)

        val replyTarget = Nip10.getReplyTarget(event)
        if (replyTarget != null) return mergeReply(event, replyTarget)

        return mergeMention(event)
    }

    private fun mergeReply(event: NostrEvent, replyTarget: String): Boolean {
        val key = "reply:${event.id}"
        groupMap[key] = NotificationGroup.ReplyNotification(
            groupId = key,
            senderPubkey = event.pubkey,
            replyEventId = event.id,
            referencedEventId = replyTarget,
            latestTimestamp = event.created_at
        )
        return true
    }

    private fun mergeQuote(event: NostrEvent, quotedEventId: String): Boolean {
        val key = "quote:${event.id}"
        groupMap[key] = NotificationGroup.QuoteNotification(
            groupId = key,
            senderPubkey = event.pubkey,
            quoteEventId = event.id,
            latestTimestamp = event.created_at
        )
        return true
    }

    private fun mergeMention(event: NostrEvent): Boolean {
        val key = "mention:${event.id}"
        groupMap[key] = NotificationGroup.MentionNotification(
            groupId = key,
            senderPubkey = event.pubkey,
            eventId = event.id,
            latestTimestamp = event.created_at
        )
        return true
    }
}
