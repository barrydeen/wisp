package cooking.zap.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Nip19
import cooking.zap.app.nostr.Nip29
import cooking.zap.app.nostr.Nip56
import cooking.zap.app.nostr.NostrUriData
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.repo.GroupMessage
import cooking.zap.app.repo.GroupRepository
import cooking.zap.app.repo.GroupRoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    // Set true when a freshly-arrived 39002 no longer lists me while I have the room open — i.e.
    // an admin removed/banned me. The screen reacts by ejecting me to the room list.
    private val _removedFromRoom = MutableStateFlow(false)
    val removedFromRoom: StateFlow<Boolean> = _removedFromRoom

    // My pubkey, for self-removal detection. Null until init() supplies it (READ_ONLY/unknown).
    private var myPubkey: String? = null
    // Guards self-removal false positives: only eject once I've actually been seen in a loaded
    // (non-empty) 39002 member list, so a transient/empty fetch can't kick a valid member.
    private var sawSelfAsMember = false

    // ── Local, room-scoped moderation (client-side only, no relay events) ──────────────────────
    // Pubkeys the current user has muted in this room, and individual messages they've locally
    // hidden (e.g. after reporting). Both are session-scoped to this room view and never published.
    private val _mutedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val mutedPubkeys: StateFlow<Set<String>> = _mutedPubkeys

    private val _hiddenMessageIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Messages to render — [messages] minus individually hidden messages (e.g. reported ones).
     * Muted authors are intentionally NOT filtered out here: the UI collapses their messages to a
     * "tap to unmute" placeholder via [mutedPubkeys], so muting stays reversible from the room.
     */
    val visibleMessages: StateFlow<List<GroupMessage>> =
        combine(_messages, _hiddenMessageIds) { msgs, hidden ->
            msgs.filter { it.id !in hidden }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Toggle a local mute for [pubkey] in this room. Client-side only — no relay event. */
    fun toggleMute(pubkey: String) {
        _mutedPubkeys.value = if (pubkey in _mutedPubkeys.value) {
            _mutedPubkeys.value - pubkey
        } else {
            _mutedPubkeys.value + pubkey
        }
    }

    /** Locally hide a single message for the current user (e.g. right after reporting it). */
    fun hideMessage(messageId: String) {
        _hiddenMessageIds.value = _hiddenMessageIds.value + messageId
    }

    /**
     * Publish a NIP-56 (kind 1984) report to the group relay and locally hide the reported message.
     * Available to every member. The report is addressed to the group's admins (39001) plus the
     * known Pantry moderation pubkeys so ops can find it, and h-tagged for relay-side querying.
     */
    fun report(
        signer: NostrSigner?,
        reportedPubkey: String,
        category: Nip56.ReportCategory,
        messageId: String?,
        reason: String,
    ) {
        val pool = relayPool ?: return
        val s = signer ?: return
        val admins = _room.value?.admins ?: emptyList()
        val recipients = (admins + Nip56.PANTRY_MOD_ADMINS).distinct()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val tags = Nip56.buildReportTags(
                    reportedPubkey = reportedPubkey,
                    category = category,
                    eventId = messageId,
                    groupId = groupId,
                    recipients = recipients,
                )
                val event = s.signEvent(
                    kind = Nip56.KIND_REPORT,
                    content = Nip56.reportContent(category, reason),
                    tags = tags,
                )
                pool.sendToRelayOrEphemeral(relayUrl, ClientMessage.event(event), skipBadCheck = true)
            } catch (_: Exception) { }
        }
        // Don't make the reporter keep seeing what they just reported.
        if (messageId != null) hideMessage(messageId)
    }

    fun init(
        groupId: String,
        relayUrl: String,
        repository: GroupRepository,
        pool: RelayPool,
        myPubkey: String? = null
    ) {
        this.myPubkey = myPubkey
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
                detectSelfRemoval(room)
            }
        }

        viewModelScope.launch {
            pool.groupRelayErrors.collect { (url, subId, message) ->
                if (url == relayUrl && subId.contains(groupId)) {
                    _relayError.value = message
                }
            }
        }

        // Clear a stale "auth-required" (or any) error as soon as NIP-42 AUTH completes
        // for our relay. GroupListViewModel re-fires the subs in parallel, so messages
        // should start flowing again; this just keeps the banner from lingering.
        viewModelScope.launch {
            pool.authCompleted.collect { url ->
                if (url == relayUrl) _relayError.value = null
            }
        }
    }

    /**
     * Detect that I've been removed/banned from the room. Triggers only on a loaded (non-empty)
     * 39002 member list that excludes me, *after* I've been confirmed as a member at least once —
     * so an empty/failed members fetch or a public-room preview can't eject a valid member. The
     * underlying 39002 is already newest-wins (see GroupListViewModel.isFreshReplaceable).
     */
    private fun detectSelfRemoval(room: GroupRoom?) {
        val me = myPubkey ?: return
        val members = room?.members ?: return
        if (members.isEmpty()) return
        if (members.contains(me)) {
            sawSelfAsMember = true
        } else if (sawSelfAsMember) {
            _removedFromRoom.value = true
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

    fun sendMessage(relayPool: RelayPool, signer: NostrSigner?, resolvedEmojis: Map<String, String> = emptyMap()) {
        val text = messageText.value.trim()
        if (text.isEmpty() || signer == null || sending.value) return
        val replyTarget = _replyTarget.value
        val replyId = replyTarget?.id
        val replyAuthorPubkey = replyTarget?.senderPubkey
        viewModelScope.launch(Dispatchers.Default) {
            _sending.value = true
            _sendError.value = null
            // Hoisted so a failure after it starts (connect failure or an exception below) can
            // cancel it — otherwise a lingering watcher could set _sendError after the send failed.
            var watcher: Job? = null
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
                // Add emoji tags for any :shortcode: references in the content
                tags.addAll(cooking.zap.app.nostr.Nip30.buildEmojiTagsForContent(text, resolvedEmojis))
                val event = signer.signEvent(
                    kind = Nip29.KIND_CHAT_MESSAGE,
                    content = text,
                    tags = tags
                )
                // Watch for the relay's OK before sending, so a rejection (e.g. a kind-9 from a
                // user the relay no longer considers a member) surfaces to the sender instead of
                // being silently dropped. Runs independently so it doesn't hold up _sending; the
                // relay's OK is a network round-trip, so the watcher is collecting well before it.
                val eventId = event.id
                watcher = viewModelScope.launch(Dispatchers.Default) {
                    val result = withTimeoutOrNull(REJECTION_TIMEOUT_MS) {
                        relayPool.publishResults.first { it.eventId == eventId && it.relayUrl == relayUrl }
                    }
                    if (result != null && !result.accepted) {
                        _sendError.value = "Couldn't send — you're not a member of this group"
                    }
                }
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
                    watcher.cancel()
                    _sendError.value = "Could not connect to relay"
                }
            } catch (e: Exception) {
                watcher?.cancel()
                _sendError.value = e.message ?: "Send failed"
            } finally {
                _sending.value = false
            }
        }
    }

    companion object {
        // How long to wait for a relay OK before giving up on surfacing a send rejection.
        private const val REJECTION_TIMEOUT_MS = 8_000L
    }
}
