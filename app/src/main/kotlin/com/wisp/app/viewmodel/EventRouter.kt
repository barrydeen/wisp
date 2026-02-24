package com.wisp.app.viewmodel

import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.Nip65
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayScoreBoard
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.BookmarkRepository
import com.wisp.app.repo.BookmarkSetRepository
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ExtendedNetworkRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.PinRepository
import com.wisp.app.repo.RelayHintStore
import com.wisp.app.repo.RelayListRepository

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
    private val bookmarkSetRepo: BookmarkSetRepository,
    private val pinRepo: PinRepository,
    private val blossomRepo: BlossomRepository,
    private val customEmojiRepo: CustomEmojiRepository,
    private val relayListRepo: RelayListRepository,
    private val relayScoreBoard: RelayScoreBoard,
    private val relayHintStore: RelayHintStore,
    private val keyRepo: KeyRepository,
    private val extendedNetworkRepo: ExtendedNetworkRepository,
    private val metadataFetcher: MetadataFetcher,
    private val getUserPubkey: () -> String?,
    private val getSigner: () -> NostrSigner?,
    private val getFeedSubId: () -> String,
    private val onRelayFeedEventReceived: () -> Unit
) {
    suspend fun processRelayEvent(event: NostrEvent, relayUrl: String, subscriptionId: String) {
        if (subscriptionId == "notif") {
            if (muteRepo.isBlocked(event.pubkey)) return
            val myPubkey = getUserPubkey()
            if (myPubkey != null) {
                when (event.kind) {
                    6 -> eventRepo.addEvent(event)
                    7, 9735 -> eventRepo.addEvent(event)
                    1 -> {
                        eventRepo.cacheEvent(event)
                        val rootId = Nip10.getRootId(event) ?: Nip10.getReplyTarget(event)
                        if (rootId != null) eventRepo.addReplyCount(rootId, event.id)
                    }
                    else -> eventRepo.cacheEvent(event)
                }
                notifRepo.addEvent(event, myPubkey)
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
                if (event.kind == 9735) {
                    val zapperPubkey = Nip57.getZapperPubkey(event)
                    if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                        metadataFetcher.addToPendingProfiles(zapperPubkey)
                    }
                }
            }
        } else if (subscriptionId.startsWith("quote-")) {
            eventRepo.cacheEvent(event)
            if (event.kind == 1 && eventRepo.getProfileData(event.pubkey) == null) {
                metadataFetcher.addToPendingProfiles(event.pubkey)
            }
        } else if (subscriptionId.startsWith("reply-count-")) {
            if (event.kind == 1) {
                eventRepo.cacheEvent(event)
                val rootId = Nip10.getRootId(event) ?: Nip10.getReplyTarget(event)
                if (rootId != null) eventRepo.addReplyCount(rootId, event.id)
            }
        } else if (subscriptionId.startsWith("zap-count-") || subscriptionId.startsWith("zap-rcpt-")) {
            if (event.kind == 9735) {
                eventRepo.addEvent(event)
                val zapperPubkey = Nip57.getZapperPubkey(event)
                if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                    metadataFetcher.addToPendingProfiles(zapperPubkey)
                }
            }
        } else if (subscriptionId == "thread-root" || subscriptionId == "thread-replies" ||
                   subscriptionId.startsWith("thread-reactions")) {
            // ThreadViewModel handles these via its own RelayPool collector — skip entirely
            return
        } else if (subscriptionId.startsWith("engage") || subscriptionId.startsWith("user-engage")) {
            when (event.kind) {
                7 -> eventRepo.addEvent(event)
                9735 -> {
                    eventRepo.addEvent(event)
                    val zapperPubkey = Nip57.getZapperPubkey(event)
                    if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                        metadataFetcher.addToPendingProfiles(zapperPubkey)
                    }
                }
                1 -> {
                    eventRepo.cacheEvent(event)
                    val rootId = Nip10.getRootId(event) ?: Nip10.getReplyTarget(event)
                    if (rootId != null) eventRepo.addReplyCount(rootId, event.id)
                }
            }
            // Engagement events win the dedup race against "notif" subscription,
            // so also route notification-eligible events to notifRepo here
            val myPubkey = getUserPubkey()
            if (myPubkey != null && event.pubkey != myPubkey &&
                event.kind in intArrayOf(1, 6, 7, 9735)) {
                notifRepo.addEvent(event, myPubkey)
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
            }
        } else if (subscriptionId.startsWith("extnet-k3-")) {
            // Extended network discovery: kind 3 follow lists — route to repo, NOT feed
            if (event.kind == 3) extendedNetworkRepo.processFollowListEvent(event)
        } else if (subscriptionId.startsWith("extnet-rl-")) {
            // Extended network discovery: relay lists — update relay list cache
            if (event.kind == 10002) relayListRepo.updateFromEvent(event)
        } else if (subscriptionId.startsWith("onb-")) {
            // Onboarding suggestion fetches — only cache kind 0 profiles, don't add to feed
            if (event.kind == 0) eventRepo.cacheEvent(event)
        } else {
            if (event.kind == 10002) {
                relayListRepo.updateFromEvent(event)
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    val relays = Nip65.parseRelayList(event)
                    if (relays.isNotEmpty()) {
                        keyRepo.saveRelays(relays)
                        relayPool.updateRelays(relays)
                    }
                }
            }
            if (event.kind == Nip51.KIND_DM_RELAYS) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    val urls = Nip51.parseRelaySet(event)
                    keyRepo.saveDmRelays(urls)
                    relayPool.updateDmRelays(urls)
                }
            }
            if (event.kind == Nip51.KIND_SEARCH_RELAYS) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
                    keyRepo.saveSearchRelays(Nip51.parseRelaySet(event))
                }
            }
            if (event.kind == Nip51.KIND_BLOCKED_RELAYS) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) {
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
            if (event.kind == Nip51.KIND_PIN_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) pinRepo.loadFromEvent(event)
            }
            if (event.kind == Blossom.KIND_SERVER_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) blossomRepo.updateFromEvent(event)
            }
            if (event.kind == Nip51.KIND_FOLLOW_SET) listRepo.updateFromEvent(event)
            if (event.kind == Nip51.KIND_BOOKMARK_SET) bookmarkSetRepo.updateFromEvent(event)
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
                    if (url.startsWith("wss://") && !relayListRepo.hasRelayList(tag[1])) {
                        relayScoreBoard.addHintRelays(tag[1], listOf(url))
                    }
                }
            }

            val feedSubId = getFeedSubId()
            val isFeedSub = subscriptionId == feedSubId ||
                subscriptionId == "loadmore" ||
                subscriptionId == "feed-backfill"
            if (isFeedSub) {
                eventRepo.addEvent(event)
                onRelayFeedEventReceived()
                if (event.kind == 1) eventRepo.addEventRelay(event.id, relayUrl)
                if (event.kind == 1) {
                    metadataFetcher.fetchQuotedEvents(event)
                    if (eventRepo.getProfileData(event.pubkey) == null) {
                        metadataFetcher.addToPendingProfiles(event.pubkey)
                    }
                }
                if (event.kind == 6 && event.content.isNotBlank()) {
                    // Fetch profile for the reposted event's original author
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
            }
            // Always handle follow list updates (from self-data subscription)
            if (event.kind == 3) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) contactRepo.updateFromEvent(event)
            }
        }
    }
}
