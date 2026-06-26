package cooking.zap.app.nostr

/**
 * Builds the web-interop recipe share payload (src/lib/utils/share.ts).
 *
 * Canonical URL: `https://zap.cooking/r/{naddr}` — the short `/r/` route, not
 * `/recipe/`. Rich text: `{title}\nShared on Zap Cooking\n{url}`.
 */
object RecipeShare {
    private const val SITE_ORIGIN = "https://zap.cooking"

    /** Recipe title for share — `title` tag, then `d`, then `"Recipe"`. */
    fun titleFor(event: NostrEvent): String {
        fun tagValue(name: String): String? = event.tags
            .firstOrNull { it.size >= 2 && it[0] == name }
            ?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        return tagValue("title") ?: tagValue("d") ?: "Recipe"
    }

    /**
     * Canonical zap.cooking share URL, or null when [event] has no `d` tag or
     * isn't a recognized recipe format (falls back to kind 30023 when format-
     * agnostic coordinate building still applies).
     */
    fun shareUrl(event: NostrEvent): String? {
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)?.trim()
        if (dTag.isNullOrBlank()) return null
        val kind = RecipeFormats.forEvent(event)?.kind ?: 30023
        val naddr = Nip19.naddrEncode(kind = kind, pubkeyHex = event.pubkey, dTag = dTag)
        return "$SITE_ORIGIN/r/$naddr"
    }

    /** Full `ACTION_SEND` body — title + source line + URL. */
    fun shareText(event: NostrEvent): String? {
        val url = shareUrl(event) ?: return null
        return "${titleFor(event)}\nShared on Zap Cooking\n$url"
    }
}
