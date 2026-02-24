package com.wisp.app.nostr

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class MuteList(
    val pubkeys: Set<String> = emptySet(),
    val words: Set<String> = emptySet()
)

data class FollowSet(
    val pubkey: String,
    val dTag: String,
    val name: String,
    val members: Set<String>,
    val createdAt: Long
)

data class BookmarkList(
    val eventIds: Set<String> = emptySet(),
    val coordinates: Set<String> = emptySet(),
    val hashtags: Set<String> = emptySet(),
    val createdAt: Long = 0
)

data class BookmarkSet(
    val pubkey: String,
    val dTag: String,
    val name: String,
    val eventIds: Set<String>,
    val coordinates: Set<String> = emptySet(),
    val hashtags: Set<String> = emptySet(),
    val createdAt: Long = 0
)

object Nip51 {
    const val KIND_BLOCKED_RELAYS = 10006
    const val KIND_SEARCH_RELAYS = 10007
    const val KIND_MUTE_LIST = 10000
    const val KIND_PIN_LIST = 10001
    const val KIND_BOOKMARK_LIST = 10003
    const val KIND_DM_RELAYS = 10050
    const val KIND_FOLLOW_SET = 30000
    const val KIND_BOOKMARK_SET = 30003

    fun parseRelaySet(event: NostrEvent): List<String> {
        return event.tags.mapNotNull { tag ->
            if (tag.size >= 2 && (tag[0] == "relay" || tag[0] == "r")) tag[1].trim().trimEnd('/') else null
        }
    }

    fun buildRelaySetTags(urls: List<String>): List<List<String>> {
        return urls.map { listOf("relay", it) }
    }

    fun parseMuteList(event: NostrEvent): MuteList {
        val pubkeys = mutableSetOf<String>()
        val words = mutableSetOf<String>()
        for (tag in event.tags) {
            if (tag.size < 2) continue
            when (tag[0]) {
                "p" -> pubkeys.add(tag[1])
                "word" -> words.add(tag[1])
            }
        }
        return MuteList(pubkeys, words)
    }

    fun parsePrivateTags(json: String): MuteList {
        val pubkeys = mutableSetOf<String>()
        val words = mutableSetOf<String>()
        try {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonArray
            for (element in arr) {
                val tag = element.jsonArray
                if (tag.size < 2) continue
                when (tag[0].jsonPrimitive.content) {
                    "p" -> pubkeys.add(tag[1].jsonPrimitive.content)
                    "word" -> words.add(tag[1].jsonPrimitive.content)
                }
            }
        } catch (_: Exception) {}
        return MuteList(pubkeys, words)
    }

    fun buildMuteListContent(blockedPubkeys: Set<String>, mutedWords: Set<String>): String {
        val arr = buildJsonArray {
            for (pubkey in blockedPubkeys) {
                add(buildJsonArray { add(JsonPrimitive("p")); add(JsonPrimitive(pubkey)) })
            }
            for (word in mutedWords) {
                add(buildJsonArray { add(JsonPrimitive("word")); add(JsonPrimitive(word)) })
            }
        }
        return arr.toString()
    }

    fun buildMuteListTags(blockedPubkeys: Set<String>, mutedWords: Set<String>): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        for (pubkey in blockedPubkeys) tags.add(listOf("p", pubkey))
        for (word in mutedWords) tags.add(listOf("word", word))
        return tags
    }

    fun parseFollowSet(event: NostrEvent): FollowSet? {
        if (event.kind != KIND_FOLLOW_SET) return null
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        val members = mutableSetOf<String>()
        for (tag in event.tags) {
            if (tag.size >= 2 && tag[0] == "p") members.add(tag[1])
        }
        return FollowSet(
            pubkey = event.pubkey,
            dTag = dTag,
            name = dTag,
            members = members,
            createdAt = event.created_at
        )
    }

    fun buildFollowSetTags(dTag: String, members: Set<String>): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("d", dTag))
        for (pubkey in members) tags.add(listOf("p", pubkey))
        return tags
    }

    fun parseBookmarkSet(event: NostrEvent): BookmarkSet? {
        if (event.kind != KIND_BOOKMARK_SET) return null
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        val eventIds = mutableSetOf<String>()
        val coordinates = mutableSetOf<String>()
        val hashtags = mutableSetOf<String>()
        for (tag in event.tags) {
            if (tag.size < 2) continue
            when (tag[0]) {
                "e" -> eventIds.add(tag[1])
                "a" -> coordinates.add(tag[1])
                "t" -> hashtags.add(tag[1])
            }
        }
        return BookmarkSet(
            pubkey = event.pubkey,
            dTag = dTag,
            name = dTag,
            eventIds = eventIds,
            coordinates = coordinates,
            hashtags = hashtags,
            createdAt = event.created_at
        )
    }

    fun buildBookmarkSetTags(
        dTag: String,
        eventIds: Set<String>,
        coordinates: Set<String> = emptySet(),
        hashtags: Set<String> = emptySet()
    ): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("d", dTag))
        for (id in eventIds) tags.add(listOf("e", id))
        for (coord in coordinates) tags.add(listOf("a", coord))
        for (tag in hashtags) tags.add(listOf("t", tag))
        return tags
    }

    fun parseBookmarkList(event: NostrEvent): BookmarkList {
        val eventIds = mutableSetOf<String>()
        val coordinates = mutableSetOf<String>()
        val hashtags = mutableSetOf<String>()
        for (tag in event.tags) {
            if (tag.size < 2) continue
            when (tag[0]) {
                "e" -> eventIds.add(tag[1])
                "a" -> coordinates.add(tag[1])
                "t" -> hashtags.add(tag[1])
            }
        }
        return BookmarkList(eventIds, coordinates, hashtags, event.created_at)
    }

    fun buildBookmarkListTags(eventIds: Set<String>, coordinates: Set<String> = emptySet(), hashtags: Set<String> = emptySet()): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        for (id in eventIds) tags.add(listOf("e", id))
        for (coord in coordinates) tags.add(listOf("a", coord))
        for (tag in hashtags) tags.add(listOf("t", tag))
        return tags
    }

    fun parsePinList(event: NostrEvent): Set<String> {
        return event.tags.mapNotNull { tag ->
            if (tag.size >= 2 && tag[0] == "e") tag[1] else null
        }.toSet()
    }

    fun buildPinListTags(eventIds: Set<String>): List<List<String>> {
        return eventIds.map { listOf("e", it) }
    }
}
