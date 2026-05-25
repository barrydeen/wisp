package com.wisp.app.ui.util

/**
 * Helpers for surfacing image attachments inside zap-receipt messages.
 *
 * Zap receipts (kind-9735) carry a free-text message in the request's
 * description field. People often paste an image URL there so the zap
 * comes with an inline image (a tip + a meme). The post-card top-zap
 * banner has a single line of room so it shows `[image]` in place of the
 * URL, while the engagement drawer renders the image inline below the
 * row.
 *
 * iOS counterpart: see `wisp-ios/Sources/.../ZapMessage.swift`.
 */
object ZapMessageImage {

    /**
     * Common image file extensions we treat as inline-renderable. Matches
     * the set used elsewhere in the app (RichContent's `imageExtensions`)
     * so behavior is consistent.
     */
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg")

    /**
     * Bare URL matcher — http/https only, captures the URL up to the
     * first whitespace. Path-segment extension is checked separately.
     */
    private val urlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /**
     * Returns the first URL inside [message] that looks like an inline
     * image — i.e. the URL's path ends with a recognised image
     * extension (after stripping query/fragment). Returns null if none.
     */
    fun firstImageUrl(message: String): String? {
        if (message.isBlank()) return null
        for (match in urlRegex.findAll(message)) {
            val url = match.value.trimEnd('.', ',', ')', ']', '!', '?')
            val path = url.substringAfter("://").substringBefore('?').substringBefore('#')
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext in imageExtensions) return url
        }
        return null
    }

    /**
     * Replace the image URL inside [message] with the literal `[image]`
     * token so a single-line preview (e.g. the top-zap banner) reads
     * something like "nice post [image]" instead of dumping a long URL.
     * Falls through unchanged when no image URL is present.
     */
    fun previewText(message: String): String {
        val url = firstImageUrl(message) ?: return message
        return message.replace(url, "[image]").trim()
    }
}
