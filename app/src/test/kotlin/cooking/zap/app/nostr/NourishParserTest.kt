package cooking.zap.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [NourishParser] against spec-accurate synthetic kind-30078 content (concern
 * 2.4a + Phase 4 macros). Pins the contract from the frontend
 * `parseNourishEvent` / `parseMacrosBlock` / `macrosRowView`.
 */
class NourishParserTest {

    // Full v3 event (all 8 dims + stored overall + improvements) — no macros.
    private val fullV3 = """
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

    private val macrosEstimate = """
        "macros": {
          "perServing": {"kcal": 420, "protein_g": 32, "carbs_g": 28, "fat_g": 18},
          "servingsUsed": 4,
          "servingsParsed": true,
          "confidence": "estimate",
          "method": "llm-per100g-v1"
        }
    """.trimIndent()

    private fun fullV4(
        confidence: String = "estimate",
        servingsParsed: Boolean = true,
        servingsUsed: Int = 4,
        extraFields: String = "",
    ): String = """
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
          "improvements": ["Add a leafy green", "Swap to olive oil"],
          "promptVersion": "4",
          "macros": {
            "perServing": {"kcal": 420, "protein_g": 32, "carbs_g": 28, "fat_g": 18},
            "servingsUsed": $servingsUsed,
            "servingsParsed": $servingsParsed,
            "confidence": "$confidence",
            "method": "llm-per100g-v1"$extraFields
          }
        }
    """.trimIndent()

    @Test
    fun servicePubkey_matchesFrontendSourceOfTruth() {
        // frontend src/lib/nourish/types.ts NOURISH_SERVICE_PUBKEY
        assertEquals(
            "fdd263f69f9e95a2a0a58ec3e7e8053011214fa66007d93b26d2f4717d31917b",
            NourishParser.SERVICE_PUBKEY,
        )
    }

    @Test
    fun parsesAllDimensionsAndOverall() {
        val s = NourishParser.parse(fullV3)!!
        assertEquals(7, s.overall)
        assertEquals("Strong", s.overallLabel)
        assertEquals(8, s.dimensions.size)
        assertEquals(NourishDimension("Real Food", 8), s.dimensions.first())
        assertEquals(NourishDimension("Heart", 9), s.dimensions.last())
        // Blank improvement filtered out.
        assertEquals(listOf("Add a leafy green", "Swap to olive oil"), s.improvements)
        // v3 → no macros (byte-identical card state: absent row).
        assertNull(s.macros)
        assertNull(NourishParser.macrosRowView(s.macros))
    }

    @Test
    fun v3_cardState_unchangedWhenMacrosAbsent() {
        val s = NourishParser.parse(fullV3)!!
        // Core score fields that drive today's card — identical to pre-Phase-4.
        assertEquals(7, s.overall)
        assertEquals("Strong", s.overallLabel)
        assertEquals(
            listOf(8, 7, 6, 5, 4, 7, 6, 9),
            s.dimensions.map { it.score },
        )
        assertEquals(listOf("Add a leafy green", "Swap to olive oil"), s.improvements)
        assertNull(s.macros)
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

    // ── Phase 4 macros ──────────────────────────────────────────────────

    @Test
    fun v4_parsesMacros_estimate() {
        val s = NourishParser.parse(fullV4(confidence = "estimate"))!!
        assertEquals(7, s.overall) // scores still parse
        val m = s.macros!!
        assertEquals(420, m.perServing.kcal)
        assertEquals(32, m.perServing.proteinG)
        assertEquals(28, m.perServing.carbsG)
        assertEquals(18, m.perServing.fatG)
        assertEquals(4, m.servingsUsed)
        assertEquals(true, m.servingsParsed)
        assertEquals("estimate", m.confidence)
        assertEquals("llm-per100g-v1", m.method)

        val row = NourishParser.macrosRowView(m)!!
        assertEquals("Estimated per serving", row.label)
        assertEquals("estimate", row.tone)
        assertEquals(420, row.kcal)
        assertEquals(32, row.proteinG)
        assertEquals(28, row.carbsG)
        assertEquals(18, row.fatG)
    }

    @Test
    fun v4_rough_confidence_mapsToRoughEstimate() {
        val s = NourishParser.parse(fullV4(confidence = "rough"))!!
        assertEquals("rough", s.macros!!.confidence)
        val row = NourishParser.macrosRowView(s.macros)!!
        assertEquals("Rough estimate", row.label)
        assertEquals("rough", row.tone)
    }

    @Test
    fun servingsParsedFalse_appendsAssumedServings() {
        val s = NourishParser.parse(
            fullV4(confidence = "estimate", servingsParsed = false, servingsUsed = 4),
        )!!
        assertEquals(false, s.macros!!.servingsParsed)
        assertEquals(
            "Estimated per serving (servings assumed: 4)",
            NourishParser.macrosRowView(s.macros)!!.label,
        )
    }

    @Test
    fun rough_plus_servingsParsedFalse() {
        val s = NourishParser.parse(
            fullV4(confidence = "rough", servingsParsed = false, servingsUsed = 4),
        )!!
        assertEquals(
            "Rough estimate (servings assumed: 4)",
            NourishParser.macrosRowView(s.macros)!!.label,
        )
    }

    @Test
    fun malformedMacros_degradeToAbsent_scoresStillParse() {
        // Missing perServing fields → macros null; scores intact.
        val json = """
            {
              "realFood":{"score":8},"gut":{"score":7},"protein":{"score":6},
              "antiInflammatory":{"score":5},"bloodSugar":{"score":4},
              "immuneSupportive":{"score":7},"brainHealth":{"score":6},
              "heartHealth":{"score":9},"overall":{"score":7,"label":"Strong"},
              "improvements":[],
              "macros": {"confidence": "estimate", "servingsUsed": 4}
            }
        """.trimIndent()
        val s = NourishParser.parse(json)!!
        assertEquals(7, s.overall)
        assertNull(s.macros)
        assertNull(NourishParser.macrosRowView(null))
    }

    @Test
    fun invalidConfidence_degradesToAbsent() {
        val s = NourishParser.parse(fullV4(confidence = "high"))!!
        assertEquals(7, s.overall)
        assertNull(s.macros)
    }

    @Test
    fun unknownFutureMacrosFields_areTolerated() {
        val s = NourishParser.parse(
            fullV4(extraFields = """, "fiber_g": 12, "sodium_mg": 400, "futureBlock": {"x": 1}"""),
        )!!
        assertNotNull(s.macros)
        assertEquals(420, s.macros!!.perServing.kcal)
        assertEquals("estimate", s.macros!!.confidence)
    }

    @Test
    fun computeResponse_attachesTopLevelMacros() {
        val response = """
            {
              "success": true,
              "scores": {
                "realFood": {"score": 8}, "gut": {"score": 7}, "protein": {"score": 6},
                "antiInflammatory": {"score": 5}, "bloodSugar": {"score": 4},
                "immuneSupportive": {"score": 7}, "brainHealth": {"score": 6},
                "heartHealth": {"score": 9}, "overall": {"score": 7, "label": "Strong"}
              },
              "improvements": ["Add greens"],
              "promptVersion": "4",
              $macrosEstimate
            }
        """.trimIndent()
        val obj = Json.parseToJsonElement(response).jsonObject
        val s = NourishParser.parseScores(
            obj["scores"]!!.jsonObject,
            NourishParser.extractImprovements(obj),
        )!!.copy(macros = NourishParser.parseMacros(obj["macros"]))
        assertEquals(7, s.overall)
        assertEquals(420, s.macros!!.perServing.kcal)
        assertEquals("Estimated per serving", NourishParser.macrosRowView(s.macros)!!.label)
    }

    @Test
    fun macrosRowView_nullWhenAbsent() {
        assertNull(NourishParser.macrosRowView(null))
    }
}
