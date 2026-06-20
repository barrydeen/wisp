package cooking.zap.app.nostr

/**
 * NIP-89: Recommended application handlers.
 *
 * This app only emits the lightweight half — the `["client", <name>]` tag
 * stamped on published events to identify the authoring client (a display-name
 * string; there is no kind-31990 handler advertisement). Every client-tag site
 * routes through [CLIENT_NAME] so the value is consistent and one-line to change.
 */
object Nip89 {
    /** The `client` tag value (NIP-89 display name) for events this app signs. */
    const val CLIENT_NAME = "Zap Cooking Android"

    /** The `["client", "Zap Cooking Android"]` tag. */
    fun clientTag(): List<String> = listOf("client", CLIENT_NAME)
}
