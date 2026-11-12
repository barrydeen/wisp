package com.wisp.app.viewmodel

import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.NostrUriData

/**
 * Something a search query points at directly, rather than text to send to a
 * NIP-50 relay: a pasted NIP-19 entity, a hex id, or a NIP-05 address.
 */
sealed class DirectTarget {
    data class Entity(val data: NostrUriData) : DirectTarget()
    /** 64-char hex: could be an event id or a pubkey, so both are looked up. */
    data class Hex(val hex: String) : DirectTarget()
    data class Nip05Address(val identifier: String) : DirectTarget()
}

object SearchQueryParser {
    private val BECH32_ENTITY = Regex(
        "(npub1|nprofile1|note1|nevent1|naddr1)[02-9ac-hj-np-z]+",
        RegexOption.IGNORE_CASE
    )
    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
    private val NIP05 = Regex("^[A-Za-z0-9._+-]*@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")

    /**
     * Returns the target a query refers to, or null for plain text search.
     * Accepts bare entities, `nostr:` URIs, and web links that embed an entity
     * (njump.me, primal.net/e/..., etc).
     */
    fun parse(query: String): DirectTarget? {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.contains(' ')) return null

        BECH32_ENTITY.find(trimmed)?.let { match ->
            val decoded = Nip19.decodeNostrUri("nostr:${match.value.lowercase()}")
            if (decoded != null) return DirectTarget.Entity(decoded)
        }
        val bare = trimmed.removePrefix("nostr:")
        if (HEX_64.matches(bare)) return DirectTarget.Hex(bare.lowercase())
        if (NIP05.matches(bare)) return DirectTarget.Nip05Address(bare.lowercase())
        return null
    }
}
