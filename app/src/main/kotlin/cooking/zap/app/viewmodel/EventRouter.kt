package cooking.zap.app.viewmodel

import android.util.Log
import cooking.zap.app.nostr.Blossom
import cooking.zap.app.nostr.DmMessage
import cooking.zap.app.nostr.DmReaction
import cooking.zap.app.nostr.EncryptedMedia
import cooking.zap.app.nostr.DmZap
import cooking.zap.app.nostr.Nip09
import cooking.zap.app.nostr.Nip10
import cooking.zap.app.nostr.Nip17
import cooking.zap.app.nostr.Nip53
import cooking.zap.app.nostr.Nip30
import cooking.zap.app.nostr.Nip51
import cooking.zap.app.nostr.Nip57
import cooking.zap.app.nostr.Nip88
import cooking.zap.app.nostr.Nip65
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.toHex
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.relay.RelayScoreBoard
import cooking.zap.app.repo.BlossomRepository
import cooking.zap.app.repo.BookmarkRepository
import cooking.zap.app.repo.BookmarkSetRepository
import cooking.zap.app.repo.ContactRepository
import cooking.zap.app.repo.CustomEmojiRepository
import cooking.zap.app.repo.DmRepository
import cooking.zap.app.repo.EventRepository
import cooking.zap.app.repo.ExtendedNetworkRepository
import cooking.zap.app.repo.InterestRepository
import cooking.zap.app.repo.KeyRepository
import cooking.zap.app.repo.ListRepository
import cooking.zap.app.repo.MetadataFetcher
import cooking.zap.app.repo.MuteRepository
import cooking.zap.app.repo.NotificationRepository
import cooking.zap.app.repo.PinRepository
import cooking.zap.app.repo.RecipeBookmarkRepository
import cooking.zap.app.repo.DiagnosticLogger
import cooking.zap.app.repo.GroupRepository
import cooking.zap.app.repo.LiveStreamRepository
import cooking.zap.app.repo.RelayHintStore
import cooking.zap.app.repo.RelayListRepository
import cooking.zap.app.repo.RelaySetRepository
import cooking.zap.app.repo.SigningMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes incoming relay events to the appropriate repositories based on subscription ID.
 * Extracted from FeedViewModel to reduce its size.
 */
