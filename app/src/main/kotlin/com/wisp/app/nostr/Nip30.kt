package com.wisp.app.nostr

data class CustomEmoji(val shortcode: String, val url: String)

data class EmojiSet(
    val pubkey: String,
    val dTag: String,
    val title: String,
    val emojis: List<CustomEmoji>,
    val createdAt: Long
)

data class UserEmojiList(
    val emojis: List<CustomEmoji>,
    val setReferences: List<String>,
    val createdAt: Long
)

object Nip30 {
    const val KIND_EMOJI_SET = 30030
    const val KIND_USER_EMOJI_LIST = 10030
    val shortcodeRegex = Regex(""":([a-zA-Z0-9_]+):""")

    fun parseEmojiTags(event: NostrEvent): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (tag in event.tags) {
            if (tag.size >= 3 && tag[0] == "emoji") {
                map[tag[1]] = tag[2]
            }
        }
        return map
    }

    fun parseEmojiSet(event: NostrEvent): EmojiSet? {
        if (event.kind != KIND_EMOJI_SET) return null
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        val title = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1) ?: dTag
        val emojis = mutableListOf<CustomEmoji>()
        for (tag in event.tags) {
            if (tag.size >= 3 && tag[0] == "emoji") {
                emojis.add(CustomEmoji(tag[1], tag[2]))
            }
        }
        return EmojiSet(
            pubkey = event.pubkey,
            dTag = dTag,
            title = title,
            emojis = emojis,
            createdAt = event.created_at
        )
    }

    fun parseUserEmojiList(event: NostrEvent): UserEmojiList {
        val emojis = mutableListOf<CustomEmoji>()
        val setRefs = mutableListOf<String>()
        for (tag in event.tags) {
            if (tag.size < 2) continue
            when (tag[0]) {
                "emoji" -> if (tag.size >= 3) emojis.add(CustomEmoji(tag[1], tag[2]))
                "a" -> if (tag[1].startsWith("$KIND_EMOJI_SET:")) setRefs.add(tag[1])
            }
        }
        return UserEmojiList(emojis, setRefs, event.created_at)
    }

    fun buildEmojiSetTags(dTag: String, title: String, emojis: List<CustomEmoji>): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("d", dTag))
        if (title != dTag) tags.add(listOf("title", title))
        for (emoji in emojis) {
            tags.add(listOf("emoji", emoji.shortcode, emoji.url))
        }
        return tags
    }

    fun buildUserEmojiListTags(emojis: List<CustomEmoji>, setRefs: List<String>): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        for (emoji in emojis) {
            tags.add(listOf("emoji", emoji.shortcode, emoji.url))
        }
        for (ref in setRefs) {
            tags.add(listOf("a", ref))
        }
        return tags
    }

    fun buildEmojiTag(emoji: CustomEmoji): List<String> {
        return listOf("emoji", emoji.shortcode, emoji.url)
    }

    fun parseSetReference(ref: String): Triple<Int, String, String>? {
        val parts = ref.split(":")
        if (parts.size < 3) return null
        val kind = parts[0].toIntOrNull() ?: return null
        return Triple(kind, parts[1], parts[2])
    }

    fun buildSetReference(pubkey: String, dTag: String): String {
        return "$KIND_EMOJI_SET:$pubkey:$dTag"
    }
}
