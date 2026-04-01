package com.wisp.app.viewmodel

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.CustomEmoji
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.Nip13
import com.wisp.app.nostr.Nip25
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.Nip18
import com.wisp.app.nostr.Nip09
import com.wisp.app.nostr.Nip57
import com.wisp.app.nostr.Nip88
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.WalletProvider
import com.wisp.app.repo.PinRepository
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.DeletedEventsRepository
import com.wisp.app.repo.InterfacePreferences
import com.wisp.app.repo.PowPreferences
import com.wisp.app.repo.ZapSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Handles user-initiated social actions: follow/block, reactions, reposts, zaps, pins, mutes.
 * Extracted from FeedViewModel to reduce its size.
 */
class SocialActionManager(
    private val relayPool: RelayPool,
    private val outboxRouter: OutboxRouter,
    private val eventRepo: EventRepository,
    private val contactRepo: ContactRepository,
    private val muteRepo: MuteRepository,
    private val notifRepo: NotificationRepository,
    private val dmRepo: DmRepository,
    private val pinRepo: PinRepository,
    private val deletedEventsRepo: DeletedEventsRepository,
    private val getWalletProvider: () -> WalletProvider,
    private val customEmojiRepo: CustomEmojiRepository,
    private val zapSender: ZapSender,
    private val powPrefs: PowPreferences,
    private val interfacePrefs: InterfacePreferences,
    private val scope: CoroutineScope,
    private val getSigner: () -> NostrSigner?,
    private val getUserPubkey: () -> String?
) {
    private val _zapInProgress = MutableStateFlow<Set<String>>(emptySet())
    val zapInProgress: StateFlow<Set<String>> = _zapInProgress

    private val _zapSuccess = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val zapSuccess: SharedFlow<String> = _zapSuccess

    private val _zapError = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val zapError: SharedFlow<String> = _zapError

    private val _reactionSent = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val reactionSent: SharedFlow<Unit> = _reactionSent

    // Non-null while the first-follow confirmation dialog is visible (holds the target pubkey).
    private val _pendingFirstFollow = MutableStateFlow<String?>(null)
    val pendingFirstFollow: StateFlow<String?> = _pendingFirstFollow

    // False while we are still checking relays; true once the check confirms no existing list.
    private val _firstFollowCheckDone = MutableStateFlow(false)
    val firstFollowCheckDone: StateFlow<Boolean> = _firstFollowCheckDone

    private var followCheckJob: Job? = null

    fun toggleFollow(pubkey: String) {
        val s = getSigner() ?: return
        val isCurrentlyFollowing = contactRepo.isFollowing(pubkey)
        val currentList = contactRepo.getFollowList()

        if (!isCurrentlyFollowing && currentList.isEmpty()) {
            // Show the dialog immediately so the follow is blocked, then check relays in background.
            _pendingFirstFollow.value = pubkey
            _firstFollowCheckDone.value = false

            followCheckJob?.cancel()
            followCheckJob = scope.launch {
                val relayList = fetchMyFollowListFromRelays()
                if (_pendingFirstFollow.value == null) return@launch // user already dismissed
                if (!relayList.isNullOrEmpty()) {
                    // Relay returned an existing follow list — add and publish silently.
                    _pendingFirstFollow.value = null
                    _firstFollowCheckDone.value = false
                    val newList = Nip02.addFollow(relayList, pubkey)
                    val tags = Nip02.buildFollowTags(newList)
                    val event = s.signEvent(kind = 3, content = "", tags = tags)
                    relayPool.sendToWriteRelays(ClientMessage.event(event))
                    contactRepo.updateFromEvent(event)
                } else {
                    // Confirmed no existing list — let user decide.
                    _firstFollowCheckDone.value = true
                }
            }
            return
        }

        val newList = if (isCurrentlyFollowing) {
            Nip02.removeFollow(currentList, pubkey)
        } else {
            Nip02.addFollow(currentList, pubkey)
        }
        val tags = Nip02.buildFollowTags(newList)
        scope.launch {
            val event = s.signEvent(kind = 3, content = "", tags = tags)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            contactRepo.updateFromEvent(event)
        }
    }

    fun confirmFirstFollow() {
        val pubkey = _pendingFirstFollow.value ?: return
        _pendingFirstFollow.value = null
        _firstFollowCheckDone.value = false
        followCheckJob?.cancel()
        followCheckJob = null
        val s = getSigner() ?: return
        val currentList = contactRepo.getFollowList()
        val newList = Nip02.addFollow(currentList, pubkey)
        val tags = Nip02.buildFollowTags(newList)
        scope.launch {
            val event = s.signEvent(kind = 3, content = "", tags = tags)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            contactRepo.updateFromEvent(event)
        }
    }

    fun dismissFirstFollow() {
        _pendingFirstFollow.value = null
        _firstFollowCheckDone.value = false
        followCheckJob?.cancel()
        followCheckJob = null
    }

    private suspend fun fetchMyFollowListFromRelays(): List<Nip02.FollowEntry>? {
        val myPubkey = getUserPubkey() ?: return null
        val subId = "check-fl-${myPubkey.take(8)}"
        relayPool.sendToReadRelays(ClientMessage.req(subId, Filter(kinds = listOf(3), authors = listOf(myPubkey), limit = 1)))
        val result = withTimeoutOrNull(5_000) {
            contactRepo.followList.first { it.isNotEmpty() }
        }
        relayPool.closeOnAllRelays(subId)
        return result
    }

    fun followAll(pubkeys: Set<String>) {
        val s = getSigner() ?: return
        var currentList = contactRepo.getFollowList()
        for (pk in pubkeys) {
            if (!contactRepo.isFollowing(pk)) {
                currentList = Nip02.addFollow(currentList, pk)
            }
        }
        val tags = Nip02.buildFollowTags(currentList)
        scope.launch {
            val event = s.signEvent(kind = 3, content = "", tags = tags)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            contactRepo.updateFromEvent(event)
        }
    }

    fun blockUser(pubkey: String) {
        muteRepo.blockUser(pubkey)
        eventRepo.purgeUser(pubkey)
        notifRepo.purgeUser(pubkey)
        dmRepo.purgeUser(pubkey)
        publishMuteList()
    }

    fun unblockUser(pubkey: String) {
        muteRepo.unblockUser(pubkey)
        publishMuteList()
    }

    fun muteThread(rootEventId: String) {
        muteRepo.muteThread(rootEventId)
        notifRepo.purgeThread(rootEventId)
        eventRepo.purgeThread(rootEventId)
    }

    fun updateMutedWords() {
        publishMuteList()
    }

    private fun publishMuteList() {
        val s = getSigner() ?: return
        scope.launch {
            val privateJson = Nip51.buildMuteListContent(muteRepo.getBlockedPubkeys(), muteRepo.getMutedWords())
            val encrypted = s.nip44Encrypt(privateJson, s.pubkeyHex)
            val event = s.signEvent(kind = Nip51.KIND_MUTE_LIST, content = encrypted, tags = emptyList())
            relayPool.sendToWriteRelays(ClientMessage.event(event))
        }
    }

    fun sendRepost(event: NostrEvent) {
        val s = getSigner() ?: return
        scope.launch {
            try {
                val hint = outboxRouter.getRelayHint(event.pubkey)
                val tags = Nip18.buildRepostTags(event, hint).toMutableList()
                if (interfacePrefs.isClientTagEnabled()) {
                    tags.add(listOf("client", "Wisp"))
                }
                val repostEvent = s.signEvent(kind = 6, content = event.toJson(), tags = tags)
                val msg = ClientMessage.event(repostEvent)
                outboxRouter.publishToInbox(msg, event.pubkey)
                eventRepo.markUserRepost(event.id)
                eventRepo.addEvent(repostEvent)
            } catch (_: Exception) {}
        }
    }

    fun sendReaction(event: NostrEvent, content: String = "+") {
        toggleReaction(event, content)
    }

    fun toggleReaction(event: NostrEvent, emoji: String) {
        val s = getSigner() ?: return
        val myPubkey = s.pubkeyHex
        val existingEventId = eventRepo.getUserReactionEventId(event.id, myPubkey, emoji)

        scope.launch {
            try {
                if (existingEventId != null) {
                    val tags = Nip09.buildDeletionTags(existingEventId, 7)
                    val deletionEvent = s.signEvent(kind = 5, content = "", tags = tags)
                    relayPool.sendToWriteRelays(ClientMessage.event(deletionEvent))
                    eventRepo.removeReaction(event.id, myPubkey, emoji)
                } else {
                    val shortcodeMatch = Nip30.shortcodeRegex.matchEntire(emoji)
                    var tags: List<List<String>> = if (shortcodeMatch != null) {
                        val shortcode = shortcodeMatch.groupValues[1]
                        val url = customEmojiRepo.resolvedEmojis.value[shortcode]
                        if (url != null) {
                            Nip25.buildReactionTagsWithEmoji(
                                event, CustomEmoji(shortcode, url)
                            )
                        } else {
                            Nip25.buildReactionTags(event)
                        }
                    } else {
                        Nip25.buildReactionTags(event)
                    }

                    if (interfacePrefs.isClientTagEnabled()) {
                        tags = tags + listOf(listOf("client", "Wisp"))
                    }

                    val createdAt: Long
                    if (powPrefs.isReactionPowEnabled()) {
                        val pinned = System.currentTimeMillis() / 1000
                        val result = withContext(Dispatchers.Default) {
                            Nip13.mine(
                                pubkeyHex = myPubkey,
                                kind = 7,
                                content = emoji,
                                tags = tags,
                                targetDifficulty = powPrefs.getReactionDifficulty(),
                                createdAt = pinned
                            )
                        }
                        tags = result.tags
                        createdAt = result.createdAt
                    } else {
                        createdAt = System.currentTimeMillis() / 1000
                    }

                    val reactionEvent = s.signEvent(kind = 7, content = emoji, tags = tags, createdAt = createdAt)
                    val msg = ClientMessage.event(reactionEvent)
                    outboxRouter.publishToInbox(msg, event.pubkey)
                    eventRepo.addEvent(reactionEvent)
                    _reactionSent.tryEmit(Unit)
                }
            } catch (_: Exception) {}
        }
    }

    fun togglePin(eventId: String) {
        if (pinRepo.isPinned(eventId)) {
            pinRepo.unpinEvent(eventId)
        } else {
            pinRepo.pinEvent(eventId)
        }
        publishPinList()
    }

    private fun publishPinList() {
        val s = getSigner() ?: return
        val ids = pinRepo.getPinnedIds()
        val hints = eventRepo.getRelayHintsForEvents(ids)
        val tags = Nip51.buildPinListTags(ids, relayHints = hints)
        scope.launch {
            val event = s.signEvent(kind = Nip51.KIND_PIN_LIST, content = "", tags = tags)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
        }
    }

    fun deleteEvent(eventId: String, kind: Int) {
        val s = getSigner() ?: return
        scope.launch {
            try {
                val tags = Nip09.buildDeletionTags(eventId, kind)
                val deletionEvent = s.signEvent(kind = 5, content = "", tags = tags)
                relayPool.sendToWriteRelays(ClientMessage.event(deletionEvent))
                deletedEventsRepo.markDeleted(eventId)
                eventRepo.removeEvent(eventId)
            } catch (_: Exception) {}
        }
    }

    /**
     * Opens a subscription for zap receipts (kind 9735) targeting [eventId].
     * Kept open for 30s to catch the receipt whenever the LNURL provider publishes it.
     * Returns the subscription ID so the caller can close it early on failure.
     */
    fun subscribeZapReceipt(eventId: String): String {
        val subId = "zap-rcpt-${eventId.take(12)}"
        val filter = Filter(kinds = listOf(9735), eTags = listOf(eventId))
        relayPool.sendToReadRelays(ClientMessage.req(subId, filter))
        scope.launch {
            kotlinx.coroutines.delay(30_000)
            relayPool.closeOnAllRelays(subId)
        }
        return subId
    }

    fun sendZap(event: NostrEvent, amountMsats: Long, message: String = "", isAnonymous: Boolean = false, isPrivate: Boolean = false) {
        val profileData = eventRepo.getProfileData(event.pubkey)
        val lud16 = profileData?.lud16
        if (lud16.isNullOrBlank()) {
            _zapError.tryEmit("This user has no lightning address")
            return
        }
        // Reconnect wallet if credentials exist but not connected
        val wallet = getWalletProvider()
        if (wallet.hasConnection() && !wallet.isConnected.value) {
            wallet.connect()
        }
        scope.launch {
            _zapInProgress.value = _zapInProgress.value + event.id
            // Open receipt subscription BEFORE paying so we catch the 9735
            // even if the LNURL provider publishes it before NWC confirms
            val receiptSubId = subscribeZapReceipt(event.id)
            // For private zaps, also subscribe on DM relays for the receipt
            if (isPrivate && relayPool.hasDmRelays()) {
                val dmFilter = Filter(kinds = listOf(9735), eTags = listOf(event.id))
                relayPool.sendToDmRelays(ClientMessage.req("zap-rcpt-dm-${event.id.take(12)}", dmFilter))
            }
            val result = zapSender.sendZap(
                recipientLud16 = lud16,
                recipientPubkey = event.pubkey,
                eventId = event.id,
                amountMsats = amountMsats,
                message = message,
                isAnonymous = isAnonymous,
                isPrivate = isPrivate
            )
            _zapInProgress.value = _zapInProgress.value - event.id
            result.fold(
                onSuccess = {
                    val myPubkey = if (isAnonymous) "" else (getUserPubkey() ?: "")
                    eventRepo.addOptimisticZap(event.id, myPubkey, amountMsats / 1000, message, isPrivate)
                    _zapSuccess.tryEmit(event.id)
                },
                onFailure = { e ->
                    if (isPaymentInFlight(e)) {
                        // Payment was sent but we lost the connection before confirmation.
                        // Treat as success to avoid prompting the user to pay again.
                        val myPubkey = if (isAnonymous) "" else (getUserPubkey() ?: "")
                        eventRepo.addOptimisticZap(event.id, myPubkey, amountMsats / 1000, message, isPrivate)
                        _zapSuccess.tryEmit(event.id)
                    } else {
                        _zapError.tryEmit(e.message ?: "Zap failed")
                        // Close receipt subscription on failure
                        relayPool.closeOnAllRelays(receiptSubId)
                        if (isPrivate) relayPool.closeOnAllRelays("zap-rcpt-dm-${event.id.take(12)}")
                    }
                }
            )
        }
    }

    fun sendZapToPubkey(
        pubkey: String,
        amountMsats: Long,
        message: String = "",
        isAnonymous: Boolean = false,
        rumorId: String? = null
    ) {
        val lud16 = eventRepo.getProfileData(pubkey)?.lud16
        if (lud16.isNullOrBlank()) {
            _zapError.tryEmit("This user has no lightning address")
            return
        }
        val wallet = getWalletProvider()
        if (wallet.hasConnection() && !wallet.isConnected.value) wallet.connect()
        scope.launch {
            _zapInProgress.value = _zapInProgress.value + pubkey
            val result = zapSender.sendZap(
                recipientLud16 = lud16,
                recipientPubkey = pubkey,
                eventId = rumorId,
                amountMsats = amountMsats,
                message = message,
                isAnonymous = isAnonymous,
                isPrivate = false
            )
            _zapInProgress.value = _zapInProgress.value - pubkey
            result.fold(
                onSuccess = { _zapSuccess.tryEmit(pubkey) },
                onFailure = { e ->
                    if (isPaymentInFlight(e)) {
                        _zapSuccess.tryEmit(pubkey)
                    } else {
                        _zapError.tryEmit(e.message ?: "Zap failed")
                    }
                }
            )
        }
    }

    /**
     * Returns true if the exception indicates the payment request was sent
     * but we lost the wallet response (e.g. connection drop, cancellation).
     * In this case the payment is likely in-flight and we should NOT show
     * an error to avoid prompting the user to pay again.
     */
    private fun isPaymentInFlight(e: Throwable): Boolean {
        // CancellationException means the coroutine/connection was cancelled
        // after the pay request was already dispatched to the wallet.
        if (e is kotlinx.coroutines.CancellationException) return true
        // Wallet-returned errors (INSUFFICIENT_BALANCE, etc.) are plain Exceptions
        // with the error code in the message — those are real failures.
        return false
    }

    fun publishPollVote(pollEventId: String, optionIds: List<String>) {
        val s = getSigner() ?: return
        scope.launch {
            try {
                val tags = Nip88.buildResponseTags(pollEventId, optionIds).toMutableList()
                if (interfacePrefs.isClientTagEnabled()) {
                    tags.add(listOf("client", "Wisp"))
                }
                val event = s.signEvent(kind = Nip88.KIND_POLL_RESPONSE, content = "", tags = tags)
                val msg = ClientMessage.event(event)
                // Send to our write relays
                relayPool.sendToWriteRelays(msg)
                // Also send to the poll's specified relays per NIP-88
                val pollEvent = eventRepo.getEvent(pollEventId)
                if (pollEvent != null) {
                    for (url in Nip88.parsePollRelays(pollEvent)) {
                        relayPool.sendToRelayOrEphemeral(url, msg)
                    }
                }
                // Optimistically add to eventRepo so UI updates immediately
                eventRepo.addEvent(event)
            } catch (_: Exception) {}
        }
    }
}
