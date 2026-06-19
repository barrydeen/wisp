package cooking.zap.app.nostr

import kotlin.math.abs
import kotlin.math.floor

/**
 * Best-effort serving scaler for recipe ingredient lines.
 *
 * The frontend has no ingredient scaler to mirror, and recipe quantities are
 * free-text authored by humans, so this is deliberately conservative:
 *
 *  - It scales **only the leading quantity token** of a line. Secondary
 *    alt-measures (`"60 mL water ¼ cup"`), oven temps (`"350°F"`), and prose
 *    numbers (`"speed 2 or 3"`) are left untouched — scaling every number
 *    would corrupt them. The leading qty is the one the cook actually adjusts.
 *  - It understands integers, decimals, unicode vulgar fractions (`½ ⅓ ¾`…),
 *    mixed numbers (`1½`, `1 ½`, `1 1/2`), simple fractions (`1/2`), and
 *    ranges (`2-3`).
 *  - On ANYTHING it can't parse, it returns the line **verbatim** — never
 *    throws, never drops the ingredient. A recipe that renders but doesn't
 *    scale a line is fine; a crash is not.
 *
 * Pure (no Android, no I/O) — unit-tested against the real Tuscan Peposo and
 * Japanese Milk Bread ingredient lines plus fraction/mixed/range/no-number
 * edges.
 */
object IngredientScaler {

    private const val EPS = 1e-6

    /** Unicode vulgar fractions → value. */
    private val UNICODE_FRACTIONS: Map<Char, Double> = mapOf(
        '¼' to 0.25, '½' to 0.5, '¾' to 0.75,
        '⅓' to 1.0 / 3, '⅔' to 2.0 / 3,
        '⅕' to 0.2, '⅖' to 0.4, '⅗' to 0.6, '⅘' to 0.8,
        '⅙' to 1.0 / 6, '⅚' to 5.0 / 6,
        '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875,
        '⅐' to 1.0 / 7, '⅑' to 1.0 / 9, '⅒' to 0.1,
    )

    /** (numerator, denominator) → unicode glyph, for reformatting scaled values. */
    private val FRACTION_GLYPHS: Map<Pair<Int, Int>, Char> = mapOf(
        (1 to 2) to '½',
        (1 to 3) to '⅓', (2 to 3) to '⅔',
        (1 to 4) to '¼', (3 to 4) to '¾',
        (1 to 8) to '⅛', (3 to 8) to '⅜', (5 to 8) to '⅝', (7 to 8) to '⅞',
    )

    /**
     * Scale the leading quantity of [line] by [multiplier]. Returns [line]
     * unchanged when [multiplier] is 1.0 or the line has no leading quantity.
     */
    fun scaleLine(line: String, multiplier: Double): String {
        if (abs(multiplier - 1.0) < EPS) return line
        val lead = line.takeWhile { it == ' ' || it == '\t' }
        val body = line.substring(lead.length)

        val first = parseQuantity(body) ?: return line
        val afterFirst = body.substring(first.consumed)

        // Range: "2-3", "2 – 3" → scale both ends, keep the separator verbatim.
        val sep = RANGE_SEP.find(afterFirst)
        if (sep != null) {
            val second = parseQuantity(afterFirst.substring(sep.value.length))
            if (second != null) {
                val a = formatQuantity(first.value * multiplier)
                val b = formatQuantity(second.value * multiplier)
                val tail = afterFirst.substring(sep.value.length + second.consumed)
                return lead + a + sep.value + b + tail
            }
        }
        return lead + formatQuantity(first.value * multiplier) + afterFirst
    }

    private val RANGE_SEP = Regex("^\\s*[-–—]\\s*")

    private data class Quantity(val value: Double, val consumed: Int)

