package cooking.zap.app.ui.component

// YouTube URL parsing — pure string/regex logic, extracted from RichContent.kt so it can be
// unit-tested on the JVM without triggering that file's Android-dependent top-level initializers
// (e.g. its android.util.LruCache caches).

// Matches a YouTube watch/short/embed/live URL and captures the 11-char video id.
private val youTubeUrlRegex = Regex(
    """(?:https?://)?(?:www\.|m\.)?(?:youtube\.com/(?:watch\?(?:[^\s]*&)?v=|shorts/|embed/|live/|v/)|youtu\.be/)([A-Za-z0-9_-]{11})""",
    RegexOption.IGNORE_CASE
)
// Start-time param: t= or start=, either bare seconds (90) or "1h2m3s" form.
private val youTubeTimeRegex = Regex("""[?&](?:t|start)=([0-9]+[hms]?(?:[0-9]+[ms]?)*)""", RegexOption.IGNORE_CASE)
private val youTubeHmsRegex = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""", RegexOption.IGNORE_CASE)

internal data class YouTubeRef(val videoId: String, val startSeconds: Int)

private fun parseYouTubeStart(raw: String): Int {
    raw.toIntOrNull()?.let { return it } // bare seconds
    val m = youTubeHmsRegex.matchEntire(raw) ?: return 0
    val (h, mn, s) = m.destructured
    return (h.toIntOrNull() ?: 0) * 3600 + (mn.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
}

/** Detects a YouTube URL and extracts its video id + optional start offset. */
internal fun parseYouTube(url: String): YouTubeRef? {
    // Note content is sometimes HTML-escaped (&amp; for &), which breaks the query-string
    // separator for both the id regex (watch?...&v=) and the start-time regex. Normalize first.
    val normalized = url.replace("&amp;", "&")
    val id = youTubeUrlRegex.find(normalized)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
    val start = youTubeTimeRegex.find(normalized)?.groupValues?.getOrNull(1)?.let { parseYouTubeStart(it) } ?: 0
    return YouTubeRef(id, start)
}
