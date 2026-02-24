package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.Nip10
import com.wisp.app.nostr.Nip65
import com.wisp.app.nostr.LocalSigner
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.ProfileData
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.ExtendedNetworkRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.RelayHintStore
import com.wisp.app.repo.RelayListRepository
import com.wisp.app.relay.SubscriptionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class UserProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    private val _profile = MutableStateFlow<ProfileData?>(null)
    val profile: StateFlow<ProfileData?> = _profile

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing

    private val _rootNotes = MutableStateFlow<List<NostrEvent>>(emptyList())
    val rootNotes: StateFlow<List<NostrEvent>> = _rootNotes

    private val _replies = MutableStateFlow<List<NostrEvent>>(emptyList())
    val replies: StateFlow<List<NostrEvent>> = _replies

    // Track which root notes are reposts: inner event id -> reposter pubkey
    private val _repostAuthors = mutableMapOf<String, String>()
    val repostAuthors: Map<String, String> get() = _repostAuthors

    private val _followList = MutableStateFlow<List<Nip02.FollowEntry>>(emptyList())
    val followList: StateFlow<List<Nip02.FollowEntry>> = _followList

    private val _relayList = MutableStateFlow<List<RelayConfig>>(emptyList())
    val relayList: StateFlow<List<RelayConfig>> = _relayList

    private val _relayHints = MutableStateFlow<Set<String>>(emptySet())
    val relayHints: StateFlow<Set<String>> = _relayHints

    private val _followedBy = MutableStateFlow<List<String>>(emptyList())
    val followedBy: StateFlow<List<String>> = _followedBy

    private val _followProfileVersion = MutableStateFlow(0)
    val followProfileVersion: StateFlow<Int> = _followProfileVersion

    private var targetPubkey: String = ""
    private var eventRepoRef: EventRepository? = null
    private var relayPoolRef: RelayPool? = null
    private var outboxRouterRef: OutboxRouter? = null
    private var subManagerRef: SubscriptionManager? = null
    private var relayHintStoreRef: RelayHintStore? = null
    private val activeEngagementSubIds = mutableListOf<String>()
    private val activeFollowProfileSubIds = mutableListOf<String>()
    private var topRelayUrls: List<String> = emptyList()

    private var isLoadingMoreNotes = false
    private var isLoadingMoreReplies = false
    // Track oldest event timestamps from the target user (kind 1/6) for pagination.
    // rootNotes contains repost inner events with different authors/timestamps,
    // so we track the user's own event timestamps separately.
    private var oldestNoteTimestamp: Long = Long.MAX_VALUE
    private var oldestReplyTimestamp: Long = Long.MAX_VALUE

    companion object {
        private val SUB_IDS = setOf("userprofile", "userposts", "userfollows", "userrelays", "followprofiles")
    }

    fun loadProfile(
        pubkey: String,
        eventRepo: EventRepository,
        contactRepo: ContactRepository,
        relayPool: RelayPool,
        outboxRouter: OutboxRouter? = null,
        relayListRepo: RelayListRepository? = null,
        subManager: SubscriptionManager? = null,
        topRelayUrls: List<String> = emptyList(),
        relayHintStore: RelayHintStore? = null,
        extendedNetworkRepo: ExtendedNetworkRepository? = null
    ) {
        targetPubkey = pubkey
        eventRepoRef = eventRepo
        relayPoolRef = relayPool
        outboxRouterRef = outboxRouter
        subManagerRef = subManager
        relayHintStoreRef = relayHintStore
        this.topRelayUrls = topRelayUrls
        oldestNoteTimestamp = Long.MAX_VALUE
        oldestReplyTimestamp = Long.MAX_VALUE
        _profile.value = eventRepo.getProfileData(pubkey)
        _relayHints.value = relayHintStore?.getHints(pubkey) ?: emptySet()
        _isFollowing.value = contactRepo.isFollowing(pubkey)
        _followedBy.value = extendedNetworkRepo?.getFollowedBy(pubkey)?.toList() ?: emptyList()

        // Close any prior subs (e.g. re-subscribe after relay list discovery)
        closeAllSubs(relayPool)

        // Request relay list for this user
        outboxRouter?.requestMissingRelayLists(listOf(pubkey))

        // Request fresh profile, posts, follow list, and relay list
        val profileFilter = Filter(kinds = listOf(0), authors = listOf(pubkey), limit = 1)
        val postsFilter = Filter(kinds = listOf(1, 6), authors = listOf(pubkey), limit = 50)
        val followFilter = Filter(kinds = listOf(3), authors = listOf(pubkey), limit = 1)
        val relayFilter = Filter(kinds = listOf(10002), authors = listOf(pubkey), limit = 1)

        if (outboxRouter != null) {
            outboxRouter.subscribeToUserWriteRelays("userprofile", pubkey, profileFilter)
            outboxRouter.subscribeToUserWriteRelays("userposts", pubkey, postsFilter)
            outboxRouter.subscribeToUserWriteRelays("userfollows", pubkey, followFilter)
            outboxRouter.subscribeToUserWriteRelays("userrelays", pubkey, relayFilter)
        } else {
            relayPool.sendToAll(ClientMessage.req("userprofile", profileFilter))
            relayPool.sendToAll(ClientMessage.req("userposts", postsFilter))
            relayPool.sendToAll(ClientMessage.req("userfollows", followFilter))
            relayPool.sendToAll(ClientMessage.req("userrelays", relayFilter))
        }
        // Also query top scored relays as safety net
        for (url in topRelayUrls) {
            relayPool.sendToRelayOrEphemeral(url, ClientMessage.req("userposts", postsFilter))
            relayPool.sendToRelayOrEphemeral(url, ClientMessage.req("userprofile", profileFilter))
            relayPool.sendToRelayOrEphemeral(url, ClientMessage.req("userfollows", followFilter))
        }

        // After posts EOSE, subscribe for engagement data
        if (subManager != null) {
            viewModelScope.launch {
                withTimeoutOrNull(15_000) {
                    relayPool.eoseSignals.first { it == "userposts" }
                }
                subscribeEngagementForProfile(relayPool)
            }
        }

        viewModelScope.launch {
            relayPool.relayEvents.collect { (event, relayUrl, subscriptionId) ->
                // Track relay hints for all events we see
                relayHintStore?.addAuthorRelay(event.pubkey, relayUrl)
                relayHintStore?.extractHintsFromTags(event)
                // Refresh hints for the profile being viewed
                if (event.pubkey == pubkey) {
                    _relayHints.value = relayHintStore?.getHints(pubkey) ?: emptySet()
                }
                // Only process events from our own subscriptions
                if (subscriptionId !in SUB_IDS && !subscriptionId.startsWith("followprofiles") && !subscriptionId.startsWith("user-engage") && subscriptionId != "userposts-more" && subscriptionId != "userreplies-more") return@collect

                // Route engagement events — reactions, zaps, reply counts
                if (subscriptionId.startsWith("user-engage")) {
                    when (event.kind) {
                        7 -> eventRepo.addEvent(event)
                        9735 -> eventRepo.addEvent(event)
                        1 -> {
                            val rootId = Nip10.getRootId(event) ?: Nip10.getReplyTarget(event)
                            if (rootId != null) eventRepo.addReplyCount(rootId, event.id)
                        }
                    }
                    return@collect
                }

                if (event.kind == 10002 && event.pubkey == pubkey) {
                    relayListRepo?.updateFromEvent(event)
                }
                if (event.pubkey == pubkey) {
                    when (event.kind) {
                        0 -> {
                            eventRepo.cacheEvent(event)
                            _profile.value = eventRepo.getProfileData(pubkey)
                        }
                        1 -> {
                            eventRepo.cacheEvent(event)
                            if (Nip10.getReplyTarget(event) == null) {
                                if (event.created_at < oldestNoteTimestamp) oldestNoteTimestamp = event.created_at
                                val current = _rootNotes.value.toMutableList()
                                if (current.none { it.id == event.id }) {
                                    current.add(event)
                                    current.sortByDescending { it.created_at }
                                    _rootNotes.value = current
                                }
                            } else {
                                if (event.created_at < oldestReplyTimestamp) oldestReplyTimestamp = event.created_at
                                val current = _replies.value.toMutableList()
                                if (current.none { it.id == event.id }) {
                                    current.add(event)
                                    current.sortByDescending { it.created_at }
                                    _replies.value = current
                                }
                            }
                        }
                        6 -> {
                            eventRepo.cacheEvent(event)
                            if (event.created_at < oldestNoteTimestamp) oldestNoteTimestamp = event.created_at
                            // Show the reposted event in profile's root notes
                            if (event.content.isNotBlank()) {
                                try {
                                    val inner = NostrEvent.fromJson(event.content)
                                    eventRepo.cacheEvent(inner)
                                    _repostAuthors[inner.id] = event.pubkey
                                    val current = _rootNotes.value.toMutableList()
                                    if (current.none { it.id == inner.id }) {
                                        current.add(inner)
                                        current.sortByDescending { it.created_at }
                                        _rootNotes.value = current
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        3 -> {
                            val follows = Nip02.parseFollowList(event)
                            _followList.value = follows
                            // Request profiles for followed users we haven't cached
                            val uncached = follows
                                .map { it.pubkey }
                                .filter { eventRepo.getProfileData(it) == null }
                            if (uncached.isNotEmpty()) {
                                // Close any prior follow profile subs
                                for (subId in activeFollowProfileSubIds) relayPool.closeOnAllRelays(subId)
                                activeFollowProfileSubIds.clear()
                                // Chunk into batches to stay within relay filter limits
                                val batches = uncached.chunked(50)
                                batches.forEachIndexed { index, batch ->
                                    val subId = if (index == 0) "followprofiles" else "followprofiles-$index"
                                    activeFollowProfileSubIds.add(subId)
                                    val profileReq = Filter(
                                        kinds = listOf(0),
                                        authors = batch,
                                        limit = batch.size
                                    )
                                    relayPool.sendToAll(
                                        ClientMessage.req(subId, profileReq)
                                    )
                                }
                                // Close all followprofiles subs after EOSE or timeout
                                viewModelScope.launch {
                                    withTimeoutOrNull(15_000) {
                                        relayPool.eoseSignals.first { it == "followprofiles" }
                                    }
                                    for (subId in activeFollowProfileSubIds) {
                                        relayPool.closeOnAllRelays(subId)
                                    }
                                    activeFollowProfileSubIds.clear()
                                }
                            }
                        }
                        10002 -> {
                            _relayList.value = Nip65.parseRelayList(event)
                            // Re-subscribe now that we know the user's write relays
                            outboxRouterRef?.let { router ->
                                // Close old subs before re-subscribing
                                relayPool.closeOnAllRelays("userposts")
                                relayPool.closeOnAllRelays("userprofile")
                                relayPool.closeOnAllRelays("userfollows")
                                router.subscribeToUserWriteRelays("userposts", pubkey, postsFilter)
                                router.subscribeToUserWriteRelays("userprofile", pubkey, profileFilter)
                                router.subscribeToUserWriteRelays("userfollows", pubkey, followFilter)
                            }
                        }
                    }
                } else if (event.kind == 0) {
                    // Profile for a followed user
                    eventRepo.cacheEvent(event)
                    _followProfileVersion.value++
                }
            }
        }
    }

    private fun subscribeEngagementForProfile(relayPool: RelayPool) {
        for (subId in activeEngagementSubIds) relayPool.closeOnAllRelays(subId)
        activeEngagementSubIds.clear()

        val eventIds = (_rootNotes.value.map { it.id } + _replies.value.map { it.id }).distinct()
        if (eventIds.isEmpty()) return

        // All events belong to targetPubkey — route to their inbox relays
        val router = outboxRouterRef
        if (router != null) {
            val eventsByAuthor = mapOf(targetPubkey to eventIds)
            router.subscribeEngagementByAuthors("user-engage", eventsByAuthor, activeEngagementSubIds)
        } else {
            // Fallback: no router available, use read relays
            eventIds.chunked(50).forEachIndexed { index, batch ->
                val subId = if (index == 0) "user-engage" else "user-engage-$index"
                activeEngagementSubIds.add(subId)
                val filters = listOf(
                    Filter(kinds = listOf(7), eTags = batch),
                    Filter(kinds = listOf(9735), eTags = batch),
                    Filter(kinds = listOf(1), eTags = batch)
                )
                relayPool.sendToReadRelays(ClientMessage.req(subId, filters))
            }
        }
        // Also query top scored relays for engagement
        eventIds.chunked(50).forEachIndexed { index, batch ->
            val subId = "user-engage-top-$index"
            activeEngagementSubIds.add(subId)
            val filters = listOf(
                Filter(kinds = listOf(7), eTags = batch),
                Filter(kinds = listOf(9735), eTags = batch),
                Filter(kinds = listOf(1), eTags = batch)
            )
            for (url in topRelayUrls) {
                relayPool.sendToRelayOrEphemeral(url, ClientMessage.req(subId, filters))
            }
        }
    }

    private fun closeAllSubs(relayPool: RelayPool) {
        for (subId in SUB_IDS) {
            relayPool.closeOnAllRelays(subId)
        }
        relayPool.closeOnAllRelays("userposts-more")
        relayPool.closeOnAllRelays("userreplies-more")
        for (subId in activeFollowProfileSubIds) {
            relayPool.closeOnAllRelays(subId)
        }
        activeFollowProfileSubIds.clear()
        for (subId in activeEngagementSubIds) {
            relayPool.closeOnAllRelays(subId)
        }
        activeEngagementSubIds.clear()
    }

    override fun onCleared() {
        super.onCleared()
        relayPoolRef?.let { closeAllSubs(it) }
    }

    fun loadMoreNotes() {
        if (isLoadingMoreNotes) return
        isLoadingMoreNotes = true
        if (oldestNoteTimestamp == Long.MAX_VALUE) { isLoadingMoreNotes = false; return }

        val pool = relayPoolRef ?: run { isLoadingMoreNotes = false; return }
        val filter = Filter(kinds = listOf(1, 6), authors = listOf(targetPubkey), until = oldestNoteTimestamp - 1, limit = 50)

        val router = outboxRouterRef
        if (router != null) {
            router.subscribeToUserWriteRelays("userposts-more", targetPubkey, filter)
        } else {
            pool.sendToAll(ClientMessage.req("userposts-more", filter))
        }
        for (url in topRelayUrls) {
            pool.sendToRelayOrEphemeral(url, ClientMessage.req("userposts-more", filter))
        }

        viewModelScope.launch {
            withTimeoutOrNull(10_000) {
                pool.eoseSignals.first { it == "userposts-more" }
            }
            pool.closeOnAllRelays("userposts-more")
            isLoadingMoreNotes = false
        }
    }

    fun loadMoreReplies() {
        if (isLoadingMoreReplies) return
        isLoadingMoreReplies = true
        if (oldestReplyTimestamp == Long.MAX_VALUE) { isLoadingMoreReplies = false; return }

        val pool = relayPoolRef ?: run { isLoadingMoreReplies = false; return }
        val filter = Filter(kinds = listOf(1), authors = listOf(targetPubkey), until = oldestReplyTimestamp - 1, limit = 50)

        val router = outboxRouterRef
        if (router != null) {
            router.subscribeToUserWriteRelays("userreplies-more", targetPubkey, filter)
        } else {
            pool.sendToAll(ClientMessage.req("userreplies-more", filter))
        }
        for (url in topRelayUrls) {
            pool.sendToRelayOrEphemeral(url, ClientMessage.req("userreplies-more", filter))
        }

        viewModelScope.launch {
            withTimeoutOrNull(10_000) {
                pool.eoseSignals.first { it == "userreplies-more" }
            }
            pool.closeOnAllRelays("userreplies-more")
            isLoadingMoreReplies = false
        }
    }

    fun toggleFollow(
        contactRepo: ContactRepository,
        relayPool: RelayPool,
        signer: NostrSigner? = null
    ) {
        val s = signer ?: keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) } ?: return
        val currentList = contactRepo.getFollowList()
        val newList = if (contactRepo.isFollowing(targetPubkey)) {
            Nip02.removeFollow(currentList, targetPubkey)
        } else {
            Nip02.addFollow(currentList, targetPubkey)
        }

        val tags = Nip02.buildFollowTags(newList)
        viewModelScope.launch {
            val event = s.signEvent(kind = 3, content = "", tags = tags)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            contactRepo.updateFromEvent(event)
            _isFollowing.value = contactRepo.isFollowing(targetPubkey)
        }
    }
}
