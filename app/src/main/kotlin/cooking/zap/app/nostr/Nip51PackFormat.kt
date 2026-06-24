package cooking.zap.app.nostr

/**
 * Recipe packs as NIP-51 curation sets (kind 30004).
 */
object Nip51PackFormat : PackFormat {
    const val RECIPE_PACK_KIND = 30004
    const val RECIPE_PACK_TAG = "recipe-pack"
    const val ZAP_COOKING_TAG = "zap-cooking"

    override val kind: Int = RECIPE_PACK_KIND
    override val formatRank: Int = 0

    override fun matches(event: NostrEvent): Boolean = event.kind == RECIPE_PACK_KIND

    override fun packDiscoverFilter(limit: Int, until: Long?): Filter = Filter(
        kinds = listOf(RECIPE_PACK_KIND),
        tTags = listOf(ZAP_COOKING_TAG, RECIPE_PACK_TAG),
        limit = limit,
        until = until,
    )

    override fun packMineFilter(author: String, limit: Int, until: Long?): Filter = Filter(
        kinds = listOf(RECIPE_PACK_KIND),
        authors = listOf(author),
        limit = limit,
        until = until,
    )

    override fun packByCoordinateFilter(pubkey: String, dTag: String): Filter = Filter(
        kinds = listOf(RECIPE_PACK_KIND),
        authors = listOf(pubkey),
        dTags = listOf(dTag),
        limit = 1,
    )
}

