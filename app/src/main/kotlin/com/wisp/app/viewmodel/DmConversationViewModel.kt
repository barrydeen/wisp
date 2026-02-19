package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.DmMessage
import com.wisp.app.nostr.Nip17
import com.wisp.app.nostr.hexToByteArray
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DmConversationViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    private val _messages = MutableStateFlow<List<DmMessage>>(emptyList())
    val messages: StateFlow<List<DmMessage>> = _messages

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText

    private var peerPubkey: String = ""
    private var dmRepo: DmRepository? = null

    fun init(peerPubkeyHex: String, dmRepository: DmRepository) {
        peerPubkey = peerPubkeyHex
        dmRepo = dmRepository
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

    fun sendMessage(relayPool: RelayPool) {
        val text = _messageText.value.trim()
        if (text.isBlank()) return
        val keypair = keyRepo.getKeypair() ?: return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val giftWrap = Nip17.createGiftWrap(
                    senderPrivkey = keypair.privkey,
                    senderPubkey = keypair.pubkey,
                    recipientPubkey = peerPubkey.hexToByteArray(),
                    message = text
                )
                val msg = ClientMessage.event(giftWrap)
                if (relayPool.hasDmRelays()) relayPool.sendToDmRelays(msg)
                else relayPool.sendToWriteRelays(msg)

                // Add to local conversation immediately
                val dmMsg = DmMessage(
                    id = "${giftWrap.id}:${System.currentTimeMillis() / 1000}",
                    senderPubkey = keypair.pubkey.toHex(),
                    content = text,
                    createdAt = System.currentTimeMillis() / 1000,
                    giftWrapId = giftWrap.id
                )
                dmRepo?.addMessage(dmMsg, peerPubkey)
                _messageText.value = ""
            } catch (_: Exception) {}
        }
    }
}
