package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.Blossom
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.Relay
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayScoreBoard
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.repo.NwcRepository
import com.wisp.app.repo.RelayInfoRepository
import com.wisp.app.repo.RelayListRepository
import com.wisp.app.repo.ZapSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class FeedType { FOLLOWS, RELAY, LIST }

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    val relayPool = RelayPool()
    val profileRepo = ProfileRepository(app)
    val muteRepo = MuteRepository(app)
    val eventRepo = EventRepository(profileRepo, muteRepo)
    val keyRepo = KeyRepository(app)
    val contactRepo = ContactRepository(app)
    val listRepo = ListRepository(app)
    val dmRepo = DmRepository()
    val notifRepo = NotificationRepository()
    val relayListRepo = RelayListRepository(app)
    val blossomRepo = BlossomRepository(app)
    val relayInfoRepo = RelayInfoRepository()
    val relayScoreBoard = RelayScoreBoard(app, relayListRepo, contactRepo)
    val outboxRouter = OutboxRouter(relayPool, relayListRepo, relayScoreBoard)
    val subManager = SubscriptionManager(relayPool)
    private val processingDispatcher = Dispatchers.Default

    val metadataFetcher = MetadataFetcher(
        relayPool, outboxRouter, subManager, profileRepo, eventRepo,
        viewModelScope, processingDispatcher
    )

    private var feedSubId = "feed"
    private var isLoadingMore = false
    private val activeReactionSubIds = mutableListOf<String>()
    private val activeZapSubIds = mutableListOf<String>()

    val nwcRepo = NwcRepository(app)
    val zapSender = ZapSender(keyRepo, nwcRepo, relayPool, Relay.createClient())

    private val _zapInProgress = MutableStateFlow<Set<String>>(emptySet())
    val zapInProgress: StateFlow<Set<String>> = _zapInProgress

    private val _zapSuccess = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val zapSuccess: SharedFlow<String> = _zapSuccess

    private val _zapError = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val zapError: SharedFlow<String> = _zapError

    val feed: StateFlow<List<NostrEvent>> = eventRepo.feed
    val newNoteCount: StateFlow<Int> = eventRepo.newNoteCount

    private val _initialLoadDone = MutableStateFlow(false)
    val initialLoadDone: StateFlow<Boolean> = _initialLoadDone

    private val _feedType = MutableStateFlow(FeedType.FOLLOWS)
    val feedType: StateFlow<FeedType> = _feedType

    private val _selectedRelay = MutableStateFlow<String?>(null)
    val selectedRelay: StateFlow<String?> = _selectedRelay

    val selectedList: StateFlow<com.wisp.app.nostr.FollowSet?> = listRepo.selectedList

    fun getUserPubkey(): String? = keyRepo.getKeypair()?.pubkey?.toHex()

    fun resetNewNoteCount() = eventRepo.resetNewNoteCount()

    fun queueProfileFetch(pubkey: String) = metadataFetcher.queueProfileFetch(pubkey)

    private var relaysInitialized = false

    fun initRelays() {
        if (relaysInitialized) return
        relaysInitialized = true
        relayPool.updateBlockedUrls(keyRepo.getBlockedRelays())
        val relays = keyRepo.getRelays()
        relayPool.updateRelays(relays)
        relayPool.updateDmRelays(keyRepo.getDmRelays())

        viewModelScope.launch { relayInfoRepo.prefetchAll(relays.map { it.url }) }

        // Main event processing loop — runs on Default dispatcher to keep UI thread free
        viewModelScope.launch(processingDispatcher) {
            relayPool.relayEvents.collect { (event, relayUrl, subscriptionId) ->
                processRelayEvent(event, relayUrl, subscriptionId)
            }
        }

        // Handle EOSE for relay-lists subscription: recompute scoreboard, update relays, re-route feed
        viewModelScope.launch {
            subManager.awaitEoseWithTimeout("relay-lists")
            subManager.closeSubscription("relay-lists")
            recomputeAndMergeRelays()
            resubscribeFeed()
        }

        // Periodic profile & quote sweep — runs on Default dispatcher
        viewModelScope.launch(processingDispatcher) {
            delay(5_000)
            metadataFetcher.sweepMissingProfiles()
            repeat(7) {
                delay(15_000)
                metadataFetcher.sweepMissingProfiles()
            }
            while (true) {
                delay(60_000)
                metadataFetcher.sweepMissingProfiles()
            }
        }

        // Periodic ephemeral relay cleanup + seen event trimming
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                relayPool.cleanupEphemeralRelays()
                eventRepo.trimSeenEvents()
            }
        }

        // Periodic relay list refresh (every 30 minutes)
        viewModelScope.launch {
            while (true) {
                delay(30 * 60 * 1000L)
                fetchRelayListsForFollows()
                delay(15_000)
                recomputeAndMergeRelays()
            }
        }

        getUserPubkey()?.let { listRepo.setOwner(it) }

        subscribeFeed()
        fetchRelayListsForFollows()
        metadataFetcher.fetchProfilesForFollows(contactRepo.getFollowList().map { it.pubkey })
    }

    private fun processRelayEvent(event: NostrEvent, relayUrl: String, subscriptionId: String) {
        if (subscriptionId == "notif") {
            val myPubkey = getUserPubkey()
            if (myPubkey != null) {
                notifRepo.addEvent(event, myPubkey)
                if (eventRepo.getProfileData(event.pubkey) == null) {
                    metadataFetcher.addToPendingProfiles(event.pubkey)
                }
                if (event.kind == 9735) {
                    val zapperPubkey = event.tags.firstOrNull { it.size >= 2 && it[0] == "P" }?.get(1)
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
                val targetId = Nip10.getReplyTarget(event)
                if (targetId != null) eventRepo.addReplyCount(targetId)
            }
        } else if (subscriptionId.startsWith("zap-count-") || subscriptionId == "zaps") {
            if (event.kind == 9735) eventRepo.addEvent(event)
        } else {
            if (event.kind == 10002) relayListRepo.updateFromEvent(event)
            if (event.kind == Nip51.KIND_MUTE_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) muteRepo.loadFromEvent(event)
            }
            if (event.kind == Blossom.KIND_SERVER_LIST) {
                val myPubkey = getUserPubkey()
                if (myPubkey != null && event.pubkey == myPubkey) blossomRepo.updateFromEvent(event)
            }
            if (event.kind == Nip51.KIND_FOLLOW_SET) listRepo.updateFromEvent(event)
            eventRepo.addEvent(event)
            if (event.kind == 1) eventRepo.addEventRelay(event.id, relayUrl)
            when (event.kind) {
                1 -> {
                    metadataFetcher.fetchQuotedEvents(event)
                    metadataFetcher.addToPendingReplyCounts(event.id)
                    metadataFetcher.addToPendingZapCounts(event.id)
                    if (eventRepo.getProfileData(event.pubkey) == null) {
                        metadataFetcher.addToPendingProfiles(event.pubkey)
                    }
                }
                3 -> {
                    val myPubkey = getUserPubkey()
                    if (myPubkey != null && event.pubkey == myPubkey) contactRepo.updateFromEvent(event)
                }
            }
        }
    }

    private fun fetchRelayListsForFollows() {
        val authors = contactRepo.getFollowList().map { it.pubkey }
        if (authors.isEmpty()) return
        outboxRouter.requestMissingRelayLists(authors)
    }

    private fun recomputeAndMergeRelays() {
        relayScoreBoard.recompute()
        if (!relayScoreBoard.hasScoredRelays()) return

        val pinnedRelays = keyRepo.getRelays()
        val pinnedUrls = pinnedRelays.map { it.url }.toSet()
        val scoredConfigs = relayScoreBoard.getScoredRelayConfigs()
            .filter { it.url !in pinnedUrls }
        val merged = pinnedRelays + scoredConfigs
        relayPool.updateRelays(merged)
    }

    fun setFeedType(type: FeedType) {
        _feedType.value = type
        eventRepo.clearFeed()
        resubscribeFeed()
    }

    fun setSelectedRelay(url: String) {
        _selectedRelay.value = url
        if (_feedType.value == FeedType.RELAY) {
            eventRepo.clearFeed()
            resubscribeFeed()
        }
    }

    fun getRelayUrls(): List<String> = relayPool.getRelayUrls()

    fun onAppResume() {
        if (!relaysInitialized) return
        subscribeFeed()
        fetchRelayListsForFollows()
    }

    private fun subscribeFeed() {
        resubscribeFeed()

        val myPubkey = getUserPubkey()
        if (myPubkey != null) {
            val selfDataFilters = listOf(
                Filter(kinds = listOf(0), authors = listOf(myPubkey), limit = 1),
                Filter(kinds = listOf(3), authors = listOf(myPubkey), limit = 1),
                Filter(kinds = listOf(Nip51.KIND_MUTE_LIST), authors = listOf(myPubkey), limit = 1),
                Filter(kinds = listOf(Blossom.KIND_SERVER_LIST), authors = listOf(myPubkey), limit = 1),
                Filter(kinds = listOf(Nip51.KIND_FOLLOW_SET), authors = listOf(myPubkey), limit = 50)
            )
            relayPool.sendToAll(ClientMessage.req("self-data", selfDataFilters))
            viewModelScope.launch {
                subManager.awaitEoseWithTimeout("self-data")
                subManager.closeSubscription("self-data")
            }

            val dmFilter = Filter(kinds = listOf(1059), pTags = listOf(myPubkey))
            relayPool.sendToAll(ClientMessage.req("dms", dmFilter))

            val notifFilter = Filter(
                kinds = listOf(1, 7, 9735),
                pTags = listOf(myPubkey),
                limit = 100
            )
            relayPool.sendToAll(ClientMessage.req("notif", notifFilter))
        }
    }

    private var feedEoseJob: Job? = null

    private fun resubscribeFeed() {
        relayPool.closeOnAllRelays(feedSubId)
        for (subId in activeReactionSubIds) relayPool.closeOnAllRelays(subId)
        activeReactionSubIds.clear()
        for (subId in activeZapSubIds) relayPool.closeOnAllRelays(subId)
        activeZapSubIds.clear()
        relayPool.clearSeenEvents()
        eventRepo.countNewNotes = false
        feedEoseJob?.cancel()

        val targetedRelays: Set<String> = when (_feedType.value) {
            FeedType.LIST -> {
                val list = listRepo.selectedList.value ?: return
                val authors = list.members.toList()
                if (authors.isEmpty()) return
                val notesFilter = Filter(kinds = listOf(1, 6), limit = 100)
                outboxRouter.subscribeByAuthors(feedSubId, authors, notesFilter)
            }
            FeedType.FOLLOWS -> {
                val authors = contactRepo.getFollowList().map { it.pubkey }
                if (authors.isEmpty()) return
                val notesFilter = Filter(kinds = listOf(1, 6), limit = 100)
                outboxRouter.subscribeByAuthors(feedSubId, authors, notesFilter)
            }
            FeedType.RELAY -> {
                val url = _selectedRelay.value ?: return
                val filter = Filter(kinds = listOf(1, 6), limit = 100)
                val msg = ClientMessage.req(feedSubId, filter)
                relayPool.sendToRelay(url, msg)
                setOf(url)
            }
        }

        feedEoseJob = viewModelScope.launch {
            subManager.awaitEoseCount(feedSubId, targetedRelays.size.coerceAtLeast(1))
            eventRepo.countNewNotes = true
            _initialLoadDone.value = true

            subscribeReactionsForFeed()
            subscribeZapsForFeed()

            withContext(processingDispatcher) {
                metadataFetcher.sweepMissingProfiles()
            }
        }
    }

    private fun subscribeReactionsForFeed() {
        for (subId in activeReactionSubIds) relayPool.closeOnAllRelays(subId)
        activeReactionSubIds.clear()

        val feedEvents = eventRepo.feed.value
        if (feedEvents.isEmpty()) return
        val eventIds = feedEvents.map { it.id }
        eventIds.chunked(50).forEachIndexed { index, batch ->
            val subId = if (index == 0) "reactions" else "reactions-$index"
            activeReactionSubIds.add(subId)
            val reactFilter = Filter(kinds = listOf(7), eTags = batch)
            relayPool.sendToAll(ClientMessage.req(subId, reactFilter))
        }
    }

    private fun subscribeZapsForFeed() {
        for (subId in activeZapSubIds) relayPool.closeOnAllRelays(subId)
        activeZapSubIds.clear()

        val feedEvents = eventRepo.feed.value
        if (feedEvents.isEmpty()) return
        val eventIds = feedEvents.map { it.id }
        eventIds.chunked(50).forEachIndexed { index, batch ->
            val subId = if (index == 0) "zaps" else "zaps-$index"
            activeZapSubIds.add(subId)
            val zapFilter = Filter(kinds = listOf(9735), eTags = batch)
            relayPool.sendToAll(ClientMessage.req(subId, zapFilter))
        }
    }

    fun toggleFollow(pubkey: String) {
        val keypair = keyRepo.getKeypair() ?: return
        val currentList = contactRepo.getFollowList()
        val newList = if (contactRepo.isFollowing(pubkey)) {
            Nip02.removeFollow(currentList, pubkey)
        } else {
            Nip02.addFollow(currentList, pubkey)
        }
        val tags = Nip02.buildFollowTags(newList)
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = 3,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
        contactRepo.updateFromEvent(event)
    }

    fun blockUser(pubkey: String) {
        muteRepo.blockUser(pubkey)
        eventRepo.purgeUser(pubkey)
        publishMuteList()
    }

    fun unblockUser(pubkey: String) {
        muteRepo.unblockUser(pubkey)
        publishMuteList()
    }

    fun updateMutedWords() {
        publishMuteList()
    }

    private fun publishMuteList() {
        val keypair = keyRepo.getKeypair() ?: return
        val tags = Nip51.buildMuteListTags(muteRepo.getBlockedPubkeys(), muteRepo.getMutedWords())
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Nip51.KIND_MUTE_LIST,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
    }

    fun sendRepost(event: NostrEvent) {
        val keypair = keyRepo.getKeypair() ?: return
        viewModelScope.launch {
            try {
                val tags = com.wisp.app.nostr.Nip18.buildRepostTags(event)
                val repostEvent = NostrEvent.create(
                    privkey = keypair.privkey,
                    pubkey = keypair.pubkey,
                    kind = 6,
                    content = event.toJson(),
                    tags = tags
                )
                val msg = ClientMessage.event(repostEvent)
                outboxRouter.publishToInbox(msg, event.pubkey)
                eventRepo.addEvent(repostEvent)
            } catch (_: Exception) {}
        }
    }

    fun sendReaction(event: NostrEvent, content: String = "+") {
        val keypair = keyRepo.getKeypair() ?: return
        viewModelScope.launch {
            try {
                val tags = com.wisp.app.nostr.Nip25.buildReactionTags(event)
                val reactionEvent = NostrEvent.create(
                    privkey = keypair.privkey,
                    pubkey = keypair.pubkey,
                    kind = 7,
                    content = content,
                    tags = tags
                )
                val msg = ClientMessage.event(reactionEvent)
                outboxRouter.publishToInbox(msg, event.pubkey)
                eventRepo.addEvent(reactionEvent)
            } catch (_: Exception) {}
        }
    }

    fun loadMore() {
        if (isLoadingMore) return
        isLoadingMore = true
        val oldest = eventRepo.getOldestTimestamp() ?: run { isLoadingMore = false; return }

        when (_feedType.value) {
            FeedType.FOLLOWS -> {
                val authors = contactRepo.getFollowList().map { it.pubkey }
                if (authors.isEmpty()) { isLoadingMore = false; return }
                val templateFilter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                outboxRouter.subscribeByAuthors("loadmore", authors, templateFilter)
            }
            FeedType.RELAY -> {
                val url = _selectedRelay.value
                if (url != null) {
                    val filter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                    relayPool.sendToRelay(url, ClientMessage.req("loadmore", filter))
                } else { isLoadingMore = false; return }
            }
            FeedType.LIST -> {
                val list = listRepo.selectedList.value ?: run { isLoadingMore = false; return }
                val authors = list.members.toList()
                if (authors.isEmpty()) { isLoadingMore = false; return }
                val templateFilter = Filter(kinds = listOf(1, 6), until = oldest - 1, limit = 50)
                outboxRouter.subscribeByAuthors("loadmore", authors, templateFilter)
            }
        }

        viewModelScope.launch {
            subManager.awaitEoseWithTimeout("loadmore")
            subManager.closeSubscription("loadmore")
            isLoadingMore = false
        }
    }

    fun initNwc() {
        if (nwcRepo.hasConnection()) {
            nwcRepo.connect()
        }
    }

    fun sendZap(event: NostrEvent, amountMsats: Long, message: String = "") {
        val profileData = eventRepo.getProfileData(event.pubkey)
        val lud16 = profileData?.lud16
        if (lud16.isNullOrBlank()) {
            _zapError.tryEmit("This user has no lightning address")
            return
        }
        viewModelScope.launch {
            _zapInProgress.value = _zapInProgress.value + event.id
            val result = zapSender.sendZap(
                recipientLud16 = lud16,
                recipientPubkey = event.pubkey,
                eventId = event.id,
                amountMsats = amountMsats,
                message = message
            )
            _zapInProgress.value = _zapInProgress.value - event.id
            result.fold(
                onSuccess = {
                    eventRepo.addZapSats(event.id, amountMsats / 1000)
                    _zapSuccess.tryEmit(event.id)
                },
                onFailure = { e ->
                    _zapError.tryEmit(e.message ?: "Zap failed")
                }
            )
        }
    }

    fun setSelectedList(followSet: com.wisp.app.nostr.FollowSet) {
        listRepo.selectList(followSet)
        if (_feedType.value == FeedType.LIST) {
            eventRepo.clearFeed()
            resubscribeFeed()
        }
    }

    fun createList(name: String) {
        val keypair = keyRepo.getKeypair() ?: return
        val dTag = name.trim().lowercase().replace(Regex("[^a-z0-9-_]"), "-")
        val tags = Nip51.buildFollowSetTags(dTag, emptySet())
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Nip51.KIND_FOLLOW_SET,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
        listRepo.updateFromEvent(event)
    }

    fun addToList(dTag: String, pubkey: String) {
        val keypair = keyRepo.getKeypair() ?: return
        val myPubkey = keypair.pubkey.toHex()
        val existing = listRepo.getList(myPubkey, dTag) ?: return
        val newMembers = existing.members + pubkey
        val tags = Nip51.buildFollowSetTags(dTag, newMembers)
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Nip51.KIND_FOLLOW_SET,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
        listRepo.updateFromEvent(event)
    }

    fun removeFromList(dTag: String, pubkey: String) {
        val keypair = keyRepo.getKeypair() ?: return
        val myPubkey = keypair.pubkey.toHex()
        val existing = listRepo.getList(myPubkey, dTag) ?: return
        val newMembers = existing.members - pubkey
        val tags = Nip51.buildFollowSetTags(dTag, newMembers)
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Nip51.KIND_FOLLOW_SET,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
        listRepo.updateFromEvent(event)
    }

    fun deleteList(dTag: String) {
        val keypair = keyRepo.getKeypair() ?: return
        val tags = Nip51.buildFollowSetTags(dTag, emptySet())
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = Nip51.KIND_FOLLOW_SET,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
        listRepo.updateFromEvent(event)
        val sel = listRepo.selectedList.value
        if (sel != null && sel.dTag == dTag) {
            listRepo.selectList(null)
        }
    }

    fun fetchUserLists(pubkey: String) {
        val subId = "user-lists-${pubkey.take(8)}"
        val filter = Filter(kinds = listOf(Nip51.KIND_FOLLOW_SET), authors = listOf(pubkey), limit = 50)
        relayPool.sendToAll(ClientMessage.req(subId, filter))
        viewModelScope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
        }
    }

    fun refreshRelays() {
        relayPool.updateBlockedUrls(keyRepo.getBlockedRelays())
        val relays = keyRepo.getRelays()
        relayPool.updateRelays(relays)
        relayPool.updateDmRelays(keyRepo.getDmRelays())
        subscribeFeed()
    }

    override fun onCleared() {
        super.onCleared()
        nwcRepo.disconnect()
        relayPool.disconnectAll()
    }
}
