package com.wisp.app.nostr

object Nip29 {
    const val KIND_CHAT_MESSAGE = 9
    const val KIND_PUT_USER = 9000
    const val KIND_EDIT_METADATA = 9002
    const val KIND_CREATE_GROUP = 9007
    const val KIND_DELETE_GROUP = 9008
    const val KIND_CREATE_INVITE = 9009
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
        val isClosed: Boolean,
        val isRestricted: Boolean = false,
        val isHidden: Boolean = false
    )

    /** One admin's pubkey and the role strings attached to it on a kind 39001 event. */
    data class AdminEntry(val pubkey: String, val roles: List<String>)

    /**
     * Parse a group identifier string like "groups.nostr.com'mygroup" into
     * (relayUrl, groupId). Returns null if the format is invalid.
     */
    fun parseGroupIdentifier(identifier: String): Pair<String, String>? {
        val withoutQuery = identifier.substringBefore('?')
        val idx = withoutQuery.indexOf('\'')
        if (idx < 0) return null
        // Normalize to lowercase — relay URLs are case-insensitive but used as map keys
        val host = withoutQuery.substring(0, idx).trimStart('/').lowercase()
        if (host.isEmpty()) return null  // bare apostrophe or leading slash only
        val groupId = withoutQuery.substring(idx + 1).ifEmpty { "_" }
        val relayUrl = (if (host.startsWith("wss://") || host.startsWith("ws://")) host
                        else "wss://$host").trimEnd('/')
        return Pair(relayUrl, groupId)
    }

    /**
     * Parse an invite link `<relay>'<groupId>[?code=<code>]`, returning relay, groupId, and
     * an optional invite code. Returns null if the basic identifier is invalid.
     */
    fun parseInviteLink(input: String): Triple<String, String, String?>? {
        val (relay, groupId) = parseGroupIdentifier(input) ?: return null
        val qIdx = input.indexOf('?')
        if (qIdx < 0) return Triple(relay, groupId, null)
        val code = input.substring(qIdx + 1).split('&')
            .firstOrNull { it.startsWith("code=") }
            ?.removePrefix("code=")
            ?.takeIf { it.isNotEmpty() }
        return Triple(relay, groupId, code)
    }

    fun parseGroupMetadata(event: NostrEvent): GroupMetadata? {
        if (event.kind != KIND_GROUP_METADATA) return null
        val groupId = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        val name = event.tags.firstOrNull { it.size >= 2 && it[0] == "name" }?.get(1)
        val picture = event.tags.firstOrNull { it.size >= 2 && it[0] == "picture" }?.get(1)
        val about = event.tags.firstOrNull { it.size >= 2 && it[0] == "about" }?.get(1)
        val isPrivate = event.tags.any { it.isNotEmpty() && it[0] == "private" }
        val isClosed = event.tags.any { it.isNotEmpty() && it[0] == "closed" }
        val isRestricted = event.tags.any { it.isNotEmpty() && it[0] == "restricted" }
        val isHidden = event.tags.any { it.isNotEmpty() && it[0] == "hidden" }
        return GroupMetadata(groupId, name, picture, about, isPrivate, isClosed, isRestricted, isHidden)
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
        groupId: String,
        inviteCode: String? = null
    ): NostrEvent {
        val tags = mutableListOf(listOf("h", groupId))
        inviteCode?.takeIf { it.isNotEmpty() }?.let { tags.add(listOf("code", it)) }
        return NostrEvent.create(
            privkey = privkey,
            pubkey = pubkey,
            kind = KIND_JOIN_REQUEST,
            content = "",
            tags = tags
        )
    }

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

    /**
     * Build a kind 9002 edit-metadata event. For each flag that is non-null, emits either the
     * positive tag (e.g. `["private"]`) when true or the inverse tag (e.g. `["public"]`) when
     * false — this way a single event can deterministically set every flag regardless of the
     * relay's default posture.
     */
    fun buildEditMetadata(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String,
        name: String? = null,
        about: String? = null,
        picture: String? = null,
        isPrivate: Boolean? = null,
        isClosed: Boolean? = null,
        isRestricted: Boolean? = null,
        isHidden: Boolean? = null
    ): NostrEvent {
        val tags = mutableListOf(listOf("h", groupId))
        name?.takeIf { it.isNotBlank() }?.let { tags.add(listOf("name", it.trim())) }
        about?.takeIf { it.isNotBlank() }?.let { tags.add(listOf("about", it.trim())) }
        picture?.takeIf { it.isNotBlank() }?.let { tags.add(listOf("picture", it.trim())) }
        isPrivate?.let { tags.add(listOf(if (it) "private" else "public")) }
        isClosed?.let { tags.add(listOf(if (it) "closed" else "open")) }
        isRestricted?.let { tags.add(listOf(if (it) "restricted" else "unrestricted")) }
        isHidden?.let { tags.add(listOf(if (it) "hidden" else "visible")) }
        return NostrEvent.create(
            privkey = privkey,
            pubkey = pubkey,
            kind = KIND_EDIT_METADATA,
            content = "",
            tags = tags
        )
    }

    /** Build a kind 9000 event that assigns the given roles to `targetPubkey` in the group. */
    fun buildPutUser(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String,
        targetPubkey: String,
        roles: List<String>
    ): NostrEvent {
        val pTag = mutableListOf("p", targetPubkey).apply { addAll(roles.filter { it.isNotBlank() }) }
        return NostrEvent.create(
            privkey = privkey,
            pubkey = pubkey,
            kind = KIND_PUT_USER,
            content = "",
            tags = listOf(listOf("h", groupId), pTag)
        )
    }

    /** Build a kind 9009 create-invite event with the given code. */
    fun buildCreateInvite(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String,
        code: String
    ): NostrEvent = NostrEvent.create(
        privkey = privkey,
        pubkey = pubkey,
        kind = KIND_CREATE_INVITE,
        content = "",
        tags = listOf(listOf("h", groupId), listOf("code", code))
    )

    /** Generates a random NIP-29 compliant group ID (lowercase alphanumeric, 12 chars). */
    fun generateGroupId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }

    /** Generates a random 16-char alphanumeric invite code. */
    fun generateInviteCode(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }

    /** Returns admin entries (pubkey + role strings) from a kind 39001 event. */
    fun parseGroupAdmins(event: NostrEvent): List<AdminEntry> {
        if (event.kind != KIND_GROUP_ADMINS) return emptyList()
        return event.tags
            .filter { it.size >= 2 && it[0] == "p" }
            .map { AdminEntry(pubkey = it[1], roles = it.drop(2)) }
    }

    /** Convenience shim: just the pubkeys from kind 39001. */
    fun parseGroupAdminPubkeys(event: NostrEvent): List<String> =
        parseGroupAdmins(event).map { it.pubkey }

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

    /** Invite link with a one-time code, e.g. "wss://groups.0xchat.com'abc123?code=xyz". */
    fun inviteLink(relayUrl: String, groupId: String, code: String): String =
        "${inviteLink(relayUrl, groupId)}?code=$code"

    val DEFAULT_GROUP_RELAYS = listOf("wss://chat.wisp.talk")
}
