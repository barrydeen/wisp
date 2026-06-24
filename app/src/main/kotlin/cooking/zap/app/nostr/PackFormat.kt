package cooking.zap.app.nostr

/**
 * One on-wire recipe-pack encoding. PR A ships NIP-51 kind 30004 via
 * [Nip51PackFormat]; future pack formats plug in here without touching UI.
 */
interface PackFormat {
    /** Event kind for this pack format. */
    val kind: Int

    /** Canonical precedence when multiple formats map to the same pack key. */
    val formatRank: Int

    /** True when [event] belongs to this format. */
    fun matches(event: NostrEvent): Boolean

    /** Discover feed filter (public pack browse). */
    fun packDiscoverFilter(limit: Int, until: Long? = null): Filter

    /** "Mine" filter for one author. */
    fun packMineFilter(author: String, limit: Int, until: Long? = null): Filter

    /** Addressable pack lookup by author + d-tag. */
    fun packByCoordinateFilter(pubkey: String, dTag: String): Filter
}

/** Format-agnostic identity for an addressable pack. */
data class PackKey(val author: String, val dTag: String)

fun packKey(event: NostrEvent): PackKey {
    val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
    return PackKey(author = event.pubkey, dTag = d)
}

