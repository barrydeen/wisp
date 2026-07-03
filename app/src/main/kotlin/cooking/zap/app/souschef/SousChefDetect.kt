package cooking.zap.app.souschef

enum class SousChefMode {
    IMAGE,
    URL,
    TEXT,
}

private val URL_TOKEN = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

/**
 * Auto-detection for the unified Sous Chef input.
 *
 * Verbatim port of the web `$lib/souschefDetect.ts` (`detectMode`). That file
 * is the source of truth — any behavioral change must land in both repos with
 * mirrored tests (`src/lib/souschefDetect.test.ts` ↔ [SousChefDetectTest]).
 *
 * Rules (precedence order):
 * 1. [hasImage] → [SousChefMode.IMAGE] unconditionally.
 * 2. Trimmed input empty → null (submit disabled).
 * 3. Entire trimmed input is one URL token → [SousChefMode.URL].
 * 4. Trimmed length ≥ 30 → [SousChefMode.TEXT].
 * 5. Otherwise null.
 */
fun detectMode(text: String, hasImage: Boolean): SousChefMode? {
    if (hasImage) return SousChefMode.IMAGE
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    if (URL_TOKEN.matches(trimmed)) return SousChefMode.URL
    if (trimmed.length >= 30) return SousChefMode.TEXT
    return null
}
