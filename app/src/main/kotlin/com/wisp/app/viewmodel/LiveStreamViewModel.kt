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
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.LiveChatMessage
import com.wisp.app.repo.LiveStreamRepository
import com.wisp.app.repo.MentionCandidate
import com.wisp.app.repo.MentionSearchRepository
import com.wisp.app.repo.ProfileRepository
import com.wisp.app.repo.RelayListRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Collections

class LiveStreamViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val CHAT_PAGE_SIZE = 50
        private const val ZAP_SCAN_PAGE_SIZE = 1000

        /**
         * Public relays that archive kind-9735 receipts for popular streams
         * (sidecar's ZAP_ARCHIVE_RELAYS) — chat relays alone only hold
         * recent zaps.
         */
        private val ZAP_ARCHIVE_RELAYS = listOf(
            "wss://nostr21.com", "wss://relay.ditto.pub", "wss://nostr-01.yakihonne.com",
            "wss://relay.nostr.net", "wss://soloco.nl"
        )
    }

    private var liveStreamRepo: LiveStreamRepository? = null
    private var relayPool: RelayPool? = null
    private var subManager: SubscriptionManager? = null
    private var contactRepo: ContactRepository? = null
    var hostPubkey: String = ""
        private set
    var dTag: String = ""
        private set
    private var aTagValue: String = ""
    var chatRelays: List<String> = emptyList()
        private set
    private var zapScanRelays: List<String> = emptyList()

    // Chat history paging (sidecar-style): newest page only on open; each
    // scroll-back pulls one more page until the in-memory cap or the stream's
    // first message is reached.
    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder
    private val _hasMoreChatHistory = MutableStateFlow(true)
    val hasMoreChatHistory: StateFlow<Boolean> = _hasMoreChatHistory

    private val _activity = MutableStateFlow<Nip53.LiveActivity?>(null)
    val activity: StateFlow<Nip53.LiveActivity?> = _activity

    private val _messages = MutableStateFlow<List<LiveChatMessage>>(emptyList())
    val messages: StateFlow<List<LiveChatMessage>> = _messages

    private val _streamZapTotal = MutableStateFlow(0L)
    val streamZapTotal: StateFlow<Long> = _streamZapTotal

    private val _streamZapTotalUncertain = MutableStateFlow(false)
    val streamZapTotalUncertain: StateFlow<Boolean> = _streamZapTotalUncertain

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _replyTarget = MutableStateFlow<LiveChatMessage?>(null)
    val replyTarget: StateFlow<LiveChatMessage?> = _replyTarget

    private var mentionSearchRepo: MentionSearchRepository? = null
    private val _mentionCandidates = MutableStateFlow<List<MentionCandidate>>(emptyList())
    val mentionCandidates: StateFlow<List<MentionCandidate>> = _mentionCandidates

    fun searchMention(query: String) { mentionSearchRepo?.search(query, viewModelScope) }
    fun clearMentionState() {
        _mentionCandidates.value = emptyList()
        mentionSearchRepo?.clear()
    }

    fun init(
        hostPubkey: String,
        dTag: String,
        liveStreamRepo: LiveStreamRepository,
        relayPool: RelayPool,
        relayListRepo: RelayListRepository,
        outboxRouter: OutboxRouter,
        subManager: SubscriptionManager,
        contactRepo: ContactRepository,
        profileRepo: ProfileRepository,
        naddrRelayHints: List<String> = emptyList()
    ) {
        if (this.hostPubkey == hostPubkey && this.dTag == dTag) return
        this.hostPubkey = hostPubkey
        this.dTag = dTag
        this.liveStreamRepo = liveStreamRepo
        this.relayPool = relayPool
        this.subManager = subManager
        this.contactRepo = contactRepo
        if (mentionSearchRepo == null) {
            mentionSearchRepo = MentionSearchRepository(
                profileRepo, contactRepo, relayPool, KeyRepository(getApplication())
            ).also { repo ->
                viewModelScope.launch { repo.candidates.collect { _mentionCandidates.value = it } }
            }
        }
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

        // Collect zap-total uncertainty (bounded relay scan may miss receipts)
        viewModelScope.launch {
            liveStreamRepo.streamZapTotalUncertain.collect { uncertain ->
                _streamZapTotalUncertain.value = uncertain
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

            // Bypass pool dedup for live chat subs — the discovery sub, the
            // per-stream sub, and the history pager all receive kind 1311
            // events and must each reach EventRouter independently. Normally
            // registered by StartupCoordinator's discovery, but re-register
            // here so deep-linked streams work even when discovery is off.
            relayPool.registerDedupBypass("live-chat-")

            // Recent history first: long-running streams carry months of kind-1311,
            // and backfilling them wholesale (the old limit=200) stalls the chat for
            // seconds. Pull the newest page up front and page older history on
            // scroll (sidecar-style); the live subscription only covers now-10 so
            // relays don't replay the whole archive on connect. Flagged with
            // isLoadingOlder so the scroll sentinel can't fire a concurrent page
            // under the same subscription id.
            _isLoadingOlder.value = true
            try {
                fetchChatHistoryPage(until = null)
            } finally {
                _isLoadingOlder.value = false
            }

            // Subscribe to chat, reactions, zaps — use writable, auto-reconnecting
            // ephemeral connections so we can both send and receive for the stream's
            // full duration without losing connectivity on transient drops.
            val chatATag = listOf(aTagValue)
            val chatFilter = Filter(
                kinds = listOf(1311),
                aTags = chatATag,
                limit = 100,
                since = System.currentTimeMillis() / 1000 - 10
            )
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

            // The live zap sub only sees receipts delivered while watching (plus
            // its small backfill) — a fraction of a popular stream's lifetime
            // total. Scan chat + archive relays once for a much closer number.
            refreshZapTotal()
        }
    }

    /**
     * One-shot kind-1311 page across the chat relays. Events route to the repo
     * through EventRouter via the shared "live-chat-" prefix (pool dedup is
     * bypassed for it; the repo dedupes the overlap). `until` is inclusive so
     * boundary-second messages aren't skipped. Null [until] fetches the newest
     * page; returns the number of unique events received.
     */
    private suspend fun fetchChatHistoryPage(until: Long?): Int {
        val pool = relayPool ?: return 0
        val manager = subManager ?: return 0
        if (chatRelays.isEmpty()) return 0

        val subId = "live-chat-hist-$dTag"
        // Count unique ids, not raw relay copies — this sub bypasses pool
        // dedup (it rides the "live-chat-" prefix), so every relay's copy
        // of the same event reaches us.
        val received = java.util.concurrent.atomic.AtomicInteger(0)
        val seenIds = Collections.synchronizedSet(HashSet<String>())
        val counter = viewModelScope.launch(Dispatchers.Default) {
            pool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId == subId && seenIds.add(relayEvent.event.id)) {
                    received.incrementAndGet()
                }
            }
        }
        try {
            val filter = Filter(
                kinds = listOf(1311),
                aTags = listOf(aTagValue),
                limit = CHAT_PAGE_SIZE,
                until = until
            )
            for (url in chatRelays) {
                pool.sendToRelayOrEphemeral(url, ClientMessage.req(subId, filter), write = true, autoReconnect = true)
            }
            manager.awaitEoseCount(subId, chatRelays.size, 8_000)
        } finally {
            counter.cancel()
            manager.closeSubscription(subId)
        }
        return received.get()
    }

    /** Loads one older page of chat (scroll-back). Re-entrant calls are cheap no-ops. */
    fun loadOlderMessages() {
        if (_isLoadingOlder.value || !_hasMoreChatHistory.value || chatRelays.isEmpty()) return
        val repo = liveStreamRepo ?: return
        val oldest = repo.oldestChatMessageCreatedAt(aTagValue) ?: return
        _isLoadingOlder.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val received = fetchChatHistoryPage(until = oldest)
                if (received < CHAT_PAGE_SIZE ||
                    repo.chatMessageCount(aTagValue) >= LiveStreamRepository.MAX_CHAT_MESSAGES
                ) {
                    _hasMoreChatHistory.value = false
                }
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }

    /**
     * One-shot kind-9735 scan across the chat relays plus zap-archive relays,
     * deduped by event id in the repository. Bounded sample: mark the total
     * uncertain (rendered "+") when relays went quiet or hit the page limit.
     */
    private fun refreshZapTotal() {
        val repo = liveStreamRepo ?: return
        val pool = relayPool ?: return
        val manager = subManager ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val relays = (chatRelays + ZAP_ARCHIVE_RELAYS).distinct().take(12)
            if (relays.isEmpty()) return@launch
            zapScanRelays = relays

            val subId = "live-zap-scan-$dTag"
            val received = java.util.concurrent.atomic.AtomicInteger(0)
            val seenIds = Collections.synchronizedSet(HashSet<String>())
            val counter = launch {
                pool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId == subId && seenIds.add(relayEvent.event.id)) {
                        received.incrementAndGet()
                    }
                }
            }
            try {
                val filter = Filter(kinds = listOf(9735), aTags = listOf(aTagValue), limit = ZAP_SCAN_PAGE_SIZE)
                for (url in relays) {
                    pool.sendToRelayOrEphemeral(url, ClientMessage.req(subId, filter))
                }
                val responded = manager.awaitEoseCount(subId, relays.size, 12_000)
                if (received.get() > 0 && (received.get() >= ZAP_SCAN_PAGE_SIZE || responded < relays.size)) {
                    repo.markZapTotalUncertain(aTagValue)
                }
            } finally {
                counter.cancel()
                manager.closeSubscription(subId)
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
        val histSubId = "live-chat-hist-$dTag"
        val zapScanSubId = "live-zap-scan-$dTag"
        for (url in chatRelays) {
            relayPool.sendToRelay(url, ClientMessage.close(chatSubId))
            relayPool.sendToRelay(url, ClientMessage.close(reactSubId))
            relayPool.sendToRelay(url, ClientMessage.close(zapSubId))
            relayPool.sendToRelay(url, ClientMessage.close(histSubId))
        }
        if (zapScanRelays.isNotEmpty()) {
            for (url in zapScanRelays) {
                relayPool.sendToRelay(url, ClientMessage.close(zapScanSubId))
            }
        } else {
            relayPool.sendToAll(ClientMessage.close(zapScanSubId))
        }
        // Release auto-reconnect so ephemeral relays can be evicted naturally
        relayPool.releaseEphemeralRelays(chatRelays)
        relayPool.releaseEphemeralRelays(zapScanRelays)
    }
}
