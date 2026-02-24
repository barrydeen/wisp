package com.wisp.app.nostr

import com.wisp.app.relay.RelayConfig

object Nip65 {
    fun parseRelayList(event: NostrEvent): List<RelayConfig> {
        if (event.kind != 10002) return emptyList()
        return event.tags.mapNotNull { tag ->
            if (tag.size < 2 || tag[0] != "r") return@mapNotNull null
            val url = tag[1].trim().trimEnd('/')
            val marker = tag.getOrNull(2)
            RelayConfig(
                url = url,
                read = marker == null || marker == "read",
                write = marker == null || marker == "write"
            )
        }
    }

    fun buildRelayTags(relays: List<RelayConfig>): List<List<String>> {
        return relays.map { relay ->
            when {
                relay.read && relay.write -> listOf("r", relay.url)
                relay.read -> listOf("r", relay.url, "read")
                relay.write -> listOf("r", relay.url, "write")
                else -> listOf("r", relay.url)
            }
        }
    }
}
