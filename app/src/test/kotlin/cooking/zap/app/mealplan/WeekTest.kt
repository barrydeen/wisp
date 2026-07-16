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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Port of frontend `week.test.ts`, driven by `/fixtures/week.vectors.json`.
 */
class WeekTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = json.parseToJsonElement(
        javaClass.getResource("/fixtures/week.vectors.json")!!.readText(),
    ).jsonObject

    @Test
    fun weeksInISOYear_vectors() {
        for (el in fixtures["weeksInISOYear"]!!.jsonArray) {
            val v = el.jsonObject
            assertEquals(
                v["id"]!!.jsonPrimitive.content,
                v["expected"]!!.jsonPrimitive.int,
                Week.weeksInISOYear(v["year"]!!.jsonPrimitive.int),
            )
        }
    }

    @Test
    fun weekIdForDate_vectors() {
        for (el in fixtures["weekIdForDate"]!!.jsonArray) {
            val v = el.jsonObject
            val d = v["date"]!!.jsonObject
            val date = LocalDate.of(
                d["y"]!!.jsonPrimitive.int,
                d["m"]!!.jsonPrimitive.int,
                d["d"]!!.jsonPrimitive.int,
            )
            assertEquals(
                v["id"]!!.jsonPrimitive.content,
                v["expected"]!!.jsonPrimitive.content,
                Week.weekIdForDate(date),
            )
        }
    }

    @Test
    fun nextWeekId_vectors() {
        for (el in fixtures["nextWeekId"]!!.jsonArray) {
            val v = el.jsonObject
            assertEquals(
                v["id"]!!.jsonPrimitive.content,
                v["expected"]!!.jsonPrimitive.content,
                Week.nextWeekId(v["input"]!!.jsonPrimitive.content),
            )
        }
    }

    @Test
    fun prevWeekId_vectors() {
        for (el in fixtures["prevWeekId"]!!.jsonArray) {
            val v = el.jsonObject
            assertEquals(
                v["id"]!!.jsonPrimitive.content,
                v["expected"]!!.jsonPrimitive.content,
                Week.prevWeekId(v["input"]!!.jsonPrimitive.content),
            )
        }
    }

    @Test
    fun mondayOfWeek_vectors() {
        for (el in fixtures["mondayOfWeek"]!!.jsonArray) {
            val v = el.jsonObject
            val monday = Week.mondayOfWeek(v["input"]!!.jsonPrimitive.content)
            assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
            assertEquals(
                v["expectedWeekId"]!!.jsonPrimitive.content,
                Week.weekIdForDate(monday),
            )
            // Fixture uses JS getDay() where Monday=1.
            assertEquals(v["expectedDayOfWeek"]!!.jsonPrimitive.int, monday.dayOfWeek.value)
        }
    }

    @Test
    fun dTagRoundTrip_vectors() {
        for (el in fixtures["dTagRoundTrip"]!!.jsonArray) {
            val v = el.jsonObject
            val weekId = v["weekId"]!!.jsonPrimitive.content
            val dTag = v["dTag"]!!.jsonPrimitive.content
            assertEquals(dTag, Week.dTagForWeek(weekId))
            assertEquals(weekId, Week.weekIdFromDTag(dTag))
        }
    }

    @Test
    fun weekIdFromDTagReject_vectors() {
        for (el in fixtures["weekIdFromDTagReject"]!!.jsonArray) {
            val v = el.jsonObject
            val expected = v["expected"]
            if (expected == null || expected is JsonNull) {
                assertNull(v["id"]!!.jsonPrimitive.content, Week.weekIdFromDTag(v["input"]!!.jsonPrimitive.content))
            } else {
                assertEquals(
                    v["id"]!!.jsonPrimitive.content,
                    expected.jsonPrimitive.contentOrNull,
                    Week.weekIdFromDTag(v["input"]!!.jsonPrimitive.content),
                )
            }
        }
    }

    @Test
    fun isValidWeekId_vectors() {
        for (el in fixtures["isValidWeekId"]!!.jsonArray) {
            val v = el.jsonObject
            val input = v["input"]!!.jsonPrimitive.content
            val expected = v["expected"]!!.jsonPrimitive.boolean
            if (expected) assertTrue(v["id"]!!.jsonPrimitive.content, Week.isValidWeekId(input))
            else assertFalse(v["id"]!!.jsonPrimitive.content, Week.isValidWeekId(input))
        }
    }

    @Test
    fun parseWeekId_vectors() {
        for (el in fixtures["parseWeekId"]!!.jsonArray) {
            val v = el.jsonObject
            val exp = v["expected"]!!.jsonObject
            val parsed = Week.parseWeekId(v["input"]!!.jsonPrimitive.content)!!
            assertEquals(exp["year"]!!.jsonPrimitive.int, parsed.year)
            assertEquals(exp["week"]!!.jsonPrimitive.int, parsed.week)
        }
    }

    @Test
    fun weekDisplayRange_vectors() {
        for (el in fixtures["weekDisplayRange"]!!.jsonArray) {
            val v = el.jsonObject
            assertEquals(
                v["id"]!!.jsonPrimitive.content,
                v["expected"]!!.jsonPrimitive.content,
                Week.weekDisplayRange(v["input"]!!.jsonPrimitive.content),
            )
        }
    }
}
