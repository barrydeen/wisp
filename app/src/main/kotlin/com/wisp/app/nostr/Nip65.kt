package com.wisp.app.nostr

import android.util.Log
import com.wisp.app.relay.RelayConfig

object Nip65 {
    fun parseRelayList(event: NostrEvent): List<RelayConfig> {
        if (event.kind != 10002) return emptyList()
        val result = event.tags.mapNotNull { tag ->
            if (tag.size < 2 || tag[0] != "r") return@mapNotNull null
            val url = tag[1].trim().trimEnd('/')
            if (!RelayConfig.isValidUrl(url)) return@mapNotNull null
            val marker = tag.getOrNull(2)
            RelayConfig(
                url = url,
                read = marker == null || marker == "read",
                write = marker == null || marker == "write"
            )
        }
        val deduped = result.groupBy { it.url }.map { (url, configs) ->
            RelayConfig(
                url = url,
                read = configs.any { it.read },
                write = configs.any { it.write }
            )
        }
        val onionRelays = deduped.filter { RelayConfig.isOnionUrl(it.url) }
        if (onionRelays.isNotEmpty()) {
            Log.d("TorRelay", "[Nip65] parseRelayList: pubkey=${event.pubkey.take(8)}… has ${onionRelays.size} .onion relay(s): ${onionRelays.map { it.url }}")
        }
        return deduped
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
