package cooking.zap.app.relay

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
    val write: Boolean = true,
    val auth: Boolean = true
) {
    companion object {
        // General relay set — mirrors the zap.cooking web `default` set
        // (ZAPCOOKING_ANDROID_BUILD.md §1). Applied only to fresh installs
        // with no saved NIP-65 list. Discovery/indexer and recipe-article
        // aggregators live in DEFAULT_INDEXER_RELAYS / RelayProber.BOOTSTRAP
        // and are intentionally left in place.
        val DEFAULTS = listOf(
            RelayConfig("wss://nos.lol", read = true, write = true),
            RelayConfig("wss://relay.damus.io", read = true, write = true),
            RelayConfig("wss://relay.primal.net", read = true, write = true)
        )

        /**
         * The Pantry — zap.cooking's members-only relay. Used to read/write
         * member-gated content; NOT added to a non-member's general relay set
         * (membership gating happens in Phase 3). See §1 "Relays".
         */
        const val MEMBERS_RELAY = "wss://pantry.zap.cooking"

        /** Default DM relays applied when a user has no DM relay set (kind 10050). */
        val DEFAULT_DM_RELAYS = listOf(
            "wss://auth.nostr1.com"
        )

        /**
         * The `articles` relay set — recipe (kind 30023) READS target these,
         * NOT [DEFAULTS]. Mirrors the zap.cooking web `articles` set (build
         * doc §1). Recipes live on these public aggregators, not on the
         * members-only Pantry. Treat as a *union*: coverage is uneven (a
         * Step-0 live probe found `nostr.wine` returned 0 for `#t zapcooking`
         * while primal/nos.lol/damus carried the same recipes), so query the
         * whole set and merge.
         */
        val ARTICLES_RELAYS = listOf(
            "wss://relay.primal.net",
            "wss://nos.lol",
            "wss://relay.damus.io",
            "wss://nostr.wine",
            "wss://eden.nostr.land",
            "wss://relay.noswhere.com"
        )

        /** Fallback indexer relays used when the user hasn't configured search relays (kind 10007). */
        val DEFAULT_INDEXER_RELAYS = listOf(
            "wss://indexer.coracle.social",
            "wss://relay.nos.social",
            "wss://nos.lol",
            "wss://indexer.nostrarchives.com",
            "wss://relay.damus.io",
            "wss://relay.primal.net"
        )

        private val IP_HOST_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

        /**
         * Structural URL validation — can this URL be stored in a relay list?
         * Rejects: non-wss schemes, localhost, IP addresses, URLs with ports.
         */
        fun isValidUrl(url: String): Boolean {
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
