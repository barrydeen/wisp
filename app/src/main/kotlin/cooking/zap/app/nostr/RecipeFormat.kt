package cooking.zap.app.nostr

/**
 * One on-wire recipe encoding. **NIP-23 (`kind 30023`) is the only active
 * format today** ([Nip23RecipeFormat]); a future recipe NIP plugs in as a
 * second [RecipeFormat] implementation with no rewrite of the screens, feeds,
 * compose form, or the [RecipeParser.Recipe] domain model.
 *
 * The seam sits **between the repos and the format logic**: `RecipeRepository`
 * and `RecipePublisher` go through [RecipeFormats] rather than reaching into
 * [RecipeParser]/[RecipeSerializer] directly. Those two objects are retained
 * unchanged as the byte-exact NIP-23 implementation (so the round-trip parity
 * tests stay green) — [Nip23RecipeFormat] is a thin adapter over them.
 *
 * Everything above the seam consumes [RecipeParser.Recipe] and is format-blind.
 */
interface RecipeFormat {
    /** The event kind this format reads/writes. */
    val kind: Int

    /**
     * Canonical-pick precedence when the same logical recipe
     * ([RecipeKey]) exists under more than one format — higher wins. The
     * migration-target NIP ranks above the format it supersedes, so it
     * auto-wins once both are active. NIP-23 is the baseline (0).
     */
    val formatRank: Int

    /** True iff [event] is a recipe in this format (kind + any tag qualifier). */
    fun matches(event: NostrEvent): Boolean

    /** Decode an event into the shared domain model. Caller pre-checks [matches]. */
    fun parse(event: NostrEvent): RecipeParser.Recipe

    /**
     * Encode a recipe into an unsigned event (kind + content + tags) ready for
     * signing. [title] is passed alongside [recipe] to preserve the current
     * write surface byte-for-byte (folding it into the model is a later
     * cleanup, not this seam).
     */
    fun serialize(
        recipe: RecipeParser.Recipe,
        title: String,
        imageUrls: List<String>,
        categories: List<String>,
    ): UnsignedRecipeEvent

    /** The addressable identifier (the `d`/slug value) for [title]. */
    fun slug(title: String): String

    /**
     * Relay filter for the recipe feed in this format. [until] pages backwards
     * in time (NIP-01 `until`); null fetches the newest window.
     */
    fun feedFilter(limit: Int, until: Long? = null): Filter

    /** Relay filter to resolve a single recipe by its coordinate (author + d/slug). */
    fun coordinateFilter(author: String, dTag: String): Filter
}

/** A recipe event before signing — the publisher signs this verbatim. */
data class UnsignedRecipeEvent(
    val kind: Int,
    val content: String,
    val tags: List<List<String>>,
)

/**
 * Format-agnostic identity of a logical recipe: **author + slug** (the `d`-tag
 * value), deliberately **without the kind**. This is the join key that lets a
 * recipe authored in two formats collapse to one feed entry. Both formats put
 * the slug in the `d` tag (the addressable identifier convention), so the keys
 * align across formats.
 */
data class RecipeKey(val author: String, val slug: String)

/** Extract the [RecipeKey] from an event (author + its `d` tag value). */
fun recipeKey(event: NostrEvent): RecipeKey {
    val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
    return RecipeKey(event.pubkey, d)
}

/**
 * Collapse events that are the **same logical recipe across formats**, keyed by
 * [RecipeKey] (author + slug, kind-independent). The winner per key is chosen
 * by `(formatRank desc, created_at desc, id asc)` — the migration-target format
 * wins when a recipe exists in both, else newest-wins, with the lower id as a
 * deterministic final tiebreak.
 *
 * [rankOf] resolves an event's format rank ([RecipeFormats.rankOf]); events
 * whose format isn't active resolve to null and are dropped.
 *
 * **Pass-through while one format is active:** every key maps to a single
 * format, so each survives unchanged and the feed is byte-for-byte what it is
 * today. The cross-format pick only ever fires once a second format registers.
 *
 * NOTE (dual-write era): rank-before-recency means a stale higher-rank event
 * could mask a newer edit of the lower-rank one. When dual-write is turned on,
 * either keep both events in lockstep or put a recency check ahead of rank.
 */
fun dedupeAcrossFormats(
    events: Iterable<NostrEvent>,
    rankOf: (NostrEvent) -> Int?,
): List<NostrEvent> {
    val byKey = LinkedHashMap<RecipeKey, NostrEvent>()
    for (event in events) {
        val rank = rankOf(event) ?: continue
        val key = recipeKey(event)
        val current = byKey[key]
        if (current == null || isMoreCanonical(event, rank, current, rankOf(current) ?: Int.MIN_VALUE)) {
            byKey[key] = event
        }
    }
    return byKey.values.toList()
}

/** The `(rank desc, created_at desc, id asc)` canonical-pick comparison. */
private fun isMoreCanonical(a: NostrEvent, aRank: Int, b: NostrEvent, bRank: Int): Boolean = when {
    aRank != bRank -> aRank > bRank
    a.created_at != b.created_at -> a.created_at > b.created_at
    else -> a.id < b.id
}
