package com.wisp.app.nostr

object Nip02 {
    data class FollowEntry(
        val pubkey: String,
        val relayHint: String? = null,
        val petname: String? = null
    )

    fun parseFollowList(event: NostrEvent): List<FollowEntry> {
        if (event.kind != 3) return emptyList()
        return event.tags.mapNotNull { tag ->
            if (tag.size < 2 || tag[0] != "p") return@mapNotNull null
            FollowEntry(
                pubkey = tag[1],
                relayHint = tag.getOrNull(2)?.ifBlank { null },
                petname = tag.getOrNull(3)?.ifBlank { null }
            )
        }
    }

    fun buildFollowTags(follows: List<FollowEntry>): List<List<String>> {
        return follows.map { entry ->
            listOfNotNull("p", entry.pubkey, entry.relayHint ?: "", entry.petname).let { parts ->
                // Trim trailing empty strings
                parts.dropLastWhile { it.isEmpty() }
            }
        }
    }

    fun addFollow(current: List<FollowEntry>, pubkey: String): List<FollowEntry> {
        if (current.any { it.pubkey == pubkey }) return current
        return current + FollowEntry(pubkey)
    }

    fun removeFollow(current: List<FollowEntry>, pubkey: String): List<FollowEntry> {
        return current.filter { it.pubkey != pubkey }
    }
}
