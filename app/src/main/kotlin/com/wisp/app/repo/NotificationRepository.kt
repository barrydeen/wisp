package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NotificationGroup
import com.wisp.app.nostr.NotificationSummary
import com.wisp.app.nostr.ZapEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class NotificationRepository(context: Context, pubkeyHex: String?) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wisp_notif_${pubkeyHex ?: "anon"}", Context.MODE_PRIVATE)

    private val seenEvents = LruCache<String, Boolean>(2000)

    private val lock = Any()
    private val groupMap = mutableMapOf<String, NotificationGroup>()

    private val _notifications = MutableStateFlow<List<NotificationGroup>>(emptyList())
    val notifications: StateFlow<List<NotificationGroup>> = _notifications

    private val _summary24h = MutableStateFlow(NotificationSummary())
    val summary24h: StateFlow<NotificationSummary> = _summary24h

    private val _hasUnread = MutableStateFlow(false)
    val hasUnread: StateFlow<Boolean> = _hasUnread

    private val _zapReceived = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val zapReceived: SharedFlow<Unit> = _zapReceived

    @Volatile var appIsActive: Boolean = true

    @Volatile var isViewing: Boolean = false

    private var lastReadTimestamp: Long = prefs.getLong(KEY_LAST_READ, 0L)

    private val _notifReceived = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val notifReceived: SharedFlow<Unit> = _notifReceived

    fun addEvent(event: NostrEvent, myPubkey: String) {
        if (event.pubkey == myPubkey) return
        val hasPTag = event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == myPubkey }
        // Kind 6 reposts may omit the p-tag; callers must pre-filter kind 6 ownership
        if (!hasPTag && event.kind != 6) return

        synchronized(lock) {
            // Atomic check-then-put inside lock to prevent race when the same
            // event arrives from multiple relays concurrently (e.g. user-engage
            // subscriptions bypass RelayPool dedup).
            if (seenEvents.get(event.id) != null) return
            seenEvents.put(event.id, true)
            val merged = when (event.kind) {
                6 -> mergeRepost(event)
                7 -> mergeReaction(event)
                1 -> mergeKind1(event)
                9735 -> mergeZap(event)
                else -> false
            }
            if (!merged) return

            if (event.created_at > lastReadTimestamp) {
                if (isViewing) {
                    lastReadTimestamp = event.created_at
                    prefs.edit().putLong(KEY_LAST_READ, event.created_at).apply()
                } else {
                    _hasUnread.value = true
                }
                if (event.kind == 9735 && appIsActive) {
                    _zapReceived.tryEmit(Unit)
                }
                if (appIsActive && event.kind != 9735) {
                    _notifReceived.tryEmit(Unit)
                }
            }
            rebuildSortedList()
        }
    }

    fun markRead() {
        _hasUnread.value = false
        val latestTimestamp = _notifications.value.firstOrNull()?.latestTimestamp ?: return
        if (latestTimestamp > lastReadTimestamp) {
            lastReadTimestamp = latestTimestamp
            prefs.edit().putLong(KEY_LAST_READ, latestTimestamp).apply()
        }
    }

    fun clear() {
        synchronized(lock) {
            seenEvents.evictAll()
            groupMap.clear()
            _notifications.value = emptyList()
            _summary24h.value = NotificationSummary()
            _hasUnread.value = false
        }
        prefs.edit().clear().apply()
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
                    else toUpdate[key] = group.copy(
                        reactions = filtered,
                        reactionTimestamps = group.reactionTimestamps - pubkey
                    )
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
                is NotificationGroup.RepostNotification -> {
                    if (group.senderPubkey == pubkey) toRemove.add(key)
                }
                is NotificationGroup.RepostGroup -> {
                    val filtered = group.reposters.filter { it != pubkey }
                    if (filtered.isEmpty()) toRemove.add(key)
                    else toUpdate[key] = group.copy(
                        reposters = filtered,
                        repostTimestamps = group.repostTimestamps - pubkey
                    )
                }
            }
        }

        toRemove.forEach { groupMap.remove(it) }
        toUpdate.forEach { (k, v) -> groupMap[k] = v }
        if (toRemove.isNotEmpty() || toUpdate.isNotEmpty()) {
            rebuildSortedList()
        }
    }

    fun refreshSplits() = synchronized(lock) {
        rebuildSortedList()
    }

    private fun rebuildSortedList() {
        val now = System.currentTimeMillis() / 1000
        val recentCutoff = now - RECENT_WINDOW_SECONDS

        val result = mutableListOf<NotificationGroup>()

        for (group in groupMap.values) {
            when (group) {
                is NotificationGroup.ReactionGroup -> {
                    val recentReactions = mutableMapOf<String, MutableList<String>>()
                    val olderReactions = mutableMapOf<String, MutableList<String>>()
                    val recentTimestamps = mutableMapOf<String, Long>()
                    val olderTimestamps = mutableMapOf<String, Long>()

                    for ((emoji, pubkeys) in group.reactions) {
                        for (pk in pubkeys) {
                            val ts = group.reactionTimestamps[pk] ?: 0L
                            if (ts >= recentCutoff) {
                                recentReactions.getOrPut(emoji) { mutableListOf() }.add(pk)
                                recentTimestamps[pk] = ts
                            } else {
                                olderReactions.getOrPut(emoji) { mutableListOf() }.add(pk)
                                olderTimestamps[pk] = ts
                            }
                        }
                    }

                    if (recentReactions.isNotEmpty()) {
                        result.add(group.copy(
                            groupId = "${group.groupId}:recent",
                            reactions = recentReactions,
                            reactionTimestamps = recentTimestamps,
                            latestTimestamp = recentTimestamps.values.max()
                        ))
                    }
                    if (olderReactions.isNotEmpty()) {
                        result.add(group.copy(
                            reactions = olderReactions,
                            reactionTimestamps = olderTimestamps,
                            latestTimestamp = olderTimestamps.values.max()
                        ))
                    }
                }
                is NotificationGroup.ZapGroup -> {
                    val recentZaps = group.zaps.filter { it.createdAt >= recentCutoff }
                    val olderZaps = group.zaps.filter { it.createdAt < recentCutoff }

                    if (recentZaps.isNotEmpty()) {
                        result.add(group.copy(
                            groupId = "${group.groupId}:recent",
                            zaps = recentZaps,
                            totalSats = recentZaps.sumOf { it.sats },
                            latestTimestamp = recentZaps.maxOf { it.createdAt }
                        ))
                    }
                    if (olderZaps.isNotEmpty()) {
                        result.add(group.copy(
                            zaps = olderZaps,
                            totalSats = olderZaps.sumOf { it.sats },
                            latestTimestamp = olderZaps.maxOf { it.createdAt }
                        ))
                    }
                }
                is NotificationGroup.RepostGroup -> {
                    val recentPubkeys = mutableListOf<String>()
                    val olderPubkeys = mutableListOf<String>()
                    val recentTimestamps = mutableMapOf<String, Long>()
                    val olderTimestamps = mutableMapOf<String, Long>()

                    for (pk in group.reposters) {
                        val ts = group.repostTimestamps[pk] ?: 0L
                        if (ts >= recentCutoff) {
                            recentPubkeys.add(pk)
                            recentTimestamps[pk] = ts
                        } else {
                            olderPubkeys.add(pk)
                            olderTimestamps[pk] = ts
                        }
                    }

                    if (recentPubkeys.isNotEmpty()) {
                        result.add(group.copy(
                            groupId = "${group.groupId}:recent",
                            reposters = recentPubkeys,
                            repostTimestamps = recentTimestamps,
                            latestTimestamp = recentTimestamps.values.max()
                        ))
                    }
                    if (olderPubkeys.isNotEmpty()) {
                        result.add(group.copy(
                            reposters = olderPubkeys,
                            repostTimestamps = olderTimestamps,
                            latestTimestamp = olderTimestamps.values.max()
                        ))
                    }
                }
                else -> result.add(group)
            }
        }

        val sorted = result.sortedByDescending { it.latestTimestamp }
        _notifications.value = if (sorted.size > 200) sorted.take(200) else sorted

        // Compute 24h summary from raw groupMap (not the split result)
        val summaryCutoff = now - SUMMARY_WINDOW_SECONDS
        var replyCount = 0
        var reactionCount = 0
        var zapCount = 0
        var zapSats = 0L
        var repostCount = 0
        var mentionCount = 0
        var quoteCount = 0

        for (group in groupMap.values) {
            when (group) {
                is NotificationGroup.ReactionGroup -> {
                    for ((_, pubkeys) in group.reactions) {
                        for (pk in pubkeys) {
                            val ts = group.reactionTimestamps[pk] ?: 0L
                            if (ts >= summaryCutoff) reactionCount++
                        }
                    }
                }
                is NotificationGroup.ZapGroup -> {
                    for (zap in group.zaps) {
                        if (zap.createdAt >= summaryCutoff) {
                            zapCount++
                            zapSats += zap.sats
                        }
                    }
                }
                is NotificationGroup.ReplyNotification -> {
                    if (group.latestTimestamp >= summaryCutoff) replyCount++
                }
                is NotificationGroup.QuoteNotification -> {
                    if (group.latestTimestamp >= summaryCutoff) quoteCount++
                }
                is NotificationGroup.MentionNotification -> {
                    if (group.latestTimestamp >= summaryCutoff) mentionCount++
                }
                is NotificationGroup.RepostNotification -> {
                    if (group.latestTimestamp >= summaryCutoff) repostCount++
                }
                is NotificationGroup.RepostGroup -> {
                    for (pk in group.reposters) {
                        val ts = group.repostTimestamps[pk] ?: 0L
                        if (ts >= summaryCutoff) repostCount++
                    }
                }
            }
        }

        _summary24h.value = NotificationSummary(
            replyCount = replyCount,
            reactionCount = reactionCount,
            zapCount = zapCount,
            zapSats = zapSats,
            repostCount = repostCount,
            mentionCount = mentionCount,
            quoteCount = quoteCount
        )
    }

    private fun mergeReaction(event: NostrEvent): Boolean {
        val emoji = event.content.ifBlank { "❤️" }
        val referencedId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
            ?: return false
        val key = "reactions:$referencedId"
        val existing = groupMap[key] as? NotificationGroup.ReactionGroup

        // Extract custom emoji URLs from event tags
        val eventEmojiUrls = Nip30.parseEmojiTags(event)

        if (existing != null) {
            val currentPubkeys = existing.reactions[emoji] ?: emptyList()
            if (event.pubkey in currentPubkeys) return false
            val updatedReactions = existing.reactions.toMutableMap()
            updatedReactions[emoji] = currentPubkeys + event.pubkey
            val updatedTimestamps = existing.reactionTimestamps.toMutableMap()
            updatedTimestamps[event.pubkey] = event.created_at
            val updatedEmojiUrls = if (eventEmojiUrls.isNotEmpty()) {
                existing.emojiUrls + eventEmojiUrls.map { (k, v) -> ":$k:" to v }
            } else existing.emojiUrls
            groupMap[key] = existing.copy(
                reactions = updatedReactions,
                reactionTimestamps = updatedTimestamps,
                emojiUrls = updatedEmojiUrls,
                latestTimestamp = maxOf(existing.latestTimestamp, event.created_at)
            )
        } else {
            val emojiUrls = eventEmojiUrls.map { (k, v) -> ":$k:" to v }.toMap()
            groupMap[key] = NotificationGroup.ReactionGroup(
                groupId = key,
                referencedEventId = referencedId,
                reactions = mapOf(emoji to listOf(event.pubkey)),
                reactionTimestamps = mapOf(event.pubkey to event.created_at),
                emojiUrls = emojiUrls,
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

    private fun mergeRepost(event: NostrEvent): Boolean {
        val repostedId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
            ?: return false
        val key = "reposts:$repostedId"
        val existing = groupMap[key] as? NotificationGroup.RepostGroup

        if (existing != null) {
            if (event.pubkey in existing.reposters) return false
            val updatedTimestamps = existing.repostTimestamps.toMutableMap()
            updatedTimestamps[event.pubkey] = event.created_at
            groupMap[key] = existing.copy(
                reposters = existing.reposters + event.pubkey,
                repostTimestamps = updatedTimestamps,
                latestTimestamp = maxOf(existing.latestTimestamp, event.created_at)
            )
        } else {
            groupMap[key] = NotificationGroup.RepostGroup(
                groupId = key,
                repostedEventId = repostedId,
                reposters = listOf(event.pubkey),
                repostTimestamps = mapOf(event.pubkey to event.created_at),
                latestTimestamp = event.created_at
            )
        }
        return true
    }

    /** Returns all event IDs that are rendered as PostCards in the notifications UI. */
    fun getAllPostCardEventIds(): List<String> = synchronized(lock) {
        groupMap.values.map { group ->
            when (group) {
                is NotificationGroup.ReactionGroup -> group.referencedEventId
                is NotificationGroup.ZapGroup -> group.referencedEventId
                is NotificationGroup.ReplyNotification -> group.replyEventId
                is NotificationGroup.QuoteNotification -> group.quoteEventId
                is NotificationGroup.MentionNotification -> group.eventId
                is NotificationGroup.RepostNotification -> group.repostedEventId
                is NotificationGroup.RepostGroup -> group.repostedEventId
            }
        }.distinct()
    }

    companion object {
        private const val KEY_LAST_READ = "last_read_timestamp"
        private const val RECENT_WINDOW_SECONDS = 600L // 10 minutes
        private const val SUMMARY_WINDOW_SECONDS = 86400L // 24 hours
    }
}
