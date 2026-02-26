package com.wisp.app.viewmodel

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.CustomEmoji
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip09
import com.wisp.app.nostr.Nip30
import com.wisp.app.nostr.Nip51
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.repo.BookmarkSetRepository
import com.wisp.app.repo.CustomEmojiRepository
import com.wisp.app.repo.EventRepository
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
    private val bookmarkSetRepo: BookmarkSetRepository,
    private val customEmojiRepo: CustomEmojiRepository,
    private val metadataFetcher: MetadataFetcher,
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
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                listRepo.updateFromEvent(event, plaintext)
            }
        } else {
            val tags = Nip51.buildFollowSetTags(dTag, emptySet(), title = title)
            scope.launch {
                val event = s.signEvent(kind = Nip51.KIND_FOLLOW_SET, content = "", tags = tags)
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
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                listRepo.updateFromEvent(event, plaintext)
            }
        } else {
            val tags = Nip51.buildFollowSetTags(dTag, newMembers, title = title)
            scope.launch {
                val event = s.signEvent(kind = Nip51.KIND_FOLLOW_SET, content = "", tags = tags)
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
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                listRepo.updateFromEvent(event, if (hasContent) plaintext else "[]")
            }
        } else {
            val tags = Nip51.buildFollowSetTags(dTag, newMembers, title = title)
            scope.launch {
                val event = s.signEvent(kind = Nip51.KIND_FOLLOW_SET, content = "", tags = tags)
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
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                bookmarkSetRepo.updateFromEvent(event, plaintext)
            }
        } else {
            val tags = Nip51.buildBookmarkSetTags(dTag, emptySet(), title = title)
            scope.launch {
                val event = s.signEvent(kind = Nip51.KIND_BOOKMARK_SET, content = "", tags = tags)
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
        if (existing.isPrivate) {
            val (tags, plaintext) = Nip51.buildBookmarkSetPrivate(dTag, newIds, existing.coordinates, existing.hashtags, title = title)
            scope.launch {
                val encrypted = s.nip44Encrypt(plaintext, myPubkey)
                val event = s.signEvent(kind = Nip51.KIND_BOOKMARK_SET, content = encrypted, tags = tags)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                bookmarkSetRepo.updateFromEvent(event, plaintext)
            }
        } else {
            val tags = Nip51.buildBookmarkSetTags(dTag, newIds, existing.coordinates, existing.hashtags, title = title)
            scope.launch {
                val event = s.signEvent(kind = Nip51.KIND_BOOKMARK_SET, content = "", tags = tags)
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
        if (existing.isPrivate) {
            val (tags, plaintext) = Nip51.buildBookmarkSetPrivate(dTag, newIds, existing.coordinates, existing.hashtags, title = title)
            scope.launch {
                val hasContent = newIds.isNotEmpty() || existing.coordinates.isNotEmpty() || existing.hashtags.isNotEmpty() || title != null
                val encrypted = if (hasContent) s.nip44Encrypt(plaintext, myPubkey) else ""
                val event = s.signEvent(kind = Nip51.KIND_BOOKMARK_SET, content = encrypted, tags = tags)
                relayPool.sendToWriteRelays(ClientMessage.event(event))
                bookmarkSetRepo.updateFromEvent(event, if (hasContent) plaintext else "[]")
            }
        } else {
            val tags = Nip51.buildBookmarkSetTags(dTag, newIds, existing.coordinates, existing.hashtags, title = title)
            scope.launch {
                val event = s.signEvent(kind = Nip51.KIND_BOOKMARK_SET, content = "", tags = tags)
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
        relayPool.sendToReadRelays(ClientMessage.req(subId, filter))
        scope.launch {
            subManager.awaitEoseWithTimeout(subId)
            subManager.closeSubscription(subId)
            withContext(processingContext) {
                metadataFetcher.sweepMissingProfiles()
            }
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
        val filters = missing.mapNotNull { ref ->
            val parsed = Nip30.parseSetReference(ref) ?: return@mapNotNull null
            Filter(kinds = listOf(Nip30.KIND_EMOJI_SET), authors = listOf(parsed.second), dTags = listOf(parsed.third), limit = 1)
        }
        if (filters.isEmpty()) return
        relayPool.sendToReadRelays(ClientMessage.req("emoji-sets", filters))
        scope.launch {
            subManager.awaitEoseWithTimeout("emoji-sets")
            subManager.closeSubscription("emoji-sets")
        }
    }
}
