package com.wisp.app.ui.component

/**
 * NIP-92 `imeta` parsing, kept in its own Android-free file so the JVM unit
 * suite can lock the wire format down.
 *
 * One `imeta` tag per media URL; slots are `key value` strings, e.g.
 * `["imeta", "url https://…", "m image/jpeg", "alt TV test pattern"]`.
 * Alt text is looked up by exact URL string — no normalization, matching
 * Amethyst/Quartz/Gossip.
 */
data class MediaMeta(
    val url: String,
    val mime: String? = null,
    val dimension: String? = null,
    val thumbhash: String? = null,
    val blurhash: String? = null,
    val image: String? = null,
    /** NIP-92 `alt` slot — the image's description for screen readers. */
    val alt: String? = null
)

/**
 * Parse NIP-92 imeta tags from a list of tags to build a URL→metadata map.
 * Kind-agnostic: applies to any event carrying imeta tags (kind 1 notes,
 * 1111 comments, 30023 articles, gallery kinds 20/21/22, …).
 * Tag format: ["imeta", "url https://...", "m image/png", "dim 1024x768", "thumbhash ...", "blurhash ...", "image https://...", "alt ..."]
 */
fun parseImetaTags(tags: List<List<String>>): Map<String, MediaMeta> {
    val map = mutableMapOf<String, MediaMeta>()
    for (tag in tags) {
        if (tag.firstOrNull() != "imeta" || tag.size < 2) continue
        var url: String? = null
        var mime: String? = null
        var dim: String? = null
        var thumb: String? = null
        var blur: String? = null
        var image: String? = null
        var alt: String? = null
        for (i in 1 until tag.size) {
            val entry = tag[i]
            when {
                entry.startsWith("url ") -> url = entry.removePrefix("url ")
                entry.startsWith("m ") -> mime = entry.removePrefix("m ")
                entry.startsWith("dim ") -> dim = entry.removePrefix("dim ")
                entry.startsWith("thumbhash ") -> thumb = entry.removePrefix("thumbhash ")
                entry.startsWith("blurhash ") -> blur = entry.removePrefix("blurhash ")
                entry.startsWith("image ") -> image = entry.removePrefix("image ")
                entry.startsWith("alt ") -> alt = entry.removePrefix("alt ")
            }
        }
        if (url != null) {
            // Alt is trimmed on read and a whitespace-only value counts as
            // absent — an image is either described or it isn't.
            map[url] = MediaMeta(
                url = url,
                mime = mime,
                dimension = dim,
                thumbhash = thumb,
                blurhash = blur,
                image = image,
                alt = alt?.trim()?.takeIf { it.isNotEmpty() }
            )
        }
    }
    return map
}

/** Authoring cap shared by the composer's alt editor. */
const val ALT_TEXT_MAX_CHARS = 2000


/**
 * Sanitize alt text for emission/storage: trim, cap at [ALT_TEXT_MAX_CHARS],
 * and collapse to null when empty — an undescribed image carries no `alt`
 * slot at all, and no imeta tag.
 */
fun sanitizeAltText(raw: String): String? =
    raw.trim().take(ALT_TEXT_MAX_CHARS).takeIf { it.isNotEmpty() }
