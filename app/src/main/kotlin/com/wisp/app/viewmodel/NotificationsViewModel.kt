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
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.PowPreferences
import com.wisp.app.repo.RelayListRepository
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

enum class NotificationFilter(val label: String) {
    ALL("All"),
    REPLIES("Replies"),
    REACTIONS("Reactions"),
    ZAPS("Zaps"),
    REPOSTS("Reposts"),
    MENTIONS("Mentions"),
    VOTES("Votes"),
    DMS("DMs")
}

class NotificationsViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsPrefs = app.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NOTIFICATION_FILTER = "notification_filter"
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

    private val _filter = MutableStateFlow(loadSavedFilter())
    val filter: StateFlow<NotificationFilter> = _filter

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

    fun init(
        notificationRepository: NotificationRepository,
        eventRepository: EventRepository,
        contactRepository: ContactRepository,
        dmRepository: DmRepository? = null,
        relayPool: RelayPool? = null,
        relayListRepository: RelayListRepository? = null,
        powPreferences: PowPreferences? = null
    ) {
        notifRepo = notificationRepository
        eventRepo = eventRepository
        contactRepo = contactRepository
        dmRepo = dmRepository
        this.relayPool = relayPool
        relayListRepo = relayListRepository
        powPrefs = powPreferences
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

    private fun startFilterCombine() {
        viewModelScope.launch {
            combine(notifications, _filter) { notifs, filterType ->
                when (filterType) {
                    NotificationFilter.ALL -> notifs
                    NotificationFilter.REPLIES -> notifs.filterIsInstance<NotificationGroup.ReplyNotification>()
                    NotificationFilter.REACTIONS -> notifs.filterIsInstance<NotificationGroup.ReactionGroup>()
                    NotificationFilter.ZAPS -> notifs.filter {
                        it is NotificationGroup.ReactionGroup &&
                            NotificationGroup.ZAP_EMOJI in it.reactions
                    }
                    NotificationFilter.REPOSTS -> notifs.filter {
                        it is NotificationGroup.ReactionGroup &&
                            NotificationGroup.REPOST_EMOJI in it.reactions
                    }
                    NotificationFilter.MENTIONS -> notifs.filter {
                        it is NotificationGroup.MentionNotification || it is NotificationGroup.QuoteNotification
                    }
                    NotificationFilter.VOTES -> emptyList() // votes only exist in flat list
                    NotificationFilter.DMS -> emptyList() // DMs only exist in flat list
                }
            }.collect { _filteredNotifications.value = it }
        }
        viewModelScope.launch {
            combine(flatNotifications, dmNotifications, _filter) { items, dmItems, filterType ->
                val merged = when (filterType) {
                    NotificationFilter.ALL -> (items + dmItems).sortedByDescending { it.timestamp }
                    NotificationFilter.REPLIES -> items.filter { it.type == NotificationType.REPLY }
                    NotificationFilter.REACTIONS -> items.filter { it.type == NotificationType.REACTION }
                    NotificationFilter.ZAPS -> items.filter { it.type == NotificationType.ZAP }
                    NotificationFilter.REPOSTS -> items.filter { it.type == NotificationType.REPOST }
                    NotificationFilter.MENTIONS -> items.filter { it.type == NotificationType.MENTION || it.type == NotificationType.QUOTE }
                    NotificationFilter.VOTES -> items.filter { it.type == NotificationType.VOTE }
                    NotificationFilter.DMS -> dmItems
                }
                merged
            }.collect { _filteredFlatNotifications.value = it }
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

    fun setFilter(filter: NotificationFilter) {
        _filter.value = filter
        settingsPrefs.edit().putString(PREF_NOTIFICATION_FILTER, filter.name).apply()
    }

    private fun loadSavedFilter(): NotificationFilter {
        val saved = settingsPrefs.getString(PREF_NOTIFICATION_FILTER, null)
        return saved?.let {
            try { NotificationFilter.valueOf(it) } catch (_: IllegalArgumentException) { null }
        } ?: NotificationFilter.ALL
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

    // ── Inline DM Send ──────────────────────────────────────────────────

    private val _dmSending = MutableStateFlow(false)
    val dmSending: StateFlow<Boolean> = _dmSending

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

                    val dmMsg = DmMessage(
                        id = "${recipientWrap.id}:${System.currentTimeMillis() / 1000}",
                        senderPubkey = signer.pubkeyHex,
                        content = text,
                        createdAt = System.currentTimeMillis() / 1000,
                        giftWrapId = recipientWrap.id,
                        relayUrls = sentRelayUrls
                    )
                    repo.addMessage(dmMsg, peerPubkey)
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

                    val dmMsg = DmMessage(
                        id = "${recipientWrap.id}:${System.currentTimeMillis() / 1000}",
                        senderPubkey = senderPubkeyHex,
                        content = text,
                        createdAt = System.currentTimeMillis() / 1000,
                        giftWrapId = recipientWrap.id,
                        relayUrls = sentRelayUrls
                    )
                    repo.addMessage(dmMsg, peerPubkey)
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
