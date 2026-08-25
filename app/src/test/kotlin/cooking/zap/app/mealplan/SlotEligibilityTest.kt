package cooking.zap.app.mealplan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixture-driven port of frontend `slotEligibility.test.ts`.
 * Vectors: `/fixtures/mealplan-eligibility.vectors.json` (62 cases).
 */
class SlotEligibilityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val fixtures = json.parseToJsonElement(
        javaClass.getResource("/fixtures/mealplan-eligibility.vectors.json")!!.readText(),
    ).jsonObject

    private val fixtureCaseArrays = listOf(
        "isRecipeEligibleForSlot",
        "eligibleSlotsForRecipe",
        "restrictCandidatesToRequestedSlots",
        "insufficientSlotCoverageMessage",
        "noEligibleRecipesMessage",
    )

    private data class Row(
        val id: String,
        override val title: String,
        override val tags: List<String>,
    ) : SlotEligibility.Candidate

    @Test
    fun executesEveryCaseArrayInTheFixture() {
        val keys = fixtures.keys.filter { it != "description" }.sorted()
        assertEquals(fixtureCaseArrays.sorted(), keys)
    }

    @Test
    fun isRecipeEligibleForSlot_allVectors() {
        val vectors = fixtures["isRecipeEligibleForSlot"]!!.jsonArray
        assertEquals(36, vectors.size)
        for (el in vectors) {
            val v = el.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val candidate = SlotEligibility.SlotCandidate(
                title = v["title"]!!.jsonPrimitive.content,
                tags = v["tags"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            val slot = v["slot"]!!.jsonPrimitive.content
            val expected = v["expected"]!!.jsonPrimitive.boolean
            assertEquals(
                id,
                expected,
                SlotEligibility.isRecipeEligibleForSlot(candidate, slot),
            )
        }
    }

    @Test
    fun eligibleSlotsForRecipe_allVectors() {
        val vectors = fixtures["eligibleSlotsForRecipe"]!!.jsonArray
        assertEquals(4, vectors.size)
        for (el in vectors) {
            val v = el.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val candidate = SlotEligibility.SlotCandidate(
                title = v["title"]!!.jsonPrimitive.content,
                tags = v["tags"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            val expected = v["expected"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(id, expected, SlotEligibility.eligibleSlotsForRecipe(candidate))
        }
    }

    @Test
    fun restrictCandidatesToRequestedSlots_allVectors() {
        val vectors = fixtures["restrictCandidatesToRequestedSlots"]!!.jsonArray
        assertEquals(5, vectors.size)
        for (el in vectors) {
            val v = el.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val candidates = v["candidates"]!!.jsonArray.map { raw ->
                val row = raw.jsonObject
                Row(
                    id = row["id"]!!.jsonPrimitive.content,
                    title = row["title"]!!.jsonPrimitive.content,
                    tags = row["tags"]!!.jsonArray.map { it.jsonPrimitive.content },
                )
            }
            val mealSlots = v["mealSlots"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expected = v["expected"]!!.jsonArray.map { it.jsonPrimitive.content }
            val surviving = SlotEligibility.restrictCandidatesToRequestedSlots(candidates, mealSlots)
            assertEquals(id, expected, surviving.map { it.id })
        }
    }

    @Test
    fun insufficientSlotCoverageMessage_allVectors() {
        val vectors = fixtures["insufficientSlotCoverageMessage"]!!.jsonArray
        assertEquals(12, vectors.size)
        for (el in vectors) {
            val v = el.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val expectedEl = v["expected"]
            val expected = if (expectedEl == null || expectedEl is JsonNull) {
                null
            } else {
                expectedEl.jsonPrimitive.contentOrNull
            }
            val actual = SlotEligibility.insufficientSlotCoverageMessage(
                mealSlots = v["mealSlots"]!!.jsonArray.map { it.jsonPrimitive.content },
                found = v["found"]!!.jsonPrimitive.int,
                requested = v["requested"]!!.jsonPrimitive.int,
            )
            assertEquals(id, expected, actual)
        }
    }

    @Test
    fun noEligibleRecipesMessage_allVectors() {
        val vectors = fixtures["noEligibleRecipesMessage"]!!.jsonArray
        assertEquals(5, vectors.size)
        for (el in vectors) {
            val v = el.jsonObject
            val id = v["id"]!!.jsonPrimitive.content
            val expected = v["expected"]!!.jsonPrimitive.content
            val actual = SlotEligibility.noEligibleRecipesMessage(
                v["mealSlots"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(id, expected, actual)
        }
    }
}