class EventRouter(
    private val relayPool: RelayPool,
    private val eventRepo: EventRepository,
    private val contactRepo: ContactRepository,
    private val muteRepo: MuteRepository,
    private val notifRepo: NotificationRepository,
    private val listRepo: ListRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val recipeBookmarkRepo: RecipeBookmarkRepository,
    private val bookmarkSetRepo: BookmarkSetRepository,
    private val pinRepo: PinRepository,
    private val blossomRepo: BlossomRepository,
    private val customEmojiRepo: CustomEmojiRepository,
    private val relayListRepo: RelayListRepository,
    private val interestRepo: InterestRepository,
    private val relaySetRepo: RelaySetRepository,
    private val relayScoreBoard: RelayScoreBoard,
    private val relayHintStore: RelayHintStore,
    private val keyRepo: KeyRepository,
    private val dmRepo: DmRepository,
    private val extendedNetworkRepo: ExtendedNetworkRepository,
    private val groupRepo: GroupRepository?,
    private val liveStreamRepo: LiveStreamRepository?,
    private val metadataFetcher: MetadataFetcher,
    private val getUserPubkey: () -> String?,
    private val getSigner: () -> NostrSigner?,
    private val getFeedSubId: () -> String,
    private val getRelayFeedSubId: () -> String,
    private val getIsTrendingFeed: () -> Boolean,
    private val getIsHashtagFeed: () -> Boolean,
    private val onRelayFeedEventReceived: () -> Unit
) {
    /**
     * IDs of the user's own recent events used to build the notif-replies-etag subscription.
     * Updated by StartupCoordinator whenever the subscription is refreshed.
     * Used to verify that an event from notif-replies-etag is a DIRECT reply to one of our
     * posts, not just a nested reply in a thread we started.
     */
    @Volatile var myOwnEventIds: Set<String> = emptySet()

    // Track newest created_at per (pubkey, kind) to prevent stale overwrites
    // when the same self-data event arrives from multiple relays.
    private val selfDataTimestamps = ConcurrentHashMap<String, Long>()

    private fun isNewestSelfData(event: NostrEvent): Boolean {
        val key = "${event.pubkey}:${event.kind}"
        val existing = selfDataTimestamps[key]
        if (existing != null && event.created_at <= existing) return false
        selfDataTimestamps[key] = event.created_at
        return true
    }

    fun clearSelfDataTimestamps() {
        selfDataTimestamps.clear()
    }

    suspend fun processRelayEvent(event: NostrEvent, relayUrl: String, subscriptionId: String) {
        if (event.kind == Nip88.KIND_POLL_RESPONSE) {
            Log.d("POLL", "[EventRouter] received kind 1018 id=${event.id.take(12)} sub=$subscriptionId relay=$relayUrl")
        }
        if (event.kind in intArrayOf(20, 21, 22)) {
            Log.d("GALLERY", "[EventRouter] received kind=${event.kind} id=${event.id.take(12)} pubkey=${event.pubkey.take(8)} sub=$subscriptionId relay=$relayUrl")
        }
        if (subscriptionId == "dms") {
            if (event.kind == 1059) processGiftWrap(event, relayUrl)
            return
        }
        if (subscriptionId == "notif") {
            if (muteRepo.isBlocked(event.pubkey)) return
            val myPubkey = getUserPubkey()
            if (myPubkey != null) {
                when (event.kind) {
                    5 -> eventRepo.addEvent(event)
                    6 -> eventRepo.addEvent(event)
                    7 -> eventRepo.addEvent(event)
                    Nip88.KIND_POLL_RESPONSE -> eventRepo.addEvent(event)
                    9735 -> {
                        eventRepo.addEvent(event)
                        eventRepo.addEventRelay(event.id, relayUrl)
                        // Associate with a DM message if the e-tag points to a known rumorId
                        val eTagId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                        if (eTagId != null) {
                            val (convKey, msgId) = dmRepo.findByRumorId(eTagId)
                                ?.let { it.first to it.second } ?: (null to null)
                            if (convKey != null && msgId != null) {
                                val sats = Nip57.getZapAmountSats(event)
                                if (sats > 0) {
                                    val zapperPubkey = eventRepo.resolveZapSender(event).first ?: event.pubkey
                                    dmRepo.addZap(convKey, msgId, DmZap(zapperPubkey, sats, event.created_at))
                                }
                            }
                        }
                    }
                    1 -> {
                        eventRepo.cacheEvent(event)
                        if (!Nip10.isStandaloneQuote(event)) {
                            val parentId = Nip10.getReplyTarget(event)
                            if (parentId != null) eventRepo.addReplyCount(parentId, event.id)
                        }
                    }
                    else -> eventRepo.cacheEvent(event)
                }
                notifRepo.addEvent(event, myPubkey, source = "notif")
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
                if (event.kind == 9735) {
                    val zapperPubkey = eventRepo.resolveZapSender(event).first
                    if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                        metadataFetcher.addToPendingProfiles(zapperPubkey)
                    }
                }
            }
        } else if (subscriptionId == "notif-replies-etag") {
            if (muteRepo.isBlocked(event.pubkey)) return
            val myPubkey = getUserPubkey()
            if (myPubkey != null && event.kind == 1) {
                eventRepo.cacheEvent(event)
                val parentId = if (!Nip10.isStandaloneQuote(event)) {
                    Nip10.getReplyTarget(event)
                } else null
                if (parentId != null) eventRepo.addReplyCount(parentId, event.id)
                // Only bypass the p-tag check if this is a DIRECT reply to one of our own
                // events. The #e subscription also returns nested thread replies (where our
                // event is the root ancestor but not the direct parent) — those should not
                // be unconditionally accepted without a p-tag.
                val isDirectReplyToMe = parentId != null && parentId in myOwnEventIds
                notifRepo.addEvent(event, myPubkey, replyToMyEvent = isDirectReplyToMe, source = "notif-replies-etag")
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
            }
        } else if (subscriptionId == "notif-quotes-qtag") {
            if (muteRepo.isBlocked(event.pubkey)) return
            val myPubkey = getUserPubkey()
            if (myPubkey != null && event.kind == 1) {
                eventRepo.cacheEvent(event)
                notifRepo.addEvent(event, myPubkey, source = "notif-quotes-qtag")
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
            }
        } else if (subscriptionId == "self-notes") {
            eventRepo.cacheEvent(event)
            if (event.kind == 1) {
                val parentId = Nip10.getReplyTarget(event)
                if (parentId != null) eventRepo.addReplyCount(parentId, event.id)
            }
        } else if (subscriptionId.startsWith("qpoll-")) {
            // Poll vote responses for quoted polls
            if (event.kind == Nip88.KIND_POLL_RESPONSE) {
                eventRepo.addEvent(event)
            }
        } else if (subscriptionId.startsWith("qzpoll-")) {
            // Zap receipts for quoted zap polls
            if (event.kind == 9735) {
                eventRepo.addEvent(event)
            }
        } else if (subscriptionId.startsWith("quote-")) {
            eventRepo.cacheEvent(event)
            if (event.kind == 1 && eventRepo.getProfileData(event.pubkey) == null) {
                metadataFetcher.addToPendingProfiles(event.pubkey)
            }
        } else if (subscriptionId.startsWith("reply-count-")) {
            if (event.kind == 1) {
                eventRepo.cacheEvent(event)
                val parentId = Nip10.getReplyTarget(event)
                if (parentId != null) eventRepo.addReplyCount(parentId, event.id)
            }
        } else if (subscriptionId.startsWith("zap-count-") || subscriptionId.startsWith("zap-rcpt-")) {
            if (event.kind == 9735) {
                eventRepo.addEvent(event)
                eventRepo.addEventRelay(event.id, relayUrl)
                val zapperPubkey = eventRepo.resolveZapSender(event).first
                if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                    metadataFetcher.addToPendingProfiles(zapperPubkey)
                }
                // Associate with a DM message if the e-tag points to a known rumorId
                val eTagId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                if (eTagId != null) {
                    val found = dmRepo.findByRumorId(eTagId)
                    if (found != null) {
                        val sats = Nip57.getZapAmountSats(event)
                        if (sats > 0) {
                            val zapper = zapperPubkey ?: event.pubkey
                            dmRepo.addZap(found.first, found.second, DmZap(zapper, sats, event.created_at))
                        }
                    }
                }
            }
        } else if (subscriptionId == "thread-root" || subscriptionId == "thread-replies" ||
                   subscriptionId.startsWith("thread-reactions")) {
            // ThreadViewModel handles these via its own RelayPool collector — skip entirely
            return
        } else if (subscriptionId.startsWith("engage") || subscriptionId.startsWith("user-engage")) {
            when (event.kind) {
                5 -> eventRepo.addEvent(event)
                6 -> eventRepo.addEvent(event)
                7 -> eventRepo.addEvent(event)
                Nip88.KIND_POLL_RESPONSE -> {
                    Log.d("POLL", "[EventRouter] kind 1018 from sub=$subscriptionId pubkey=${event.pubkey.take(8)} pollId=${Nip88.getPollEventId(event)?.take(12)} options=${Nip88.getResponseOptionIds(event)}")
                    eventRepo.addEvent(event)
                }
                9735 -> {
                    eventRepo.addEvent(event)
                    eventRepo.addEventRelay(event.id, relayUrl)
                    val zapperPubkey = eventRepo.resolveZapSender(event).first
                    if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                        metadataFetcher.addToPendingProfiles(zapperPubkey)
                    }
                }
                1 -> {
                    eventRepo.cacheEvent(event)
                    val parentId = Nip10.getReplyTarget(event)
                    if (parentId != null) eventRepo.addReplyCount(parentId, event.id)
                }
            }
            // Engagement events win the dedup race against "notif" subscription,
            // so also route notification-eligible events to notifRepo here.
            // For kind 6 reposts, only notify if the reposted event is ours
            // (engagement subs fetch reposts for all viewed posts, not just ours).
            val myPubkey = getUserPubkey()
            val isNotifEligible = myPubkey != null && event.pubkey != myPubkey &&
                event.kind in intArrayOf(1, 6, 7, 9735, Nip88.KIND_POLL_RESPONSE) &&
                !muteRepo.isBlocked(event.pubkey)
            val isRepostOfOther = event.kind == 6 && run {
                val repostedId = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                val repostedEvent = repostedId?.let { eventRepo.getEvent(it) }
                repostedEvent == null || repostedEvent.pubkey != myPubkey
            }
            if (isNotifEligible && !isRepostOfOther) {
                notifRepo.addEvent(event, myPubkey!!, source = subscriptionId)
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
            }
        } else if (subscriptionId.startsWith("extnet-k3")) {
            // Extended network discovery: kind 3 follow lists — route to repo, NOT feed
            if (event.kind == 3) {
                Log.d("EventRouter", "Routing kind 3 from sub=$subscriptionId pubkey=${event.pubkey.take(8)}")
                extendedNetworkRepo.processFollowListEvent(event)
            }
        } else if (subscriptionId.startsWith("extnet-rl-")) {
            // Extended network discovery: relay lists — update relay list cache
            if (event.kind == 10002) relayListRepo.updateFromEvent(event)
        } else if (subscriptionId.startsWith("onb-")) {
            // Onboarding suggestion fetches — only cache kind 0 profiles, don't add to feed
            if (event.kind == 0) eventRepo.cacheEvent(event)
        } else if (subscriptionId.startsWith("fetch-bkset-") || subscriptionId == "fetch-bookmarks") {
            // Bookmark/list event fetches — cache the notes so screens can display them
            eventRepo.cacheEvent(event)
            if (eventRepo.getProfileData(event.pubkey) == null) {
                metadataFetcher.addToPendingProfiles(event.pubkey)
            }
        } else if (subscriptionId == "live-activities") {
            if (event.kind == Nip53.KIND_LIVE_ACTIVITY) {
                liveStreamRepo?.addActivity(event)
                // Also cache in eventRepo so LiveStreamCard can find it via findAddressableEvent
                eventRepo.cacheEvent(event)
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
            }
            return
        } else if (subscriptionId == "live-chat-discovery") {
            // Discovery only — track chatter counts for pill ranking, don't consume from dedup
            if (event.kind == Nip53.KIND_LIVE_CHAT_MESSAGE) {
                liveStreamRepo?.trackChatter(event)
            }
            return
        } else if (subscriptionId.startsWith("live-chat-") || subscriptionId.startsWith("live-react-") || subscriptionId.startsWith("live-zap-")) {
            when (event.kind) {
                Nip53.KIND_LIVE_CHAT_MESSAGE -> {
                    eventRepo.cacheEvent(event)
                    liveStreamRepo?.addChatMessage(event)
                }
                7 -> {
                    val eTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                    val aTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "a" }?.get(1)
                    if (eTag != null && aTag != null) {
                        liveStreamRepo?.addReaction(aTag, eTag, event.pubkey, event.content)
                    }
                }
                9735 -> {
                    eventRepo.addEvent(event)
                    eventRepo.addEventRelay(event.id, relayUrl)
                    // Route stream-level zaps to liveStreamRepo for chat announcements
                    var zapATag = event.tags.firstOrNull { it.size >= 2 && it[0] == "a" }?.get(1)
                    if (zapATag == null) {
                        // Some LNURL servers only include e-tag, not a-tag.
                        // If the e-tag points to a cached 30311 event, derive the a-tag.
                        val eTagId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                        if (eTagId != null) {
                            val targetEvent = eventRepo.getEvent(eTagId)
                            if (targetEvent?.kind == Nip53.KIND_LIVE_ACTIVITY) {
                                val dTag = targetEvent.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
                                if (dTag != null) {
                                    zapATag = Nip53.aTagValue(targetEvent.pubkey, dTag)
                                }
                            }
                        }
                    }
                    if (zapATag != null) {
                        liveStreamRepo?.addStreamZap(event, zapATag)
                    }
                    val zapperPubkey = Nip57.getZapperPubkey(event)
                    if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                        metadataFetcher.addToPendingProfiles(zapperPubkey)
                    }
                }
            }
            if (eventRepo.getProfileData(event.pubkey) == null) {
                metadataFetcher.addToPendingProfiles(event.pubkey)
            }
            return
        } else {
            if (event.kind == 10002) {
                relayListRepo.updateFromEvent(event)
                val myPubkey = getUserPubkey()
                if (DiagnosticLogger.isEnabled && event.kind in SELF_DATA_KINDS) {
                    val isMine = myPubkey != null && event.pubkey == myPubkey
                    DiagnosticLogger.log("SELF_DATA", "kind=${event.kind} pubkey=${event.pubkey.take(8)} " +
                        "myPubkey=${myPubkey?.take(8)} isMine=$isMine sub=$subscriptionId relay=$relayUrl")
                }
                if (myPubkey != null && event.pubkey == myPubkey && isNewestSelfData(event)) {
                    val relays = Nip65.parseRelayList(event)
                    if (relays.isNotEmpty()) {
                        keyRepo.saveRelays(relays)
                        // Don't call relayPool.updateRelays() here — it would replace
                        // the full pool (pinned+scored+extended ~150 relays) with just
                        // the user's ~5-10 NIP-65 relays, destroying active subscriptions.
                        // The saved list is picked up on next startup by rebuildRelayPool().
                    }
                }
            }
            if (event.kind == Nip51.KIND_DM_RELAYS) {
                relayListRepo.updateDmRelaysFromEvent(event)
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey && isNewestSelfData(event)) {
                    val urls = Nip51.parseRelaySet(event)
                    keyRepo.saveDmRelays(urls)
                    relayPool.updateDmRelays(urls)
                }
            }
            if (event.kind == Nip51.KIND_SEARCH_RELAYS) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey && isNewestSelfData(event)) {
                    keyRepo.saveSearchRelays(Nip51.parseRelaySet(event))
                }
            }
            if (event.kind == Nip51.KIND_BLOCKED_RELAYS) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey && isNewestSelfData(event)) {
                    val urls = Nip51.parseRelaySet(event)
                    keyRepo.saveBlockedRelays(urls)
                    relayPool.updateBlockedUrls(urls)
                }
            }
            if (event.kind == Nip51.KIND_MUTE_LIST) {
                val myPubkey = getUserPubkey()
                val s = getSigner()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    if (s != null) muteRepo.loadFromEvent(event, s)
                    else muteRepo.loadFromEvent(event)
                }
            }
            if (event.kind == Nip51.KIND_BOOKMARK_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) bookmarkRepo.loadFromEvent(event)
            }
            if (event.kind == RecipeBookmarkRepository.LIST_KIND) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) recipeBookmarkRepo.applyEvent(event)
            }
            if (event.kind == Nip51.KIND_PIN_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) pinRepo.loadFromEvent(event)
            }
            if (event.kind == Blossom.KIND_SERVER_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey && isNewestSelfData(event)) blossomRepo.updateFromEvent(event)
            }
            if (event.kind == Nip51.KIND_FOLLOW_SET) {
                val myPubkey = getUserPubkey()
                val s = getSigner()
                val decrypted = if (myPubkey != null && s != null && event.pubkey == myPubkey && event.content.isNotBlank()) {
                    try { s.nip44Decrypt(event.content, myPubkey) } catch (_: Exception) { null }
                } else null
                listRepo.updateFromEvent(event, decrypted)
            }
            if (event.kind == Nip51.KIND_INTEREST_SET) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    interestRepo.updateFromEvent(event)
                }
            }
            if (event.kind == 5) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    // Run through EventRepository so deletion coords/ids are persisted
                    // in DeletedEventsRepository. Safe to call repeatedly.
                    eventRepo.addEvent(event)
                    // Relays often keep re-serving deleted addressable events; sweep
                    // matching entries out of the repos that hold them in memory.
                    for (coord in Nip09.getDeletedAddresses(event)) {
                        val parts = coord.split(":", limit = 3)
                        if (parts.size != 3 || parts[1] != myPubkey) continue
                        val kind = parts[0].toIntOrNull() ?: continue
                        val dTag = parts[2]
                        if (kind == Nip51.KIND_INTEREST_SET) interestRepo.removeSet(dTag)
                    }
                }
            }
            if (event.kind == Nip51.KIND_BOOKMARK_SET) {
                val myPubkey = getUserPubkey()
                val s = getSigner()
                val decrypted = if (myPubkey != null && s != null && event.pubkey == myPubkey && event.content.isNotBlank()) {
                    try { s.nip44Decrypt(event.content, myPubkey) } catch (_: Exception) { null }
                } else null
                bookmarkSetRepo.updateFromEvent(event, decrypted)
            }
            if (event.kind == Nip51.KIND_RELAY_SET) {
                relaySetRepo.updateRelaySetFromEvent(event)
            }
            if (event.kind == Nip51.KIND_FAVORITE_RELAYS) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    relaySetRepo.updateFavoriteRelaysFromEvent(event)
                }
            }
            if (event.kind == Nip51.KIND_SIMPLE_GROUPS) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey && isNewestSelfData(event)) {
                    val groups = Nip51.parseSimpleGroups(event)
                    val repo = groupRepo
                    if (repo != null && groups.isNotEmpty()) {
                        val existing = repo.getJoinedGroupKeys().toSet()
                        for (entry in groups) {
                            val key = Pair(entry.relayUrl, entry.groupId)
                            if (key !in existing) {
                                repo.addGroup(entry.relayUrl, entry.groupId, localName = entry.name)
                            }
                        }
                    }
                }
            }
            if (event.kind == Nip30.KIND_USER_EMOJI_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) customEmojiRepo.updateFromEvent(event)
            }
            if (event.kind == Nip30.KIND_EMOJI_SET) customEmojiRepo.updateFromEvent(event)

            // Only add to feed for feed-related subscriptions;
            // other subs (user profile, bookmarks, threads) just cache
            // Track author provenance and feed hints into scoreboard
            relayHintStore.addAuthorRelay(event.pubkey, relayUrl)
            if (!relayListRepo.hasRelayList(event.pubkey)) {
                relayScoreBoard.addHintRelays(event.pubkey, listOf(relayUrl))
            }
            for (tag in event.tags) {
                if (tag.size >= 3 && tag[0] == "p") {
                    val url = tag[2].trimEnd('/')
                    if (RelayConfig.isValidUrl(url) && !relayListRepo.hasRelayList(tag[1])) {
                        relayScoreBoard.addHintRelays(tag[1], listOf(url))
                    }
                }
            }

            val feedSubId = getFeedSubId()
            val relayFeedSubId = getRelayFeedSubId()
            val isFeedSub = subscriptionId == feedSubId ||
                subscriptionId == "loadmore" ||
                subscriptionId == "feed-backfill"
            val isRelayFeedSub = subscriptionId == relayFeedSubId ||
                subscriptionId == "relay-loadmore"
            if (isRelayFeedSub && subscriptionId.startsWith("trending-users-")) {
                // Kind 0 events collected directly by subscribeTrendingUsers() — skip feed routing
                return
            }
            if (isRelayFeedSub) {
                if (getIsHashtagFeed()) {
                    // OnlyFood: skip cacheEvent() — it marks the global seenEventIds,
                    // which would block the same event from later entering the
                    // author-based main feed via addEvent(). addHashtagFeedEvent()
                    // caches into eventCache itself without touching seenEventIds,
                    // so a followed author's food note can appear in both feeds.
                    eventRepo.addHashtagFeedEvent(event)
                } else {
                    eventRepo.cacheEvent(event)
                    if (getIsTrendingFeed()) {
                        eventRepo.addTrendingFeedEvent(event)
                    } else {
                        eventRepo.addRelayFeedEvent(event)
                    }
                }
                onRelayFeedEventReceived()
                eventRepo.addEventRelay(event.id, relayUrl)
                if (event.kind == 1 || event.kind == 30023) {
                    metadataFetcher.fetchQuotedEvents(event)
                    if (eventRepo.getProfileData(event.pubkey) == null) {
                        metadataFetcher.addToPendingProfiles(event.pubkey)
                    }
                }
                if (event.kind == 6 && event.content.isNotBlank()) {
                    try {
                        val inner = NostrEvent.fromJson(event.content)
                        if (eventRepo.getProfileData(inner.pubkey) == null) {
                            metadataFetcher.addToPendingProfiles(inner.pubkey)
                        }
                        metadataFetcher.fetchQuotedEvents(inner)
                    } catch (_: Exception) {}
                }
            } else if (isFeedSub) {
                if (event.kind in intArrayOf(20, 21, 22)) {
                    android.util.Log.d("GALLERY", "[EventRouter] feed sub gallery event kind=${event.kind} id=${event.id.take(12)} pubkey=${event.pubkey.take(8)} sub=$subscriptionId relay=$relayUrl")
                }
                eventRepo.addEvent(event)
                eventRepo.addEventRelay(event.id, relayUrl)
                if (event.kind == 1 || event.kind == 30023) {
                    metadataFetcher.fetchQuotedEvents(event)
                    if (eventRepo.getProfileData(event.pubkey) == null) {
                        metadataFetcher.addToPendingProfiles(event.pubkey)
                    }
                }
                if (event.kind == 6 && event.content.isNotBlank()) {
                    try {
                        val inner = NostrEvent.fromJson(event.content)
                        if (eventRepo.getProfileData(inner.pubkey) == null) {
                            metadataFetcher.addToPendingProfiles(inner.pubkey)
                        }
                        metadataFetcher.fetchQuotedEvents(inner)
                    } catch (_: Exception) {}
                }
            } else {
                eventRepo.cacheEvent(event)
                // NIP-38: route user status events to addEvent so they get processed
                if (event.kind == 30315) eventRepo.addEvent(event)
            }
            // Always handle follow list updates (from self-data subscription)
            if (event.kind == 3) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) contactRepo.updateFromEvent(event)
            }
        }
    }

    companion object {
        private val SELF_DATA_KINDS = intArrayOf(
            0, 3, 5, 10002, 10050, 10007, 10006,
            Nip51.KIND_MUTE_LIST, Nip51.KIND_SIMPLE_GROUPS, Nip51.KIND_BOOKMARK_LIST,
            Nip51.KIND_PIN_LIST, Blossom.KIND_SERVER_LIST, Nip51.KIND_FOLLOW_SET,
            Nip51.KIND_INTEREST_SET, Nip51.KIND_BOOKMARK_SET, Nip51.KIND_RELAY_SET,
            Nip51.KIND_FAVORITE_RELAYS, Nip30.KIND_USER_EMOJI_LIST, Nip30.KIND_EMOJI_SET
        )
    }

    private fun processGiftWrap(event: NostrEvent, relayUrl: String) {
        dmRepo.updateLatestGiftWrapTimestamp(event.created_at)
        val keypair = keyRepo.getKeypair() ?: return
        val myPubkey = keypair.pubkey.toHex()

        val rumor = try {
            Nip17.unwrapGiftWrap(keypair.privkey, event)
        } catch (e: Exception) {
            Log.w("EventRouter", "Failed to unwrap gift wrap ${event.id}: ${e.message}")
            null
        } ?: return

        // Private reaction (kind 7 rumor). The "k" tag carries the kind of the message
        // being reacted to: k=1 → reaction on a NIP-17 private reply, route through the
        // note repository so thread/notification rendering picks it up the same way as
        // public reactions; k=14 (or absent for back-compat) → DM reaction, route into
        // the DM conversation entry.
        if (Nip17.isReaction(rumor)) {
            val kTag = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "k" }?.get(1)
            if (kTag == "1") {
                handlePrivateReplyReaction(rumor, myPubkey)
                return
            }
            val targetId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
            val participants = Nip17.getConversationParticipants(rumor, myPubkey)
            if (participants.any { muteRepo.isBlocked(it) }) return
            val convKey = DmRepository.conversationKey(participants + myPubkey)
            val emojiContent = rumor.content.trim()
            val emojiUrl = if (emojiContent.startsWith(":") && emojiContent.endsWith(":")) {
                Nip30.parseEmojiTags(rumor.tags)[emojiContent.removeSurrounding(":")]
            } else null
            dmRepo.addReaction(convKey, targetId, DmReaction(rumor.pubkey, emojiContent, rumor.createdAt, emojiUrl))
            return
        }

        // NIP-17 private reply — kind 1 rumor with NIP-10 reply tags.
        // Surface as a normal reply in threads + notifications, flagged as private.
        if (rumor.kind == 1) {
            handlePrivateReply(event, rumor, myPubkey)
            return
        }

        val participants = Nip17.getConversationParticipants(rumor, myPubkey)
        if (participants.any { muteRepo.isBlocked(it) }) return

        val convKey = DmRepository.conversationKey(participants + myPubkey)
        val replyToId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" && it.any { v -> v == "reply" } }?.get(1)
        val rumorId = Nip17.computeRumorId(rumor)
        val emojiMap = Nip30.parseEmojiTags(rumor.tags)
        val fileMetadata = if (Nip17.isFileMessage(rumor)) {
            EncryptedMedia.parseKind15Tags(rumor.tags, rumor.content)
        } else null
        val msg = DmMessage(
            id = "${event.id}:${rumor.createdAt}",
            senderPubkey = rumor.pubkey,
            content = rumor.content,
            createdAt = rumor.createdAt,
            giftWrapId = event.id,
            relayUrls = if (relayUrl.isNotEmpty()) setOf(relayUrl) else emptySet(),
            rumorId = rumorId,
            replyToId = replyToId,
            participants = participants,
            emojiMap = emojiMap,
            encryptedFileMetadata = fileMetadata,
            debugGiftWrapJson = event.toJson(),
            debugRumorJson = Nip17.rumorToJson(rumor)
        )
        dmRepo.addMessage(msg, convKey)
    }

    private fun handlePrivateReplyReaction(rumor: Nip17.Rumor, myPubkey: String) {
        if (muteRepo.isBlocked(rumor.pubkey)) return
        val targetId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
        if (eventRepo.getEvent(targetId) == null) {
            // Without the target rumor cached we can't bind ownership in NotificationRepository,
            // which would surface reactions on threads we never received. Drop quietly — the
            // target arrives via the same kind-1059 subscription, so a refetch will heal.
            Log.d("EventRouter", "Skipping private reaction on uncached target ${targetId.take(12)}")
            return
        }
        val rumorId = Nip17.computeRumorId(rumor)
        val synthetic = NostrEvent(
            id = rumorId,
            pubkey = rumor.pubkey,
            created_at = rumor.createdAt,
            kind = 7,
            tags = rumor.tags,
            content = rumor.content,
            sig = ""
        )
        eventRepo.markPrivate(rumorId)
        eventRepo.addEvent(synthetic)
        if (rumor.pubkey == myPubkey) return
        notifRepo.addEvent(synthetic, myPubkey, source = "gift-wrap-private-reaction")
        if (eventRepo.getProfileData(rumor.pubkey) == null) {
            metadataFetcher.addToPendingProfiles(rumor.pubkey)
        }
    }

    private fun handlePrivateReply(wrap: NostrEvent, rumor: Nip17.Rumor, myPubkey: String) {
        if (muteRepo.isBlocked(rumor.pubkey)) return

        val rumorId = Nip17.computeRumorId(rumor)
        val synthetic = NostrEvent(
            id = rumorId,
            pubkey = rumor.pubkey,
            created_at = rumor.createdAt,
            kind = 1,
            tags = rumor.tags,
            content = rumor.content,
            sig = ""
        )
        eventRepo.markPrivate(rumorId)
        eventRepo.cacheEvent(synthetic)
        if (!Nip10.isStandaloneQuote(synthetic)) {
            val parentId = Nip10.getReplyTarget(synthetic)
            if (parentId != null) eventRepo.addReplyCount(parentId, synthetic.id)
        }
        // Self-copy wraps from another device land here too — skip the notification, but
        // the cache write above still surfaces the reply in our thread view.
        if (rumor.pubkey == myPubkey) return
        notifRepo.addEvent(synthetic, myPubkey, replyToMyEvent = true, source = "gift-wrap-private-reply")
        if (eventRepo.getProfileData(rumor.pubkey) == null) {
            metadataFetcher.addToPendingProfiles(rumor.pubkey)
        }
    }
}