    /** Parse a quantity at the start of [s], or null if there isn't one. */
    private fun parseQuantity(s: String): Quantity? {
        var i = 0
        while (i < s.length && s[i].isDigit()) i++
        val hasInt = i > 0
        val intText = s.substring(0, i)

        // "1/2" — the leading integer is a fraction numerator (no space).
        if (hasInt && i < s.length && s[i] == '/') {
            val frac = matchAsciiFraction(s, 0)
            if (frac != null) return Quantity(frac.value, frac.consumed)
        }

        // Decimal: "1.5", "0.25", ".5".
        if (i < s.length && s[i] == '.') {
            var j = i + 1
            val fracStart = j
            while (j < s.length && s[j].isDigit()) j++
            if (j > fracStart) {
                val text = (if (hasInt) intText else "0") + "." + s.substring(fracStart, j)
                return Quantity(text.toDouble(), j)
            }
        }

        if (hasInt) {
            val whole = intText.toDouble()
            // Mixed with whitespace: "1 ½" or "1 1/2".
            var j = i
            while (j < s.length && s[j] == ' ') j++
            if (j > i) {
                if (j < s.length && s[j] in UNICODE_FRACTIONS) {
                    return Quantity(whole + UNICODE_FRACTIONS.getValue(s[j]), j + 1)
                }
                val frac = matchAsciiFraction(s, j)
                if (frac != null) return Quantity(whole + frac.value, frac.consumed)
            }
            // Adjacent mixed: "1½".
            if (i < s.length && s[i] in UNICODE_FRACTIONS) {
                return Quantity(whole + UNICODE_FRACTIONS.getValue(s[i]), i + 1)
            }
            return Quantity(whole, i)
        }

        // No integer: bare unicode fraction "½", or bare "a/b".
        if (i < s.length && s[i] in UNICODE_FRACTIONS) {
            return Quantity(UNICODE_FRACTIONS.getValue(s[i]), i + 1)
        }
        return matchAsciiFraction(s, 0)?.let { Quantity(it.value, it.consumed) }
    }

    private data class AsciiFraction(val value: Double, val consumed: Int)

    /** Match `\d+/\d+` starting at [start]. */
    private fun matchAsciiFraction(s: String, start: Int): AsciiFraction? {
        var i = start
        val numStart = i
        while (i < s.length && s[i].isDigit()) i++
        if (i == numStart) return null
        if (i >= s.length || s[i] != '/') return null
        i++
        val denStart = i
        while (i < s.length && s[i].isDigit()) i++
        if (i == denStart) return null
        val num = s.substring(numStart, denStart - 1).toDouble()
        val den = s.substring(denStart, i).toDouble()
        if (den == 0.0) return null
        return AsciiFraction(num / den, i)
    }

    /** Render a scaled value back to a clean integer / mixed-fraction / decimal string. */
    internal fun formatQuantity(value: Double): String {
        if (value < 0) return trimDecimal(value)
        val whole = floor(value + EPS).toInt()
        val frac = value - whole
        if (frac < EPS) return whole.toString()

        val glyphOrAscii = fractionString(frac)
        if (glyphOrAscii != null) {
            val (text, isUnicode) = glyphOrAscii
            return when {
                whole == 0 -> text
                isUnicode -> "$whole$text"   // "1½"
                else -> "$whole $text"        // "1 1/3"
            }
        }
        return trimDecimal(value)
    }

    /** A fraction in [0,1) as (renderedText, isUnicodeGlyph), or null if not a tidy fraction. */
    private fun fractionString(frac: Double): Pair<String, Boolean>? {
        for (den in intArrayOf(2, 3, 4, 8)) {
            val num = Math.round(frac * den).toInt()
            if (num <= 0 || num >= den) continue
            if (abs(frac - num.toDouble() / den) > EPS) continue
            val g = gcd(num, den)
            val rn = num / g
            val rd = den / g
            val glyph = FRACTION_GLYPHS[rn to rd]
            return if (glyph != null) glyph.toString() to true else "$rn/$rd" to false
        }
        return null
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun trimDecimal(value: Double): String {
        val s = String.format(java.util.Locale.US, "%.2f", value)
        return s.trimEnd('0').trimEnd('.')
    }
}
