package cooking.zap.app.api

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HTTP contract for `GET /api/pantry/recipes-by-week`.
 *
 * Pins the two things that can silently break the trend pill: the exact
 * request (right path, and **no** Authorization header — the relay secret is
 * the backend's, never the app's) and lenient decoding of every body shape the
 * route can return, including the two undocumented top-level fields the live
 * endpoint actually sends.
 */
class RecipesByWeekApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ZapCookingApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ZapCookingApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Verbatim live response, captured 2026-07-24. */
    private val liveBody = """
        {"weeks":[{"week_start":"2026-05-04","count":1},{"week_start":"2026-05-11","count":2},
        {"week_start":"2026-05-18","count":2},{"week_start":"2026-05-25","count":0},
        {"week_start":"2026-06-01","count":1},{"week_start":"2026-06-08","count":1},
        {"week_start":"2026-06-15","count":2},{"week_start":"2026-06-22","count":6},
        {"week_start":"2026-06-29","count":4},{"week_start":"2026-07-06","count":8},
        {"week_start":"2026-07-13","count":8},{"week_start":"2026-07-20","count":5}],
        "timezone":"UTC","week_start_day":"monday"}
    """.trimIndent()

    @Test
    fun liveBody_parsesAllTwelveBuckets() = runBlocking {
        server.enqueue(MockResponse().setBody(liveBody))

        val weeks = api.getRecipesByWeek()

        assertEquals(12, weeks.size)
        assertEquals(RecipeWeek("2026-05-04", 1), weeks.first())
        assertEquals(RecipeWeek("2026-05-25", 0), weeks[3]) // zero-filled bucket survives
        assertEquals(RecipeWeek("2026-07-20", 5), weeks.last())
    }

    @Test
    fun request_hitsBackendPath_withNoAuthHeader() = runBlocking {
        server.enqueue(MockResponse().setBody(liveBody))

        api.getRecipesByWeek()

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/pantry/recipes-by-week", recorded.path)
        // The Bearer secret lives server-side; the app must never send one.
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun unknownTopLevelAndPerWeekFields_areIgnored() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"weeks":[{"week_start":"2026-07-20","count":5,"kinds":[30023,35000]}],
                 "timezone":"UTC","week_start_day":"monday","generated_at":"2026-07-24T00:00:00Z"}
                """.trimIndent()
            )
        )

        val weeks = api.getRecipesByWeek()

        assertEquals(listOf(RecipeWeek("2026-07-20", 5)), weeks)
    }

    @Test
    fun emptyObject_decodesToEmptyList() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))

        assertTrue(api.getRecipesByWeek().isEmpty())
    }

    @Test
    fun emptyWeeksArray_decodesToEmptyList() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"weeks":[]}"""))

        assertTrue(api.getRecipesByWeek().isEmpty())
    }

    @Test
    fun secretUnconfigured_503_throwsApiExceptionCarryingCodeAndBody() = runBlocking {
        val body = """{"error":"recipe stats unavailable"}"""
        server.enqueue(MockResponse().setResponseCode(503).setBody(body))

        val e = runCatching { api.getRecipesByWeek() }.exceptionOrNull()

        assertTrue("expected ZapCookingApiException, got $e", e is ZapCookingApiException)
        assertEquals(503, (e as ZapCookingApiException).code)
        assertEquals(body, e.body)
    }

    @Test
    fun relayDown_502_throwsApiException() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(502).setBody("""{"error":"recipe stats unavailable"}""")
        )

        val e = runCatching { api.getRecipesByWeek() }.exceptionOrNull()

        assertTrue("expected ZapCookingApiException, got $e", e is ZapCookingApiException)
        assertEquals(502, (e as ZapCookingApiException).code)
    }

    @Test
    fun malformedBody_throwsRatherThanCrashingWithSomethingElse() = runBlocking {
        // A 200 carrying HTML (proxy/error page) must surface as a catchable
        // exception, not a hang or an NPE. The caller collapses it to "hidden".
        server.enqueue(MockResponse().setBody("<html>nope</html>"))

        val e = runCatching { api.getRecipesByWeek() }.exceptionOrNull()

        assertTrue("expected an exception, got none", e != null)
    }
}
