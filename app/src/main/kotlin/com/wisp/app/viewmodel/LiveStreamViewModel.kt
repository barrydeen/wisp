package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip53
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.NostrUriData
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.LiveChatMessage
import com.wisp.app.repo.LiveStreamRepository
import com.wisp.app.repo.RelayListRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LiveStreamViewModel(app: Application) : AndroidViewModel(app) {

    private var liveStreamRepo: LiveStreamRepository? = null
    private var relayPool: RelayPool? = null
    private var contactRepo: ContactRepository? = null
    var hostPubkey: String = ""
        private set
    var dTag: String = ""
        private set
    private var aTagValue: String = ""
    var chatRelays: List<String> = emptyList()
        private set

    private val _activity = MutableStateFlow<Nip53.LiveActivity?>(null)
    val activity: StateFlow<Nip53.LiveActivity?> = _activity

    private val _messages = MutableStateFlow<List<LiveChatMessage>>(emptyList())
    val messages: StateFlow<List<LiveChatMessage>> = _messages

    private val _streamZapTotal = MutableStateFlow(0L)
    val streamZapTotal: StateFlow<Long> = _streamZapTotal

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _replyTarget = MutableStateFlow<LiveChatMessage?>(null)
    val replyTarget: StateFlow<LiveChatMessage?> = _replyTarget

    fun init(
        hostPubkey: String,
        dTag: String,
        liveStreamRepo: LiveStreamRepository,
        relayPool: RelayPool,
        relayListRepo: RelayListRepository,
        outboxRouter: OutboxRouter,
        subManager: SubscriptionManager,
        contactRepo: ContactRepository,
        naddrRelayHints: List<String> = emptyList()
    ) {
        if (this.hostPubkey == hostPubkey && this.dTag == dTag) return
        this.hostPubkey = hostPubkey
        this.dTag = dTag
        this.liveStreamRepo = liveStreamRepo
        this.relayPool = relayPool
        this.contactRepo = contactRepo
        this.aTagValue = Nip53.aTagValue(hostPubkey, dTag)

        // Set current stream in repo
        liveStreamRepo.setCurrentStream(aTagValue)

        // Load initial activity
        _activity.value = liveStreamRepo.getActivity(aTagValue)

        // Collect messages
        viewModelScope.launch {
            liveStreamRepo.currentChatMessages.collect { msgs ->
                _messages.value = msgs
            }
        }

        // Collect stream zap total
        viewModelScope.launch {
            liveStreamRepo.streamZapTotal.collect { total ->
                _streamZapTotal.value = total
            }
        }

        // Also collect live activity updates
        viewModelScope.launch {
            liveStreamRepo.liveStreams.collect { streams ->
                val stream = streams[aTagValue]
                if (stream != null) _activity.value = stream.activity
            }
        }

        // Fetch host's relay list if not cached, then subscribe to chat
        viewModelScope.launch(Dispatchers.Default) {
            if (!relayListRepo.hasRelayList(hostPubkey)) {
                val rlSubId = "live-rl-${hostPubkey.take(8)}"
                val rlFilter = Filter(kinds = listOf(10002), authors = listOf(hostPubkey), limit = 1)
                val rlMsg = ClientMessage.req(rlSubId, rlFilter)
                relayPool.sendToAll(rlMsg)
                for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
                    relayPool.sendToRelayOrEphemeral(url, rlMsg, skipBadCheck = true)
                }
                subManager.awaitEoseWithTimeout(rlSubId, timeoutMs = 4_000)
                subManager.closeSubscription(rlSubId)
            }

            val inboxRelays = relayListRepo.getReadRelays(hostPubkey) ?: emptyList()
            val outboxRelays = relayListRepo.getWriteRelays(hostPubkey) ?: emptyList()
            val activityHints = _activity.value?.relayHints ?: emptyList()
            // Prioritize relay hints (most likely to have chat), then inbox/outbox
            chatRelays = (naddrRelayHints + activityHints + inboxRelays + outboxRelays)
                .distinct().take(10).ifEmpty { relayPool.getReadRelayUrls().take(3) }
            // Subscribe to chat, reactions, zaps — use writable, auto-reconnecting
            // ephemeral connections so we can both send and receive for the stream's
            // full duration without losing connectivity on transient drops.
            val chatATag = listOf(aTagValue)
            val chatFilter = Filter(kinds = listOf(1311), aTags = chatATag, limit = 200)
            val reactFilter = Filter(kinds = listOf(7), aTags = chatATag, limit = 500)
            val zapFilter = Filter(kinds = listOf(9735), aTags = chatATag, limit = 200)

            val chatSubId = "live-chat-$dTag"
            val reactSubId = "live-react-$dTag"
            val zapSubId = "live-zap-$dTag"

            for (url in chatRelays) {
                relayPool.sendToRelayOrEphemeral(url, ClientMessage.req(chatSubId, chatFilter), write = true, autoReconnect = true)
                relayPool.sendToRelayOrEphemeral(url, ClientMessage.req(reactSubId, reactFilter), write = true, autoReconnect = true)
                relayPool.sendToRelayOrEphemeral(url, ClientMessage.req(zapSubId, zapFilter), write = true, autoReconnect = true)
            }
        }
    }

    fun updateText(text: String) {
        _messageText.value = text
    }

    fun setReplyTarget(message: LiveChatMessage) { _replyTarget.value = message }
    fun clearReplyTarget() { _replyTarget.value = null }

    fun sendMessage(signer: NostrSigner?) {
        val text = messageText.value.trim()
        if (text.isEmpty() || signer == null || sending.value) return
        val replyId = _replyTarget.value?.id
        val relayHint = chatRelays.firstOrNull() ?: ""

        viewModelScope.launch(Dispatchers.Default) {
            _sending.value = true
            try {
                val tags = mutableListOf(
                    listOf("a", aTagValue, relayHint),
                    listOf("p", hostPubkey)
                )
                if (replyId != null) {
                    tags.add(listOf("e", replyId, "", "reply"))
                }
                // Auto-add p tags for nostr: mentions
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
                    kind = Nip53.KIND_LIVE_CHAT_MESSAGE,
                    content = text,
                    tags = tags
                )
                var sent = false
                for (url in chatRelays) {
                    if (relayPool?.sendToRelayOrEphemeral(url, ClientMessage.event(event)) == true) {
                        sent = true
                    }
                }
                if (sent) {
                    // Optimistic local update
                    liveStreamRepo?.addChatMessage(event)
                    _messageText.value = ""
                    _replyTarget.value = null
                }
            } catch (_: Exception) {
            } finally {
                _sending.value = false
            }
        }
    }

    fun sendReaction(
        messageId: String,
        targetPubkey: String,
        emoji: String,
        signer: NostrSigner?,
        resolvedEmojis: Map<String, String> = emptyMap()
    ) {
        signer ?: return
        val repo = liveStreamRepo ?: return
        val emojiUrl = if (emoji.startsWith(":") && emoji.endsWith(":")) {
            resolvedEmojis[emoji.removeSurrounding(":")]
        } else null
        // Optimistic local update
        repo.addReaction(aTagValue, messageId, signer.pubkeyHex, emoji)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val tags = mutableListOf(
                    listOf("e", messageId),
                    listOf("p", targetPubkey),
                    listOf("a", aTagValue),
                    listOf("k", Nip53.KIND_LIVE_CHAT_MESSAGE.toString())
                )
                if (emojiUrl != null) {
                    tags.add(listOf("emoji", emoji.removeSurrounding(":"), emojiUrl))
                }
                val event = signer.signEvent(kind = 7, content = emoji, tags = tags)
                for (url in chatRelays) {
                    relayPool?.sendToRelayOrEphemeral(url, ClientMessage.event(event))
                }
            } catch (_: Exception) { }
        }
    }

    fun cleanup(relayPool: RelayPool) {
        liveStreamRepo?.setCurrentStream(null)
        val chatSubId = "live-chat-$dTag"
        val reactSubId = "live-react-$dTag"
        val zapSubId = "live-zap-$dTag"
        for (url in chatRelays) {
            relayPool.sendToRelay(url, ClientMessage.close(chatSubId))
            relayPool.sendToRelay(url, ClientMessage.close(reactSubId))
            relayPool.sendToRelay(url, ClientMessage.close(zapSubId))
        }
        // Release auto-reconnect so ephemeral relays can be evicted naturally
        relayPool.releaseEphemeralRelays(chatRelays)
    }
}
