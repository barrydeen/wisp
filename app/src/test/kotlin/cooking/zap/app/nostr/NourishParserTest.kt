package cooking.zap.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [NourishParser] against spec-accurate synthetic kind-30078 content (concern
 * 2.4a). The pantry relay requires NIP-42 AUTH, so a real-event golden can't be
 * captured off-device here — that's a follow-up once the on-device auth'd read
 * works. These pin the contract from the frontend `parseNourishEvent`.
 */
class NourishParserTest {

    // Full v3 event (all 8 dims + stored overall + improvements).
    private val full = """
        {
          "realFood": {"score": 8, "label": "Strong"},
          "gut": {"score": 7, "label": "Strong"},
          "protein": {"score": 6, "label": "Moderate"},
          "antiInflammatory": {"score": 5, "label": "Moderate"},
          "bloodSugar": {"score": 4, "label": "Fair"},
          "immuneSupportive": {"score": 7, "label": "Strong"},
          "brainHealth": {"score": 6, "label": "Moderate"},
          "heartHealth": {"score": 9, "label": "Excellent"},
          "overall": {"score": 7, "label": "Strong"},
          "improvements": ["Add a leafy green", "Swap to olive oil", ""],
          "promptVersion": "3"
        }
    """.trimIndent()

    @Test
    fun parsesAllDimensionsAndOverall() {
        val s = NourishParser.parse(full)!!
        assertEquals(7, s.overall)
        assertEquals("Strong", s.overallLabel)
        assertEquals(8, s.dimensions.size)
        assertEquals(NourishDimension("Real Food", 8), s.dimensions.first())
        assertEquals(NourishDimension("Heart", 9), s.dimensions.last())
        // Blank improvement filtered out.
        assertEquals(listOf("Add a leafy green", "Swap to olive oil"), s.improvements)
    }

    @Test
    fun trustsStoredOverall_doesNotRecomputeFromDims() {
        // Stored overall is 9, but the dims would compute much lower. Parser
        // must report the STORED 9, not a recomputed value (legacy deflation).
        val json = """
            {"realFood":{"score":1},"gut":{"score":1},"protein":{"score":1},
             "antiInflammatory":{"score":0},"bloodSugar":{"score":0},
             "immuneSupportive":{"score":0},"brainHealth":{"score":0},
             "heartHealth":{"score":0},"overall":{"score":9,"label":"Excellent"}}
        """.trimIndent()
        assertEquals(9, NourishParser.parse(json)!!.overall)
    }

    @Test
    fun legacyEvent_missingDims_defaultToZero() {
        // v1 event: only the original three dims + overall.
        val json = """
            {"gut":{"score":6},"protein":{"score":5},"realFood":{"score":7},
             "overall":{"score":6,"label":"Moderate"}}
        """.trimIndent()
        val s = NourishParser.parse(json)!!
        assertEquals(6, s.overall)
        assertEquals(0, s.dimensions.first { it.name == "Heart" }.score)
        assertEquals(0, s.dimensions.first { it.name == "Blood Sugar" }.score)
        assertEquals(7, s.dimensions.first { it.name == "Real Food" }.score)
    }

    @Test
    fun missingOverall_yieldsNull() {
        assertNull(NourishParser.parse("""{"gut":{"score":5}}"""))
    }

    @Test
    fun malformed_yieldsNull_noCrash() {
        assertNull(NourishParser.parse("not json"))
        assertNull(NourishParser.parse(""))
    }

    @Test
    fun outOfRangeScores_areClampedTo0to10() {
        val json = """
            {"realFood":{"score":15},"gut":{"score":-3},"protein":{"score":7},
             "antiInflammatory":{"score":0},"bloodSugar":{"score":0},
             "immuneSupportive":{"score":0},"brainHealth":{"score":0},
             "heartHealth":{"score":0},"overall":{"score":99}}
        """.trimIndent()
        val s = NourishParser.parse(json)!!
        assertEquals(10, s.overall) // 99 → 10, so the progress bar stays in 0..1
        assertEquals(10, s.dimensions.first { it.name == "Real Food" }.score) // 15 → 10
        assertEquals(0, s.dimensions.first { it.name == "Gut" }.score)        // -3 → 0
    }

    @Test
    fun parseScores_computeResponsePath_trustsStoredOverall() {
        // The /api/nourish response nests dims+overall under `scores`, with
        // `improvements` at the top level — fed to the shared parseScores core.
        val response = """
            {
              "success": true,
              "scores": {
                "realFood": {"score": 1}, "gut": {"score": 1}, "protein": {"score": 1},
                "antiInflammatory": {"score": 0}, "bloodSugar": {"score": 0},
                "immuneSupportive": {"score": 0}, "brainHealth": {"score": 0},
                "heartHealth": {"score": 0}, "overall": {"score": 8, "label": "Strong"}
              },
              "improvements": ["Add greens"],
              "audience_scores": {"kidFriendly": {"score": 5}},
              "promptVersion": "3",
              "createdAt": 1700000000
            }
        """.trimIndent()
        val obj = Json.parseToJsonElement(response).jsonObject
        val s = NourishParser.parseScores(
            obj["scores"]!!.jsonObject,
            NourishParser.extractImprovements(obj),
        )!!
        // Stored overall trusted (8), not recomputed from the low dims.
        assertEquals(8, s.overall)
        assertEquals("Strong", s.overallLabel)
        assertEquals(8, s.dimensions.size)
        assertEquals(listOf("Add greens"), s.improvements)
        // audience_scores / promptVersion / createdAt ignored without throwing.
    }

    @Test
    fun dTag_format() {
        assertEquals(
            "nourish:30023:abc:tuscan-peposo-(black-pepper-beef-stew)",
            NourishParser.dTag("abc", "tuscan-peposo-(black-pepper-beef-stew)"),
        )
    }
}
