package cooking.zap.app.repo

import cooking.zap.app.api.ZapCookingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.util.concurrent.TimeUnit

/**
 * The process-scoped stats cache: TTL semantics, failure tolerance, and
 * single-flight. Request counts come from [MockWebServer], so "no request was
 * made" is asserted against the wire rather than inferred.
 */
class RecipeTrendCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ZapCookingApi
    private var now = 1_000_000L

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

    private fun cache(ttlMillis: Long = RecipeTrendCache.TTL_MILLIS) =
        RecipeTrendCache(ttlMillis = ttlMillis, nowMillis = { now })

    private fun enqueueOk(vararg counts: Int) {
        val weeks = counts.mapIndexed { i, c ->
            """{"week_start":"2026-01-%02d","count":$c}""".format(i + 1)
        }.joinToString(",")
        server.enqueue(MockResponse().setBody("""{"weeks":[$weeks],"timezone":"UTC"}"""))
    }

    private fun enqueueFailure(code: Int = 502) {
        server.enqueue(
            MockResponse().setResponseCode(code).setBody("""{"error":"recipe stats unavailable"}""")
        )
    }

    // --- TTL ---

    @Test
    fun withinTtl_servesCacheWithoutASecondRequest() = runBlocking {
        enqueueOk(1, 2, 3)
        enqueueOk(9, 9, 9) // must never be consumed
        val cache = cache()

        val first = cache.weeks(api)
        now += TimeUnit.HOURS.toMillis(5)
        val second = cache.weeks(api)

        assertEquals(first, second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun pastTtl_refetches() = runBlocking {
        enqueueOk(1, 2, 3)
        enqueueOk(4, 5, 6)
        val cache = cache()

        cache.weeks(api)
        now += TimeUnit.HOURS.toMillis(7)
        val second = cache.weeks(api)

        assertEquals(listOf(4, 5, 6), second?.map { it.count })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun force_bypassesAFreshTtl() = runBlocking {
        enqueueOk(1, 2, 3)
        enqueueOk(7, 7, 7)
        val cache = cache()

        cache.weeks(api)
        // No clock movement at all — pull-to-refresh still goes to the network.
        val refreshed = cache.weeks(api, force = true)

        assertEquals(listOf(7, 7, 7), refreshed?.map { it.count })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun failedFetch_doesNotAdvanceTheTtlClock() = runBlocking {
        // The whole point: one bad request at 8am must not blank the pill until 2pm.
        enqueueFailure()
        enqueueOk(1, 2, 3)
        val cache = cache()

        assertNull(cache.weeks(api))
        // Not a second later, and it must still be willing to try.
        val second = cache.weeks(api)

        assertEquals(listOf(1, 2, 3), second?.map { it.count })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun successAfterFailure_startsTheTtlWindow() = runBlocking {
        enqueueFailure()
        enqueueOk(1, 2, 3)
        enqueueOk(9, 9, 9) // must never be consumed
        val cache = cache()

        cache.weeks(api)
        cache.weeks(api)
        now += TimeUnit.HOURS.toMillis(1)
        cache.weeks(api)

        assertEquals(2, server.requestCount)
    }

    // --- Failure tolerance ---

    @Test
    fun failureWithNoCache_isNull() = runBlocking {
        enqueueFailure(503)

        assertNull(cache().weeks(api))
    }

    @Test
    fun failureAfterAGoodValue_keepsServingTheGoodValue() = runBlocking {
        enqueueOk(4, 8, 8, 5)
        enqueueFailure()
        val cache = cache()

        val good = cache.weeks(api)
        now += TimeUnit.HOURS.toMillis(7)
        val afterFailure = cache.weeks(api, force = true)

        assertEquals(good, afterFailure)
        assertEquals(listOf(4, 8, 8, 5), afterFailure?.map { it.count })
    }

    @Test
    fun successfulEmptyResponse_doesNotClobberAGoodValue() = runBlocking {
        enqueueOk(4, 8, 8, 5)
        server.enqueue(MockResponse().setBody("""{"weeks":[]}"""))
        val cache = cache()

        val good = cache.weeks(api)
        val afterEmpty = cache.weeks(api, force = true)

        assertEquals(good, afterEmpty)
    }

    @Test
    fun successfulEmptyResponse_withNoCache_isNull() = runBlocking {
        // The cache reports "nothing displayable", not "an empty series": it
        // only ever returns what it retained, and an empty response is not
        // worth retaining. The API layer still distinguishes the two (see
        // RecipesByWeekApiTest); by this layer the difference has no consumer,
        // since the reducer collapses both to Hidden.
        server.enqueue(MockResponse().setBody("""{"weeks":[]}"""))

        assertNull(cache().weeks(api))
    }

    @Test
    fun emptyResponsesKeepRetrying_thenLatchOnTheFirstGoodValue() = runBlocking {
        // With nothing retained the TTL has nothing to serve, so the cache
        // keeps asking on every call until real data shows up — at which point
        // the window finally starts.
        server.enqueue(MockResponse().setBody("""{"weeks":[]}"""))
        server.enqueue(MockResponse().setBody("""{"weeks":[]}"""))
        enqueueOk(1, 2, 3)
        server.enqueue(MockResponse().setBody("""{"weeks":[]}""")) // must go unused
        val cache = cache()

        cache.weeks(api)
        cache.weeks(api)
        assertEquals(2, server.requestCount)

        assertEquals(listOf(1, 2, 3), cache.weeks(api)?.map { it.count })
        now += TimeUnit.HOURS.toMillis(1)
        cache.weeks(api)

        assertEquals(3, server.requestCount)
    }

    // --- Single-flight ---

    @Test
    fun concurrentCallers_issueOneRequest() = runBlocking {
        enqueueOk(1, 2, 3)
        enqueueOk(9, 9, 9) // available, and must go unused
        val cache = cache()

        val results = listOf(
            async(Dispatchers.IO) { cache.weeks(api) },
            async(Dispatchers.IO) { cache.weeks(api) },
            async(Dispatchers.IO) { cache.weeks(api) },
        ).awaitAll()

        assertEquals(1, server.requestCount)
        assertTrue("all callers should see the same series", results.all { it == results[0] })
        assertEquals(listOf(1, 2, 3), results[0]?.map { it.count })
    }

    @Test
    fun concurrentCallers_afterAFailure_stillRetryOnTheNextCall() = runBlocking {
        // Single-flight must not latch a failure into a permanent no-request state.
        enqueueFailure()
        enqueueOk(1, 2, 3)
        val cache = cache()

        listOf(
            async(Dispatchers.IO) { cache.weeks(api) },
            async(Dispatchers.IO) { cache.weeks(api) },
        ).awaitAll()
        val eventual = cache.weeks(api)

        assertEquals(listOf(1, 2, 3), eventual?.map { it.count })
    }

    @Test
    fun sharedInstance_isASingleton() {
        assertTrue(RecipeTrendCache.shared === RecipeTrendCache.shared)
        assertEquals(TimeUnit.HOURS.toMillis(6), RecipeTrendCache.TTL_MILLIS)
    }
}
