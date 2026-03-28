package com.wisp.app.nostr

object Nip29 {
    const val KIND_CHAT_MESSAGE = 9
    const val KIND_EDIT_METADATA = 9002
    const val KIND_CREATE_GROUP = 9007
    const val KIND_DELETE_GROUP = 9008
    const val KIND_JOIN_REQUEST = 9021
    const val KIND_LEAVE_REQUEST = 9022
    const val KIND_GROUP_METADATA = 39000
    const val KIND_GROUP_ADMINS = 39001
    const val KIND_GROUP_MEMBERS = 39002

    data class GroupMetadata(
        val groupId: String,
        val name: String?,
        val picture: String?,
        val about: String?,
        val isPrivate: Boolean,
        val isClosed: Boolean
    )

    /**
     * Parse a group identifier string like "groups.nostr.com'mygroup" into
     * (relayUrl, groupId). Returns null if the format is invalid.
     */
    fun parseGroupIdentifier(identifier: String): Pair<String, String>? {
        val idx = identifier.indexOf('\'')
        if (idx < 0) return null
        // Normalize to lowercase — relay URLs are case-insensitive but used as map keys
        val host = identifier.substring(0, idx).trimStart('/').lowercase()
        if (host.isEmpty()) return null  // bare apostrophe or leading slash only
        val groupId = identifier.substring(idx + 1).ifEmpty { "_" }
        val relayUrl = (if (host.startsWith("wss://") || host.startsWith("ws://")) host
                        else "wss://$host").trimEnd('/')
        return Pair(relayUrl, groupId)
    }

    fun parseGroupMetadata(event: NostrEvent): GroupMetadata? {
        if (event.kind != KIND_GROUP_METADATA) return null
        val groupId = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        val name = event.tags.firstOrNull { it.size >= 2 && it[0] == "name" }?.get(1)
        val picture = event.tags.firstOrNull { it.size >= 2 && it[0] == "picture" }?.get(1)
        val about = event.tags.firstOrNull { it.size >= 2 && it[0] == "about" }?.get(1)
        val isPrivate = event.tags.any { it.isNotEmpty() && it[0] == "private" }
        val isClosed = event.tags.any { it.isNotEmpty() && it[0] == "closed" }
        return GroupMetadata(groupId, name, picture, about, isPrivate, isClosed)
    }

    fun buildChatMessage(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String,
        content: String
    ): NostrEvent = NostrEvent.create(
        privkey = privkey,
        pubkey = pubkey,
        kind = KIND_CHAT_MESSAGE,
        content = content,
        tags = listOf(listOf("h", groupId))
    )

    fun buildJoinRequest(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String
    ): NostrEvent = NostrEvent.create(
        privkey = privkey,
        pubkey = pubkey,
        kind = KIND_JOIN_REQUEST,
        content = "",
        tags = listOf(listOf("h", groupId))
    )

    fun buildCreateGroup(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String
    ): NostrEvent = NostrEvent.create(
        privkey = privkey,
        pubkey = pubkey,
        kind = KIND_CREATE_GROUP,
        content = "",
        tags = listOf(listOf("h", groupId))
    )

    fun buildEditMetadata(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String,
        name: String? = null,
        about: String? = null
    ): NostrEvent {
        val tags = mutableListOf(listOf("h", groupId))
        name?.let { tags.add(listOf("name", it)) }
        about?.let { tags.add(listOf("about", it)) }
        return NostrEvent.create(
            privkey = privkey,
            pubkey = pubkey,
            kind = KIND_EDIT_METADATA,
            content = "",
            tags = tags
        )
    }

    /** Generates a random NIP-29 compliant group ID (lowercase alphanumeric, 12 chars). */
    fun generateGroupId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }

    fun parseGroupAdmins(event: NostrEvent): List<String> {
        if (event.kind != KIND_GROUP_ADMINS) return emptyList()
        return event.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
    }

    fun parseGroupMembers(event: NostrEvent): List<String> {
        if (event.kind != KIND_GROUP_MEMBERS) return emptyList()
        return event.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
    }

    fun buildRemoveUser(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String,
        targetPubkey: String
    ): NostrEvent = NostrEvent.create(
        privkey = privkey,
        pubkey = pubkey,
        kind = 9001,
        content = "",
        tags = listOf(listOf("h", groupId), listOf("p", targetPubkey))
    )

    /** Returns the canonical invite link string, e.g. "wss://groups.0xchat.com'abc123" */
    fun inviteLink(relayUrl: String, groupId: String): String = "${relayUrl.lowercase()}'$groupId"

    val DEFAULT_GROUP_RELAYS = listOf("wss://groups.0xchat.com")
}
