package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.DmMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip17
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.SignerCancelledException
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.RelayListRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DmConversationViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    private val _messages = MutableStateFlow<List<DmMessage>>(emptyList())
    val messages: StateFlow<List<DmMessage>> = _messages

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    private var peerPubkey: String = ""
    private var dmRepo: DmRepository? = null
    private var relayListRepo: RelayListRepository? = null

    fun init(peerPubkeyHex: String, dmRepository: DmRepository, relayListRepository: RelayListRepository? = null) {
        peerPubkey = peerPubkeyHex
        dmRepo = dmRepository
        relayListRepo = relayListRepository
        _messages.value = dmRepository.getConversation(peerPubkeyHex)

        viewModelScope.launch {
            dmRepository.conversationList.collect {
                _messages.value = dmRepository.getConversation(peerPubkey)
            }
        }
    }

    fun updateMessageText(value: String) {
        _messageText.value = value
    }

    fun clearSendError() {
        _sendError.value = null
    }

    /**
     * Fetch recipient's kind 10050 DM relays, with cache-first strategy.
     */
    private suspend fun fetchRecipientDmRelays(relayPool: RelayPool): List<String> {
        val repo = dmRepo ?: return emptyList()

        // Cache hit
        repo.getCachedDmRelays(peerPubkey)?.let { return it }

        // Send REQ for kind 10050 to all connected relays
        val subId = "dm_relay_${peerPubkey.take(8)}"
        val filter = Filter(
            kinds = listOf(Nip51.KIND_DM_RELAYS),
            authors = listOf(peerPubkey),
            limit = 1
        )
        relayPool.sendToAll(ClientMessage.req(subId, filter))

        // Wait up to 3s for a matching event
        val result = withTimeoutOrNull(3000L) {
            relayPool.relayEvents.first { it.subscriptionId == subId }
        }

        // Close subscription
        relayPool.sendToAll(ClientMessage.close(subId))

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
     * 2. Recipient's kind 10002 write relays
     * 3. Sender's own write relays
     */
    private fun resolveRecipientRelays(
        recipientDmRelays: List<String>,
        relayPool: RelayPool
    ): List<String> {
        // 1. Recipient's DM relays
        if (recipientDmRelays.isNotEmpty()) return recipientDmRelays

        // 2. Recipient's kind 10002 write relays
        val writeRelays = relayListRepo?.getWriteRelays(peerPubkey)
        if (!writeRelays.isNullOrEmpty()) return writeRelays

        // 3. Sender's own write relays (last resort)
        return relayPool.getWriteRelayUrls()
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
                    val deliveryRelays = resolveRecipientRelays(recipientDmRelays, relayPool)

                    val recipientWrap = Nip17.createGiftWrapRemote(
                        signer = signer,
                        recipientPubkeyHex = peerPubkey,
                        message = text
                    )
                    val recipientMsg = ClientMessage.event(recipientWrap)
                    val sentRelayUrls = mutableSetOf<String>()
                    for (url in deliveryRelays) {
                        if (relayPool.sendToRelayOrEphemeral(url, recipientMsg)) {
                            sentRelayUrls.add(url)
                        }
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
                } catch (_: Exception) {
                } finally {
                    _sending.value = false
                }
            }
            return
        }

        // Local signer mode
        val keypair = keyRepo.getKeypair() ?: return

        _messageText.value = ""
        _sending.value = true

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val senderPubkeyHex = keypair.pubkey.toHex()

                val recipientDmRelays = fetchRecipientDmRelays(relayPool)
                val deliveryRelays = resolveRecipientRelays(recipientDmRelays, relayPool)

                val recipientWrap = Nip17.createGiftWrap(
                    senderPrivkey = keypair.privkey,
                    senderPubkey = keypair.pubkey,
                    recipientPubkey = peerPubkey.hexToByteArray(),
                    message = text
                )
                val recipientMsg = ClientMessage.event(recipientWrap)
                val sentRelayUrls = mutableSetOf<String>()
                for (url in deliveryRelays) {
                    if (relayPool.sendToRelayOrEphemeral(url, recipientMsg)) {
                        sentRelayUrls.add(url)
                    }
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
            } catch (_: Exception) {
            } finally {
                _sending.value = false
            }
        }
    }
}
