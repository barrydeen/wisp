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
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip17
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.SignerCancelledException
import com.wisp.app.repo.MuteRepository
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.RelayListRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    private val _userDmRelays = MutableStateFlow<List<String>>(emptyList())
    val userDmRelays: StateFlow<List<String>> = _userDmRelays

    private val _decrypting = MutableStateFlow(false)
    val decrypting: StateFlow<Boolean> = _decrypting

    private val _pendingDecryptCount = MutableStateFlow(0)
    val pendingDecryptCount: StateFlow<Int> = _pendingDecryptCount

    private var peerPubkey: String = ""
    private var dmRepo: DmRepository? = null
    private var relayListRepo: RelayListRepository? = null

    fun init(peerPubkeyHex: String, dmRepository: DmRepository, relayListRepository: RelayListRepository? = null, relayPool: RelayPool? = null) {
        peerPubkey = peerPubkeyHex
        dmRepo = dmRepository
        relayListRepo = relayListRepository
        _messages.value = dmRepository.getConversation(peerPubkeyHex)

        // Expose user's own DM relays
        if (relayPool != null) {
            _userDmRelays.value = relayPool.getDmRelayUrls()
        }

        // Fetch peer's DM relays and resolve delivery relays with fallback chain
        if (relayPool != null) {
            viewModelScope.launch {
                val cached = dmRepository.getCachedDmRelays(peerPubkeyHex)
                if (cached != null) {
                    _peerDeliveryRelays.value = PeerDeliveryRelays(cached, DeliveryRelaySource.DM_RELAYS)
                }
                // Always fetch fresh relays from indexers to ensure we have the latest
                val fetched = fetchRecipientDmRelays(relayPool, forceRefresh = true)
                if (fetched.isNotEmpty()) {
                    _peerDeliveryRelays.value = PeerDeliveryRelays(fetched, DeliveryRelaySource.DM_RELAYS)
                } else if (cached.isNullOrEmpty()) {
                    // No DM relays found — resolve fallback chain
                    _peerDeliveryRelays.value = resolveRecipientRelaysWithSource(emptyList(), relayPool)
                }
            }
        }

        viewModelScope.launch {
            dmRepository.conversationList.collect {
                _messages.value = dmRepository.getConversation(peerPubkey)
            }
        }

        // Mirror the repo's decrypting/pending state for the UI
        viewModelScope.launch {
            dmRepository.decrypting.collect { _decrypting.value = it }
        }
        viewModelScope.launch {
            dmRepository.pendingDecryptCount.collect { _pendingDecryptCount.value = it }
        }
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
                        val peerPubkey = if (rumor.pubkey == myPubkey) {
                            rumor.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1) ?: continue
                        } else {
                            rumor.pubkey
                        }

                        if (muteRepo?.isBlocked(peerPubkey) == true) continue

                        val msg = DmMessage(
                            id = "${wrap.event.id}:${rumor.createdAt}",
                            senderPubkey = rumor.pubkey,
                            content = rumor.content,
                            createdAt = rumor.createdAt,
                            giftWrapId = wrap.event.id,
                            relayUrls = if (wrap.relayUrl.isNotEmpty()) setOf(wrap.relayUrl) else emptySet()
                        )
                        repo.addMessage(msg, peerPubkey)
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
        _messageText.value = value
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

        // Wait up to 4s for a matching event
        val result = withTimeoutOrNull(4000L) {
            relayPool.relayEvents.first { it.subscriptionId == subId }
        }

        // Close subscription
        val closeMsg = ClientMessage.close(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelay(url, closeMsg)
        }
        relayPool.sendToAll(closeMsg)

        if (result != null) {
            val urls = Nip51.parseRelaySet(result.event)
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

        // Ensure we have the peer's kind 10002 relay list
        if (relayListRepo?.hasRelayList(peerPubkey) != true) {
            fetchPeerRelayList(relayPool)
        }

        val readRelays = relayListRepo?.getReadRelays(peerPubkey)
        if (!readRelays.isNullOrEmpty())
            return PeerDeliveryRelays(readRelays, DeliveryRelaySource.READ_RELAYS)

        val writeRelays = relayListRepo?.getWriteRelays(peerPubkey)
        if (!writeRelays.isNullOrEmpty())
            return PeerDeliveryRelays(writeRelays, DeliveryRelaySource.WRITE_RELAYS)

        return PeerDeliveryRelays(relayPool.getWriteRelayUrls(), DeliveryRelaySource.OWN_RELAYS)
    }

    /**
     * Fetch peer's kind 10002 relay list from indexer relays if not already cached.
     */
    private suspend fun fetchPeerRelayList(relayPool: RelayPool) {
        val repo = relayListRepo ?: return

        val subId = "rl_${peerPubkey.take(8)}"
        val filter = Filter(
            kinds = listOf(10002),
            authors = listOf(peerPubkey),
            limit = 1
        )
        val reqMsg = ClientMessage.req(subId, filter)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, reqMsg, skipBadCheck = true)
        }
        relayPool.sendToAll(reqMsg)

        val result = withTimeoutOrNull(4000L) {
            relayPool.relayEvents.first { it.subscriptionId == subId }
        }

        val closeMsg = ClientMessage.close(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelay(url, closeMsg)
        }
        relayPool.sendToAll(closeMsg)

        if (result != null) {
            repo.updateFromEvent(result.event)
        }
    }

    fun sendMessage(relayPool: RelayPool, signer: NostrSigner? = null) {
        val text = _messageText.value.trim()
        if (text.isBlank() || _sending.value) return

        // Remote signer mode: use signer, no keypair needed
        if (signer != null) {
            _messageText.value = ""
            _sendError.value = null
            _sending.value = true
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val recipientDmRelays = fetchRecipientDmRelays(relayPool)
                    val deliveryRelays = resolveRecipientRelaysWithSource(recipientDmRelays, relayPool).urls

                    val recipientWrap = Nip17.createGiftWrapRemote(
                        signer = signer,
                        recipientPubkeyHex = peerPubkey,
                        message = text
                    )
                    val recipientMsg = ClientMessage.event(recipientWrap)
                    val sentRelayUrls = sendToDeliveryRelays(relayPool, deliveryRelays, recipientMsg)

                    if (sentRelayUrls.isEmpty()) {
                        _messageText.value = text
                        _sendError.value = "Failed to deliver — no relays accepted the message"
                    }

                    val selfWrap = Nip17.createGiftWrapRemote(
                        signer = signer,
                        recipientPubkeyHex = signer.pubkeyHex,
                        message = text,
                        rumorPTag = peerPubkey
                    )
                    val selfMsg = ClientMessage.event(selfWrap)
                    if (relayPool.hasDmRelays()) {
                        relayPool.sendToDmRelays(selfMsg)
                    } else {
                        relayPool.sendToWriteRelays(selfMsg)
                    }

                    val dmMsg = DmMessage(
                        id = "${recipientWrap.id}:${System.currentTimeMillis() / 1000}",
                        senderPubkey = signer.pubkeyHex,
                        content = text,
                        createdAt = System.currentTimeMillis() / 1000,
                        giftWrapId = recipientWrap.id,
                        relayUrls = sentRelayUrls
                    )
                    dmRepo?.addMessage(dmMsg, peerPubkey)
                    dmRepo?.markGiftWrapSeen(selfWrap.id, dmMsg.id)
                } catch (e: SignerCancelledException) {
                    _messageText.value = text
                    _sendError.value = "Signing cancelled"
                } catch (e: Exception) {
                    _messageText.value = text
                    _sendError.value = "Failed to send message"
                    Log.w("DmConversation", "Send failed (remote signer)", e)
                } finally {
                    _sending.value = false
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

                val recipientDmRelays = fetchRecipientDmRelays(relayPool)
                val deliveryRelays = resolveRecipientRelaysWithSource(recipientDmRelays, relayPool).urls

                val recipientWrap = Nip17.createGiftWrap(
                    senderPrivkey = keypair.privkey,
                    senderPubkey = keypair.pubkey,
                    recipientPubkey = peerPubkey.hexToByteArray(),
                    message = text
                )
                val recipientMsg = ClientMessage.event(recipientWrap)
                val sentRelayUrls = sendToDeliveryRelays(relayPool, deliveryRelays, recipientMsg)

                if (sentRelayUrls.isEmpty()) {
                    _messageText.value = text
                    _sendError.value = "Failed to deliver — no relays accepted the message"
                }

                val selfWrap = Nip17.createGiftWrap(
                    senderPrivkey = keypair.privkey,
                    senderPubkey = keypair.pubkey,
                    recipientPubkey = keypair.pubkey,
                    message = text,
                    rumorPTag = peerPubkey
                )
                val selfMsg = ClientMessage.event(selfWrap)
                if (relayPool.hasDmRelays()) {
                    relayPool.sendToDmRelays(selfMsg)
                } else {
                    relayPool.sendToWriteRelays(selfMsg)
                }

                val dmMsg = DmMessage(
                    id = "${recipientWrap.id}:${System.currentTimeMillis() / 1000}",
                    senderPubkey = senderPubkeyHex,
                    content = text,
                    createdAt = System.currentTimeMillis() / 1000,
                    giftWrapId = recipientWrap.id,
                    relayUrls = sentRelayUrls
                )
                dmRepo?.addMessage(dmMsg, peerPubkey)
                dmRepo?.markGiftWrapSeen(selfWrap.id, dmMsg.id)
            } catch (e: Exception) {
                _messageText.value = text
                _sendError.value = "Failed to send message"
                Log.w("DmConversation", "Send failed (local signer)", e)
            } finally {
                _sending.value = false
            }
        }
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
