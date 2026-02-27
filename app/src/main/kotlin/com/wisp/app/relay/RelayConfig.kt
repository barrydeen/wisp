package com.wisp.app.relay

import kotlinx.serialization.Serializable

enum class RelaySetType(val displayName: String, val eventKind: Int) {
    GENERAL("General", 10002),
    DM("DM", 10050),
    SEARCH("Search", 10007),
    BLOCKED("Blocked", 10006)
}

@Serializable
data class RelayConfig(
    val url: String,
    val read: Boolean = true,
    val write: Boolean = true
) {
    companion object {
        val DEFAULTS = listOf(
            RelayConfig("wss://relay.damus.io", read = true, write = true),
            RelayConfig("wss://relay.primal.net", read = true, write = true),
            RelayConfig("wss://indexer.coracle.social", read = true, write = false),
            RelayConfig("wss://relay.nos.social", read = true, write = false)
        )

        /** Fallback indexer relays used when the user hasn't configured search relays (kind 10007). */
        val DEFAULT_INDEXER_RELAYS = listOf(
            "wss://indexer.coracle.social",
            "wss://relay.nos.social",
            "wss://relay.damus.io",
            "wss://relay.primal.net"
        )

        private val IP_HOST_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

        /**
         * Returns true if the relay URL is safe to use.
         * Rejects: localhost, IP addresses, URLs with ports (unless .onion with Tor active).
         * When Tor is enabled, .onion addresses are allowed with ws:// or wss:// and any port.
         */
        fun isAcceptableUrl(url: String): Boolean {
            val isOnion = url.contains(".onion")

            if (isOnion) {
                if (!TorManager.isEnabled()) return false
                // .onion relays can use ws:// (TLS redundant over Tor) or wss://
                if (!url.startsWith("wss://") && !url.startsWith("ws://")) return false
                return true
            }

            if (!url.startsWith("wss://")) return false
            val afterScheme = url.removePrefix("wss://")
            val hostPort = afterScheme.split("/", limit = 2)[0]
            if (":" in hostPort) return false // has a port
            val host = hostPort.lowercase()
            if (host == "localhost" || host.endsWith(".localhost")) return false
            if (IP_HOST_REGEX.matches(host)) return false
            return true
        }
    }
}
