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
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.GroupMessage
import com.wisp.app.repo.GroupPreview
import com.wisp.app.repo.GroupRepository
import com.wisp.app.repo.GroupRoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class GroupListViewModel(app: Application) : AndroidViewModel(app) {

    private var groupRepo: GroupRepository? = null
    private var relayPool: RelayPool? = null
    private var eventRepo: EventRepository? = null

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

    val groups: StateFlow<List<GroupRoom>>
        get() = groupRepo?.joinedGroups ?: MutableStateFlow(emptyList())

    /** Clears all refs so init() can run again after an account switch. */
    fun reset() {
        subscribedGroups.clear()
        previewCache.clear()
        groupRepo = null
        relayPool = null
        eventRepo = null
    }

    val unreadGroups: StateFlow<Set<String>>
        get() = groupRepo?.unreadGroups ?: MutableStateFlow(emptySet())

    val notifiedGroups: StateFlow<Set<String>>
        get() = groupRepo?.notifiedGroups ?: MutableStateFlow(emptySet())

    fun init(repository: GroupRepository, pool: RelayPool, evRepo: EventRepository? = null) {
        if (groupRepo != null) return
        groupRepo = repository
        relayPool = pool
        eventRepo = evRepo
        collectRelayEvents()
        // Auto-subscribe to groups with notifications enabled so messages arrive in the background
        for ((relayUrl, groupId) in repository.getNotifiedGroupKeys()) {
            subscribeToGroup(relayUrl, groupId)
        }
    }

    fun subscribeToGroup(relayUrl: String, groupId: String) {
        val pool = relayPool ?: return
        val key = "$relayUrl|$groupId"
        if (!subscribedGroups.add(key)) return  // already subscribed this session
        Log.d("GroupListVM", "[subscribe] relay=$relayUrl group=$groupId")
        pool.ensureGroupRelay(relayUrl)
        // Use the latest persisted message timestamp as `since` to avoid re-downloading
        // messages we already have in ObjectBox. 5-minute buffer covers relay clock skew.
        val room = groupRepo?.getRoom(relayUrl, groupId)
        val since = room?.lastMessageAt?.takeIf { it > 0 }?.let { it - 5 * 60 }
        sendGroupReqs(relayUrl, groupId, since)
    }

    /** Send all 6 group subscription REQs without touching the subscribedGroups guard.
     *  When [since] is set, messages/reactions/zaps use it to skip already-persisted events.
     *  Metadata, admins, and members are replaceable — always fetched fresh. */
    private fun sendGroupReqs(relayUrl: String, groupId: String, since: Long? = null) {
        val pool = relayPool ?: return
        pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(
            subscriptionId = subId("msg", groupId),
            filter = Filter(kinds = listOf(Nip29.KIND_CHAT_MESSAGE), hTags = listOf(groupId),
                since = since, limit = if (since != null) null else 100)
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
                since = since, limit = if (since != null) null else 500)
        ), skipBadCheck = true)
        pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.req(
            subscriptionId = subId("zap", groupId),
            filter = Filter(kinds = listOf(9735), hTags = listOf(groupId),
                since = since, limit = if (since != null) null else 200)
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
                        val metadata = Nip29.parseGroupMetadata(event) ?: return@collect
                        repo.getRoom(relayUrl, metadata.groupId) ?: return@collect
                        repo.updateMetadata(relayUrl, metadata.groupId, metadata)
                    }
                    Nip29.KIND_GROUP_ADMINS -> {
                        val groupId = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
                            ?: return@collect
                        repo.getRoom(relayUrl, groupId) ?: return@collect
                        repo.updateAdmins(relayUrl, groupId, Nip29.parseGroupAdmins(event))
                    }
                    Nip29.KIND_GROUP_MEMBERS -> {
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

    fun joinGroup(relayUrl: String, groupId: String, signer: NostrSigner?) {
        val repo = groupRepo ?: return
        val pool = relayPool ?: return
        val relayUrl = relayUrl.lowercase().trimEnd('/')
        repo.addGroup(relayUrl, groupId)
        applyCachedPreview(relayUrl, groupId)
        subscribeToGroup(relayUrl, groupId)
        publishGroupList(signer)
        viewModelScope.launch(Dispatchers.Default) {
            signer?.let { s ->
                try {
                    val event = s.signEvent(
                        kind = Nip29.KIND_JOIN_REQUEST,
                        content = "",
                        tags = listOf(listOf("h", groupId))
                    )
                    pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.event(event), skipBadCheck = true)
                } catch (_: Exception) { }
            }
            // Re-request all subscriptions after a short delay — private relays close the initial
            // REQs with "restricted: not a member" and only respond after the join is processed.
            kotlinx.coroutines.delay(2_000)
            sendGroupReqs(relayUrl, groupId)
        }
    }

    fun createGroup(relayUrl: String, name: String, signer: NostrSigner?) {
        val repo = groupRepo ?: return
        val pool = relayPool ?: return
        val relayUrl = relayUrl.lowercase().trimEnd('/')
        val groupId = Nip29.generateGroupId()
        // Store name locally — avoids a second signing operation at create time which
        // causes an infinite loop in the remote signer bridge after "Always allow" is granted.
        repo.addGroup(relayUrl, groupId, localName = name.trim().ifEmpty { null })
        subscribeToGroup(relayUrl, groupId)
        publishGroupList(signer)
        signer?.let { s ->
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val createEvent = s.signEvent(
                        kind = Nip29.KIND_CREATE_GROUP,
                        content = "",
                        tags = listOf(listOf("h", groupId))
                    )
                    pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.event(createEvent), skipBadCheck = true)
                } catch (_: Exception) { }
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
        signer ?: return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val tags = mutableListOf(listOf("h", groupId))
                if (name.isNotBlank()) tags.add(listOf("name", name.trim()))
                if (about.isNotBlank()) tags.add(listOf("about", about.trim()))
                if (picture.isNotBlank()) tags.add(listOf("picture", picture.trim()))
                val event = signer.signEvent(
                    kind = Nip29.KIND_EDIT_METADATA,
                    content = "",
                    tags = tags
                )
                pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.event(event), skipBadCheck = true)
                // Optimistically update local metadata
                val existing = repo.getRoom(relayUrl, groupId)?.metadata
                repo.updateMetadata(relayUrl, groupId, Nip29.GroupMetadata(
                    groupId = groupId,
                    name = name.trim().ifEmpty { existing?.name },
                    picture = picture.trim().ifEmpty { existing?.picture },
                    about = about.trim().ifEmpty { existing?.about },
                    isPrivate = existing?.isPrivate ?: false,
                    isClosed = existing?.isClosed ?: false
                ))
                // Persist updated name locally
                if (name.isNotBlank()) {
                    repo.addGroup(relayUrl, groupId, localName = name.trim())
                }
            } catch (_: Exception) { }
        }
    }

    /** Leave a group: send kind 9022, remove locally, clean up relay, publish updated list. */
    fun leaveGroup(relayUrl: String, groupId: String, signer: NostrSigner?) {
        val pool = relayPool ?: return
        signer?.let { s ->
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val event = s.signEvent(
                        kind = Nip29.KIND_LEAVE_REQUEST,
                        content = "",
                        tags = listOf(listOf("h", groupId))
                    )
                    pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.event(event), skipBadCheck = true)
                } catch (_: Exception) { }
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
                try {
                    val event = s.signEvent(
                        kind = Nip29.KIND_DELETE_GROUP,
                        content = "",
                        tags = listOf(listOf("h", groupId))
                    )
                    pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.event(event), skipBadCheck = true)
                } catch (_: Exception) { }
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
        signer ?: return
        // Optimistic local update — remove from members list immediately
        val repo = groupRepo ?: return
        repo.getRoom(relayUrl, groupId)?.let { room ->
            repo.updateMembers(relayUrl, groupId, room.members.filter { it != targetPubkey })
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val event = signer.signEvent(
                    kind = 9001,
                    content = "",
                    tags = listOf(listOf("h", groupId), listOf("p", targetPubkey))
                )
                pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.event(event), skipBadCheck = true)
            } catch (_: Exception) { }
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
    }
}
