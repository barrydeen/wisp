package com.wisp.app.viewmodel

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.CustomEmoji
import com.wisp.app.nostr.Filter
import com.wisp.app.relay.OutboxRouter
import kotlinx.coroutines.delay
import com.wisp.app.nostr.Nip09
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.repo.BookmarkSetRepository
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.InterestRepository
import com.wisp.app.repo.ListRepository
import com.wisp.app.repo.MetadataFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Handles CRUD for follow-set lists, bookmark sets, and custom emoji sets.
 * Extracted from FeedViewModel to reduce its size.
 */
class ListCrudManager(
    private val relayPool: RelayPool,
    private val subManager: SubscriptionManager,
    private val eventRepo: EventRepository,
    private val listRepo: ListRepository,
    private val interestRepo: InterestRepository,
    private val bookmarkSetRepo: BookmarkSetRepository,
    private val customEmojiRepo: CustomEmojiRepository,
    private val metadataFetcher: MetadataFetcher,
    private val outboxRouter: OutboxRouter?,
    private val scope: CoroutineScope,
    private val processingContext: CoroutineContext,
    private val getSigner: () -> NostrSigner?,
    private val getUserPubkey: () -> String?
) {
    // -- Follow Set (kind 30000) CRUD --

    fun createList(name: String, isPrivate: Boolean = false) {
        val s = getSigner() ?: return
        val title = name.trim()
        val dTag = title.lowercase().replace(Regex("[^a-z0-9-_]"), "-")
        if (isPrivate) {
            val (tags, plaintext) = Nip51.buildFollowSetPrivate(dTag, emptySet(), title = title)
            scope.launch {
                val encrypted = s.nip44Encrypt(plaintext, s.pubkeyHex)
                val event = s.signEvent(kind = Nip51.KIND_FOLLOW_SET, content = encrypted, tags = tags)
                eventRepo.cacheEvent(event)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                listRepo.updateFromEvent(event, plaintext)
            }
        } else {
            val tags = Nip51.buildFollowSetTags(dTag, emptySet(), title = title)
            scope.launch {
                val event = s.signEvent(kind = Nip51.KIND_FOLLOW_SET, content = "", tags = tags)
                eventRepo.cacheEvent(event)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                listRepo.updateFromEvent(event)
            }
        }
    }

    fun addToList(dTag: String, pubkey: String) {
        val s = getSigner() ?: return
        val myPubkey = s.pubkeyHex
        val existing = listRepo.getList(myPubkey, dTag) ?: return
        val newMembers = existing.members + pubkey
        val title = existing.name.takeIf { it != existing.dTag }
        if (existing.isPrivate) {
            val (tags, plaintext) = Nip51.buildFollowSetPrivate(dTag, newMembers, title = title)
            scope.launch {
                val encrypted = s.nip44Encrypt(plaintext, myPubkey)
                val event = s.signEvent(kind = Nip51.KIND_FOLLOW_SET, content = encrypted, tags = tags)
                eventRepo.cacheEvent(event)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                listRepo.updateFromEvent(event, plaintext)
            }
        } else {
            val tags = Nip51.buildFollowSetTags(dTag, newMembers, title = title)
            scope.launch {
                val event = s.signEvent(kind = Nip51.KIND_FOLLOW_SET, content = "", tags = tags)
                eventRepo.cacheEvent(event)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                listRepo.updateFromEvent(event)
            }
        }
    }

    fun removeFromList(dTag: String, pubkey: String) {
        val s = getSigner() ?: return
        val myPubkey = s.pubkeyHex
        val existing = listRepo.getList(myPubkey, dTag) ?: return
        val newMembers = existing.members - pubkey
        val title = existing.name.takeIf { it != existing.dTag }
        if (existing.isPrivate) {
            val (tags, plaintext) = Nip51.buildFollowSetPrivate(dTag, newMembers, title = title)
            scope.launch {
                val hasContent = newMembers.isNotEmpty() || title != null
                val encrypted = if (hasContent) s.nip44Encrypt(plaintext, myPubkey) else ""
                val event = s.signEvent(kind = Nip51.KIND_FOLLOW_SET, content = encrypted, tags = tags)
                eventRepo.cacheEvent(event)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                listRepo.updateFromEvent(event, if (hasContent) plaintext else "[]")
            }
        } else {
            val tags = Nip51.buildFollowSetTags(dTag, newMembers, title = title)
            scope.launch {
                val event = s.signEvent(kind = Nip51.KIND_FOLLOW_SET, content = "", tags = tags)
                eventRepo.cacheEvent(event)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                listRepo.updateFromEvent(event)
            }
        }
    }

    fun deleteList(dTag: String) {
        val s = getSigner() ?: return
        val myPubkey = s.pubkeyHex
        val deletionTags = Nip09.buildAddressableDeletionTags(Nip51.KIND_FOLLOW_SET, myPubkey, dTag)
        scope.launch {
            val deleteEvent = s.signEvent(kind = 5, content = "", tags = deletionTags)
            relayPool.sendToWriteRelays(ClientMessage.event(deleteEvent))
            listRepo.removeList(myPubkey, dTag)
        }
    }

    fun fetchUserLists(pubkey: String) {
        val subId = "user-lists-${pubkey.take(8)}"
        val filter = Filter(kinds = listOf(Nip51.KIND_FOLLOW_SET), authors = listOf(pubkey), limit = 50)
        relayPool.sendToAll(ClientMessage.req(subId, filter))
        scope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
        }
    }

    // -- Bookmark Set (kind 30003) CRUD --

    fun createBookmarkSet(name: String, isPrivate: Boolean = false) {
        val s = getSigner() ?: return
        val title = name.trim()
        val dTag = title.lowercase().replace(Regex("[^a-z0-9-_]"), "-")
        if (isPrivate) {
            val (tags, plaintext) = Nip51.buildBookmarkSetPrivate(dTag, emptySet(), title = title)
            scope.launch {
                val encrypted = s.nip44Encrypt(plaintext, s.pubkeyHex)
                val event = s.signEvent(kind = Nip51.KIND_BOOKMARK_SET, content = encrypted, tags = tags)
                eventRepo.cacheEvent(event)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                bookmarkSetRepo.updateFromEvent(event, plaintext)
            }
        } else {
            val tags = Nip51.buildBookmarkSetTags(dTag, emptySet(), title = title)
            scope.launch {
                val event = s.signEvent(kind = Nip51.KIND_BOOKMARK_SET, content = "", tags = tags)
                eventRepo.cacheEvent(event)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                bookmarkSetRepo.updateFromEvent(event)
            }
        }
    }

    fun addNoteToBookmarkSet(dTag: String, eventId: String) {
        val s = getSigner() ?: return
        val myPubkey = s.pubkeyHex
        val existing = bookmarkSetRepo.getSet(myPubkey, dTag) ?: return
        val newIds = existing.eventIds + eventId
        val title = existing.name.takeIf { it != existing.dTag }
        val hints = eventRepo.getRelayHintsForEvents(newIds)
        if (existing.isPrivate) {
            val (tags, plaintext) = Nip51.buildBookmarkSetPrivate(dTag, newIds, existing.coordinates, existing.hashtags, title = title, relayHints = hints)
            scope.launch {
                val encrypted = s.nip44Encrypt(plaintext, myPubkey)
                val event = s.signEvent(kind = Nip51.KIND_BOOKMARK_SET, content = encrypted, tags = tags)
                eventRepo.cacheEvent(event)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                bookmarkSetRepo.updateFromEvent(event, plaintext)
            }
        } else {
            val tags = Nip51.buildBookmarkSetTags(dTag, newIds, existing.coordinates, existing.hashtags, title = title, relayHints = hints)
            scope.launch {
                val event = s.signEvent(kind = Nip51.KIND_BOOKMARK_SET, content = "", tags = tags)
                eventRepo.cacheEvent(event)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                bookmarkSetRepo.updateFromEvent(event)
            }
        }
    }

    fun removeNoteFromBookmarkSet(dTag: String, eventId: String) {
        val s = getSigner() ?: return
        val myPubkey = s.pubkeyHex
        val existing = bookmarkSetRepo.getSet(myPubkey, dTag) ?: return
        val newIds = existing.eventIds - eventId
        val title = existing.name.takeIf { it != existing.dTag }
        val hints = eventRepo.getRelayHintsForEvents(newIds)
        if (existing.isPrivate) {
            val (tags, plaintext) = Nip51.buildBookmarkSetPrivate(dTag, newIds, existing.coordinates, existing.hashtags, title = title, relayHints = hints)
            scope.launch {
                val hasContent = newIds.isNotEmpty() || existing.coordinates.isNotEmpty() || existing.hashtags.isNotEmpty() || title != null
                val encrypted = if (hasContent) s.nip44Encrypt(plaintext, myPubkey) else ""
                val event = s.signEvent(kind = Nip51.KIND_BOOKMARK_SET, content = encrypted, tags = tags)
                eventRepo.cacheEvent(event)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                bookmarkSetRepo.updateFromEvent(event, if (hasContent) plaintext else "[]")
            }
        } else {
            val tags = Nip51.buildBookmarkSetTags(dTag, newIds, existing.coordinates, existing.hashtags, title = title, relayHints = hints)
            scope.launch {
                val event = s.signEvent(kind = Nip51.KIND_BOOKMARK_SET, content = "", tags = tags)
                eventRepo.cacheEvent(event)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                bookmarkSetRepo.updateFromEvent(event)
            }
        }
    }

    fun deleteBookmarkSet(dTag: String) {
        val s = getSigner() ?: return
        val myPubkey = s.pubkeyHex
        val deletionTags = Nip09.buildAddressableDeletionTags(Nip51.KIND_BOOKMARK_SET, myPubkey, dTag)
        scope.launch {
            val deleteEvent = s.signEvent(kind = 5, content = "", tags = deletionTags)
            relayPool.sendToWriteRelays(ClientMessage.event(deleteEvent))
            bookmarkSetRepo.removeSet(myPubkey, dTag)
        }
    }

    fun fetchBookmarkSetEvents(dTag: String) {
        val myPubkey = getUserPubkey() ?: return
        val set = bookmarkSetRepo.getSet(myPubkey, dTag) ?: return
        val ids = set.eventIds.toList()
        if (ids.isEmpty()) return
        val missing = ids.filter { eventRepo.getEvent(it) == null }
        if (missing.isEmpty()) return
        val subId = "fetch-bkset-${dTag.take(8)}"
        val filter = Filter(ids = missing)
        relayPool.sendToTopRelays(ClientMessage.req(subId, filter))
        scope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
            eventRepo.bumpEventCacheVersion()
            withContext(processingContext) {
                metadataFetcher.sweepMissingProfiles()
            }
        }
    }

    // -- Interest Set (kind 30015) CRUD --

    fun followHashtag(hashtag: String, dTag: String) {
        val s = getSigner() ?: return
        val existing = interestRepo.getSet(dTag)
        val newHashtags = (existing?.hashtags ?: emptySet()) + hashtag.lowercase()
        val title = existing?.name?.takeIf { it != dTag }
        val tags = Nip51.buildInterestSetTags(dTag, newHashtags, title = title)
        scope.launch {
            val event = s.signEvent(kind = Nip51.KIND_INTEREST_SET, content = "", tags = tags)
            eventRepo.cacheEvent(event)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            interestRepo.updateFromEvent(event)
        }
    }

    fun unfollowHashtag(hashtag: String, dTag: String) {
        val s = getSigner() ?: return
        val existing = interestRepo.getSet(dTag) ?: return
        val newHashtags = existing.hashtags - hashtag.lowercase()
        val title = existing.name.takeIf { it != dTag }
        val tags = Nip51.buildInterestSetTags(dTag, newHashtags, title = title)
        scope.launch {
            val event = s.signEvent(kind = Nip51.KIND_INTEREST_SET, content = "", tags = tags)
            eventRepo.cacheEvent(event)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            interestRepo.updateFromEvent(event)
        }
    }

    fun createInterestSet(name: String) {
        val s = getSigner() ?: return
        val title = name.trim()
        val dTag = title.lowercase().replace(Regex("[^a-z0-9-_]"), "-")
        val tags = Nip51.buildInterestSetTags(dTag, emptySet(), title = title)
        scope.launch {
            val event = s.signEvent(kind = Nip51.KIND_INTEREST_SET, content = "", tags = tags)
            eventRepo.cacheEvent(event)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            interestRepo.updateFromEvent(event)
        }
    }

    fun renameInterestSet(dTag: String, newName: String) {
        val s = getSigner() ?: return
        val existing = interestRepo.getSet(dTag) ?: return
        val tags = Nip51.buildInterestSetTags(dTag, existing.hashtags, title = newName.trim())
        scope.launch {
            val event = s.signEvent(kind = Nip51.KIND_INTEREST_SET, content = "", tags = tags)
            eventRepo.cacheEvent(event)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            interestRepo.updateFromEvent(event)
        }
    }

    fun deleteInterestSet(dTag: String) {
        val s = getSigner() ?: return
        val myPubkey = s.pubkeyHex
        val deletionTags = Nip09.buildAddressableDeletionTags(Nip51.KIND_INTEREST_SET, myPubkey, dTag)
        scope.launch {
            val deleteEvent = s.signEvent(kind = 5, content = "", tags = deletionTags)
            relayPool.sendToWriteRelays(ClientMessage.event(deleteEvent))
            interestRepo.removeSet(dTag)
        }
    }

    // -- Custom Emoji (NIP-30) CRUD --

    fun createEmojiSet(name: String, emojis: List<CustomEmoji>) {
        val s = getSigner() ?: return
        val dTag = name.trim().lowercase().replace(Regex("[^a-z0-9-_]"), "-")
        val tags = Nip30.buildEmojiSetTags(dTag, name.trim(), emojis)
        scope.launch {
            val event = s.signEvent(kind = Nip30.KIND_EMOJI_SET, content = "", tags = tags)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            customEmojiRepo.updateFromEvent(event)
        }
    }

    fun updateEmojiSet(dTag: String, title: String, emojis: List<CustomEmoji>) {
        val s = getSigner() ?: return
        val tags = Nip30.buildEmojiSetTags(dTag, title, emojis)
        scope.launch {
            val event = s.signEvent(kind = Nip30.KIND_EMOJI_SET, content = "", tags = tags)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            customEmojiRepo.updateFromEvent(event)
        }
    }

    fun deleteEmojiSet(dTag: String) {
        val s = getSigner() ?: return
        val myPubkey = s.pubkeyHex
        val deletionTags = Nip09.buildAddressableDeletionTags(Nip30.KIND_EMOJI_SET, myPubkey, dTag)
        scope.launch {
            val deleteEvent = s.signEvent(kind = 5, content = "", tags = deletionTags)
            relayPool.sendToWriteRelays(ClientMessage.event(deleteEvent))
        }
    }

    fun publishUserEmojiList(emojis: List<CustomEmoji>, setRefs: List<String>) {
        val s = getSigner() ?: return
        val tags = Nip30.buildUserEmojiListTags(emojis, setRefs)
        scope.launch {
            val event = s.signEvent(kind = Nip30.KIND_USER_EMOJI_LIST, content = "", tags = tags)
            relayPool.sendToWriteRelays(ClientMessage.event(event))
            customEmojiRepo.updateFromEvent(event)
        }
    }

    fun addSetToEmojiList(pubkey: String, dTag: String) {
        val current = customEmojiRepo.userEmojiList.value
        val emojis = current?.emojis ?: emptyList()
        val refs = current?.setReferences?.toMutableList() ?: mutableListOf()
        val newRef = Nip30.buildSetReference(pubkey, dTag)
        if (newRef !in refs) refs.add(newRef)
        publishUserEmojiList(emojis, refs)
    }

    fun removeSetFromEmojiList(pubkey: String, dTag: String) {
        val current = customEmojiRepo.userEmojiList.value
        val emojis = current?.emojis ?: emptyList()
        val refs = current?.setReferences?.toMutableList() ?: mutableListOf()
        refs.remove(Nip30.buildSetReference(pubkey, dTag))
        publishUserEmojiList(emojis, refs)
    }

    fun fetchMissingEmojiSets() {
        val missing = customEmojiRepo.getMissingSetReferences()
        if (missing.isEmpty()) return
        val parsed = missing.mapNotNull { ref ->
            Nip30.parseSetReference(ref)?.let { (_, pubkey, dTag) -> pubkey to dTag }
        }
        if (parsed.isEmpty()) return
        // Send per-author via outbox routing to hit each author's write relays,
        // with a fallback to read+top relays for authors without known relay lists.
        val subIds = mutableListOf<String>()
        for ((pubkey, dTag) in parsed) {
            val filter = Filter(kinds = listOf(Nip30.KIND_EMOJI_SET), authors = listOf(pubkey), dTags = listOf(dTag), limit = 1)
            val subId = "emoji-set-${pubkey.take(8)}"
            subIds.add(subId)
            val targeted = outboxRouter?.subscribeToUserWriteRelays(subId, pubkey, filter)
            if (targeted == null || targeted.isEmpty()) {
                val msg = ClientMessage.req(subId, filter)
                relayPool.sendToReadRelays(msg)
                relayPool.sendToTopRelays(msg, maxRelays = 5)
            }
        }
        // Wait for results then clean up — don't close on first EOSE since that
        // may be a relay with 0 results while slower relays have the data.
        scope.launch {
            delay(4_000)
            for (subId in subIds) subManager.closeSubscription(subId)
        }
    }
}
