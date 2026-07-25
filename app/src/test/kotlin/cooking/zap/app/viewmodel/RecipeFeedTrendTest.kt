package cooking.zap.app.viewmodel

import cooking.zap.app.api.ZapCookingApi
import cooking.zap.app.repo.RecipeTrendCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The trend's wiring into [RecipeFeedViewModel] — above all its **isolation**
 * from the feed. The feed's four state flows must be untouched by anything the
 * trend does, including failing.
 *
 * Each test builds its own [RecipeTrendCache] so nothing leaks between tests
 * via the process-wide instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeFeedTrendTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var api: ZapCookingApi

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
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
        Dispatchers.resetMain()
    }

    private var now = NOW

    private fun cache() = RecipeTrendCache(nowMillis = { now })

    private fun enqueueOk() {
        server.enqueue(
            MockResponse().setBody(
                """{"weeks":[{"week_start":"2026-07-06","count":8},
                   {"week_start":"2026-07-13","count":8},
                   {"week_start":"2026-07-20","count":5}],"timezone":"UTC"}""".trimIndent()
            )
        )
    }

    private fun enqueueFailure() {
        server.enqueue(
            MockResponse().setResponseCode(502).setBody("""{"error":"recipe stats unavailable"}""")
        )
    }

    /** The trend resolves off the main dispatcher, so wait for it rather than assuming. */
    private fun awaitSettled(vm: RecipeFeedViewModel): RecipeTrendState {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val state = vm.trend.value
            if (state !is RecipeTrendState.Loading) return state
            Thread.sleep(10)
        }
        fail("trend never left Loading")
        error("unreachable")
    }

    private fun awaitRequests(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && server.requestCount < count) {
            Thread.sleep(10)
        }
    }

    @Test
    fun startsInLoading_beforeAnythingIsWired() {
        assertEquals(RecipeTrendState.Loading, RecipeFeedViewModel().trend.value)
    }

    @Test
    fun successfulFetch_becomesVisibleWithTheRollingSum() {
        enqueueOk()
        val vm = RecipeFeedViewModel()

        vm.loadTrend(api, cache())

        val state = awaitSettled(vm)
        assertTrue("expected Visible, was $state", state is RecipeTrendState.Visible)
        assertEquals(21, (state as RecipeTrendState.Visible).count)
        assertEquals(3, state.weeks.size)
    }

    @Test
    fun failedFetch_isHidden_andLeavesEveryFeedFlowUntouched() {
        enqueueFailure()
        val vm = RecipeFeedViewModel()

        vm.loadTrend(api, cache())

        assertEquals(RecipeTrendState.Hidden, awaitSettled(vm))
        // The isolation contract, asserted flow by flow.
        assertFalse("trend must not drive the feed spinner", vm.isLoading.value)
        assertFalse(vm.isLoadingMore.value)
        assertFalse(vm.exhausted.value)
        assertFalse("trend must not drive pull-to-refresh", vm.isRefreshing.value)
        assertTrue(vm.recipes.value.isEmpty())
    }

    @Test
    fun successfulFetch_alsoLeavesEveryFeedFlowUntouched() {
        enqueueOk()
        val vm = RecipeFeedViewModel()

        vm.loadTrend(api, cache())

        awaitSettled(vm)
        assertFalse(vm.isLoading.value)
        assertFalse(vm.isLoadingMore.value)
        assertFalse(vm.exhausted.value)
        assertFalse(vm.isRefreshing.value)
    }

    @Test
    fun loadTrend_isIdempotent() {
        enqueueOk()
        server.enqueue(MockResponse().setBody("""{"weeks":[]}""")) // must go unused
        val vm = RecipeFeedViewModel()
        val cache = cache()

        vm.loadTrend(api, cache)
        awaitSettled(vm)
        vm.loadTrend(api, cache)
        vm.loadTrend(api, cache)

        assertEquals(1, server.requestCount)
    }

    @Test
    fun refresh_bypassesTheTtl_andRefetches() {
        enqueueOk()
        enqueueOk()
        val vm = RecipeFeedViewModel()

        vm.loadTrend(api, cache())
        awaitSettled(vm)
        // Half an hour on: far inside the 6h TTL, so only the force path can
        // produce a second request. Past the few-second force floor.
        now += TimeUnit.MINUTES.toMillis(30)
        vm.refresh()

        awaitRequests(2)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun reEntryWithAWarmCache_startsVisible_neverLoading() {
        // B2: a back-nav recreates this ViewModel. With a retained value the
        // pill must be on screen from the first frame — no reserve-then-fill.
        enqueueOk()
        val cache = cache()
        val first = RecipeFeedViewModel()
        first.loadTrend(api, cache)
        awaitSettled(first)

        val reEntered = RecipeFeedViewModel()
        reEntered.loadTrend(api, cache)

        // Asserted synchronously — no awaitSettled, because waiting would hide
        // exactly the flicker this guards against.
        val state = reEntered.trend.value
        assertTrue("expected Visible immediately, was $state", state is RecipeTrendState.Visible)
        assertEquals(21, (state as RecipeTrendState.Visible).count)
    }

    @Test
    fun refresh_withNoRepoBound_doesNotThrow() {
        // refresh() reaches the feed repo and the trend independently; with no
        // repo bound the feed half is a no-op and the trend half still runs.
        enqueueOk()
        val vm = RecipeFeedViewModel()

        vm.loadTrend(api, cache())
        awaitSettled(vm)
        vm.refresh()

        assertFalse(vm.isRefreshing.value)
    }

    @Test
    fun failedRefreshAfterAGoodValue_keepsThePillVisible() {
        // The guarantee PR 3's header reservation is built on: no collapse
        // once something has been shown.
        enqueueOk()
        enqueueFailure()
        val vm = RecipeFeedViewModel()

        vm.loadTrend(api, cache())
        val first = awaitSettled(vm) as RecipeTrendState.Visible
        vm.refresh()
        awaitRequests(2)

        val after = vm.trend.value
        assertTrue("expected still Visible, was $after", after is RecipeTrendState.Visible)
        assertEquals(first.count, (after as RecipeTrendState.Visible).count)
    }

    @Test
    fun refreshBeforeLoadTrend_issuesNoRequest() {
        val vm = RecipeFeedViewModel()

        vm.refresh()

        assertEquals(0, server.requestCount)
        assertEquals(RecipeTrendState.Loading, vm.trend.value)
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
