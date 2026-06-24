package cooking.zap.app.nostr

/**
 * Registry for pack formats (parallel to [RecipeFormats]).
 */
object PackFormats {
    val active: List<PackFormat> = listOf(Nip51PackFormat)
    val primary: PackFormat = Nip51PackFormat

    fun forEvent(event: NostrEvent): PackFormat? = active.firstOrNull { it.matches(event) }
    fun rankOf(event: NostrEvent): Int? = forEvent(event)?.formatRank
}

