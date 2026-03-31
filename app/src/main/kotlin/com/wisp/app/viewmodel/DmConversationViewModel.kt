package com.wisp.app.viewmodel

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import android.app.Application
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.DmMessage
import com.wisp.app.nostr.DmReaction
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip13
import com.wisp.app.nostr.Nip17
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.SignerCancelledException
import com.wisp.app.repo.MuteRepository
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayEvent
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.PowPreferences
import com.wisp.app.repo.RelayListRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val DM_BARE_BECH32_REGEX = Regex("(?<!nostr:)(?<![a-z0-9/.:#])((note1|nevent1|npub1|nprofile1)[a-z0-9]{10,})")

enum class DeliveryRelaySource { DM_RELAYS, READ_RELAYS, WRITE_RELAYS, OWN_RELAYS }

data class PeerDeliveryRelays(
    val urls: List<String> = emptyList(),
    val source: DeliveryRelaySource = DeliveryRelaySource.DM_RELAYS
)

class DmConversationViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    private val blossomRepo = BlossomRepository(app, keyRepo.getPubkeyHex())

    private val _messages = MutableStateFlow<List<DmMessage>>(emptyList())
    val messages: StateFlow<List<DmMessage>> = _messages

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    private val _uploadProgress = MutableStateFlow<String?>(null)
    val uploadProgress: StateFlow<String?> = _uploadProgress

    private val _peerDeliveryRelays = MutableStateFlow(PeerDeliveryRelays())
    val peerDeliveryRelays: StateFlow<PeerDeliveryRelays> = _peerDeliveryRelays

    /** Relay info for every participant (pubkey → relays), used by the relay panel in group DMs. */
    private val _allParticipantRelays = MutableStateFlow<Map<String, PeerDeliveryRelays>>(emptyMap())
    val allParticipantRelays: StateFlow<Map<String, PeerDeliveryRelays>> = _allParticipantRelays

    private val _userDmRelays = MutableStateFlow<List<String>>(emptyList())
    val userDmRelays: StateFlow<List<String>> = _userDmRelays

    private val _decrypting = MutableStateFlow(false)
    val decrypting: StateFlow<Boolean> = _decrypting

    private val _pendingDecryptCount = MutableStateFlow(0)
    val pendingDecryptCount: StateFlow<Int> = _pendingDecryptCount

    private val _miningStatus = MutableStateFlow<PowStatus>(PowStatus.Idle)
    val miningStatus: StateFlow<PowStatus> = _miningStatus

    private val _replyingToMessage = MutableStateFlow<DmMessage?>(null)
    val replyingToMessage: StateFlow<DmMessage?> = _replyingToMessage

    private val _selectedMessageId = MutableStateFlow<String?>(null)
    val selectedMessageId: StateFlow<String?> = _selectedMessageId

    private var peerPubkey: String = ""
    private var conversationKey: String = ""
    /** All participants in this conversation, excluding the local user. */
    private var participants: List<String> = emptyList()
    val isGroup: Boolean get() = participants.size > 1

    private var dmRepo: DmRepository? = null
    private var relayListRepo: RelayListRepository? = null
    private var powPrefs: PowPreferences? = null

    fun setReplyingTo(message: DmMessage?) {
        _replyingToMessage.value = message
        if (message != null) _selectedMessageId.value = null
    }

    fun clearReply() {
        _replyingToMessage.value = null
    }

    fun selectMessage(id: String?) {
        _selectedMessageId.value = if (_selectedMessageId.value == id) null else id
    }

    fun init(
        peerPubkeyHex: String,
        dmRepository: DmRepository,
        relayListRepository: RelayListRepository? = null,
        relayPool: RelayPool? = null,
        powPreferences: PowPreferences? = null,
        myPubkeyHex: String? = null,
        participantPubkeys: List<String> = emptyList()
    ) {
        peerPubkey = peerPubkeyHex
        participants = participantPubkeys.ifEmpty { listOf(peerPubkeyHex) }
        val myKey = myPubkeyHex ?: keyRepo.getPubkeyHex() ?: ""
        conversationKey = DmRepository.conversationKey(participants + myKey)

        dmRepo = dmRepository
        relayListRepo = relayListRepository
        powPrefs = powPreferences
        _messages.value = dmRepository.getConversation(conversationKey)

        // Expose user's own DM relays
        if (relayPool != null) {
            _userDmRelays.value = relayPool.getDmRelayUrls()
        }

        // Fetch DM relays for all participants; populate allParticipantRelays for the relay panel
        if (relayPool != null) {
            viewModelScope.launch {
                val map = mutableMapOf<String, PeerDeliveryRelays>()

                // Seed from cache for all participants
                for (p in participants) {
                    val cached = dmRepository.getCachedDmRelays(p)
                    if (!cached.isNullOrEmpty()) {
                        map[p] = PeerDeliveryRelays(cached, DeliveryRelaySource.DM_RELAYS)
                    }
                }
                if (map.isNotEmpty()) _allParticipantRelays.value = map.toMap()

                // Fetch fresh for primary peer
                val cached = dmRepository.getCachedDmRelays(peerPubkeyHex)
                if (cached != null) {
                    _peerDeliveryRelays.value = PeerDeliveryRelays(cached, DeliveryRelaySource.DM_RELAYS)
                }
                val fetched = fetchRecipientDmRelays(relayPool, forceRefresh = true)
                val primaryRelays = when {
                    fetched.isNotEmpty() -> PeerDeliveryRelays(fetched, DeliveryRelaySource.DM_RELAYS)
                    cached.isNullOrEmpty() -> resolveRecipientRelaysWithSource(emptyList(), relayPool)
                    else -> _peerDeliveryRelays.value
                }
                _peerDeliveryRelays.value = primaryRelays
                map[peerPubkeyHex] = primaryRelays
                _allParticipantRelays.value = map.toMap()

                // Fetch fresh for additional participants (group DMs)
                for (p in participants.filter { it != peerPubkeyHex }) {
                    val relays = fetchRelaysForParticipant(p, relayPool)
                    if (relays.isNotEmpty()) {
                        map[p] = PeerDeliveryRelays(relays, DeliveryRelaySource.DM_RELAYS)
                    }
                }
                if (participants.size > 1) _allParticipantRelays.value = map.toMap()
            }
        }

        viewModelScope.launch {
            dmRepository.conversationList.collect {
                _messages.value = dmRepository.getConversation(conversationKey)
            }
        }

        // Mirror the repo's decrypting/pending state for the UI
        viewModelScope.launch {
            dmRepository.decrypting.collect { _decrypting.value = it }
        }
        viewModelScope.launch {
            dmRepository.pendingDecryptCount.collect { _pendingDecryptCount.value = it }
        }

        // Fetch historical zap receipts for messages already in this conversation
        if (relayPool != null) {
            viewModelScope.launch {
                fetchDmZapReceipts(relayPool)
            }
        }
    }

    /**
     * Query relays for 9735 zap receipts targeting any known rumorId in this conversation.
     * Runs once on open to restore badges from previous sessions.
     */
    private suspend fun fetchDmZapReceipts(relayPool: RelayPool) {
        val rumorIds = _messages.value.mapNotNull { it.rumorId.ifEmpty { null } }
        if (rumorIds.isEmpty()) return
        val subId = "zap-rcpt-dm-${conversationKey.take(16)}"
        val filter = Filter(kinds = listOf(9735), eTags = rumorIds)
        val msg = ClientMessage.req(subId, filter)
        relayPool.sendToReadRelays(msg)
        if (relayPool.hasDmRelays()) relayPool.sendToDmRelays(msg)
        // Close after a short window — results arrive via EventRouter's zap-rcpt-* handler
        delay(8000)
        relayPool.closeOnAllRelays(subId)
    }

    /**
     * Decrypt pending gift wraps using the remote signer.
     * Shares the same pending queue as DmListViewModel — both screens can drive
     * decryption and progress is visible from either.
     */
    fun decryptPending(signer: NostrSigner, muteRepo: MuteRepository? = null) {
        val repo = dmRepo ?: return
        val myPubkey = signer.pubkeyHex

        viewModelScope.launch(Dispatchers.Default) {
            if (repo.pendingDecryptCount.value == 0) return@launch

            repo.markDecryptingStart()
            try {
                while (true) {
                    val wrap = repo.takeNextPendingGiftWrap() ?: break
                    try {
                        val rumor = Nip17.unwrapGiftWrapRemote(signer, wrap.event) ?: continue

                        if (Nip17.isReaction(rumor)) {
                            val targetId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                                ?: continue
                            val pList = Nip17.getConversationParticipants(rumor, myPubkey)
                            val convKey = DmRepository.conversationKey(pList + myPubkey)
                            repo.addReaction(convKey, targetId, DmReaction(rumor.pubkey, rumor.content.trim(), rumor.createdAt))
                            continue
                        }

                        val pList = Nip17.getConversationParticipants(rumor, myPubkey)
                        if (pList.any { muteRepo?.isBlocked(it) == true }) continue

                        val convKey = DmRepository.conversationKey(pList + myPubkey)
                        val replyToId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" && it.any { v -> v == "reply" } }?.get(1)
                        val rumorId = Nip17.computeRumorId(rumor)
                        val msg = DmMessage(
                            id = "${wrap.event.id}:${rumor.createdAt}",
                            senderPubkey = rumor.pubkey,
                            content = rumor.content,
                            createdAt = rumor.createdAt,
                            giftWrapId = wrap.event.id,
                            relayUrls = if (wrap.relayUrl.isNotEmpty()) setOf(wrap.relayUrl) else emptySet(),
                            rumorId = rumorId,
                            replyToId = replyToId,
                            participants = pList,
                            debugGiftWrapJson = wrap.event.toJson(),
                            debugRumorJson = Nip17.rumorToJson(rumor)
                        )
                        repo.addMessage(msg, convKey)
                    } catch (_: Exception) {
                        // Individual wrap failed, continue with the rest
                    }
                }
            } finally {
                repo.markDecryptingEnd()
            }
        }
    }

    fun updateMessageText(value: String) {
        val prefixed = DM_BARE_BECH32_REGEX.find(value)?.let { match ->
            val bare = match.groupValues[1]
            val valid = try { Nip19.decodeNostrUri("nostr:$bare") != null } catch (_: Exception) { false }
            if (valid) value.substring(0, match.range.first) + "nostr:" + value.substring(match.range.first)
            else value
        } ?: value
        _messageText.value = prefixed
    }

    fun clearSendError() {
        _sendError.value = null
    }

    fun uploadMedia(uris: List<Uri>, contentResolver: ContentResolver, signer: NostrSigner? = null) {
        viewModelScope.launch {
            val total = uris.size
            for ((index, uri) in uris.withIndex()) {
                try {
                    _uploadProgress.value = if (total > 1) "Uploading ${index + 1}/$total..." else "Uploading..."
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw Exception("Cannot read file")
                    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                    val url = blossomRepo.uploadMedia(bytes, mimeType, ext, signer)
                    val current = _messageText.value
                    _messageText.value = if (current.isBlank()) url else "$current\n$url"
                } catch (e: Exception) {
                    _sendError.value = "Upload failed: ${e.message}"
                    break
                }
            }
            _uploadProgress.value = null
        }
    }

    /**
     * Fetch recipient's kind 10050 DM relays from indexer relays.
     * When [forceRefresh] is false, returns cached relays if available.
     * When true, always queries indexer relays for the freshest relay set.
     */
    private suspend fun fetchRecipientDmRelays(relayPool: RelayPool, forceRefresh: Boolean = false): List<String> {
        val repo = dmRepo ?: return emptyList()

        // Cache hit (skip when forcing a refresh)
        if (!forceRefresh) {
            repo.getCachedDmRelays(peerPubkey)?.let { return it }
        }

        // Send REQ for kind 10050 to indexer relays (most likely to have relay metadata)
        val subId = "dm_relay_${peerPubkey.take(8)}"
        val filter = Filter(
            kinds = listOf(Nip51.KIND_DM_RELAYS),
            authors = listOf(peerPubkey),
            limit = 1
        )
        val reqMsg = ClientMessage.req(subId, filter)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, reqMsg, skipBadCheck = true)
        }
        // Also broadcast to connected relays for additional coverage
        relayPool.sendToAll(reqMsg)

        // Collect all responses within 4s; pick the freshest (highest created_at)
        val results = mutableListOf<RelayEvent>()
        withTimeoutOrNull(4000L) {
            relayPool.relayEvents
                .filter { it.subscriptionId == subId }
                .collect { results.add(it) }
        }

        // Close subscription
        val closeMsg = ClientMessage.close(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelay(url, closeMsg)
        }
        relayPool.sendToAll(closeMsg)

        val best = results.maxByOrNull { it.event.created_at }
        if (best != null) {
            val urls = Nip51.parseRelaySet(best.event)
            if (urls.isNotEmpty()) {
                repo.cacheDmRelays(peerPubkey, urls)
                return urls
            }
        }
        return emptyList()
    }

    /**
     * Resolve which relays to send to for the recipient, with fallback chain:
     * 1. Recipient's kind 10050 DM relays
     * 2. Recipient's kind 10002 read/inbox relays
     * 3. Recipient's kind 10002 write relays
     * 4. Sender's own write relays
     *
     * If the peer's relay list (kind 10002) isn't cached, fetches it from indexers first.
     */
    private suspend fun resolveRecipientRelaysWithSource(
        recipientDmRelays: List<String>,
        relayPool: RelayPool
    ): PeerDeliveryRelays {
        if (recipientDmRelays.isNotEmpty())
            return PeerDeliveryRelays(recipientDmRelays, DeliveryRelaySource.DM_RELAYS)

        // Always fetch the freshest kind 10002 relay list — cached version may be stale
        fetchPeerRelayList(peerPubkey, relayPool)

        val readRelays = relayListRepo?.getReadRelays(peerPubkey)
        if (!readRelays.isNullOrEmpty())
            return PeerDeliveryRelays(readRelays, DeliveryRelaySource.READ_RELAYS)

        val writeRelays = relayListRepo?.getWriteRelays(peerPubkey)
        if (!writeRelays.isNullOrEmpty())
            return PeerDeliveryRelays(writeRelays, DeliveryRelaySource.WRITE_RELAYS)

        return PeerDeliveryRelays(relayPool.getWriteRelayUrls(), DeliveryRelaySource.OWN_RELAYS)
    }

    /**
     * Fetch a pubkey's kind 10002 relay list from indexer relays.
     */
    private suspend fun fetchPeerRelayList(pubkeyHex: String, relayPool: RelayPool) {
        val repo = relayListRepo ?: return

        val subId = "rl_${pubkeyHex.take(8)}"
        val filter = Filter(
            kinds = listOf(10002),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val reqMsg = ClientMessage.req(subId, filter)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, reqMsg, skipBadCheck = true)
        }
        relayPool.sendToAll(reqMsg)

        // Collect all responses within 4s; pick the freshest (highest created_at)
        val results = mutableListOf<RelayEvent>()
        withTimeoutOrNull(4000L) {
            relayPool.relayEvents
                .filter { it.subscriptionId == subId }
                .collect { results.add(it) }
        }

        val closeMsg = ClientMessage.close(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelay(url, closeMsg)
        }
        relayPool.sendToAll(closeMsg)

        val best = results.maxByOrNull { it.event.created_at }
        if (best != null) {
            repo.updateFromEvent(best.event)
        }
    }

    /**
     * Full fallback relay resolution for any group participant.
     * Used when kind 10050 fetch returns nothing: tries kind 10002 read → write → own write relays.
     */
    private suspend fun resolveRelaysForParticipant(pubkeyHex: String, relayPool: RelayPool): List<String> {
        fetchPeerRelayList(pubkeyHex, relayPool)
        val readRelays = relayListRepo?.getReadRelays(pubkeyHex)
        if (!readRelays.isNullOrEmpty()) return readRelays
        val writeRelays = relayListRepo?.getWriteRelays(pubkeyHex)
        if (!writeRelays.isNullOrEmpty()) return writeRelays
        return relayPool.getWriteRelayUrls()
    }

    fun sendMessage(relayPool: RelayPool, signer: NostrSigner? = null, resolvedEmojis: Map<String, String> = emptyMap()) {
        val text = _messageText.value.trim()
        if (text.isBlank() || _sending.value) return
        val replyingTo = _replyingToMessage.value

        val replyTags: List<List<String>> = if (replyingTo?.rumorId?.isNotEmpty() == true) {
            listOf(listOf("e", replyingTo.rumorId, "", "reply"))
        } else emptyList()

        // Build the full p-tag list for the rumor (all participants)
        val allParticipantTags: List<List<String>> = if (isGroup) {
            participants.drop(1).map { listOf("p", it) } // extra p-tags beyond the first
        } else emptyList()

        // Remote signer mode: use signer, no keypair needed
        if (signer != null) {
            _messageText.value = ""
            _sendError.value = null
            _sending.value = true
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val recipientDmRelays = fetchRecipientDmRelays(relayPool)
                    val deliveryRelays = resolveRecipientRelaysWithSource(recipientDmRelays, relayPool).urls

                    val dmPowEnabled = powPrefs?.isDmPowEnabled() == true
                    val dmDifficulty = if (dmPowEnabled) powPrefs?.getDmDifficulty() ?: 0 else 0
                    if (dmDifficulty > 0) {
                        _miningStatus.value = PowStatus.Mining(1059, 0, dmDifficulty)
                    }

                    val emojiTags = com.wisp.app.nostr.Nip30.buildEmojiTagsForContent(text, resolvedEmojis)
                    val combinedTags = replyTags + allParticipantTags + emojiTags

                    // Fix rumorId consistency: compute timestamp before any wraps so all recipients
                    // decrypt the same rumor (identical tags + created_at → identical rumorId).
                    val rumorCreatedAt = System.currentTimeMillis() / 1000
                    val rumor = Nip17.buildRumor(
                        senderPubkeyHex = signer.pubkeyHex,
                        message = text,
                        pTag = peerPubkey,
                        replyTags = combinedTags,
                        createdAt = rumorCreatedAt
                    )
                    val rumorId = Nip17.computeRumorId(rumor)

                    var firstSentRelayUrls = emptySet<String>()

                    // Send a separate gift wrap to each participant.
                    // rumorPTag = peerPubkey so all wraps encode the same rumor (same ID).
                    for (recipient in participants) {
                        val wrap = Nip17.createGiftWrapRemote(
                            signer = signer,
                            recipientPubkeyHex = recipient,
                            message = text,
                            replyTags = combinedTags,
                            rumorPTag = peerPubkey,
                            targetDifficulty = if (recipient == peerPubkey) dmDifficulty else 0,
                            onProgress = if (recipient == peerPubkey) { attempts ->
                                _miningStatus.value = PowStatus.Mining(1059, attempts, dmDifficulty)
                            } else null,
                            createdAt = rumorCreatedAt
                        )
                        val recipientRelays = if (recipient == peerPubkey) {
                            deliveryRelays
                        } else {
                            val dmRelays = fetchRelaysForParticipant(recipient, relayPool)
                            dmRelays.ifEmpty { resolveRelaysForParticipant(recipient, relayPool) }
                        }
                        val sent = sendToDeliveryRelays(relayPool, recipientRelays, ClientMessage.event(wrap))
                        if (recipient == peerPubkey) firstSentRelayUrls = sent
                    }

                    if (firstSentRelayUrls.isEmpty() && participants.size == 1) {
                        _messageText.value = text
                        _sendError.value = "Failed to deliver — no relays accepted the message"
                    }

                    val selfWrap = Nip17.createGiftWrapRemote(
                        signer = signer,
                        recipientPubkeyHex = signer.pubkeyHex,
                        message = text,
                        replyTags = combinedTags,
                        rumorPTag = peerPubkey,
                        targetDifficulty = dmDifficulty,
                        createdAt = rumorCreatedAt
                    )
                    val selfMsg = ClientMessage.event(selfWrap)
                    if (relayPool.hasDmRelays()) {
                        relayPool.sendToDmRelays(selfMsg)
                    } else {
                        relayPool.sendToWriteRelays(selfMsg)
                    }

                    val dmMsg = DmMessage(
                        id = "${selfWrap.id}:$rumorCreatedAt",
                        senderPubkey = signer.pubkeyHex,
                        content = text,
                        createdAt = rumorCreatedAt,
                        giftWrapId = selfWrap.id,
                        relayUrls = firstSentRelayUrls,
                        rumorId = rumorId,
                        replyToId = replyingTo?.rumorId,
                        participants = participants
                    )
                    dmRepo?.addMessage(dmMsg, conversationKey)
                    dmRepo?.markGiftWrapSeen(selfWrap.id, dmMsg.id)
                    clearReply()
                } catch (e: SignerCancelledException) {
                    _messageText.value = text
                    _sendError.value = "Signing cancelled"
                } catch (e: Exception) {
                    _messageText.value = text
                    _sendError.value = "Failed to send message"
                    Log.w("DmConversation", "Send failed (remote signer)", e)
                } finally {
                    _sending.value = false
                    _miningStatus.value = PowStatus.Idle
                }
            }
            return
        }

        // Local signer mode
        val keypair = keyRepo.getKeypair() ?: return

        _messageText.value = ""
        _sendError.value = null
        _sending.value = true

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val senderPubkeyHex = keypair.pubkey.toHex()
                val emojiTags2 = com.wisp.app.nostr.Nip30.buildEmojiTagsForContent(text, resolvedEmojis)
                val combinedTags = replyTags + allParticipantTags + emojiTags2

                val recipientDmRelays = fetchRecipientDmRelays(relayPool)
                val deliveryRelays = resolveRecipientRelaysWithSource(recipientDmRelays, relayPool).urls

                val dmPowEnabled = powPrefs?.isDmPowEnabled() == true
                val dmDifficulty = if (dmPowEnabled) powPrefs?.getDmDifficulty() ?: 0 else 0
                if (dmDifficulty > 0) {
                    _miningStatus.value = PowStatus.Mining(1059, 0, dmDifficulty)
                }

                // Fix rumorId consistency: compute timestamp before any wraps so all recipients
                // decrypt the same rumor (identical tags + created_at → identical rumorId).
                val rumorCreatedAt = System.currentTimeMillis() / 1000
                val rumor = Nip17.buildRumor(
                    senderPubkeyHex = senderPubkeyHex,
                    message = text,
                    pTag = peerPubkey,
                    replyTags = combinedTags,
                    createdAt = rumorCreatedAt
                )
                val rumorId = Nip17.computeRumorId(rumor)

                var firstSentRelayUrls = emptySet<String>()

                // Send a separate gift wrap to each participant.
                // rumorPTag = peerPubkey so all wraps encode the same rumor (same ID).
                for (recipient in participants) {
                    val wrap = Nip17.createGiftWrap(
                        senderPrivkey = keypair.privkey,
                        senderPubkey = keypair.pubkey,
                        recipientPubkey = recipient.hexToByteArray(),
                        message = text,
                        replyTags = combinedTags,
                        rumorPTag = peerPubkey,
                        targetDifficulty = if (recipient == peerPubkey) dmDifficulty else 0,
                        onProgress = if (recipient == peerPubkey) { attempts ->
                            _miningStatus.value = PowStatus.Mining(1059, attempts, dmDifficulty)
                        } else null,
                        createdAt = rumorCreatedAt
                    )
                    val recipientRelays = if (recipient == peerPubkey) {
                        deliveryRelays
                    } else {
                        val dmRelays = fetchRelaysForParticipant(recipient, relayPool)
                        dmRelays.ifEmpty { resolveRelaysForParticipant(recipient, relayPool) }
                    }
                    val sent = sendToDeliveryRelays(relayPool, recipientRelays, ClientMessage.event(wrap))
                    if (recipient == peerPubkey) firstSentRelayUrls = sent
                }

                if (firstSentRelayUrls.isEmpty() && participants.size == 1) {
                    _messageText.value = text
                    _sendError.value = "Failed to deliver — no relays accepted the message"
                }

                val selfWrap = Nip17.createGiftWrap(
                    senderPrivkey = keypair.privkey,
                    senderPubkey = keypair.pubkey,
                    recipientPubkey = keypair.pubkey,
                    message = text,
                    replyTags = combinedTags,
                    rumorPTag = peerPubkey,
                    targetDifficulty = dmDifficulty,
                    createdAt = rumorCreatedAt
                )
                val selfMsg = ClientMessage.event(selfWrap)
                if (relayPool.hasDmRelays()) {
                    relayPool.sendToDmRelays(selfMsg)
                } else {
                    relayPool.sendToWriteRelays(selfMsg)
                }

                val dmMsg = DmMessage(
                    id = "${selfWrap.id}:$rumorCreatedAt",
                    senderPubkey = senderPubkeyHex,
                    content = text,
                    createdAt = rumorCreatedAt,
                    giftWrapId = selfWrap.id,
                    relayUrls = firstSentRelayUrls,
                    rumorId = rumorId,
                    replyToId = replyingTo?.rumorId,
                    participants = participants
                )
                dmRepo?.addMessage(dmMsg, conversationKey)
                dmRepo?.markGiftWrapSeen(selfWrap.id, dmMsg.id)
                clearReply()
            } catch (e: Exception) {
                _messageText.value = text
                _sendError.value = "Failed to send message"
                Log.w("DmConversation", "Send failed (local signer)", e)
            } finally {
                _sending.value = false
                _miningStatus.value = PowStatus.Idle
            }
        }
    }

    /**
     * Send a private DM reaction (gift-wrapped kind 14) to all conversation participants.
     */
    fun sendReaction(targetRumorId: String, emoji: String, relayPool: RelayPool, signer: NostrSigner? = null, resolvedEmojis: Map<String, String> = emptyMap()) {
        val originalSenderPubkey = _messages.value.firstOrNull { it.rumorId == targetRumorId }?.senderPubkey
            ?: return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val myPubkey = signer?.pubkeyHex ?: keyRepo.getKeypair()?.pubkey?.toHex() ?: return@launch
                // Resolve custom emoji URL if this is a shortcode reaction
                val emojiUrl = if (emoji.startsWith(":") && emoji.endsWith(":")) {
                    resolvedEmojis[emoji.removeSurrounding(":")]
                } else null
                // Optimistic local update — show the reaction immediately
                dmRepo?.addReaction(conversationKey, targetRumorId, DmReaction(myPubkey, emoji, System.currentTimeMillis() / 1000))

                if (signer != null) {
                    for (recipient in participants) {
                        val wrap = Nip17.createDmReactionRemote(signer, recipient, targetRumorId, originalSenderPubkey, emoji, emojiUrl)
                        val relays = fetchRelaysForParticipant(recipient, relayPool)
                            .ifEmpty { resolveRelaysForParticipant(recipient, relayPool) }
                        sendToDeliveryRelays(relayPool, relays, ClientMessage.event(wrap))
                    }
                    // Self-copy
                    val selfWrap = Nip17.createDmReactionRemote(signer, signer.pubkeyHex, targetRumorId, originalSenderPubkey, emoji, emojiUrl)
                    if (relayPool.hasDmRelays()) relayPool.sendToDmRelays(ClientMessage.event(selfWrap))
                    else relayPool.sendToWriteRelays(ClientMessage.event(selfWrap))
                } else {
                    val keypair = keyRepo.getKeypair() ?: return@launch
                    for (recipient in participants) {
                        val wrap = Nip17.createDmReaction(keypair.privkey, keypair.pubkey, recipient.hexToByteArray(), targetRumorId, originalSenderPubkey, emoji, emojiUrl)
                        val relays = fetchRelaysForParticipant(recipient, relayPool)
                            .ifEmpty { resolveRelaysForParticipant(recipient, relayPool) }
                        sendToDeliveryRelays(relayPool, relays, ClientMessage.event(wrap))
                    }
                    // Self-copy
                    val selfWrap = Nip17.createDmReaction(keypair.privkey, keypair.pubkey, keypair.pubkey, targetRumorId, originalSenderPubkey, emoji, emojiUrl)
                    if (relayPool.hasDmRelays()) relayPool.sendToDmRelays(ClientMessage.event(selfWrap))
                    else relayPool.sendToWriteRelays(ClientMessage.event(selfWrap))
                }
            } catch (e: Exception) {
                Log.w("DmConversation", "sendReaction failed", e)
            }
        }
    }

    /**
     * Fetch kind 10050 DM relays for any participant.
     * Always goes to the network (no cache short-circuit) and broadcasts to all connected
     * relays in addition to indexers, matching the aggressiveness of fetchRecipientDmRelays.
     */
    private suspend fun fetchRelaysForParticipant(pubkey: String, relayPool: RelayPool): List<String> {
        val subId = "dm_relay_${pubkey.take(8)}"
        val filter = Filter(kinds = listOf(Nip51.KIND_DM_RELAYS), authors = listOf(pubkey), limit = 1)
        val reqMsg = ClientMessage.req(subId, filter)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, reqMsg, skipBadCheck = true)
        }
        relayPool.sendToAll(reqMsg)

        // Collect all responses within 4s; pick the freshest (highest created_at)
        val results = mutableListOf<RelayEvent>()
        withTimeoutOrNull(4000L) {
            relayPool.relayEvents
                .filter { it.subscriptionId == subId }
                .collect { results.add(it) }
        }

        val closeMsg = ClientMessage.close(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) relayPool.sendToRelay(url, closeMsg)
        relayPool.sendToAll(closeMsg)

        val best = results.maxByOrNull { it.event.created_at }
        if (best != null) {
            val urls = Nip51.parseRelaySet(best.event)
            if (urls.isNotEmpty()) {
                dmRepo?.cacheDmRelays(pubkey, urls)
                return urls
            }
        }
        return emptyList()
    }

    /**
     * Send a message to delivery relays, awaiting ephemeral connections.
     * Skips health checks since these are the recipient's chosen relays.
     * Tries all relays independently — failure on one doesn't block others.
     */
    private suspend fun sendToDeliveryRelays(
        relayPool: RelayPool,
        deliveryRelays: List<String>,
        message: String
    ): Set<String> {
        // Tag each delivery relay so RelayPool knows it's tier 2 for AUTH
        for (url in deliveryRelays) {
            relayPool.markDmDeliveryTarget(url)
        }
        val sentRelayUrls = mutableSetOf<String>()
        for (url in deliveryRelays) {
            try {
                if (relayPool.sendToRelayOrEphemeral(url, message, skipBadCheck = true)) {
                    sentRelayUrls.add(url)
                }
            } catch (e: Exception) {
                Log.w("DmConversation", "Failed to send to relay $url", e)
            }
        }
        return sentRelayUrls
    }
}
