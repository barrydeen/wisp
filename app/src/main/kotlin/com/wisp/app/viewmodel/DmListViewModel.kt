package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.DmConversation
import com.wisp.app.nostr.DmMessage
import com.wisp.app.nostr.Nip17
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.toHex
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.SigningMode
import com.wisp.app.repo.MuteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DmListViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    val conversationList: StateFlow<List<DmConversation>>
        get() = dmRepo?.conversationList ?: MutableStateFlow(emptyList())

    val hasUnreadDms: StateFlow<Boolean>
        get() = dmRepo?.hasUnreadDms ?: MutableStateFlow(false)

    private var dmRepo: DmRepository? = null
    private var muteRepo: MuteRepository? = null

    fun init(dmRepository: DmRepository, muteRepository: MuteRepository? = null) {
        dmRepo = dmRepository
        muteRepo = muteRepository
    }

    fun markDmsRead() {
        dmRepo?.markDmsRead()
    }

    fun processGiftWrap(event: NostrEvent, relayUrl: String = "") {
        if (event.kind != 1059) return
        val repo = dmRepo ?: return

        // Remote signer mode: store raw gift wraps, decrypt later when user views DMs
        if (keyRepo.getSigningMode() == SigningMode.REMOTE) {
            repo.addPendingGiftWrap(event, relayUrl)
            return
        }

        val keypair = keyRepo.getKeypair() ?: return
        val myPubkey = keypair.pubkey.toHex()

        viewModelScope.launch(Dispatchers.Default) {
            val rumor = Nip17.unwrapGiftWrap(keypair.privkey, event) ?: return@launch
            val peerPubkey = if (rumor.pubkey == myPubkey) {
                // Sent by me, peer is the recipient
                rumor.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1) ?: return@launch
            } else {
                rumor.pubkey
            }

            if (muteRepo?.isBlocked(peerPubkey) == true) return@launch

            val msg = DmMessage(
                id = "${event.id}:${rumor.createdAt}",
                senderPubkey = rumor.pubkey,
                content = rumor.content,
                createdAt = rumor.createdAt,
                giftWrapId = event.id,
                relayUrls = if (relayUrl.isNotEmpty()) setOf(relayUrl) else emptySet()
            )
            repo.addMessage(msg, peerPubkey)
        }
    }

    private val _decrypting = MutableStateFlow(false)
    val decrypting: StateFlow<Boolean> = _decrypting

    /**
     * Decrypt pending gift wraps using the remote signer, one at a time.
     * Called when user navigates to the DM list screen in remote signer mode.
     */
    fun decryptPending(signer: NostrSigner) {
        val repo = dmRepo ?: return
        val myPubkey = signer.pubkeyHex

        viewModelScope.launch(Dispatchers.Default) {
            val pending = repo.takePendingGiftWraps()
            if (pending.isEmpty()) return@launch

            _decrypting.value = true
            try {
                for (wrap in pending) {
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
                _decrypting.value = false
            }
        }
    }
}
