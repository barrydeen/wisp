package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.Nip29
import com.wisp.app.nostr.NostrUriData
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.GroupMessage
import com.wisp.app.repo.GroupRepository
import com.wisp.app.repo.GroupRoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

class GroupRoomViewModel(app: Application) : AndroidViewModel(app) {

    private var groupRepo: GroupRepository? = null
    private var relayPool: RelayPool? = null
    var groupId: String = ""
        private set
    var relayUrl: String = ""
        private set

    private val _messages = MutableStateFlow<List<GroupMessage>>(emptyList())
    val messages: StateFlow<List<GroupMessage>> = _messages

    private val _room = MutableStateFlow<GroupRoom?>(null)
    val room: StateFlow<GroupRoom?> = _room

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    private val _replyTarget = MutableStateFlow<GroupMessage?>(null)
    val replyTarget: StateFlow<GroupMessage?> = _replyTarget

    private val _relayError = MutableStateFlow<String?>(null)
    val relayError: StateFlow<String?> = _relayError

    fun init(
        groupId: String,
        relayUrl: String,
        repository: GroupRepository,
        pool: RelayPool
    ) {
        if (this.groupId == groupId && this.relayUrl == relayUrl) return
        this.groupId = groupId
        this.relayUrl = relayUrl
        groupRepo = repository
        relayPool = pool

        // Synchronously pre-populate from current repo state so the first composition
        // doesn't flash the join screen for already-joined rooms.
        repository.getRoom(relayUrl, groupId)?.let { initial ->
            _room.value = initial
            _messages.value = initial.messages
        }

        viewModelScope.launch {
            repository.joinedGroups.collect { rooms ->
                val room = rooms.firstOrNull { it.groupId == groupId && it.relayUrl == relayUrl }
                _room.value = room
                _messages.value = room?.messages ?: emptyList()
                // Clear relay error once messages arrive
                if (room != null && room.messages.isNotEmpty()) _relayError.value = null
            }
        }

        viewModelScope.launch {
            pool.groupRelayErrors.collect { (url, subId, message) ->
                if (url == relayUrl && subId.contains(groupId)) {
                    _relayError.value = message
                }
            }
        }
    }

    fun updateText(text: String) {
        _messageText.value = text
        _sendError.value = null
    }

    fun setReplyTarget(message: GroupMessage) { _replyTarget.value = message }
    fun clearReplyTarget() { _replyTarget.value = null }

    fun appendToText(suffix: String) {
        val current = _messageText.value
        _messageText.value = if (current.isBlank()) suffix else "$current\n$suffix"
    }

    fun sendReaction(
        messageId: String,
        targetPubkey: String,
        emoji: String,
        signer: NostrSigner?,
        pool: RelayPool,
        resolvedEmojis: Map<String, String> = emptyMap()
    ) {
        signer ?: return
        val repo = groupRepo ?: return
        // Look up URL for custom emoji shortcodes (e.g. ":partying:")
        val emojiUrl = if (emoji.startsWith(":") && emoji.endsWith(":")) {
            resolvedEmojis[emoji.removeSurrounding(":")]
        } else null
        // Optimistic local update
        repo.addReaction(relayUrl, groupId, messageId, signer.pubkeyHex, emoji, emojiUrl)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val tags = mutableListOf(
                    listOf("e", messageId),
                    listOf("p", targetPubkey),
                    listOf("h", groupId),
                    listOf("k", Nip29.KIND_CHAT_MESSAGE.toString())
                )
                if (emojiUrl != null) {
                    tags.add(listOf("emoji", emoji.removeSurrounding(":"), emojiUrl))
                }
                val event = signer.signEvent(kind = 7, content = emoji, tags = tags)
                pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.event(event), skipBadCheck = true)
            } catch (_: Exception) { }
        }
    }

    fun sendMessage(relayPool: RelayPool, signer: NostrSigner?) {
        val text = messageText.value.trim()
        if (text.isEmpty() || signer == null || sending.value) return
        val replyTarget = _replyTarget.value
        val replyId = replyTarget?.id
        val replyAuthorPubkey = replyTarget?.senderPubkey
        viewModelScope.launch(Dispatchers.Default) {
            _sending.value = true
            _sendError.value = null
            try {
                val tags = mutableListOf(listOf("h", groupId, relayUrl))
                if (replyId != null && replyAuthorPubkey != null) {
                    // q is the NIP-29 convention for chat replies
                    tags.add(listOf("q", replyId, relayUrl, replyAuthorPubkey))
                    tags.add(listOf("p", replyAuthorPubkey))
                }
                // Auto-add p tags for any nostr:npub1.../nostr:nprofile1... mentions in content
                val mentionRegex = Regex("nostr:(npub1[a-z0-9]+|nprofile1[a-z0-9]+)")
                mentionRegex.findAll(text).forEach { match ->
                    val data = Nip19.decodeNostrUri(match.value)
                    val pubkey = when (data) {
                        is NostrUriData.ProfileRef -> data.pubkey
                        else -> null
                    }
                    if (pubkey != null && tags.none { it.size >= 2 && it[0] == "p" && it[1] == pubkey }) {
                        tags.add(listOf("p", pubkey))
                    }
                }
                val event = signer.signEvent(
                    kind = Nip29.KIND_CHAT_MESSAGE,
                    content = text,
                    tags = tags
                )
                val sent = relayPool.sendToRelayOrEphemeral(relayUrl, ClientMessage.event(event), skipBadCheck = true)
                if (sent) {
                    groupRepo?.addMessage(relayUrl, groupId, GroupMessage(
                        id = event.id,
                        senderPubkey = event.pubkey,
                        content = event.content,
                        createdAt = event.created_at,
                        replyToId = replyId
                    ))
                    _messageText.value = ""
                    _replyTarget.value = null
                } else {
                    _sendError.value = "Could not connect to relay"
                }
            } catch (e: Exception) {
                _sendError.value = e.message ?: "Send failed"
            } finally {
                _sending.value = false
            }
        }
    }
}
