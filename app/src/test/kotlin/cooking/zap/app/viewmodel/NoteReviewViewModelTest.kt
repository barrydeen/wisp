package cooking.zap.app.viewmodel

import cooking.zap.app.api.NoteReviewMode
import cooking.zap.app.api.NoteReviewResult
import cooking.zap.app.api.ZapCookingApi
import cooking.zap.app.cheffy.Cheffy
import cooking.zap.app.cheffy.NoteReview
import cooking.zap.app.nostr.FakeNip98Signer
import cooking.zap.app.nostr.Nip98HeaderCache
import cooking.zap.app.nostr.SignerRejectedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [NoteReviewViewModel] phase machine (CHEFFY_NOTE_REVIEW_PLAN.md,
 * Phase 2). The result → phase mapping is covered exhaustively through
 * [NoteReviewViewModel.applyResult]; the request flow (signing/loading
 * split, regenerate semantics, dead-end rotation) runs end-to-end against
 * MockWebServer with the real [ZapCookingApi] and a fake signer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteReviewViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ZapCookingApi
    private lateinit var signer: FakeNip98Signer
    private lateinit var vm: NoteReviewViewModel

    private val imageUrl = "https://image.nostr.build/dish.jpg"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        api = ZapCookingApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
            nip98Cache = Nip98HeaderCache(),
        )
        signer = FakeNip98Signer()
        vm = NoteReviewViewModel()
        vm.open(noteText = "tonight's plate", noteId = "ab".repeat(32), imageUrl = imageUrl)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    // --- applyResult: every API result → phase (pure, no HTTP) ---

    @Test
    fun applyResult_success_landsDraftWithOutputAndCredits() {
        vm.applyResult(NoteReviewResult.Success("A lovely draft", 2))
        val s = vm.state.value
        assertEquals(NoteReview.Phase.DRAFT, s.phase)
        assertEquals("A lovely draft", s.draft)
        assertEquals(2, s.creditsRemaining)
        assertEquals("", s.message)
    }

    @Test
    fun applyResult_notMember_landsUpsell() {
        vm.applyResult(NoteReviewResult.NotMember)
        assertEquals(NoteReview.Phase.UPSELL, vm.state.value.phase)
    }

    @Test
    fun applyResult_membershipUnavailable_landsRetryableError_notUpsell() {
        vm.applyResult(NoteReviewResult.MembershipUnavailable)
        val s = vm.state.value
        assertEquals(NoteReview.Phase.ERROR, s.phase)
        assertEquals(NoteReview.MEMBERSHIP_UNAVAILABLE_LINE, s.message)
    }

    @Test
    fun applyResult_rateLimited_landsError() {
        vm.applyResult(NoteReviewResult.RateLimited(1800))
        val s = vm.state.value
        assertEquals(NoteReview.Phase.ERROR, s.phase)
        assertEquals(NoteReview.RATE_LIMITED_LINE, s.message)
    }

    @Test
    fun applyResult_deadEnd_landsDeadEndWithPoolLine() {
        vm.applyResult(NoteReviewResult.DeadEnd)
        val s = vm.state.value
        assertEquals(NoteReview.Phase.DEAD_END, s.phase)
        assertTrue(s.message in NoteReview.DEAD_END_LINES)
    }

    @Test
    fun applyResult_signFailed_landsErrorWithSignFailedLine() {
        vm.applyResult(NoteReviewResult.SignFailed)
        val s = vm.state.value
        assertEquals(NoteReview.Phase.ERROR, s.phase)
        assertEquals(NoteReview.SIGN_FAILED_LINE, s.message)
    }

    @Test
    fun applyResult_error_landsErrorWithMessageAndRotatingHeadline() {
        vm.applyResult(NoteReviewResult.Error("Network error"))
        val first = vm.state.value
        assertEquals(NoteReview.Phase.ERROR, first.phase)
        assertEquals("Network error", first.message)
        assertTrue(first.errorLine in Cheffy.ERROR_LINES)

        vm.applyResult(NoteReviewResult.Error("Still down"))
        val second = vm.state.value
        assertNotEquals(first.errorLine, second.errorLine)
        assertTrue(second.errorLine in Cheffy.ERROR_LINES)
    }

    @Test
    fun applyResult_consecutiveDeadEnds_rotateTheLine() {
        vm.applyResult(NoteReviewResult.DeadEnd)
        val first = vm.state.value.message
        vm.applyResult(NoteReviewResult.DeadEnd)
        val second = vm.state.value.message
        assertNotEquals(first, second)
        assertTrue(second in NoteReview.DEAD_END_LINES)
    }

    // --- Draft editing and reset ---

    @Test
    fun updateDraft_edits_andStartOverReturnsToChooseKeepingTheTarget() {
        vm.applyResult(NoteReviewResult.Success("Cheffy's words", null))
        vm.updateDraft("my own words")
        assertEquals("my own words", vm.state.value.draft)

        vm.startOver()
        val s = vm.state.value
        assertEquals(NoteReview.Phase.CHOOSE, s.phase)
        assertEquals("", s.draft)
        assertNull(s.mode)
        assertEquals(imageUrl, s.imageUrl) // session target survives
    }

    // --- End-to-end flow against MockWebServer ---

    @Test
    fun choose_walksSigningThenLoadingThenDraft() {
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"Golden crust!","mode":"comment"}"""))
        val phases = recordPhases()

        vm.choose(NoteReviewMode.COMMENT, api, signer)
        val s = awaitPhase(NoteReview.Phase.DRAFT)

        assertEquals("Golden crust!", s.draft)
        assertEquals(NoteReviewMode.COMMENT, s.mode)
        assertTrue(s.loadingLine in Cheffy.THINKING_LINES)

        // Invariant 7: signing and loading are distinct, in order.
        val log = phases.snapshot()
        val signingAt = log.indexOf(NoteReview.Phase.SIGNING)
        val loadingAt = log.indexOf(NoteReview.Phase.LOADING)
        assertTrue("expected SIGNING in $log", signingAt >= 0)
        assertTrue("expected LOADING after SIGNING in $log", loadingAt > signingAt)
    }

    @Test
    fun regenerate_reusesTheSameModeAndImage_andSkipsTheSigningPhase() {
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"Take one","mode":"recipe"}"""))
        vm.choose(NoteReviewMode.RECIPE, api, signer)
        awaitPhase(NoteReview.Phase.DRAFT)
        server.takeRequestBody() // consume request 1

        server.enqueue(jsonResponse(200, """{"ok":true,"output":"Take two","mode":"recipe"}"""))
        val phases = recordPhases()
        vm.regenerate(api, signer)
        val s = awaitPhase(NoteReview.Phase.DRAFT)

        assertEquals("Take two", s.draft)
        assertEquals(NoteReviewMode.RECIPE, s.mode)
        assertTrue(s.loadingLine in Cheffy.COOKING_LINES)

        // Same mode + same image went back to the server.
        val body = server.takeRequestBody()
        assertEquals("recipe", body["mode"]!!.jsonPrimitive.content)
        assertEquals(imageUrl, body["imageUrl"]!!.jsonPrimitive.content)

        // Regenerate goes straight to LOADING — within the NIP-98 header
        // cache TTL there is no sign to wait on.
        val log = phases.snapshot()
        assertFalse("did not expect SIGNING in $log", NoteReview.Phase.SIGNING in log)
        assertTrue("expected LOADING in $log", NoteReview.Phase.LOADING in log)
    }

    @Test
    fun consecutiveDeadEnds_endToEnd_rotateTheLine() {
        val deadEndBody = """{"ok":false,"code":"NOT_FOOD","error":"server line, never shown"}"""
        server.enqueue(jsonResponse(422, deadEndBody))
        vm.choose(NoteReviewMode.COMMENT, api, signer)
        val first = awaitPhase(NoteReview.Phase.DEAD_END).message

        vm.startOver()
        server.enqueue(jsonResponse(422, deadEndBody))
        vm.choose(NoteReviewMode.COMMENT, api, signer)
        val second = awaitPhase(NoteReview.Phase.DEAD_END).message

        assertNotEquals(first, second)
        assertTrue(first in NoteReview.DEAD_END_LINES)
        assertTrue(second in NoteReview.DEAD_END_LINES)
        // The server's line never leaks (structurally impossible — the API
        // DeadEnd carries no message — but this is the invariant to hold).
        assertFalse(first.contains("server line"))
        assertFalse(second.contains("server line"))
    }

    @Test
    fun notMember_endToEnd_landsUpsell() {
        server.enqueue(
            jsonResponse(403, """{"ok":false,"code":"NOT_MEMBER","error":"members or 21 sats"}""")
        )
        vm.choose(NoteReviewMode.COMMENT, api, signer)
        assertEquals(NoteReview.Phase.UPSELL, awaitPhase(NoteReview.Phase.UPSELL).phase)
    }

    @Test
    fun signerRejection_landsSignFailedError_withoutTouchingTheNetwork() {
        signer.failure = SignerRejectedException("declined")
        vm.choose(NoteReviewMode.COMMENT, api, signer)
        val s = awaitPhase(NoteReview.Phase.ERROR)
        assertEquals(NoteReview.SIGN_FAILED_LINE, s.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun nullSigner_isDefensiveSignFailedError() {
        vm.choose(NoteReviewMode.COMMENT, api, signer = null)
        val s = vm.state.value
        assertEquals(NoteReview.Phase.ERROR, s.phase)
        assertEquals(NoteReview.SIGN_FAILED_LINE, s.message)
        assertEquals(0, server.requestCount)
    }

    // --- helpers ---

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun MockWebServer.takeRequestBody() =
        Json.parseToJsonElement(takeRequest().body.readUtf8()).jsonObject

    private fun awaitPhase(vararg phases: NoteReview.Phase): NoteReviewViewModel.UiState =
        runBlocking {
            withTimeout(10_000) { vm.state.first { it.phase in phases } }
        }

    /** Records every phase the state passes through, in order. */
    private inner class PhaseLog {
        private val phases = mutableListOf<NoteReview.Phase>()
        private val job: Job = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.state.collect { s ->
                synchronized(phases) {
                    if (phases.lastOrNull() != s.phase) phases.add(s.phase)
                }
            }
        }

        fun snapshot(): List<NoteReview.Phase> {
            job.cancel()
            return synchronized(phases) { phases.toList() }
        }
    }

    private fun recordPhases() = PhaseLog()
}
