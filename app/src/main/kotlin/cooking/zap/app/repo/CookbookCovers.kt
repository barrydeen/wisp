package cooking.zap.app.repo

import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.repo.RecipeBookmarkRepository.CookbookList

/**
 * Cover-image resolution for cookbook collections (A14 PR 3b). Mirrors the web's
 * `getCookbookCoverImage` fallback chain (src/lib/stores/cookbookStore.ts):
 *
 *   cover coord's recipe image → first recipe's image → legacy `image` tag.
 *
 * Resolution reuses the existing recipe lookups ([RecipeRepository.findRecipeEventByCoordinate]
 * cache-first, then [RecipeRepository.requestRecipeEventByCoordinate] network) — no
 * new resolver. A null result simply means the card falls back to its placeholder.
 */
object CookbookCovers {
    /**
     * Resolve [list]'s cover URL, or null when nothing resolves (card shows a
     * placeholder). Suspends while a cover recipe is fetched from relays on a cache miss.
     */
    suspend fun resolve(list: CookbookList, recipeRepo: RecipeRepository): String? {
        // Ordered candidates: the explicit `cover` coordinate first, then the
        // first saved recipe — de-duped so we don't fetch the same coord twice.
        val candidates = LinkedHashSet<String>()
        list.coverCoord?.trim()?.takeIf { it.isNotBlank() }?.let(candidates::add)
        list.coordinates.firstOrNull()?.let(candidates::add)
        for (raw in candidates) {
            val coord = parseCoordinate(raw) ?: continue
            val event = recipeRepo.findRecipeEventByCoordinate(coord.kind, coord.author, coord.dTag)
                ?: recipeRepo.requestRecipeEventByCoordinate(coord.kind, coord.author, coord.dTag)
            val image = event?.let { RecipeFormats.forEvent(it)?.parse(it)?.image }
            if (!image.isNullOrBlank()) return image
        }
        // Legacy `image` tag fallback (a direct URL set by the web).
        return list.image?.trim()?.takeIf { it.isNotBlank() }
    }

    /** Parse an `a`-coordinate (`kind:pubkey:dTag`) into a [PackRecipeCoordinate], or null if malformed. */
    fun parseCoordinate(raw: String): PackRecipeCoordinate? {
        val parts = raw.split(":", limit = 3)
        if (parts.size != 3) return null
        val kind = parts[0].toIntOrNull() ?: return null
        val author = parts[1].trim()
        val dTag = parts[2].trim()
        if (author.isBlank() || dTag.isBlank()) return null
        return PackRecipeCoordinate(kind = kind, author = author, dTag = dTag)
    }
}
