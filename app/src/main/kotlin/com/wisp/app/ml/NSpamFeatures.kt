package com.wisp.app.ml

import kotlin.math.sqrt

data class NoteInput(
    val content: String,
    val tags: List<List<String>>,
    val createdAt: Long
)

object NSpamFeatures {
    const val N_CHAR = 131072
    const val N_WORD = 131072
    const val N_STRUCTURAL = 17
    const val N_GROUP = 6
    const val TOTAL = N_CHAR + N_WORD + N_STRUCTURAL + N_GROUP

    private val WORD_PATTERN = Regex("[\\p{L}\\p{N}_]{2,}")
    private val WHITESPACE = Regex("""\s+""")
    private val URL_PATTERN = Regex("""https?://([^\s/]+)""", RegexOption.IGNORE_CASE)
    private val MENTION_PATTERN = Regex(
        """\b(?:nostr:)?(?:npub1|note1|nprofile1|nevent1|naddr1)[0-9a-z]+""",
        RegexOption.IGNORE_CASE
    )
    private val HASHTAG_PATTERN = Regex("""#\w+""")
    private val NONWS_TOKEN = Regex("""\S+""")

    private val EMOJI_PATTERN = Regex(
        "[\\p{IsEmoji_Presentation}\\p{IsExtended_Pictographic}]"
    )

    private val UNICODE_DIGIT = Regex("\\p{N}")
    private val UNICODE_PUNCT = Regex("\\p{P}")

    private val TOKENIZE_RE = Regex(
        "\\p{L}[\\p{L}\\p{M}\\p{N}_]*|\\p{N}+|https?://\\S+|[#@][\\w]+"
    )

