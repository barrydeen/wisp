package com.wisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.Nip69
import com.wisp.app.nostr.Nip88
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.FlatNotificationItem
import com.wisp.app.nostr.NotificationGroup
import com.wisp.app.nostr.NotificationSummary
import com.wisp.app.nostr.NotificationType
import com.wisp.app.nostr.ZapEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class NotificationRepository(
    context: Context,
    pubkeyHex: String?,
    private val muteRepo: MuteRepository? = null,
    private val eventRepo: EventRepository? = null
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wisp_notif_${pubkeyHex ?: "anon"}", Context.MODE_PRIVATE)

    private val seenEvents = LruCache<String, Boolean>(2000)

    /**
     * IDs of the user's own recent events, mirrored from EventRouter.myOwnEventIds.
     * Used as a fallback ownership check when the referenced event has been evicted
     * from the LRU cache (so eventRepo.getEvent() returns null).
     */
    @Volatile var myOwnEventIds: Set<String> = emptySet()

    private val lock = Any()
    private val groupMap = mutableMapOf<String, NotificationGroup>()
    private val zapEventIdsByGroup = mutableMapOf<String, MutableSet<String>>()

    private val _notifications = MutableStateFlow<List<NotificationGroup>>(emptyList())
    val notifications: StateFlow<List<NotificationGroup>> = _notifications

    private val flatItems = mutableListOf<FlatNotificationItem>()
    private val flatItemIds = mutableSetOf<String>()
    private val _flatNotifications = MutableStateFlow<List<FlatNotificationItem>>(emptyList())
    val flatNotifications: StateFlow<List<FlatNotificationItem>> = _flatNotifications

    private val _summary24h = MutableStateFlow(NotificationSummary())
    val summary24h: StateFlow<NotificationSummary> = _summary24h

    private val _hasUnread = MutableStateFlow(false)
    val hasUnread: StateFlow<Boolean> = _hasUnread

    private val _zapReceived = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val zapReceived: SharedFlow<Unit> = _zapReceived

    @Volatile var appIsActive: Boolean = true

    /** Only play sounds for events created after this timestamp (app start time). */
    @Volatile var soundEligibleAfter: Long = System.currentTimeMillis() / 1000

    @Volatile var isViewing: Boolean = false

    private var lastReadTimestamp: Long = prefs.getLong(KEY_LAST_READ, 0L)
    private var latestNotifTs: Long = prefs.getLong(KEY_LATEST_NOTIF_TS, 0L)

    private val _replyReceived = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val replyReceived: SharedFlow<Unit> = _replyReceived

    private val _notifReceived = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val notifReceived: SharedFlow<Int> = _notifReceived

    fun getLatestNotifTimestamp(): Long? = if (latestNotifTs > 0) latestNotifTs else null

    /**
     * Add a group chat reply (kind 9) as a reply notification.
     * Called from GroupListViewModel when a kind 9 message with a q-tag reply targets one of our messages.
     */
    fun addGroupChatReply(event: NostrEvent, myPubkey: String, replyToId: String, groupId: String) {
        if (event.pubkey == myPubkey) return
        if (muteRepo?.isBlocked(event.pubkey) == true) return
        synchronized(lock) {
            if (seenEvents.get(event.id) != null) return
            seenEvents.put(event.id, true)
            if (event.created_at > latestNotifTs) {
                latestNotifTs = event.created_at
                prefs.edit().putLong(KEY_LATEST_NOTIF_TS, event.created_at).apply()
            }

            val key = "reply:${event.id}"
            groupMap[key] = NotificationGroup.ReplyNotification(
                groupId = key,
                senderPubkey = event.pubkey,
                replyEventId = event.id,
                referencedEventId = replyToId,
                referencedEventHints = emptyList(),
                latestTimestamp = event.created_at
            )

            val flatReplyId = "reply:${event.id}"
            if (flatItemIds.add(flatReplyId)) {
                flatItems.add(FlatNotificationItem(
                    id = flatReplyId,
                    type = NotificationType.REPLY,
                    actorPubkey = event.pubkey,
                    referencedEventId = replyToId,
                    timestamp = event.created_at,
                    replyEventId = event.id,
                    groupChatId = groupId
                ))
            }

            if (event.created_at > lastReadTimestamp) {
                if (isViewing) {
                    lastReadTimestamp = event.created_at
                    prefs.edit().putLong(KEY_LAST_READ, event.created_at).apply()
                } else {
                    _hasUnread.value = true
                }
                if (event.created_at >= soundEligibleAfter && appIsActive) {
                    _replyReceived.tryEmit(Unit)
                }
            }
            rebuildSortedList()
        }
    }

    fun addEvent(event: NostrEvent, myPubkey: String, replyToMyEvent: Boolean = false, source: String = "") {
        if (event.pubkey == myPubkey) return
        // Defense-in-depth: reject events from blocked users even if the caller forgot to check.
        // For zap receipts, event.pubkey is the lightning service — check the actual zapper too.
        if (muteRepo?.isBlocked(event.pubkey) == true) return
        if (event.kind == 9735) {
            val zapperPubkey = Nip57.getZapperPubkey(event)
            if (zapperPubkey != null && muteRepo?.isBlocked(zapperPubkey) == true) return
        }
        val hasPTag = event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == myPubkey }
        // Kind 6 reposts may omit the p-tag; callers must pre-filter kind 6 ownership.
        // replyToMyEvent bypasses p-tag check for kind 1 replies found via e-tag subscription.
        if (!hasPTag && event.kind != 6 && event.kind != Nip88.KIND_POLL_RESPONSE && !(replyToMyEvent && event.kind == 1)) {
            if (DiagnosticLogger.isEnabled) {
                DiagnosticLogger.log("NOTIF", "REJECTED:no_ptag id=${event.id.take(12)} kind=${event.kind} " +
                    "pubkey=${event.pubkey.take(8)} myPubkey=${myPubkey.take(8)} source=$source " +
                    "hasPTag=false replyToMyEvent=$replyToMyEvent")
            }
            return
        }
        // Kind 1018 poll votes: only notify if the poll is ours
        if (event.kind == Nip88.KIND_POLL_RESPONSE) {
            val pollId = Nip88.getPollEventId(event)
            val pollEvent = pollId?.let { eventRepo?.getEvent(it) }
            if (pollEvent == null || pollEvent.pubkey != myPubkey) return
        }
        // Reactions, reposts, and zaps: only notify if the referenced event is ours.
        // A p-tag on these events can come from thread inheritance (the original note
        // being reacted to was in a thread the user participated in), so a p-tag match
        // alone is not sufficient.
        // If the referenced event is cached we can check authorship directly. If it has
        // been evicted from the LRU cache, we fall back to myOwnEventIds (the set of IDs
        // used to build the notif-replies-etag subscription) — if the ID is not there
        // either, we reject rather than risk a false positive.
        if (event.kind == 6 || event.kind == 7 || event.kind == 9735) {
            val referencedId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
            val referencedEvent = referencedId?.let { eventRepo?.getEvent(it) }
            val isOwnEvent = when {
                referencedEvent != null -> referencedEvent.pubkey == myPubkey
                referencedId != null -> referencedId in myOwnEventIds
                // No e-tag: profile/DM zap addressed directly to us via p-tag.
                // The relay subscription already filtered by p-tag = myPubkey so this is safe.
                else -> event.kind == 9735 && event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == myPubkey }
            }
            if (!isOwnEvent) {
                if (DiagnosticLogger.isEnabled) {
                    val refOwner = referencedEvent?.pubkey?.take(8) ?: if (referencedId != null) "not_cached" else "no_ref"
                    DiagnosticLogger.log("NOTIF", "REJECTED:not_our_event id=${event.id.take(12)} kind=${event.kind} " +
                        "refId=${referencedId?.take(12)} refOwner=$refOwner source=$source")
                }
                return
            }
        }

        synchronized(lock) {
            // Atomic check-then-put inside lock to prevent race when the same
            // event arrives from multiple relays concurrently (e.g. user-engage
            // subscriptions bypass RelayPool dedup).
            if (seenEvents.get(event.id) != null) return
            seenEvents.put(event.id, true)
            if (event.created_at > latestNotifTs) {
                latestNotifTs = event.created_at
                prefs.edit().putLong(KEY_LATEST_NOTIF_TS, event.created_at).apply()
            }

            val threadRoot = resolveThreadRoot(event)
            if (threadRoot != null && muteRepo?.isThreadMuted(threadRoot) == true) return

            val merged = when (event.kind) {
                6 -> mergeRepost(event)
                7 -> mergeReaction(event)
                1 -> mergeKind1(event)
                9735 -> mergeZap(event)
                Nip88.KIND_POLL_RESPONSE -> mergeVote(event)
                else -> false
            }
            if (!merged) return

            if (DiagnosticLogger.isEnabled) {
                val ownershipStatus = if (event.kind in intArrayOf(6, 7, 9735)) {
                    val refId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                    val refEvent = refId?.let { eventRepo?.getEvent(it) }
                    when {
                        refEvent?.pubkey == myPubkey -> "cached_ours"
                        refEvent != null -> "cached_others"
                        refId != null && refId in myOwnEventIds -> "fallback_ours"
                        else -> "not_cached"
                    }
                } else "n/a"
                val logMsg = "ACCEPTED id=${event.id.take(12)} kind=${event.kind} " +
                    "pubkey=${event.pubkey.take(8)} source=$source " +
                    "hasPTag=$hasPTag ownership=$ownershipStatus"
                DiagnosticLogger.log("NOTIF", logMsg)
                if (!hasPTag) {
                    DiagnosticLogger.log("CANARY", "accepted_without_ptag id=${event.id.take(12)} " +
                        "kind=${event.kind} source=$source replyToMyEvent=$replyToMyEvent")
                }
            }

            if (event.created_at > lastReadTimestamp) {
                if (isViewing) {
                    lastReadTimestamp = event.created_at
                    prefs.edit().putLong(KEY_LAST_READ, event.created_at).apply()
                } else {
                    _hasUnread.value = true
                }
                if (event.created_at >= soundEligibleAfter && appIsActive) {
                    when (event.kind) {
                        9735 -> _zapReceived.tryEmit(Unit)
                        1 -> {
                            // Replies get the ICQ flower effect; quotes/mentions get generic blip
                            val isQuote = event.tags.any { it.size >= 2 && it[0] == "q" }
                            val isReply = !isQuote && Nip10.getReplyTarget(event) != null
                            if (isReply) _replyReceived.tryEmit(Unit)
                            else _notifReceived.tryEmit(event.kind)
                        }
                        else -> _notifReceived.tryEmit(event.kind)
                    }
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
            zapEventIdsByGroup.clear()
            flatItems.clear()
            flatItemIds.clear()
            _notifications.value = emptyList()
            _flatNotifications.value = emptyList()
            _summary24h.value = NotificationSummary()
            _hasUnread.value = false
            soundEligibleAfter = System.currentTimeMillis() / 1000
        }
        latestNotifTs = 0L
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
                    val filteredZaps = group.zapEntries.filter { it.pubkey != pubkey }
                    if (filtered.isEmpty() && filteredZaps.isEmpty()) toRemove.add(key)
                    else toUpdate[key] = group.copy(
                        reactions = filtered,
                        reactionTimestamps = group.reactionTimestamps - pubkey,
                        zapEntries = filteredZaps
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
        flatItems.removeAll { item ->
            if (item.actorPubkey == pubkey) { flatItemIds.remove(item.id); true } else false
        }
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
                            if (muteRepo?.isBlocked(pk) == true) continue
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

                    val recentZaps = group.zapEntries.filter { it.createdAt >= recentCutoff && muteRepo?.isBlocked(it.pubkey) != true }
                    val olderZaps = group.zapEntries.filter { it.createdAt < recentCutoff && muteRepo?.isBlocked(it.pubkey) != true }

                    if (recentReactions.isNotEmpty() || recentZaps.isNotEmpty()) {
                        val ts = maxOf(
                            recentTimestamps.values.maxOrNull() ?: 0L,
                            recentZaps.maxOfOrNull { it.createdAt } ?: 0L
                        )
                        result.add(group.copy(
                            groupId = "${group.groupId}:recent",
                            reactions = recentReactions,
                            reactionTimestamps = recentTimestamps,
                            zapEntries = recentZaps,
                            latestTimestamp = ts
                        ))
                    }
                    if (olderReactions.isNotEmpty() || olderZaps.isNotEmpty()) {
                        val ts = maxOf(
                            olderTimestamps.values.maxOrNull() ?: 0L,
                            olderZaps.maxOfOrNull { it.createdAt } ?: 0L
                        )
                        result.add(group.copy(
                            reactions = olderReactions,
                            reactionTimestamps = olderTimestamps,
                            zapEntries = olderZaps,
                            latestTimestamp = ts
                        ))
                    }
                }
                is NotificationGroup.ReplyNotification -> {
                    if (muteRepo?.isBlocked(group.senderPubkey) != true) result.add(group)
                }
                is NotificationGroup.QuoteNotification -> {
                    if (muteRepo?.isBlocked(group.senderPubkey) != true) result.add(group)
                }
                is NotificationGroup.MentionNotification -> {
                    if (muteRepo?.isBlocked(group.senderPubkey) != true) result.add(group)
                }
            }
        }

        val sorted = result.sortedByDescending { it.latestTimestamp }
        _notifications.value = if (sorted.size > 200) sorted.take(200) else sorted

        val sortedFlat = flatItems.filter { muteRepo?.isBlocked(it.actorPubkey) != true }
            .sortedByDescending { it.timestamp }
        _flatNotifications.value = if (sortedFlat.size > 500) sortedFlat.take(500) else sortedFlat

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
                    for ((emoji, pubkeys) in group.reactions) {
                        for (pk in pubkeys) {
                            if (muteRepo?.isBlocked(pk) == true) continue
                            val ts = group.reactionTimestamps[pk] ?: 0L
                            if (ts >= summaryCutoff) {
                                when (emoji) {
                                    NotificationGroup.REPOST_EMOJI -> repostCount++
                                    NotificationGroup.ZAP_EMOJI -> {} // counted via zapEntries below
                                    else -> reactionCount++
                                }
                            }
                        }
                    }
                    for (zap in group.zapEntries) {
                        if (muteRepo?.isBlocked(zap.pubkey) == true) continue
                        if (zap.createdAt >= summaryCutoff) {
                            zapCount++
                            zapSats += zap.sats
                        }
                    }
                }
                is NotificationGroup.ReplyNotification -> {
                    if (muteRepo?.isBlocked(group.senderPubkey) != true && group.latestTimestamp >= summaryCutoff) replyCount++
                }
                is NotificationGroup.QuoteNotification -> {
                    if (muteRepo?.isBlocked(group.senderPubkey) != true && group.latestTimestamp >= summaryCutoff) quoteCount++
                }
                is NotificationGroup.MentionNotification -> {
                    if (muteRepo?.isBlocked(group.senderPubkey) != true && group.latestTimestamp >= summaryCutoff) mentionCount++
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
        val eTag = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" } ?: return false
        val referencedId = eTag[1]
        val eTagHint = eTag.getOrNull(2)?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
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
                relayHints = listOfNotNull(eTagHint),
                latestTimestamp = event.created_at
            )
        }

        val shortcode = Nip30.shortcodeRegex.matchEntire(emoji)?.groupValues?.get(1)
        val flatEmojiUrl = eventEmojiUrls[shortcode ?: ""]
        val groupChatId = event.tags.firstOrNull { it.size >= 2 && it[0] == "h" }?.get(1)
        val flatId = "reaction:${referencedId}:${event.pubkey}:${emoji.hashCode()}"
        if (flatItemIds.add(flatId)) {
            flatItems.add(FlatNotificationItem(
                id = flatId,
                type = NotificationType.REACTION,
                actorPubkey = event.pubkey,
                referencedEventId = referencedId,
                timestamp = event.created_at,
                emoji = emoji,
                emojiUrl = flatEmojiUrl,
                groupChatId = groupChatId
            ))
        }

        return true
    }

    private fun mergeZap(event: NostrEvent): Boolean {
        val amount = Nip57.getZapAmountSats(event)
        if (amount <= 0) return false
        val zapperPubkey = Nip57.getZapperPubkey(event) ?: return false
        val zapETag = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }
            ?: return mergeProfileZap(event, zapperPubkey, amount)
        val referencedId = zapETag[1]
        val zapHint = zapETag.getOrNull(2)?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
        val key = "reactions:$referencedId"
        // Secondary dedup: guard against LRU eviction in seenEvents allowing
        // the same 9735 event to be re-processed on periodic refresh cycles.
        val zapEventIds = zapEventIdsByGroup.getOrPut(key) { mutableSetOf() }
        if (!zapEventIds.add(event.id)) return false
        val message = Nip57.getZapMessage(event)
        val dmRelays = eventRepo?.dmRelayUrls ?: emptySet()
        val isPrivate = dmRelays.isNotEmpty() && Nip57.getZapRequestRelays(event).let { reqRelays ->
            reqRelays.isNotEmpty() && reqRelays.all { it in dmRelays }
        }
        val entry = ZapEntry(pubkey = zapperPubkey, sats = amount, message = message, createdAt = event.created_at, receiptEventId = event.id, isPrivate = isPrivate)
        val emoji = NotificationGroup.ZAP_EMOJI
        val existing = groupMap[key] as? NotificationGroup.ReactionGroup

        if (existing != null) {
            val currentPubkeys = existing.reactions[emoji] ?: emptyList()
            val updatedReactions = existing.reactions.toMutableMap()
            if (zapperPubkey !in currentPubkeys) {
                updatedReactions[emoji] = currentPubkeys + zapperPubkey
            }
            val updatedTimestamps = existing.reactionTimestamps.toMutableMap()
            updatedTimestamps[zapperPubkey] = event.created_at
            groupMap[key] = existing.copy(
                reactions = updatedReactions,
                reactionTimestamps = updatedTimestamps,
                zapEntries = existing.zapEntries + entry,
                latestTimestamp = maxOf(existing.latestTimestamp, event.created_at)
            )
        } else {
            groupMap[key] = NotificationGroup.ReactionGroup(
                groupId = key,
                referencedEventId = referencedId,
                reactions = mapOf(emoji to listOf(zapperPubkey)),
                reactionTimestamps = mapOf(zapperPubkey to event.created_at),
                zapEntries = listOf(entry),
                relayHints = listOfNotNull(zapHint),
                latestTimestamp = event.created_at
            )
        }

        val groupChatId = event.tags.firstOrNull { it.size >= 2 && it[0] == "h" }?.get(1)
        // Check if this zap is a vote on a kind 6969 zap poll
        val referencedEvent = eventRepo?.getEvent(referencedId)
        val zapPollOptionIndex = if (referencedEvent?.kind == Nip69.KIND_ZAP_POLL) {
            Nip69.getZapPollOptionFromZapReceipt(event)
        } else null
        val flatZapId = "zap:${event.id}"
        if (flatItemIds.add(flatZapId)) {
            flatItems.add(FlatNotificationItem(
                id = flatZapId,
                type = NotificationType.ZAP,
                actorPubkey = zapperPubkey,
                referencedEventId = referencedId,
                timestamp = event.created_at,
                zapSats = amount,
                zapMessage = message,
                isPrivateZap = isPrivate,
                groupChatId = groupChatId,
                zapPollOptionIndex = zapPollOptionIndex
            ))
        }

        return true
    }

    private fun mergeProfileZap(event: NostrEvent, zapperPubkey: String, amount: Long): Boolean {
        val message = Nip57.getZapMessage(event)
        val recipientPubkey = event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1) ?: return false
        val flatZapId = "profilezap:${event.id}"
        if (flatItemIds.add(flatZapId)) {
            flatItems.add(FlatNotificationItem(
                id = flatZapId,
                type = NotificationType.PROFILE_ZAP,
                actorPubkey = zapperPubkey,
                referencedEventId = recipientPubkey,
                timestamp = event.created_at,
                zapSats = amount,
                zapMessage = message
            ))
        }
        return true
    }

    private fun mergeKind1(event: NostrEvent): Boolean {
        val quotedId = event.tags.firstOrNull { it.size >= 2 && it[0] == "q" }?.get(1)
        if (quotedId != null) return mergeQuote(event, quotedId)

        val replyResult = Nip10.getReplyTargetWithHint(event)
        if (replyResult != null) return mergeReply(event, replyResult.first, replyResult.second)

        return mergeMention(event)
    }

    private fun mergeReply(event: NostrEvent, replyTarget: String, replyTargetHint: String?): Boolean {
        val key = "reply:${event.id}"
        val hints = listOfNotNull(replyTargetHint)
        groupMap[key] = NotificationGroup.ReplyNotification(
            groupId = key,
            senderPubkey = event.pubkey,
            replyEventId = event.id,
            referencedEventId = replyTarget,
            referencedEventHints = hints,
            latestTimestamp = event.created_at
        )

        val groupChatId = event.tags.firstOrNull { it.size >= 2 && it[0] == "h" }?.get(1)
        val flatReplyId = "reply:${event.id}"
        if (flatItemIds.add(flatReplyId)) {
            flatItems.add(FlatNotificationItem(
                id = flatReplyId,
                type = NotificationType.REPLY,
                actorPubkey = event.pubkey,
                referencedEventId = replyTarget,
                timestamp = event.created_at,
                replyEventId = event.id,
                groupChatId = groupChatId
            ))
        }

        return true
    }

    private fun mergeQuote(event: NostrEvent, quotedEventId: String): Boolean {
        val key = "quote:${event.id}"
        val qTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "q" && it[1] == quotedEventId }
        val hint = qTag?.getOrNull(2)?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
        groupMap[key] = NotificationGroup.QuoteNotification(
            groupId = key,
            senderPubkey = event.pubkey,
            quoteEventId = event.id,
            relayHints = listOfNotNull(hint),
            latestTimestamp = event.created_at
        )

        val flatQuoteId = "quote:${event.id}"
        if (flatItemIds.add(flatQuoteId)) {
            flatItems.add(FlatNotificationItem(
                id = flatQuoteId,
                type = NotificationType.QUOTE,
                actorPubkey = event.pubkey,
                referencedEventId = quotedEventId,
                timestamp = event.created_at,
                quoteEventId = event.id
            ))
        }

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

        val flatMentionId = "mention:${event.id}"
        if (flatItemIds.add(flatMentionId)) {
            flatItems.add(FlatNotificationItem(
                id = flatMentionId,
                type = NotificationType.MENTION,
                actorPubkey = event.pubkey,
                referencedEventId = event.id,
                timestamp = event.created_at
            ))
        }

        return true
    }

    private fun mergeRepost(event: NostrEvent): Boolean {
        val eTag = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" } ?: return false
        val repostedId = eTag[1]
        val eTagHint = eTag.getOrNull(2)?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
        val key = "reactions:$repostedId"
        val emoji = NotificationGroup.REPOST_EMOJI
        val existing = groupMap[key] as? NotificationGroup.ReactionGroup

        if (existing != null) {
            val currentPubkeys = existing.reactions[emoji] ?: emptyList()
            if (event.pubkey in currentPubkeys) return false
            val updatedReactions = existing.reactions.toMutableMap()
            updatedReactions[emoji] = currentPubkeys + event.pubkey
            val updatedTimestamps = existing.reactionTimestamps.toMutableMap()
            updatedTimestamps[event.pubkey] = event.created_at
            groupMap[key] = existing.copy(
                reactions = updatedReactions,
                reactionTimestamps = updatedTimestamps,
                latestTimestamp = maxOf(existing.latestTimestamp, event.created_at)
            )
        } else {
            groupMap[key] = NotificationGroup.ReactionGroup(
                groupId = key,
                referencedEventId = repostedId,
                reactions = mapOf(emoji to listOf(event.pubkey)),
                reactionTimestamps = mapOf(event.pubkey to event.created_at),
                relayHints = listOfNotNull(eTagHint),
                latestTimestamp = event.created_at
            )
        }

        val flatRepostId = "repost:${repostedId}:${event.pubkey}"
        if (flatItemIds.add(flatRepostId)) {
            flatItems.add(FlatNotificationItem(
                id = flatRepostId,
                type = NotificationType.REPOST,
                actorPubkey = event.pubkey,
                referencedEventId = repostedId,
                timestamp = event.created_at
            ))
        }

        return true
    }

    private fun mergeVote(event: NostrEvent): Boolean {
        val pollEventId = Nip88.getPollEventId(event) ?: return false
        val optionIds = Nip88.getResponseOptionIds(event)
        if (optionIds.isEmpty()) return false

        val flatVoteId = "vote:${event.id}"
        if (flatItemIds.add(flatVoteId)) {
            flatItems.add(FlatNotificationItem(
                id = flatVoteId,
                type = NotificationType.VOTE,
                actorPubkey = event.pubkey,
                referencedEventId = pollEventId,
                timestamp = event.created_at,
                voteOptionIds = optionIds
            ))
        }

        return true
    }

    /** Returns all event IDs that are rendered as PostCards in the notifications UI. */
    fun getAllPostCardEventIds(): List<String> = synchronized(lock) {
        groupMap.values.map { group ->
            when (group) {
                is NotificationGroup.ReactionGroup -> group.referencedEventId
                is NotificationGroup.ReplyNotification -> group.replyEventId
                is NotificationGroup.QuoteNotification -> group.quoteEventId
                is NotificationGroup.MentionNotification -> group.eventId
            }
        }.distinct()
    }

    fun purgeThread(rootEventId: String) = synchronized(lock) {
        val toRemove = mutableListOf<String>()
        for ((key, group) in groupMap) {
            val root = when (group) {
                is NotificationGroup.ReactionGroup -> {
                    val referenced = eventRepo?.getEvent(group.referencedEventId)
                    if (referenced != null) Nip10.getRootId(referenced) ?: referenced.id
                    else group.referencedEventId
                }
                is NotificationGroup.ReplyNotification -> {
                    val replyEvent = eventRepo?.getEvent(group.replyEventId)
                    if (replyEvent != null) Nip10.getRootId(replyEvent) ?: Nip10.getReplyTarget(replyEvent) ?: replyEvent.id
                    else group.referencedEventId ?: group.replyEventId
                }
                is NotificationGroup.QuoteNotification -> {
                    val quoteEvent = eventRepo?.getEvent(group.quoteEventId)
                    if (quoteEvent != null) Nip10.getRootId(quoteEvent) ?: quoteEvent.id else null
                }
                is NotificationGroup.MentionNotification -> {
                    val mentionEvent = eventRepo?.getEvent(group.eventId)
                    if (mentionEvent != null) Nip10.getRootId(mentionEvent) ?: mentionEvent.id else null
                }
            }
            if (root == rootEventId) toRemove.add(key)
        }
        if (toRemove.isNotEmpty()) {
            toRemove.forEach { groupMap.remove(it) }
            flatItems.removeAll { item ->
                val ref = eventRepo?.getEvent(item.referencedEventId)
                val itemRoot = if (ref != null) Nip10.getRootId(ref) ?: ref.id else item.referencedEventId
                if (itemRoot == rootEventId) { flatItemIds.remove(item.id); true } else false
            }
            rebuildSortedList()
        }
    }

    private fun resolveThreadRoot(event: NostrEvent): String? {
        return when (event.kind) {
            1 -> Nip10.getRootId(event) ?: Nip10.getReplyTarget(event)
            7 -> {
                val refId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                    ?: return null
                val refEvent = eventRepo?.getEvent(refId)
                if (refEvent != null) Nip10.getRootId(refEvent) ?: refId else refId
            }
            6 -> {
                val refId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                    ?: return null
                val refEvent = eventRepo?.getEvent(refId)
                if (refEvent != null) Nip10.getRootId(refEvent) ?: refId else refId
            }
            9735 -> {
                val refId = Nip57.getZappedEventId(event) ?: return null
                val refEvent = eventRepo?.getEvent(refId)
                if (refEvent != null) Nip10.getRootId(refEvent) ?: refId else refId
            }
            else -> null
        }
    }

    fun getSeenEventsSize(): Int = seenEvents.size()

    companion object {
        private const val KEY_LAST_READ = "last_read_timestamp"
        private const val KEY_LATEST_NOTIF_TS = "latest_notif_ts"
        private const val RECENT_WINDOW_SECONDS = 600L // 10 minutes
        private const val SUMMARY_WINDOW_SECONDS = 86400L // 24 hours
    }
}
