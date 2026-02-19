package com.wisp.app.nostr

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

object Nip51 {
    const val KIND_BLOCKED_RELAYS = 10006
    const val KIND_SEARCH_RELAYS = 10007
    const val KIND_MUTE_LIST = 10000
    const val KIND_DM_RELAYS = 10050
    const val KIND_FOLLOW_SET = 30000

    fun parseRelaySet(event: NostrEvent): List<String> {
        return event.tags.mapNotNull { tag ->
            if (tag.size >= 2 && (tag[0] == "relay" || tag[0] == "r")) tag[1] else null
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
}