    fun extractFeatures(notes: List<NoteInput>): FloatArray {
        val features = FloatArray(TOTAL)
        val n = notes.size
        if (n == 0) return features

        val preps = notes.map { NSpamPreprocessor.preprocess(it.content) }

        val charText = preps.joinToString(" ") { it.rawText }
        hashCharWbNgrams(charText, features)

        val wordText = preps.joinToString(" ") { it.text }
        hashWordNgrams(wordText, features)

        val structuralSums = FloatArray(N_STRUCTURAL)
        val charLengths = ArrayList<Float>(n)
        val bodyKeys = ArrayList<String>(n)
        val rawTexts = ArrayList<String>(n)

        for (i in notes.indices) {
            val raw = notes[i].content
            val tags = notes[i].tags
            rawTexts.add(raw)

            val bodyKey = raw.filter { it !in NSpamPreprocessor.INVISIBLE_CHARS }
                .trim().lowercase().take(200)
            bodyKeys.add(bodyKey)

            val struct = extractStructural(raw, tags)
            for (j in struct.indices) structuralSums[j] += struct[j]
            charLengths.add(raw.length.toFloat())
        }

        val structOffset = N_CHAR + N_WORD
        for (i in 0 until N_STRUCTURAL) {
            features[structOffset + i] = structuralSums[i] / n
        }

        val groupOffset = structOffset + N_STRUCTURAL
        features[groupOffset] = n.toFloat()
        features[groupOffset + 1] = if (n > 1) {
            (notes.maxOf { it.createdAt } - notes.minOf { it.createdAt }).toFloat() / 3600f
        } else {
            0f
        }
        val uniqueBodies = bodyKeys.filter { it.isNotEmpty() }.toSet()
        features[groupOffset + 2] = uniqueBodies.size.toFloat()

        if (n >= 2) {
            features[groupOffset + 3] = populationStdDev(charLengths)

            val tokenLists = rawTexts.map { TOKENIZE_RE.findAll(it.lowercase()).map { m -> m.value }.toList() }
            val firstTokens = tokenLists.mapNotNull { it.firstOrNull() }
            if (firstTokens.isNotEmpty()) {
                val counts = firstTokens.groupingBy { it }.eachCount()
                features[groupOffset + 4] = counts.values.max().toFloat() / n
            }

            val tokenSets = tokenLists.map { it.toSet() }
            var jaccSum = 0.0
            var jaccCount = 0
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val a = tokenSets[i]
                    val b = tokenSets[j]
                    val union = (a + b).size
                    if (union > 0) {
                        jaccSum += a.count { it in b }.toDouble() / union
                    }
                    jaccCount++
                }
            }
            if (jaccCount > 0) features[groupOffset + 5] = (jaccSum / jaccCount).toFloat()
        }

        return features
    }

    private fun extractStructural(raw: String, tags: List<List<String>>): FloatArray {
        val lenChars = raw.length.toFloat()
        val lenTokens = NONWS_TOKEN.findAll(raw).count().toFloat()
        val urls = URL_PATTERN.findAll(raw).toList()
        val urlCount = urls.size.toFloat()
        val uniqueDomains = urls.map { it.groupValues[1].lowercase() }.toSet().size.toFloat()
        val mentionCount = MENTION_PATTERN.findAll(raw).count().toFloat()
        val hashtagCount = HASHTAG_PATTERN.findAll(raw).count().toFloat()

        var tagP = 0f; var tagE = 0f; var tagT = 0f; var tagOther = 0f
        for (t in tags) {
            if (t.isEmpty()) continue
            when (t[0]) {
                "p" -> tagP++
                "e" -> tagE++
                "t" -> tagT++
                else -> tagOther++
            }
        }

        val emojiCount = EMOJI_PATTERN.findAll(raw).count().toFloat()
        val emojiRatio = if (raw.isNotEmpty()) emojiCount / raw.length else 0f
        val zeroWidthCount = NSpamPreprocessor.countInvisibleChars(raw).toFloat()
        val alphaChars = raw.count { it.isLetter() }
        val capsChars = raw.count { it.isLetter() && it.isUpperCase() }
        val capsRatio = if (alphaChars > 0) capsChars.toFloat() / alphaChars else 0f
        val digitCount = UNICODE_DIGIT.findAll(raw).count()
        val digitRatio = if (raw.isNotEmpty()) digitCount.toFloat() / raw.length else 0f
        val punctCount = UNICODE_PUNCT.findAll(raw).count()
        val punctRatio = if (raw.isNotEmpty()) punctCount.toFloat() / raw.length else 0f

        return floatArrayOf(
            lenChars, lenTokens, urlCount, uniqueDomains,
            mentionCount, hashtagCount, tagP, tagE, tagT, tagOther,
            emojiCount, emojiRatio, zeroWidthCount,
            capsRatio, digitRatio, punctRatio,
            0f // dup_body_bucket — zeroed for portability
        )
    }

    private fun hashWordNgrams(text: String, features: FloatArray) {
        val tokens = WORD_PATTERN.findAll(text).map { it.value }.toList()
        for (token in tokens) {
            hashInto(token, features, N_CHAR, N_WORD)
        }
        for (i in 0 until tokens.size - 1) {
            hashInto(tokens[i] + " " + tokens[i + 1], features, N_CHAR, N_WORD)
        }
    }

    private fun hashCharWbNgrams(text: String, features: FloatArray) {
        val normalized = WHITESPACE.replace(text, " ")
        for (word in normalized.split(' ')) {
            if (word.isEmpty()) continue
            val padded = " $word "
            for (n in 3..5) {
                for (start in 0..padded.length - n) {
                    hashInto(padded.substring(start, start + n), features, 0, N_CHAR)
                }
            }
        }
    }

    private fun hashInto(token: String, features: FloatArray, offset: Int, nFeatures: Int) {
        val hash = MurmurHash3.hash32(token.toByteArray(Charsets.UTF_8))
        val absHash = kotlin.math.abs(hash.toLong())
        val index = (absHash % nFeatures).toInt()
        val sign = if (hash >= 0) 1f else -1f
        features[offset + index] += sign
    }

    private fun populationStdDev(values: List<Float>): Float {
        val n = values.size
        if (n <= 1) return 0f
        val mean = values.sum() / n
        val variance = values.sumOf { ((it - mean) * (it - mean)).toDouble() } / n
        return sqrt(variance).toFloat()
    }
}
