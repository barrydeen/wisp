package com.wisp.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip29
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.SimpleGroupEntry
import com.wisp.app.relay.PublishResult
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.GroupMessage
import com.wisp.app.repo.GroupPreview
import com.wisp.app.repo.GroupRepository
import com.wisp.app.repo.GroupRoom
import com.wisp.app.repo.NotificationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class GroupListViewModel(app: Application) : AndroidViewModel(app) {

    private var groupRepo: GroupRepository? = null
    private var relayPool: RelayPool? = null
    private var eventRepo: EventRepository? = null
    private var notifRepo: NotificationRepository? = null
    private var myPubkey: String? = null

    /** Groups that currently have an open relay connection and active subscriptions. */
    private val subscribedGroups = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Short-lived cache of preview data (metadata + members) for groups not yet joined locally.
     *  Populated by GroupInviteCard fetches on the feed so that tapping through to GroupRoomScreen
     *  gets a cache hit instead of a second relay round-trip (which often times out). */
    private val previewCache = java.util.concurrent.ConcurrentHashMap<String, GroupPreview>()

    data class DiscoveredGroup(
        val relayUrl: String,
        val metadata: Nip29.GroupMetadata,
        val members: List<String> = emptyList()
    )

    private val _discoveredGroups = MutableStateFlow<List<DiscoveredGroup>>(emptyList())
    val discoveredGroups: StateFlow<List<DiscoveredGroup>> = _discoveredGroups

    private val _discoveryLoading = MutableStateFlow(false)
    val discoveryLoading: StateFlow<Boolean> = _discoveryLoading
    private var discoverGen = 0

    /** One-shot join-rejection signal — the UI listens and surfaces a dialog. */
    data class JoinError(val relayUrl: String, val groupId: String, val message: String)
    private val _joinErrors = MutableSharedFlow<JoinError>(extraBufferCapacity = 8)
    val joinErrors: SharedFlow<JoinError> = _joinErrors

    /** One-shot admin-action error signal — UI can toast. Covers silent failures of 9007/9009/9002
     *  etc. that previously got lost because the publish was fire-and-forget. */
    data class AdminError(val relayUrl: String, val action: String, val message: String)
    private val _adminErrors = MutableSharedFlow<AdminError>(extraBufferCapacity = 8)
    val adminErrors: SharedFlow<AdminError> = _adminErrors

    val groups: StateFlow<List<GroupRoom>>
        get() = groupRepo?.joinedGroups ?: MutableStateFlow(emptyList())

    /** Clears all refs so init() can run again after an account switch. */
    fun reset() {
        subscribedGroups.clear()
        previewCache.clear()
        groupRepo = null
        relayPool = null
        eventRepo = null
        notifRepo = null
        myPubkey = null
    }

    val unreadGroups: StateFlow<Set<String>>
        get() = groupRepo?.unreadGroups ?: MutableStateFlow(emptySet())

    val notifiedGroups: StateFlow<Set<String>>
        get() = groupRepo?.notifiedGroups ?: MutableStateFlow(emptySet())

    fun init(repository: GroupRepository, pool: RelayPool, evRepo: EventRepository? = null,
             nRepo: NotificationRepository? = null, pubkey: String? = null) {
        if (groupRepo != null) return
        groupRepo = repository
        relayPool = pool
        eventRepo = evRepo
        notifRepo = nRepo
        myPubkey = pubkey
        collectRelayEvents()
        collectAuthCompleted()
        // Auto-subscribe to groups with notifications enabled so messages arrive in the background
        for ((relayUrl, groupId) in repository.getNotifiedGroupKeys()) {
            subscribeToGroup(relayUrl, groupId)
        }
    }

    /** When NIP-42 AUTH completes for a relay we have groups on, re-fire the group
     *  subscriptions — the relay likely closed them with "auth-required:" and they won't
     *  deliver events until we re-send the REQs post-auth. */
    private fun collectAuthCompleted() {
        val pool = relayPool ?: return
        viewModelScope.launch(Dispatchers.Default) {
            pool.authCompleted.collect { relayUrl ->
                val repo = groupRepo ?: return@collect
                val groupsOnRelay = repo.getJoinedGroupKeys().filter { it.first == relayUrl }
                if (groupsOnRelay.isEmpty()) return@collect
                Log.d("GroupListVM", "[authCompleted] re-firing subs for ${groupsOnRelay.size} groups on $relayUrl")
                for ((url, groupId) in groupsOnRelay) {
                    sendGroupReqs(url, groupId)
                }
            }
        }
    }

    fun subscribeToGroup(relayUrl: String, groupId: String) {
        val pool = relayPool ?: return
        val key = "$relayUrl|$groupId"
        if (!subscribedGroups.add(key)) return  // already subscribed this session
        Log.d("GroupListVM", "[subscribe] relay=$relayUrl group=$groupId")
        pool.ensureGroupRelay(relayUrl)
        sendGroupReqs(relayUrl, groupId)
    }

    /** Re-subscribe to all groups with notifications enabled after a relay reconnect.
     *  Clears the subscribedGroups guard so subscriptions are re-sent fresh. */
    fun resubscribeNotifiedGroups() {
        val repo = groupRepo ?: return
        val notified = repo.getNotifiedGroupKeys()
        if (notified.isEmpty()) return
        // Clear the guard so subscribeToGroup() will re-send REQs
        for ((relayUrl, groupId) in notified) {
            subscribedGroups.remove("$relayUrl|$groupId")
        }
        Log.d("GroupListVM", "[resubscribe] re-subscribing ${notified.size} notified groups after reconnect")
        for ((relayUrl, groupId) in notified) {
            subscribeToGroup(relayUrl, groupId)
        }
    }

    /** Send all 6 group subscription REQs without touching the subscribedGroups guard.
     *  Always fetches the last N messages/reactions/zaps (no since timestamp).
     *  Metadata, admins, and members are replaceable — always fetched fresh. */
    private fun sendGroupReqs(relayUrl: String, groupId: String) {
        val pool = relayPool ?: return
        pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(
            subscriptionId = subId("msg", groupId),
            filter = Filter(kinds = listOf(Nip29.KIND_CHAT_MESSAGE), hTags = listOf(groupId),
                limit = 100)
        ), skipBadCheck = true)
        pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(
            subscriptionId = subId("meta", groupId),
            filter = Filter(kinds = listOf(Nip29.KIND_GROUP_METADATA), dTags = listOf(groupId))
        ), skipBadCheck = true)
        pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(
            subscriptionId = subId("admins", groupId),
            filter = Filter(kinds = listOf(Nip29.KIND_GROUP_ADMINS), dTags = listOf(groupId))
        ), skipBadCheck = true)
        pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(
            subscriptionId = subId("members", groupId),
            filter = Filter(kinds = listOf(Nip29.KIND_GROUP_MEMBERS), dTags = listOf(groupId))
        ), skipBadCheck = true)
        pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(
            subscriptionId = subId("react", groupId),
            filter = Filter(kinds = listOf(7), hTags = listOf(groupId),
                limit = 500)
        ), skipBadCheck = true)
        pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(
            subscriptionId = subId("zap", groupId),
            filter = Filter(kinds = listOf(9735), hTags = listOf(groupId),
                limit = 200)
        ), skipBadCheck = true)
    }

    /** Toggle notification subscription for a group. When enabled, the relay connection stays open. */
    fun setGroupNotified(relayUrl: String, groupId: String, enabled: Boolean) {
        val repo = groupRepo ?: return
        repo.setNotified(relayUrl, groupId, enabled)
        if (enabled) {
            subscribeToGroup(relayUrl, groupId)
        }
        // When disabling, don't unsubscribe — the user might still be viewing the room.
        // The connection will be cleaned up when they leave the room screen.
    }

    fun isGroupNotified(relayUrl: String, groupId: String): Boolean =
        groupRepo?.isNotified(relayUrl, groupId) ?: false

    fun markGroupRead(relayUrl: String, groupId: String) {
        groupRepo?.markRead(relayUrl, groupId)
    }

    /** Close subscriptions for a room and disconnect its relay if no other rooms use it.
     *  If the group has notifications enabled, the connection is kept alive. */
    fun unsubscribeFromGroup(relayUrl: String, groupId: String) {
        val key = "$relayUrl|$groupId"
        // If this group has notifications enabled, keep the subscription alive
        if (groupRepo?.isNotified(relayUrl, groupId) == true) {
            Log.d("GroupListVM", "[unsubscribe] skipped — notifications enabled relay=$relayUrl group=$groupId")
            return
        }
        subscribedGroups.remove(key)
        val pool = relayPool ?: return
        Log.d("GroupListVM", "[unsubscribe] relay=$relayUrl group=$groupId")
        listOf("msg", "meta", "admins", "members", "react", "zap").forEach { type ->
            pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.close(subId(type, groupId)), skipBadCheck = true)
        }
        // Disconnect relay only if no other open rooms share it
        val otherRoomsOnRelay = subscribedGroups.any { it.startsWith("$relayUrl|") }
        if (!otherRoomsOnRelay) {
            pool.removeGroupRelay(relayUrl)
        }
    }

    private fun collectRelayEvents() {
        val pool = relayPool ?: return
        viewModelScope.launch(Dispatchers.Default) {
            pool.relayEvents.collect { relayEvent ->
                val subId = relayEvent.subscriptionId
                // Pass zap receipts from group relays through to EventRepository
                if (subId.startsWith("zap-rcpt-grp-")) {
                    eventRepo?.addEvent(relayEvent.event)
                    return@collect
                }
                if (!subId.startsWith(SUB_PREFIX)) return@collect
                val event = relayEvent.event
                val repo = groupRepo ?: return@collect
                val relayUrl = relayEvent.relayUrl

                when (event.kind) {
                    Nip29.KIND_CHAT_MESSAGE -> {
                        val groupId = event.tags.firstOrNull { it.size >= 2 && it[0] == "h" }?.get(1)
                            ?: return@collect
                        repo.getRoom(relayUrl, groupId) ?: return@collect
                        // q tag is the NIP-29 convention for replies; e "reply" marker as fallback
                        val replyToId = event.tags.firstOrNull { it.size >= 2 && it[0] == "q" }?.get(1)
                            ?: event.tags.firstOrNull { it.size >= 4 && it[0] == "e" && it[3] == "reply" }?.get(1)
                            ?: event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                        repo.addMessage(relayUrl, groupId, GroupMessage(
                            id = event.id,
                            senderPubkey = event.pubkey,
                            content = event.content,
                            createdAt = event.created_at,
                            replyToId = replyToId,
                            emojiTags = Nip30.parseEmojiTags(event)
                        ))
                        // Route reply notifications: if this is a reply (q-tag) to one of our
                        // messages, feed it to NotificationRepository so it shows in notifications.
                        val pk = myPubkey
                        val nr = notifRepo
                        if (pk != null && nr != null && event.pubkey != pk && replyToId != null) {
                            val hasPTag = event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == pk }
                            if (hasPTag) {
                                nr.addGroupChatReply(event, pk, replyToId, groupId)
                            }
                        }
                    }
                    7 -> {
                        val messageId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                            ?: return@collect
                        val groupId = event.tags.firstOrNull { it.size >= 2 && it[0] == "h" }?.get(1)
                            ?: return@collect
                        repo.getRoom(relayUrl, groupId) ?: return@collect
                        val emoji = event.content.ifEmpty { "+" }
                        // Extract URL for custom emoji reactions (e.g. content=":partying:", tag=["emoji","partying","https://..."])
                        val emojiUrl = if (emoji.startsWith(":") && emoji.endsWith(":")) {
                            val shortcode = emoji.removeSurrounding(":")
                            event.tags.firstOrNull { it.size >= 3 && it[0] == "emoji" && it[1] == shortcode }?.get(2)
                        } else null
                        repo.addReaction(relayUrl, groupId, messageId, event.pubkey, emoji, emojiUrl)
                    }
                    Nip29.KIND_GROUP_METADATA -> {
                        Log.d("GroupListVM", "[raw 39000 metadata] relay=$relayUrl ${event.toJson()}")
                        val metadata = Nip29.parseGroupMetadata(event) ?: return@collect
                        repo.getRoom(relayUrl, metadata.groupId) ?: return@collect
                        repo.updateMetadata(relayUrl, metadata.groupId, metadata)
                    }
                    Nip29.KIND_GROUP_ADMINS -> {
                        Log.d("GroupListVM", "[raw 39001 admins] relay=$relayUrl ${event.toJson()}")
                        val groupId = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
                            ?: return@collect
                        repo.getRoom(relayUrl, groupId) ?: return@collect
                        repo.updateAdmins(relayUrl, groupId, Nip29.parseGroupAdminPubkeys(event))
                    }
                    Nip29.KIND_GROUP_MEMBERS -> {
                        Log.d("GroupListVM", "[raw 39002 members] relay=$relayUrl ${event.toJson()}")
                        val groupId = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
                            ?: return@collect
                        repo.getRoom(relayUrl, groupId) ?: return@collect
                        repo.updateMembers(relayUrl, groupId, Nip29.parseGroupMembers(event))
                    }
                    9735 -> {
                        // Zap receipt — route through EventRepository for zap tracking
                        eventRepo?.addEvent(event)
                    }
                }
            }
        }
    }

    /** Apply any cached preview metadata/members to a freshly-added room. */
    private fun applyCachedPreview(relayUrl: String, groupId: String) {
        val repo = groupRepo ?: return
        val cacheKey = "$relayUrl|$groupId"
        val cached = previewCache.remove(cacheKey) ?: return
        cached.metadata?.let { repo.updateMetadata(relayUrl, groupId, it) }
        if (cached.members.isNotEmpty()) repo.updateMembers(relayUrl, groupId, cached.members)
    }

    /**
     * Silently register a group the user already belongs to on the relay (joined via another
     * client). Adds the room locally and subscribes — no kind-9021 join request is sent.
     */
    fun silentJoin(relayUrl: String, groupId: String, signer: NostrSigner? = null) {
        val repo = groupRepo ?: return
        val normalizedUrl = relayUrl.lowercase().trimEnd('/')
        repo.addGroup(normalizedUrl, groupId)
        applyCachedPreview(normalizedUrl, groupId)
        subscribeToGroup(normalizedUrl, groupId)
        publishGroupList(signer)
    }

    fun joinGroup(relayUrl: String, groupId: String, signer: NostrSigner?, inviteCode: String? = null) {
        val repo = groupRepo ?: return
        val pool = relayPool ?: return
        val normalizedUrl = relayUrl.lowercase().trimEnd('/')

        // Without a signer we can't prove identity — nothing to do.
        val s = signer ?: return

        // Bring up the relay connection up front so the OK response lands on a live socket.
        pool.ensureGroupRelay(normalizedUrl)

        viewModelScope.launch(Dispatchers.Default) {
            attemptJoin(repo, pool, normalizedUrl, groupId, s, inviteCode, attempt = 1)
        }
    }

    /** Sign + publish a kind-9021 once and react to the OK. When [attempt] is 1, a rejection
     *  prefixed `auth-required:` will be retried once after NIP-42 AUTH completes — this fixes
     *  the race where a local nsec signs + sends the join faster than the relay's AUTH challenge
     *  round-trips, causing the relay to reject pre-auth. */
    private suspend fun attemptJoin(
        repo: GroupRepository,
        pool: RelayPool,
        normalizedUrl: String,
        groupId: String,
        s: NostrSigner,
        inviteCode: String?,
        attempt: Int
    ) {
        val event = try {
            val tags = mutableListOf(listOf("h", groupId))
            inviteCode?.takeIf { it.isNotEmpty() }?.let { tags.add(listOf("code", it)) }
            s.signEvent(kind = Nip29.KIND_JOIN_REQUEST, content = "", tags = tags)
        } catch (e: Exception) {
            _joinErrors.tryEmit(JoinError(normalizedUrl, groupId,
                "Couldn't sign join request: ${e.message ?: "unknown error"}"))
            return
        }

        // Subscribe to publishResults AND authCompleted BEFORE sending so we can't miss either
        // signal — authCompleted has no replay, and a LocalSigner can respond to an AUTH
        // challenge before we'd have a chance to attach a collector.
        val resultDeferred = CompletableDeferred<PublishResult>()
        val authDeferred = CompletableDeferred<Unit>()
        val collectResultJob = viewModelScope.launch(Dispatchers.Default) {
            pool.publishResults
                .filter { it.eventId == event.id && it.relayUrl == normalizedUrl }
                .collect { resultDeferred.complete(it); return@collect }
        }
        val collectAuthJob = viewModelScope.launch(Dispatchers.Default) {
            pool.authCompleted
                .filter { it == normalizedUrl }
                .collect { authDeferred.complete(Unit); return@collect }
        }
        // Cover the case where AUTH already happened before we subscribed.
        if (pool.isAuthenticated(normalizedUrl)) authDeferred.complete(Unit)

        // Most private NIP-29 relays silently ignore the `code` tag on an unauthenticated 9021
        // and fall through to a generic "closed group" rejection. Wait for NIP-42 AUTH before
        // sending — public relays that never challenge AUTH pay only the grace-window ceiling.
        if (!authDeferred.isCompleted) {
            withTimeoutOrNull(AUTH_PRE_SEND_WAIT_MS) { authDeferred.await() }
        }

        pool.sendToRelayOrEphemeral(normalizedUrl, ClientMessage.event(event), skipBadCheck = true)

        val result = withTimeoutOrNull(10_000) { resultDeferred.await() }
        collectResultJob.cancel()

        when {
            // No OK came back in 10s — some relays silently accept. Be optimistic and admit,
            // then let subsequent REQs surface reality (no messages = they never got in).
            result == null -> {
                collectAuthJob.cancel()
                commitJoin(repo, normalizedUrl, groupId, s)
            }
            // Accepted outright, or relay says we're already a member (per NIP-29 spec).
            result.accepted || result.message.startsWith("duplicate:", ignoreCase = true) -> {
                collectAuthJob.cancel()
                commitJoin(repo, normalizedUrl, groupId, s)
            }
            attempt == 1 && result.message.startsWith("auth-required", ignoreCase = true) -> {
                // Relay required NIP-42 AUTH before processing the 9021. Wait for AUTH to
                // complete (the pool handles auto-sign or user approval) and retry once.
                val authed = withTimeoutOrNull(15_000) { authDeferred.await() } != null
                collectAuthJob.cancel()
                if (authed) {
                    attemptJoin(repo, pool, normalizedUrl, groupId, s, inviteCode, attempt = attempt + 1)
                } else {
                    _joinErrors.tryEmit(JoinError(normalizedUrl, groupId, result.message))
                    if (groupRepo?.getJoinedGroupKeys()?.none { it.first == normalizedUrl } == true) {
                        pool.removeGroupRelay(normalizedUrl)
                    }
                }
            }
            else -> {
                // Rejection. Don't add the room locally — surface the relay's reason to the UI.
                collectAuthJob.cancel()
                _joinErrors.tryEmit(JoinError(normalizedUrl, groupId, result.message))
                // Tear the relay connection back down if we're not using it for any other
                // group — no reason to hold an open socket after a rejected join.
                if (groupRepo?.getJoinedGroupKeys()?.none { it.first == normalizedUrl } == true) {
                    pool.removeGroupRelay(normalizedUrl)
                }
            }
        }
    }

    /** Wait for NIP-42 AUTH on [relayUrl] up to [AUTH_PRE_SEND_WAIT_MS] before a write. Many
     *  relays (relay29 especially) quietly reject or misclassify events from unauthenticated
     *  connections — waiting here turns those into the "happy path" for auth-required relays
     *  and costs only the 5 s ceiling for public relays that never challenge AUTH. */
    private suspend fun waitForRelayAuth(pool: RelayPool, relayUrl: String) {
        if (pool.isAuthenticated(relayUrl)) return
        val authDeferred = CompletableDeferred<Unit>()
        val authJob = viewModelScope.launch(Dispatchers.Default) {
            pool.authCompleted
                .filter { it == relayUrl }
                .collect { authDeferred.complete(Unit); return@collect }
        }
        // Guard against AUTH landing between the check above and the subscribe above.
        if (pool.isAuthenticated(relayUrl)) authDeferred.complete(Unit)
        withTimeoutOrNull(AUTH_PRE_SEND_WAIT_MS) { authDeferred.await() }
        authJob.cancel()
    }

    /** Sign+publish an admin event, waiting for NIP-42 AUTH first and then the relay's OK.
     *  On rejection or timeout, surfaces an [AdminError] so the UI can tell the user the
     *  action silently failed (e.g. rate-limit, "group doesn't exist") instead of leaving
     *  the app in a state that assumes success. */
    private suspend fun publishAdminEvent(
        pool: RelayPool,
        signer: NostrSigner,
        relayUrl: String,
        kind: Int,
        content: String,
        tags: List<List<String>>,
        label: String,
        okTimeoutMs: Long = 10_000
    ): PublishResult? {
        pool.ensureGroupRelay(relayUrl)
        waitForRelayAuth(pool, relayUrl)

        val event = try {
            signer.signEvent(kind = kind, content = content, tags = tags)
        } catch (e: Exception) {
            _adminErrors.tryEmit(AdminError(relayUrl, label,
                "Couldn't sign: ${e.message ?: "unknown error"}"))
            return null
        }

        val resultDeferred = CompletableDeferred<PublishResult>()
        val collectJob = viewModelScope.launch(Dispatchers.Default) {
            pool.publishResults
                .filter { it.eventId == event.id && it.relayUrl == relayUrl }
                .collect { resultDeferred.complete(it); return@collect }
        }

        pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.event(event), skipBadCheck = true)

        val result = withTimeoutOrNull(okTimeoutMs) { resultDeferred.await() }
        collectJob.cancel()
        when {
            result == null -> _adminErrors.tryEmit(AdminError(relayUrl, label, "Relay did not confirm (timeout)"))
            !result.accepted -> _adminErrors.tryEmit(AdminError(relayUrl, label, result.message))
        }
        return result
    }

    /** Shared post-accept path: add the group locally, subscribe, publish kind 10009. */
    private fun commitJoin(repo: GroupRepository, relayUrl: String, groupId: String, signer: NostrSigner?) {
        repo.addGroup(relayUrl, groupId)
        applyCachedPreview(relayUrl, groupId)
        subscribeToGroup(relayUrl, groupId)
        publishGroupList(signer)
        // Re-request all subscriptions after a short delay — private relays close the initial
        // REQs with "restricted: not a member" and only respond after the join is processed.
        viewModelScope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(2_000)
            sendGroupReqs(relayUrl, groupId)
        }
    }

    fun createGroup(
        relayUrl: String,
        name: String,
        signer: NostrSigner?,
        isPrivate: Boolean = false,
        isClosed: Boolean = false,
        isRestricted: Boolean = false,
        isHidden: Boolean = false
    ) {
        val repo = groupRepo ?: return
        val pool = relayPool ?: return
        val relayUrl = relayUrl.lowercase().trimEnd('/')
        val groupId = Nip29.generateGroupId()
        // Store name locally — avoids a second signing operation at create time which
        // causes an infinite loop in the remote signer bridge after "Always allow" is granted.
        repo.addGroup(relayUrl, groupId, localName = name.trim().ifEmpty { null })
        // Optimistically record the chosen flags so the UI reflects the intended posture
        // before the 39000 metadata event round-trips back from the relay.
        repo.updateMetadata(relayUrl, groupId, Nip29.GroupMetadata(
            groupId = groupId,
            name = name.trim().ifEmpty { null },
            picture = null,
            about = null,
            isPrivate = isPrivate,
            isClosed = isClosed,
            isRestricted = isRestricted,
            isHidden = isHidden
        ))
        subscribeToGroup(relayUrl, groupId)
        publishGroupList(signer)
        val anyFlagSet = isPrivate || isClosed || isRestricted || isHidden
        signer?.let { s ->
            viewModelScope.launch(Dispatchers.Default) {
                val createResult = publishAdminEvent(
                    pool = pool,
                    signer = s,
                    relayUrl = relayUrl,
                    kind = Nip29.KIND_CREATE_GROUP,
                    content = "",
                    tags = listOf(listOf("h", groupId)),
                    label = "createGroup/9007"
                )

                // If the relay rejected the create, rolling the local placeholder back avoids
                // later 9009/9002 calls trying to address a group the relay doesn't know about.
                if (createResult != null && !createResult.accepted) {
                    repo.removeGroup(relayUrl, groupId)
                    return@launch
                }

                // Only fire the follow-up 9002 if the user actually customized the group's
                // posture. This keeps default "open" creations to a single signing op (the
                // original behavior that avoided the remote-signer bridge infinite loop).
                if (!anyFlagSet) return@launch

                // Give the relay time to process 9007 and mark us as admin before 9002 —
                // relay29 rejects edit-metadata from non-admins, and back-to-back events
                // can race against the admin grant.
                kotlinx.coroutines.delay(1_500)

                val editTags = mutableListOf(listOf("h", groupId))
                if (name.isNotBlank()) editTags.add(listOf("name", name.trim()))
                editTags.add(listOf(if (isPrivate) "private" else "public"))
                editTags.add(listOf(if (isClosed) "closed" else "open"))
                editTags.add(listOf(if (isRestricted) "restricted" else "unrestricted"))
                editTags.add(listOf(if (isHidden) "hidden" else "visible"))
                publishAdminEvent(
                    pool = pool,
                    signer = s,
                    relayUrl = relayUrl,
                    kind = Nip29.KIND_EDIT_METADATA,
                    content = "",
                    tags = editTags,
                    label = "createGroup/9002"
                )
            }
        }
    }

    /** Admin action: update group name/about/picture on the relay (kind 9002). Single signing operation. */
    fun updateMetadataOnRelay(
        relayUrl: String,
        groupId: String,
        name: String,
        about: String,
        picture: String = "",
        signer: NostrSigner?
    ) {
        val pool = relayPool ?: return
        val repo = groupRepo ?: return
        val s = signer ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val existing = repo.getRoom(relayUrl, groupId)
            val existingMeta = existing?.metadata
            val tags = mutableListOf(listOf("h", groupId))
            if (name.isNotBlank()) tags.add(listOf("name", name.trim()))
            if (about.isNotBlank()) tags.add(listOf("about", about.trim()))
            if (picture.isNotBlank()) tags.add(listOf("picture", picture.trim()))
            tags.add(listOf(if (existingMeta?.isPrivate == true) "private" else "public"))
            tags.add(listOf(if (existingMeta?.isClosed == true) "closed" else "open"))
            tags.add(listOf(if (existingMeta?.isRestricted == true) "restricted" else "unrestricted"))
            tags.add(listOf(if (existingMeta?.isHidden == true) "hidden" else "visible"))
            publishAdminEvent(
                pool = pool,
                signer = s,
                relayUrl = relayUrl,
                kind = Nip29.KIND_EDIT_METADATA,
                content = "",
                tags = tags,
                label = "updateMetadata/9002"
            )
            // Optimistically update local metadata regardless of OK — the repo state will
            // resync when the 39000 replay arrives (or stay in its prior form if rejected).
            repo.updateMetadata(relayUrl, groupId, Nip29.GroupMetadata(
                groupId = groupId,
                name = name.trim().ifEmpty { existingMeta?.name },
                picture = picture.trim().ifEmpty { existingMeta?.picture },
                about = about.trim().ifEmpty { existingMeta?.about },
                isPrivate = existingMeta?.isPrivate ?: false,
                isClosed = existingMeta?.isClosed ?: false,
                isRestricted = existingMeta?.isRestricted ?: false,
                isHidden = existingMeta?.isHidden ?: false
            ))
            if (name.isNotBlank()) {
                repo.addGroup(relayUrl, groupId, localName = name.trim())
            }
        }
    }

    /**
     * Admin action: generate a new invite code and publish it via kind 9009. Returns the code
     * immediately so the caller can display / copy it; the relay publish happens asynchronously.
     */
    fun createInvite(relayUrl: String, groupId: String, signer: NostrSigner?): String? {
        val pool = relayPool ?: return null
        val s = signer ?: return null
        val code = Nip29.generateInviteCode()
        viewModelScope.launch(Dispatchers.Default) {
            publishAdminEvent(
                pool = pool,
                signer = s,
                relayUrl = relayUrl,
                kind = Nip29.KIND_CREATE_INVITE,
                content = "",
                tags = listOf(listOf("h", groupId), listOf("code", code)),
                label = "createInvite/9009"
            )
        }
        return code
    }

    /** Admin action: assign one or more roles to a user (kind 9000). */
    fun putUser(
        relayUrl: String,
        groupId: String,
        targetPubkey: String,
        roles: List<String>,
        signer: NostrSigner?
    ) {
        val pool = relayPool ?: return
        val repo = groupRepo ?: return
        val s = signer ?: return
        val cleanedRoles = roles.map { it.trim() }.filter { it.isNotEmpty() }
        // Optimistic local promotion — if any role was assigned, reflect the user in the
        // admins list immediately so the UI updates without waiting for a 39001 replay.
        if (cleanedRoles.isNotEmpty()) {
            repo.getRoom(relayUrl, groupId)?.let { room ->
                if (targetPubkey !in room.admins) {
                    repo.updateAdmins(relayUrl, groupId, room.admins + targetPubkey)
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            val pTag = mutableListOf("p", targetPubkey).apply { addAll(cleanedRoles) }
            publishAdminEvent(
                pool = pool,
                signer = s,
                relayUrl = relayUrl,
                kind = Nip29.KIND_PUT_USER,
                content = "",
                tags = listOf(listOf("h", groupId), pTag),
                label = "putUser/9000"
            )
        }
    }

    /** Leave a group: send kind 9022, remove locally, clean up relay, publish updated list. */
    fun leaveGroup(relayUrl: String, groupId: String, signer: NostrSigner?) {
        val pool = relayPool ?: return
        signer?.let { s ->
            viewModelScope.launch(Dispatchers.Default) {
                publishAdminEvent(
                    pool = pool,
                    signer = s,
                    relayUrl = relayUrl,
                    kind = Nip29.KIND_LEAVE_REQUEST,
                    content = "",
                    tags = listOf(listOf("h", groupId)),
                    label = "leaveGroup/9022"
                )
            }
        }
        groupRepo?.removeGroup(relayUrl, groupId)
        unsubscribeFromGroup(relayUrl, groupId)
        publishGroupList(signer)
    }

    /** Admin action: delete a group (kind 9008), remove locally, clean up relay, publish updated list. */
    fun deleteGroup(relayUrl: String, groupId: String, signer: NostrSigner?) {
        val pool = relayPool ?: return
        signer?.let { s ->
            viewModelScope.launch(Dispatchers.Default) {
                publishAdminEvent(
                    pool = pool,
                    signer = s,
                    relayUrl = relayUrl,
                    kind = Nip29.KIND_DELETE_GROUP,
                    content = "",
                    tags = listOf(listOf("h", groupId)),
                    label = "deleteGroup/9008"
                )
            }
        }
        groupRepo?.removeGroup(relayUrl, groupId)
        unsubscribeFromGroup(relayUrl, groupId)
        publishGroupList(signer)
    }

    /** Admin action: remove a user from the group (kind 9001). */
    fun removeUser(
        relayUrl: String,
        groupId: String,
        targetPubkey: String,
        signer: NostrSigner?
    ) {
        val pool = relayPool ?: return
        val s = signer ?: return
        // Optimistic local update — remove from members list immediately
        val repo = groupRepo ?: return
        repo.getRoom(relayUrl, groupId)?.let { room ->
            repo.updateMembers(relayUrl, groupId, room.members.filter { it != targetPubkey })
        }
        viewModelScope.launch(Dispatchers.Default) {
            publishAdminEvent(
                pool = pool,
                signer = s,
                relayUrl = relayUrl,
                kind = 9001,
                content = "",
                tags = listOf(listOf("h", groupId), listOf("p", targetPubkey)),
                label = "removeUser/9001"
            )
        }
    }

    /** One-shot preview fetch (metadata + members) for rooms not yet joined. Returns cached data immediately if available. */
    suspend fun fetchGroupPreview(relayUrl: String, groupId: String): GroupPreview {
        groupRepo?.getRoom(relayUrl, groupId)?.let { room ->
            if (room.metadata != null || room.members.isNotEmpty()) {
                Log.d("GroupListVM", "[preview] cache hit (joined) relay=$relayUrl group=$groupId name=${room.metadata?.name}")
                return GroupPreview(room.metadata, room.members)
            }
        }
        val cacheKey = "$relayUrl|$groupId"
        previewCache[cacheKey]?.let { cached ->
            if (cached.metadata != null || cached.members.isNotEmpty()) {
                Log.d("GroupListVM", "[preview] cache hit (preview) relay=$relayUrl group=$groupId members=${cached.members.size}")
                return cached
            }
        }
        if (relayUrl.isEmpty() || groupId.isEmpty()) {
            Log.w("GroupListVM", "[preview] called with empty relay or group — ignoring")
            return GroupPreview(null, emptyList())
        }
        val pool = relayPool ?: run {
            Log.w("GroupListVM", "[preview] relayPool is null — init() not called yet for relay=$relayUrl group=$groupId")
            return GroupPreview(null, emptyList())
        }
        val metaSubId = "grp-preview-meta-$groupId"
        val membersSubId = "grp-preview-members-$groupId"

        val metaDeferred = CompletableDeferred<Nip29.GroupMetadata?>()
        val membersDeferred = CompletableDeferred<List<String>>()

        Log.d("GroupListVM", "[preview] starting fetch relay=$relayUrl group=$groupId")
        return coroutineScope {
            // Subscribe BEFORE sending REQs — relayEvents has no replay, so events
            // arriving before collect() is active would be silently missed.
            val collectorReady = CompletableDeferred<Unit>()
            val collectJob = launch(Dispatchers.Default) {
                pool.relayEvents
                    .onSubscription { collectorReady.complete(Unit) }
                    .collect { ev ->
                        when {
                            ev.subscriptionId == metaSubId && ev.event.kind == Nip29.KIND_GROUP_METADATA -> {
                                val parsed = Nip29.parseGroupMetadata(ev.event)
                                Log.d("GroupListVM", "[preview] got metadata name=${parsed?.name} from ${ev.relayUrl}")
                                metaDeferred.complete(parsed)
                            }
                            ev.subscriptionId == membersSubId && ev.event.kind == Nip29.KIND_GROUP_MEMBERS -> {
                                val members = Nip29.parseGroupMembers(ev.event)
                                Log.d("GroupListVM", "[preview] got members count=${members.size} from ${ev.relayUrl}")
                                membersDeferred.complete(members)
                            }
                        }
                    }
            }

            // Wait until the collector is actually subscribed, then send REQs
            collectorReady.await()
            val sentMeta = pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(
                subscriptionId = metaSubId,
                filter = Filter(kinds = listOf(Nip29.KIND_GROUP_METADATA), dTags = listOf(groupId))
            ), skipBadCheck = true)
            val sentMembers = pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(
                subscriptionId = membersSubId,
                filter = Filter(kinds = listOf(Nip29.KIND_GROUP_MEMBERS), dTags = listOf(groupId))
            ), skipBadCheck = true)
            Log.d("GroupListVM", "[preview] REQs sent sentMeta=$sentMeta sentMembers=$sentMembers relay=$relayUrl")

            val metadata = withTimeoutOrNull(5_000) { metaDeferred.await() }
            Log.d("GroupListVM", "[preview] metadata result=${metadata?.name} (${if (metadata == null) "timed out" else "ok"})")
            val members = withTimeoutOrNull(1_000) { membersDeferred.await() } ?: emptyList()
            Log.d("GroupListVM", "[preview] members result=${members.size}")

            collectJob.cancel()
            val preview = GroupPreview(metadata, members)
            if (metadata != null || members.isNotEmpty()) previewCache[cacheKey] = preview
            preview
        }
    }

    /**
     * Discover public chat rooms from known group relays.
     * Fetches kind 39000 (metadata) and 39002 (members), deduplicates, and ranks by member count.
     */
    fun discoverGroups() {
        val pool = relayPool ?: return
        if (_discoveryLoading.value) return
        _discoveryLoading.value = true
        _discoveredGroups.value = emptyList()

        discoverGen++
        val gen = discoverGen
        val metaSubId = "grp-discover-meta-$gen"
        val membersSubId = "grp-discover-members-$gen"
        pool.registerDedupBypass(metaSubId)
        pool.registerDedupBypass(membersSubId)
        val groupRelays = Nip29.DEFAULT_GROUP_RELAYS +
            (groupRepo?.getJoinedGroupKeys()?.map { it.first }?.distinct() ?: emptyList())
        val relayUrls = groupRelays.distinct()

        viewModelScope.launch(Dispatchers.Default) {
            val metadataMap = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Nip29.GroupMetadata>>() // key -> (relayUrl, metadata)
            val membersMap = java.util.concurrent.ConcurrentHashMap<String, List<String>>() // groupId -> members

            val collectorReady = CompletableDeferred<Unit>()
            val collectJob = launch {
                pool.relayEvents
                    .onSubscription { collectorReady.complete(Unit) }
                    .collect { ev ->
                        when {
                            ev.subscriptionId == metaSubId && ev.event.kind == Nip29.KIND_GROUP_METADATA -> {
                                val meta = Nip29.parseGroupMetadata(ev.event) ?: return@collect
                                val key = "${ev.relayUrl}|${meta.groupId}"
                                metadataMap.putIfAbsent(key, Pair(ev.relayUrl, meta))
                            }
                            ev.subscriptionId == membersSubId && ev.event.kind == Nip29.KIND_GROUP_MEMBERS -> {
                                val groupId = ev.event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return@collect
                                val members = Nip29.parseGroupMembers(ev.event)
                                val key = "${ev.relayUrl}|$groupId"
                                membersMap[key] = members
                            }
                        }
                    }
            }

            collectorReady.await()

            val metaFilter = Filter(kinds = listOf(Nip29.KIND_GROUP_METADATA), limit = 200)
            val membersFilter = Filter(kinds = listOf(Nip29.KIND_GROUP_MEMBERS), limit = 200)
            for (url in relayUrls) {
                pool.sendToRelayOrEphemeral(url, ClientMessage.req(metaSubId, metaFilter), skipBadCheck = true)
                pool.sendToRelayOrEphemeral(url, ClientMessage.req(membersSubId, membersFilter), skipBadCheck = true)
            }

            // Wait for responses, emitting intermediate results
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(500)
                emitDiscoveredGroups(metadataMap, membersMap)
            }

            collectJob.cancel()
            for (url in relayUrls) {
                pool.sendToRelayOrEphemeral(url, ClientMessage.close(metaSubId), skipBadCheck = true)
                pool.sendToRelayOrEphemeral(url, ClientMessage.close(membersSubId), skipBadCheck = true)
            }

            emitDiscoveredGroups(metadataMap, membersMap)
            _discoveryLoading.value = false
        }
    }

    private fun emitDiscoveredGroups(
        metadataMap: Map<String, Pair<String, Nip29.GroupMetadata>>,
        membersMap: Map<String, List<String>>
    ) {
        val joinedKeys = groupRepo?.getJoinedGroupKeys()
            ?.map { "${it.first}|${it.second}" }?.toSet() ?: emptySet()

        val groups = metadataMap.entries
            .filter { it.key !in joinedKeys }
            .map { (key, pair) ->
                val members = membersMap[key] ?: emptyList()
                DiscoveredGroup(pair.first, pair.second, members)
            }
            .sortedByDescending { it.members.size }

        _discoveredGroups.value = groups
    }

    /**
     * Publish the user's current group list as a kind 10009 replaceable event.
     * Builds entries from GroupRepository's joined groups.
     */
    private fun publishGroupList(signer: NostrSigner?) {
        val s = signer ?: return
        val repo = groupRepo ?: return
        val pool = relayPool ?: return
        val entries = repo.getJoinedGroupKeys().map { (relayUrl, groupId) ->
            val room = repo.getRoom(relayUrl, groupId)
            SimpleGroupEntry(groupId, relayUrl, room?.metadata?.name)
        }
        val tags = Nip51.buildSimpleGroupsTags(entries)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val event = s.signEvent(
                    kind = Nip51.KIND_SIMPLE_GROUPS,
                    content = "",
                    tags = tags
                )
                val msg = ClientMessage.event(event)
                pool.sendToWriteRelays(msg)
                for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
                    pool.sendToRelayOrEphemeral(url, msg)
                }
            } catch (_: Exception) { }
        }
    }

    private fun subId(type: String, groupId: String) = "$SUB_PREFIX$type-$groupId"

    companion object {
        private const val SUB_PREFIX = "grp-"
        /** How long to wait for NIP-42 AUTH to complete before sending a 9021. Covers the
         *  common case where the relay challenges AUTH on connect — without waiting, an
         *  unauthenticated 9021 gets silently downgraded to a "closed group" rejection even
         *  when the invite code is valid. 5 s is long enough for a TLS handshake + AUTH
         *  round-trip but short enough that public (no-auth) relays don't feel stalled. */
        private const val AUTH_PRE_SEND_WAIT_MS = 5_000L
    }
}
