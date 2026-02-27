package com.wisp.app.viewmodel

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.CustomEmoji
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.Nip25
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.Nip18
import com.wisp.app.nostr.Nip09
import com.wisp.app.nostr.Nip57
import com.wisp.app.relay.OutboxRouter
import com.wisp.app.relay.RelayPool
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.DmRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.MuteRepository
import com.wisp.app.repo.NotificationRepository
import com.wisp.app.repo.NwcRepository
import com.wisp.app.repo.PinRepository
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.DeletedEventsRepository
import com.wisp.app.repo.ZapSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val nwcRepo: NwcRepository,
    private val customEmojiRepo: CustomEmojiRepository,
    private val zapSender: ZapSender,
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

    fun toggleFollow(pubkey: String) {
        val s = getSigner() ?: return
        val currentList = contactRepo.getFollowList()
        val newList = if (contactRepo.isFollowing(pubkey)) {
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
                val tags = Nip18.buildRepostTags(event, hint)
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
                    val tags = if (shortcodeMatch != null) {
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
                    val reactionEvent = s.signEvent(kind = 7, content = emoji, tags = tags)
                    val msg = ClientMessage.event(reactionEvent)
                    outboxRouter.publishToInbox(msg, event.pubkey)
                    eventRepo.addEvent(reactionEvent)
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
        val tags = Nip51.buildPinListTags(pinRepo.getPinnedIds())
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

    fun sendZap(event: NostrEvent, amountMsats: Long, message: String = "", isAnonymous: Boolean = false) {
        val profileData = eventRepo.getProfileData(event.pubkey)
        val lud16 = profileData?.lud16
        if (lud16.isNullOrBlank()) {
            _zapError.tryEmit("This user has no lightning address")
            return
        }
        // Reconnect NWC relay if credentials exist but relay disconnected
        if (nwcRepo.hasConnection() && !nwcRepo.isConnected.value) {
            nwcRepo.connect()
        }
        scope.launch {
            _zapInProgress.value = _zapInProgress.value + event.id
            // Open receipt subscription BEFORE paying so we catch the 9735
            // even if the LNURL provider publishes it before NWC confirms
            val receiptSubId = subscribeZapReceipt(event.id)
            val result = zapSender.sendZap(
                recipientLud16 = lud16,
                recipientPubkey = event.pubkey,
                eventId = event.id,
                amountMsats = amountMsats,
                message = message,
                isAnonymous = isAnonymous
            )
            _zapInProgress.value = _zapInProgress.value - event.id
            result.fold(
                onSuccess = {
                    val myPubkey = if (isAnonymous) "" else (getUserPubkey() ?: "")
                    eventRepo.addOptimisticZap(event.id, myPubkey, amountMsats / 1000, message)
                    _zapSuccess.tryEmit(event.id)
                },
                onFailure = { e ->
                    _zapError.tryEmit(e.message ?: "Zap failed")
                    // Close receipt subscription on failure
                    relayPool.closeOnAllRelays(receiptSubId)
                }
            )
        }
    }
}
