package com.wisp.app.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.DmMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.FlatNotificationItem
import com.wisp.app.nostr.Nip17
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.NotificationGroup
import com.wisp.app.nostr.NotificationSummary
import com.wisp.app.nostr.NotificationType
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.SignerCancelledException
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.MentionCandidate
import com.wisp.app.repo.MentionSearchRepository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.PowPreferences
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.repo.RelayListRepository
import com.wisp.app.R
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.wisp.app.nostr.Nip19
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class NotificationFilter(val labelResId: Int) {
    REPLIES(R.string.filter_replies),
    REACTIONS(R.string.filter_reactions),
    ZAPS(R.string.filter_zaps),
    REPOSTS(R.string.filter_reposts),
    MENTIONS(R.string.filter_mentions),
    VOTES(R.string.filter_votes),
    DMS(R.string.filter_dms)
}

class NotificationsViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsPrefs = app.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NOTIFICATION_FILTER = "notification_filter"
        private const val PREF_ENABLED_NOTIF_TYPES = "enabled_notif_types"
        private const val PREF_CHAT_ROOMS_NOTIF_ENABLED = "chat_rooms_notif_enabled"
        private val ALL_TYPES = NotificationFilter.entries.toSet()
    }

    val notifications: StateFlow<List<NotificationGroup>>
        get() = notifRepo?.notifications ?: MutableStateFlow(emptyList())

    val hasUnread: StateFlow<Boolean>
        get() = notifRepo?.hasUnread ?: MutableStateFlow(false)

    val zapReceived: SharedFlow<Unit>
        get() = notifRepo?.zapReceived ?: MutableSharedFlow()

    val replyReceived: SharedFlow<Unit>
        get() = notifRepo?.replyReceived ?: MutableSharedFlow()

    val notifReceived: SharedFlow<Unit>
        get() = notifRepo?.notifReceived ?: MutableSharedFlow()

    val dmReceived: SharedFlow<Unit>
        get() = dmRepo?.dmReceived ?: MutableSharedFlow()

    val flatNotifications: StateFlow<List<FlatNotificationItem>>
        get() = notifRepo?.flatNotifications ?: MutableStateFlow(emptyList())

    val eventRepository: EventRepository?
        get() = eventRepo

    val contactRepository: ContactRepository?
        get() = contactRepo

    private val _combinedSummary = MutableStateFlow(NotificationSummary())
    val summary24h: StateFlow<NotificationSummary> = _combinedSummary

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _enabledTypes = MutableStateFlow(loadEnabledTypes())
    val enabledTypes: StateFlow<Set<NotificationFilter>> = _enabledTypes

    private val _chatRoomsEnabled = MutableStateFlow(settingsPrefs.getBoolean(PREF_CHAT_ROOMS_NOTIF_ENABLED, true))
    val chatRoomsEnabled: StateFlow<Boolean> = _chatRoomsEnabled

    private val _filteredNotifications = MutableStateFlow<List<NotificationGroup>>(emptyList())
    val filteredNotifications: StateFlow<List<NotificationGroup>> = _filteredNotifications

    private val _filteredFlatNotifications = MutableStateFlow<List<FlatNotificationItem>>(emptyList())
    val filteredFlatNotifications: StateFlow<List<FlatNotificationItem>> = _filteredFlatNotifications

    private var notifRepo: NotificationRepository? = null
    private var eventRepo: EventRepository? = null
    private var contactRepo: ContactRepository? = null
    private var dmRepo: DmRepository? = null
    private var relayPool: RelayPool? = null
    private var relayListRepo: RelayListRepository? = null
    private var powPrefs: PowPreferences? = null
    private val keyRepo = KeyRepository(getApplication())

    // ── Mention search ──────────────────────────────────────────────────
    private var mentionSearchRepo: MentionSearchRepository? = null
    private var mentionStartIndex: Int = -1

    private val _mentionQuery = MutableStateFlow<String?>(null)
    val mentionQuery: StateFlow<String?> = _mentionQuery

    private val _mentionCandidates = MutableStateFlow<List<MentionCandidate>>(emptyList())
    val mentionCandidates: StateFlow<List<MentionCandidate>> = _mentionCandidates

    fun init(
        notificationRepository: NotificationRepository,
        eventRepository: EventRepository,
        contactRepository: ContactRepository,
        dmRepository: DmRepository? = null,
        relayPool: RelayPool? = null,
        relayListRepository: RelayListRepository? = null,
        powPreferences: PowPreferences? = null,
        profileRepository: ProfileRepository? = null,
        eventPersistence: com.wisp.app.db.EventPersistence? = null
    ) {
        notifRepo = notificationRepository
        eventRepo = eventRepository
        contactRepo = contactRepository
        dmRepo = dmRepository
        this.relayPool = relayPool
        relayListRepo = relayListRepository
        powPrefs = powPreferences
        if (profileRepository != null && relayPool != null) {
            mentionSearchRepo = MentionSearchRepository(profileRepository, contactRepository, relayPool, keyRepo).also {
                it.eventPersistence = eventPersistence
            }
            viewModelScope.launch {
                mentionSearchRepo!!.candidates.collect { _mentionCandidates.value = it }
            }
        }
        startPeriodicRefresh()
        startFilterCombine()
        startSummaryCombine()
    }

    private fun startPeriodicRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(60_000L)
                notifRepo?.refreshSplits()
            }
        }
    }

    private val dmNotifications: StateFlow<List<FlatNotificationItem>>
        get() = dmRepo?.dmNotifications ?: MutableStateFlow(emptyList())

    private fun notifTypeToFilter(type: NotificationType): NotificationFilter? = when (type) {
        NotificationType.REPLY -> NotificationFilter.REPLIES
        NotificationType.REACTION, NotificationType.DM_REACTION -> NotificationFilter.REACTIONS
        NotificationType.ZAP, NotificationType.DM_ZAP, NotificationType.PROFILE_ZAP -> NotificationFilter.ZAPS
        NotificationType.REPOST -> NotificationFilter.REPOSTS
        NotificationType.MENTION, NotificationType.QUOTE -> NotificationFilter.MENTIONS
        NotificationType.VOTE -> NotificationFilter.VOTES
        NotificationType.DM -> NotificationFilter.DMS
    }

    private fun isItemEnabled(item: FlatNotificationItem, enabled: Set<NotificationFilter>, chatEnabled: Boolean): Boolean {
        val filter = notifTypeToFilter(item.type) ?: return true
        if (filter !in enabled) return false
        if (item.groupChatId != null && !chatEnabled) return false
        return true
    }

    private fun startFilterCombine() {
        viewModelScope.launch {
            combine(
                notifications,
                _enabledTypes
            ) { notifs: List<NotificationGroup>, enabled: Set<NotificationFilter> ->
                notifs.filter { group ->
                    when (group) {
                        is NotificationGroup.ReplyNotification -> NotificationFilter.REPLIES in enabled
                        is NotificationGroup.ReactionGroup -> {
                            val hasZap = NotificationGroup.ZAP_EMOJI in group.reactions
                            val hasRepost = NotificationGroup.REPOST_EMOJI in group.reactions
                            when {
                                hasZap -> NotificationFilter.ZAPS in enabled
                                hasRepost -> NotificationFilter.REPOSTS in enabled
                                else -> NotificationFilter.REACTIONS in enabled
                            }
                        }
                        is NotificationGroup.MentionNotification -> NotificationFilter.MENTIONS in enabled
                        is NotificationGroup.QuoteNotification -> NotificationFilter.MENTIONS in enabled
                    }
                }
            }.collect { filtered -> _filteredNotifications.value = filtered }
        }
        viewModelScope.launch {
            combine(
                flatNotifications,
                dmNotifications,
                _enabledTypes
            ) { items: List<FlatNotificationItem>, dmItems: List<FlatNotificationItem>, enabled: Set<NotificationFilter> ->
                val chatEnabled = _chatRoomsEnabled.value
                (items + dmItems)
                    .filter { isItemEnabled(it, enabled, chatEnabled) }
                    .sortedByDescending { it.timestamp }
            }.collect { filtered -> _filteredFlatNotifications.value = filtered }
        }
        // Re-filter when chat rooms toggle changes
        viewModelScope.launch {
            _chatRoomsEnabled.collect { chatEnabled ->
                val enabled = _enabledTypes.value
                _filteredFlatNotifications.value = (flatNotifications.value + dmNotifications.value)
                    .filter { isItemEnabled(it, enabled, chatEnabled) }
                    .sortedByDescending { it.timestamp }
            }
        }
    }

    private fun startSummaryCombine() {
        val baseSummary = notifRepo?.summary24h ?: return
        viewModelScope.launch {
            combine(baseSummary, dmNotifications) { summary, dmItems ->
                val cutoff = System.currentTimeMillis() / 1000 - 86400L
                val dmCount = dmItems.count { it.timestamp >= cutoff }
                summary.copy(dmCount = dmCount)
            }.collect { _combinedSummary.value = it }
        }
    }

    fun toggleType(type: NotificationFilter) {
        val current = _enabledTypes.value.toMutableSet()
        if (type in current) current.remove(type) else current.add(type)
        _enabledTypes.value = current
        saveEnabledTypes(current)
    }

    fun toggleChatRooms() {
        val newValue = !_chatRoomsEnabled.value
        _chatRoomsEnabled.value = newValue
        settingsPrefs.edit().putBoolean(PREF_CHAT_ROOMS_NOTIF_ENABLED, newValue).apply()
    }

    /** Show only this notification type (single atomic update, no intermediate empty state). */
    fun isolateType(type: NotificationFilter) {
        _enabledTypes.value = setOf(type)
        saveEnabledTypes(setOf(type))
    }

    fun enableAll() {
        _enabledTypes.value = ALL_TYPES
        _chatRoomsEnabled.value = true
        saveEnabledTypes(ALL_TYPES)
        settingsPrefs.edit().putBoolean(PREF_CHAT_ROOMS_NOTIF_ENABLED, true).apply()
    }

    fun disableAll() {
        _enabledTypes.value = emptySet()
        _chatRoomsEnabled.value = false
        saveEnabledTypes(emptySet())
        settingsPrefs.edit().putBoolean(PREF_CHAT_ROOMS_NOTIF_ENABLED, false).apply()
    }

    private fun saveEnabledTypes(types: Set<NotificationFilter>) {
        settingsPrefs.edit().putStringSet(PREF_ENABLED_NOTIF_TYPES, types.map { it.name }.toSet()).apply()
    }

    private fun loadEnabledTypes(): Set<NotificationFilter> {
        val saved = settingsPrefs.getStringSet(PREF_ENABLED_NOTIF_TYPES, null)
        if (saved != null) {
            return saved.mapNotNull { name ->
                try { NotificationFilter.valueOf(name) } catch (_: IllegalArgumentException) { null }
            }.toSet()
        }
        // Migrate from old single-filter pref
        val oldFilter = settingsPrefs.getString(PREF_NOTIFICATION_FILTER, null)
        if (oldFilter != null) {
            settingsPrefs.edit().remove(PREF_NOTIFICATION_FILTER).apply()
        }
        // Migrate from old global sound toggle
        val soundEnabled = settingsPrefs.getBoolean("notif_sound_enabled", true)
        if (!soundEnabled) {
            settingsPrefs.edit().remove("notif_sound_enabled").apply()
            val empty = emptySet<NotificationFilter>()
            saveEnabledTypes(empty)
            return empty
        }
        return ALL_TYPES
    }

    fun isFollowing(pubkey: String): Boolean {
        return contactRepo?.isFollowing(pubkey) ?: false
    }

    fun refresh(onRefresh: () -> Unit) {
        _isRefreshing.value = true
        onRefresh()
        viewModelScope.launch {
            delay(3000)
            _isRefreshing.value = false
        }
    }

    fun markRead() {
        notifRepo?.markRead()
    }

    fun getProfileData(pubkey: String): ProfileData? {
        return eventRepo?.getProfileData(pubkey)
    }

    // ── Mention autocomplete ──────────────────────────────────────────────

    fun detectMentionQuery(value: TextFieldValue) {
        val text = value.text
        val cursor = value.selection.start

        if (cursor == 0 || text.isEmpty()) {
            clearMentionState()
            return
        }

        var atIndex = -1
        for (i in (cursor - 1) downTo 0) {
            val c = text[i]
            if (c == '@') {
                if (i == 0 || text[i - 1].isWhitespace()) {
                    atIndex = i
                }
                break
            }
            if (c.isWhitespace()) break
        }

        if (atIndex == -1) {
            clearMentionState()
            return
        }

        mentionStartIndex = atIndex
        val query = text.substring(atIndex + 1, cursor)
        _mentionQuery.value = query
        mentionSearchRepo?.search(query, viewModelScope)
    }

    fun selectMention(candidate: MentionCandidate, currentText: String, cursorPos: Int): TextFieldValue {
        if (mentionStartIndex < 0 || mentionStartIndex > currentText.length) {
            clearMentionState()
            return TextFieldValue(currentText, TextRange(cursorPos))
        }

        val nprofile = "nostr:" + Nip19.nprofileEncode(candidate.profile.pubkey)
        val before = currentText.substring(0, mentionStartIndex)
        val after = if (cursorPos < currentText.length) currentText.substring(cursorPos) else ""
        val newText = before + nprofile + " " + after
        val newCursor = before.length + nprofile.length + 1

        clearMentionState()
        return TextFieldValue(newText, TextRange(newCursor))
    }

    fun clearMentionState() {
        _mentionQuery.value = null
        mentionStartIndex = -1
        mentionSearchRepo?.clear()
    }

    // ── Inline DM Send ──────────────────────────────────────────────────

    private val _dmSending = MutableStateFlow(false)
    val dmSending: StateFlow<Boolean> = _dmSending

    fun sendDmReaction(peerPubkey: String, rumorId: String, senderPubkey: String, emoji: String, signer: NostrSigner? = null) {
        if (rumorId.isBlank()) return
        val pool = relayPool ?: return
        val repo = dmRepo ?: return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val recipientDmRelays = fetchRecipientDmRelays(peerPubkey, pool, repo)
                val deliveryRelays = resolveDeliveryRelays(peerPubkey, recipientDmRelays, pool)

                if (signer != null) {
                    val wrap = Nip17.createDmReactionRemote(signer, peerPubkey, rumorId, senderPubkey, emoji)
                    sendToDeliveryRelays(pool, deliveryRelays, ClientMessage.event(wrap))
                    val selfWrap = Nip17.createDmReactionRemote(signer, signer.pubkeyHex, rumorId, senderPubkey, emoji)
                    if (pool.hasDmRelays()) pool.sendToDmRelays(ClientMessage.event(selfWrap))
                    else pool.sendToWriteRelays(ClientMessage.event(selfWrap))
                } else {
                    val keypair = keyRepo.getKeypair() ?: return@launch
                    val wrap = Nip17.createDmReaction(keypair.privkey, keypair.pubkey, peerPubkey.hexToByteArray(), rumorId, senderPubkey, emoji)
                    sendToDeliveryRelays(pool, deliveryRelays, ClientMessage.event(wrap))
                    val selfWrap = Nip17.createDmReaction(keypair.privkey, keypair.pubkey, keypair.pubkey, rumorId, senderPubkey, emoji)
                    if (pool.hasDmRelays()) pool.sendToDmRelays(ClientMessage.event(selfWrap))
                    else pool.sendToWriteRelays(ClientMessage.event(selfWrap))
                }
            } catch (e: SignerCancelledException) {
                Log.w("NotifVM", "DM reaction signing cancelled", e)
            } catch (e: Exception) {
                Log.w("NotifVM", "DM reaction failed", e)
            }
        }
    }

    fun sendDm(peerPubkey: String, content: String, signer: NostrSigner? = null) {
        val text = content.trim()
        if (text.isBlank() || _dmSending.value) return
        val pool = relayPool ?: return
        val repo = dmRepo ?: return

        _dmSending.value = true

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val recipientDmRelays = fetchRecipientDmRelays(peerPubkey, pool, repo)
                val deliveryRelays = resolveDeliveryRelays(peerPubkey, recipientDmRelays, pool)

                val dmPowEnabled = powPrefs?.isDmPowEnabled() == true
                val dmDifficulty = if (dmPowEnabled) powPrefs?.getDmDifficulty() ?: 0 else 0

                if (signer != null) {
                    // Remote signer mode
                    val recipientWrap = Nip17.createGiftWrapRemote(
                        signer = signer,
                        recipientPubkeyHex = peerPubkey,
                        message = text,
                        targetDifficulty = dmDifficulty
                    )
                    val recipientMsg = ClientMessage.event(recipientWrap)
                    val sentRelayUrls = sendToDeliveryRelays(pool, deliveryRelays, recipientMsg)

                    val selfWrap = Nip17.createGiftWrapRemote(
                        signer = signer,
                        recipientPubkeyHex = signer.pubkeyHex,
                        message = text,
                        rumorPTag = peerPubkey,
                        targetDifficulty = dmDifficulty
                    )
                    val selfMsg = ClientMessage.event(selfWrap)
                    if (pool.hasDmRelays()) pool.sendToDmRelays(selfMsg)
                    else pool.sendToWriteRelays(selfMsg)

                    val convKey = DmRepository.conversationKey(listOf(peerPubkey, signer.pubkeyHex))
                    val dmMsg = DmMessage(
                        id = "${recipientWrap.id}:${System.currentTimeMillis() / 1000}",
                        senderPubkey = signer.pubkeyHex,
                        content = text,
                        createdAt = System.currentTimeMillis() / 1000,
                        giftWrapId = recipientWrap.id,
                        relayUrls = sentRelayUrls,
                        participants = listOf(peerPubkey)
                    )
                    repo.addMessage(dmMsg, convKey)
                    repo.markGiftWrapSeen(selfWrap.id, dmMsg.id)
                } else {
                    // Local signer mode
                    val keypair = keyRepo.getKeypair() ?: return@launch
                    val senderPubkeyHex = keypair.pubkey.toHex()

                    val recipientWrap = Nip17.createGiftWrap(
                        senderPrivkey = keypair.privkey,
                        senderPubkey = keypair.pubkey,
                        recipientPubkey = peerPubkey.hexToByteArray(),
                        message = text,
                        targetDifficulty = dmDifficulty
                    )
                    val recipientMsg = ClientMessage.event(recipientWrap)
                    val sentRelayUrls = sendToDeliveryRelays(pool, deliveryRelays, recipientMsg)

                    val selfWrap = Nip17.createGiftWrap(
                        senderPrivkey = keypair.privkey,
                        senderPubkey = keypair.pubkey,
                        recipientPubkey = keypair.pubkey,
                        message = text,
                        rumorPTag = peerPubkey,
                        targetDifficulty = dmDifficulty
                    )
                    val selfMsg = ClientMessage.event(selfWrap)
                    if (pool.hasDmRelays()) pool.sendToDmRelays(selfMsg)
                    else pool.sendToWriteRelays(selfMsg)

                    val convKey = DmRepository.conversationKey(listOf(peerPubkey, senderPubkeyHex))
                    val dmMsg = DmMessage(
                        id = "${recipientWrap.id}:${System.currentTimeMillis() / 1000}",
                        senderPubkey = senderPubkeyHex,
                        content = text,
                        createdAt = System.currentTimeMillis() / 1000,
                        giftWrapId = recipientWrap.id,
                        relayUrls = sentRelayUrls,
                        participants = listOf(peerPubkey)
                    )
                    repo.addMessage(dmMsg, convKey)
                    repo.markGiftWrapSeen(selfWrap.id, dmMsg.id)
                }
            } catch (e: SignerCancelledException) {
                Log.w("NotifVM", "DM signing cancelled", e)
            } catch (e: Exception) {
                Log.w("NotifVM", "DM send failed", e)
            } finally {
                _dmSending.value = false
            }
        }
    }

    private suspend fun fetchRecipientDmRelays(
        peerPubkey: String,
        pool: RelayPool,
        repo: DmRepository
    ): List<String> {
        repo.getCachedDmRelays(peerPubkey)?.let { return it }

        val subId = "dm_relay_${peerPubkey.take(8)}"
        val filter = Filter(
            kinds = listOf(Nip51.KIND_DM_RELAYS),
            authors = listOf(peerPubkey),
            limit = 1
        )
        val reqMsg = ClientMessage.req(subId, filter)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            pool.sendToRelayOrEphemeral(url, reqMsg, skipBadCheck = true)
        }
        pool.sendToAll(reqMsg)

        val result = withTimeoutOrNull(4000L) {
            pool.relayEvents.first { it.subscriptionId == subId }
        }

        val closeMsg = ClientMessage.close(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            pool.sendToRelay(url, closeMsg)
        }
        pool.sendToAll(closeMsg)

        if (result != null) {
            val urls = Nip51.parseRelaySet(result.event)
            if (urls.isNotEmpty()) {
                repo.cacheDmRelays(peerPubkey, urls)
                return urls
            }
        }
        return emptyList()
    }

    private suspend fun resolveDeliveryRelays(
        peerPubkey: String,
        recipientDmRelays: List<String>,
        pool: RelayPool
    ): List<String> {
        if (recipientDmRelays.isNotEmpty()) return recipientDmRelays

        if (relayListRepo?.hasRelayList(peerPubkey) != true) {
            fetchPeerRelayList(peerPubkey, pool)
        }
        relayListRepo?.getReadRelays(peerPubkey)?.takeIf { it.isNotEmpty() }?.let { return it }
        relayListRepo?.getWriteRelays(peerPubkey)?.takeIf { it.isNotEmpty() }?.let { return it }
        return pool.getWriteRelayUrls()
    }

    private suspend fun fetchPeerRelayList(peerPubkey: String, pool: RelayPool) {
        val repo = relayListRepo ?: return
        val subId = "rl_${peerPubkey.take(8)}"
        val filter = Filter(kinds = listOf(10002), authors = listOf(peerPubkey), limit = 1)
        val reqMsg = ClientMessage.req(subId, filter)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            pool.sendToRelayOrEphemeral(url, reqMsg, skipBadCheck = true)
        }
        pool.sendToAll(reqMsg)

        val result = withTimeoutOrNull(4000L) {
            pool.relayEvents.first { it.subscriptionId == subId }
        }

        val closeMsg = ClientMessage.close(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            pool.sendToRelay(url, closeMsg)
        }
        pool.sendToAll(closeMsg)

        if (result != null) repo.updateFromEvent(result.event)
    }

    private suspend fun sendToDeliveryRelays(
        pool: RelayPool,
        deliveryRelays: List<String>,
        message: String
    ): Set<String> {
        for (url in deliveryRelays) pool.markDmDeliveryTarget(url)
        val sent = mutableSetOf<String>()
        for (url in deliveryRelays) {
            try {
                if (pool.sendToRelayOrEphemeral(url, message, skipBadCheck = true)) {
                    sent.add(url)
                }
            } catch (e: Exception) {
                Log.w("NotifVM", "Failed to send DM to $url", e)
            }
        }
        return sent
    }
}
