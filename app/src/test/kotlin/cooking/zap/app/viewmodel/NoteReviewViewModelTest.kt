package cooking.zap.app.viewmodel

import cooking.zap.app.api.NoteReviewMode
import cooking.zap.app.api.NoteReviewResult
import cooking.zap.app.api.ZapCookingApi
import cooking.zap.app.cheffy.Cheffy
import cooking.zap.app.cheffy.NoteReview
import cooking.zap.app.nostr.FakeNip98Signer
import cooking.zap.app.nostr.Nip98HeaderCache
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.SignerRejectedException
import cooking.zap.app.repo.DisclosurePreferences
import cooking.zap.app.repo.NoteReviewReplyPublisher
import kotlinx.coroutines.CompletableDeferred
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

    /** The kind-1 note being reviewed — the Phase 3 reply's parent. */
    private val parentEvent = NostrEvent(
        id = "ab".repeat(32),
        pubkey = "cd".repeat(32),
        created_at = 1_700_000_000L,
        kind = 1,
        tags = emptyList(),
        content = "tonight's plate",
        sig = "0".repeat(128),
    )

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
        vm.open(parent = parentEvent, imageUrls = listOf(imageUrl))
    }

    private fun landDraftViaChoose(
        mode: NoteReviewMode,
        output: String = "my edited reply",
        prefs: DisclosurePreferences? = null,
    ): NoteReviewViewModel.UiState {
        val wire = if (mode == NoteReviewMode.RECIPE) "recipe" else "comment"
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"$output","mode":"$wire"}"""))
        vm.choose(mode, api, signer, prefs)
        return awaitPhase(NoteReview.Phase.DRAFT)
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

    // --- Phase 3: publish state machine ---

    private val signedReply = NostrEvent(
        id = "ef".repeat(32),
        pubkey = "12".repeat(32),
        created_at = 1_700_000_100L,
        kind = 1,
        tags = emptyList(),
        content = "my edited reply",
        sig = "1".repeat(128),
    )

    private fun landDraft(text: String = "my edited reply") {
        vm.applyResult(NoteReviewResult.Success(text, null))
        assertEquals(NoteReview.Phase.DRAFT, vm.state.value.phase)
    }

    @Test
    fun post_fromDraft_publishesTheTrimmedDraft_andLandsPosted() {
        landDraft()
        vm.updateDraft("  make it mine  ")
        val publisher = FakePublisher(NoteReviewReplyPublisher.Outcome.Published(signedReply))

        vm.post(publisher, signer, clientTagEnabled = true)
        val s = awaitPhase(NoteReview.Phase.POSTED)

        assertEquals(1, publisher.publishCalls.size)
        assertEquals("make it mine", publisher.publishCalls[0].first)
        assertEquals(parentEvent.id, publisher.publishCalls[0].second.id)
        assertEquals(signedReply, s.postedEvent)
        assertNull(s.timeoutSignedEvent)
    }

    @Test
    fun post_isANoOpFromEveryNonDraftPhase() {
        val publisher = FakePublisher(NoteReviewReplyPublisher.Outcome.Published(signedReply))

        // CHOOSE (fresh open).
        vm.post(publisher, signer, clientTagEnabled = true)
        assertEquals(0, publisher.publishCalls.size)
        assertEquals(NoteReview.Phase.CHOOSE, vm.state.value.phase)

        // DEAD_END, UPSELL, ERROR.
        for (result in listOf(
            NoteReviewResult.DeadEnd,
            NoteReviewResult.NotMember,
            NoteReviewResult.Error("down"),
        )) {
            vm.applyResult(result)
            val phaseBefore = vm.state.value.phase
            vm.post(publisher, signer, clientTagEnabled = true)
            assertEquals(0, publisher.publishCalls.size)
            assertEquals(phaseBefore, vm.state.value.phase)
        }

        // POST_TIMEOUT and POSTED.
        for (outcome in listOf(
            NoteReviewReplyPublisher.Outcome.Timeout(signedReply),
            NoteReviewReplyPublisher.Outcome.Published(signedReply),
        )) {
            landDraft()
            vm.applyPublishOutcome(outcome)
            val phaseBefore = vm.state.value.phase
            vm.post(publisher, signer, clientTagEnabled = true)
            assertEquals(0, publisher.publishCalls.size)
            assertEquals(phaseBefore, vm.state.value.phase)
        }

        // SIGNING/LOADING (request in flight — no response enqueued, so it hangs).
        vm.startOver()
        vm.choose(NoteReviewMode.COMMENT, api, signer)
        val inFlight = vm.state.value.phase
        assertTrue(
            "expected an in-flight phase, got $inFlight",
            inFlight == NoteReview.Phase.SIGNING || inFlight == NoteReview.Phase.LOADING,
        )
        vm.post(publisher, signer, clientTagEnabled = true)
        assertEquals(0, publisher.publishCalls.size)

        // POSTING (publish in flight) — the double-post guard proper.
        val hanging = FakePublisher(NoteReviewReplyPublisher.Outcome.Published(signedReply), hang = true)
        vm.open(parent = parentEvent, imageUrls = listOf(imageUrl))
        landDraft()
        vm.post(hanging, signer, clientTagEnabled = true)
        assertEquals(NoteReview.Phase.POSTING, vm.state.value.phase)
        vm.post(hanging, signer, clientTagEnabled = true)
        assertEquals(1, hanging.publishCalls.size)
    }

    @Test
    fun postTimeout_retainsTheSignedEvent_andRetryRepublishesTheSameIdWithoutResigning() {
        landDraft()
        val publisher = FakePublisher(NoteReviewReplyPublisher.Outcome.Timeout(signedReply))

        vm.post(publisher, signer, clientTagEnabled = true)
        val timedOut = awaitPhase(NoteReview.Phase.POST_TIMEOUT)

        assertEquals(signedReply, timedOut.timeoutSignedEvent) // invariant 2: never discarded
        assertEquals(NoteReview.POST_TIMEOUT_LINE, timedOut.message)

        publisher.nextOutcome = NoteReviewReplyPublisher.Outcome.Published(signedReply)
        vm.retryPost(publisher)
        val posted = awaitPhase(NoteReview.Phase.POSTED)

        // Retry went through publishSigned with the EXACT retained event —
        // same id, and never through the signing publish() path again.
        assertEquals(listOf(signedReply), publisher.publishSignedCalls)
        assertEquals(signedReply.id, publisher.publishSignedCalls[0].id)
        assertEquals(1, publisher.publishCalls.size)
        assertEquals(signedReply, posted.postedEvent)
    }

    @Test
    fun retryPost_isANoOpOutsidePostTimeout() {
        landDraft()
        val publisher = FakePublisher(NoteReviewReplyPublisher.Outcome.Published(signedReply))
        vm.retryPost(publisher)
        assertEquals(0, publisher.publishSignedCalls.size)
        assertEquals(NoteReview.Phase.DRAFT, vm.state.value.phase)
    }

    @Test
    fun publishFailed_returnsToDraftWithTheDraftIntact_andTheFailedLine() {
        landDraft("carefully edited words")
        val publisher = FakePublisher(NoteReviewReplyPublisher.Outcome.Failed)

        vm.post(publisher, signer, clientTagEnabled = true)
        val s = awaitPhase(NoteReview.Phase.DRAFT)

        assertEquals("carefully edited words", s.draft)
        assertEquals(NoteReview.PUBLISH_FAILED_LINE, s.postError)
        // The draft phase is live again — a fresh post attempt is legal.
        publisher.nextOutcome = NoteReviewReplyPublisher.Outcome.Published(signedReply)
        vm.post(publisher, signer, clientTagEnabled = true)
        assertEquals(NoteReview.Phase.POSTED, awaitPhase(NoteReview.Phase.POSTED).phase)
    }

    @Test
    fun signerRejectionDuringPosting_returnsToDraftWithTheSignFailedLine() {
        landDraft("carefully edited words")
        val publisher = FakePublisher(NoteReviewReplyPublisher.Outcome.SignRejected)

        vm.post(publisher, signer, clientTagEnabled = true)
        val s = awaitPhase(NoteReview.Phase.DRAFT)

        assertEquals("carefully edited words", s.draft)
        assertEquals(NoteReview.SIGN_FAILED_LINE, s.postError)
    }

    @Test
    fun post_withBlankDraftOrNullSigner_isANoOp() {
        landDraft("")
        val publisher = FakePublisher(NoteReviewReplyPublisher.Outcome.Published(signedReply))
        vm.post(publisher, signer, clientTagEnabled = true)
        assertEquals(0, publisher.publishCalls.size)
        assertEquals(NoteReview.Phase.DRAFT, vm.state.value.phase)

        landDraft("real words")
        vm.post(publisher, signer = null, clientTagEnabled = true)
        assertEquals(0, publisher.publishCalls.size)
        assertEquals(NoteReview.Phase.DRAFT, vm.state.value.phase)
        assertEquals(NoteReview.SIGN_FAILED_LINE, vm.state.value.postError)
    }

    // --- Phase 4: disclosure footer ---

    @Test
    fun choose_seedsTheToggleFromTheStoredPerModePref() {
        val prefs = FakeDisclosurePrefs()
        prefs.store(NoteReviewMode.COMMENT, true) // member opted comment ON

        landDraftViaChoose(NoteReviewMode.COMMENT, prefs = prefs)
        assertTrue(vm.state.value.disclosureOn)
    }

    @Test
    fun choose_withNoStoredPref_usesThePerModeDefaults() {
        val prefs = FakeDisclosurePrefs()
        landDraftViaChoose(NoteReviewMode.COMMENT, prefs = prefs)
        assertFalse("comment defaults OFF — the member's own voice", vm.state.value.disclosureOn)

        vm.startOver()
        landDraftViaChoose(NoteReviewMode.RECIPE, prefs = prefs)
        assertTrue("recipe defaults ON — Cheffy's structured work product", vm.state.value.disclosureOn)
    }

    @Test
    fun toggle_persistsImmediately_andRegeneratePreservesTheInSessionToggle() {
        val prefs = FakeDisclosurePrefs()
        landDraftViaChoose(NoteReviewMode.COMMENT, prefs = prefs)
        assertFalse(vm.state.value.disclosureOn)

        vm.toggleDisclosure(prefs)
        assertTrue(vm.state.value.disclosureOn)
        assertEquals(listOf(NoteReviewMode.COMMENT to true), prefs.saves) // persisted on the spot

        // The stored pref now DISAGREES with the session (someone flipped
        // it back) — regenerate must keep the in-session toggle, not
        // re-seed (seed-only-from-Choose).
        prefs.store(NoteReviewMode.COMMENT, false)
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"take two","mode":"comment"}"""))
        vm.regenerate(api, signer)
        awaitPhase(NoteReview.Phase.DRAFT)
        assertTrue(vm.state.value.disclosureOn)
    }

    @Test
    fun post_appendsTheFooterOnlyAtTheHandoff_neverIntoTheEditableDraft() {
        landDraftViaChoose(NoteReviewMode.RECIPE) // recipe → disclosure defaults ON
        assertTrue(vm.state.value.disclosureOn)
        vm.updateDraft("My take on it  ") // member edit with trailing whitespace

        val publisher = FakePublisher(NoteReviewReplyPublisher.Outcome.Published(signedReply))
        vm.post(publisher, signer, clientTagEnabled = true)
        awaitPhase(NoteReview.Phase.POSTED)

        assertEquals(
            "My take on it\n\n" + NoteReview.DISCLOSURE_FOOTER,
            publisher.publishCalls[0].first,
        )
        // The editable field never contained the footer.
        assertEquals("My take on it  ", vm.state.value.draft)
        assertFalse(vm.state.value.draft.contains(NoteReview.DISCLOSURE_FOOTER))
    }

    @Test
    fun post_withToggleOff_sendsTheDraftAlone() {
        landDraftViaChoose(NoteReviewMode.COMMENT) // comment → disclosure defaults OFF
        assertFalse(vm.state.value.disclosureOn)

        val publisher = FakePublisher(NoteReviewReplyPublisher.Outcome.Published(signedReply))
        vm.post(publisher, signer, clientTagEnabled = true)
        awaitPhase(NoteReview.Phase.POSTED)

        assertEquals("my edited reply", publisher.publishCalls[0].first)
        assertFalse(publisher.publishCalls[0].first.contains(NoteReview.DISCLOSURE_FOOTER))
    }

    @Test
    fun postTimeoutRetry_neverReappliesTheFooter() {
        landDraftViaChoose(NoteReviewMode.RECIPE) // disclosure ON
        val publisher = FakePublisher(NoteReviewReplyPublisher.Outcome.Timeout(signedReply))

        vm.post(publisher, signer, clientTagEnabled = true)
        awaitPhase(NoteReview.Phase.POST_TIMEOUT)

        // The footer was baked into the content exactly once at hand-off…
        val sentContent = publisher.publishCalls[0].first
        assertEquals(1, Regex(Regex.escape(NoteReview.DISCLOSURE_FOOTER)).findAll(sentContent).count())

        // …and the retry path re-broadcasts the retained SIGNED event as
        // is: publishSigned takes no content, the signing publish() path
        // is never re-entered, so the footer function cannot run again.
        publisher.nextOutcome = NoteReviewReplyPublisher.Outcome.Published(signedReply)
        vm.retryPost(publisher)
        awaitPhase(NoteReview.Phase.POSTED)
        assertEquals(1, publisher.publishCalls.size)
        assertEquals(listOf(signedReply), publisher.publishSignedCalls)
    }

    // --- Phase 4: multi-image picker ---

    private val threeImages = listOf(
        "https://image.nostr.build/one.jpg",
        "https://image.nostr.build/two.jpg",
        "https://image.nostr.build/three.jpg",
    )

    @Test
    fun picker_defaultsToTheFirstImage_andTheRequestCarriesIt() {
        vm.open(parent = parentEvent, imageUrls = threeImages)
        assertEquals(0, vm.state.value.selectedImageIndex)

        landDraftViaChoose(NoteReviewMode.COMMENT)
        assertEquals(threeImages[0], server.takeRequestBody()["imageUrl"]!!.jsonPrimitive.content)
    }

    @Test
    fun selectingAnImageWithADraft_onlyArmsTheNextRequest() {
        vm.open(parent = parentEvent, imageUrls = threeImages)
        landDraftViaChoose(NoteReviewMode.COMMENT, output = "first draft")
        assertEquals(1, server.requestCount)

        // Web parity (CheffyNoteReview.svelte:405 — selection assigns the
        // index, no run()): no request fires, the draft stays.
        vm.selectImage(2)
        assertEquals(1, server.requestCount)
        assertEquals(NoteReview.Phase.DRAFT, vm.state.value.phase)
        assertEquals("first draft", vm.state.value.draft)

        // The NEXT regenerate sends exactly the selected URL.
        server.takeRequestBody() // consume request 1
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"second draft","mode":"comment"}"""))
        vm.regenerate(api, signer)
        awaitPhase(NoteReview.Phase.DRAFT)
        assertEquals(threeImages[2], server.takeRequestBody()["imageUrl"]!!.jsonPrimitive.content)
        // Selection persisted across the regenerate.
        assertEquals(2, vm.state.value.selectedImageIndex)
    }

    @Test
    fun pickerSelection_survivesStartOver_andResetsOnOpen() {
        vm.open(parent = parentEvent, imageUrls = threeImages)
        vm.selectImage(1)

        vm.startOver()
        assertEquals(1, vm.state.value.selectedImageIndex)

        vm.open(parent = parentEvent, imageUrls = threeImages)
        assertEquals(0, vm.state.value.selectedImageIndex)
    }

    @Test
    fun selectImage_ignoresOutOfRangeIndices() {
        vm.open(parent = parentEvent, imageUrls = threeImages)
        vm.selectImage(9)
        assertEquals(0, vm.state.value.selectedImageIndex)
        vm.selectImage(-1)
        assertEquals(0, vm.state.value.selectedImageIndex)
    }

    // --- Phase 5a: credit invoice + status poll ---

    private val farFutureMs = 4_102_444_800_000L // 2100 — never expires in a test
    private fun invoiceResponse(bolt11: String = "lnbc210n1cheffy", expiresAt: Long = farFutureMs) =
        jsonResponse(200, """{"ok":true,"invoiceId":"rr-1","bolt11":"$bolt11","expiresAt":$expiresAt}""")

    private fun statusResponse(status: String, balance: Int = 0) =
        jsonResponse(200, """{"ok":true,"status":"$status","balance":$balance}""")

    private fun landUpsell(pollIntervalMs: Long, inAppPayTimeoutMs: Long = 30_000) {
        vm = NoteReviewViewModel(pollIntervalMs = pollIntervalMs, inAppPayTimeoutMs = inAppPayTimeoutMs)
        vm.open(parent = parentEvent, imageUrls = listOf(imageUrl))
        server.enqueue(jsonResponse(403, """{"ok":false,"code":"NOT_MEMBER","error":"members or sats"}"""))
        vm.choose(NoteReviewMode.COMMENT, api, signer)
        awaitPhase(NoteReview.Phase.UPSELL)
    }

    private fun awaitState(predicate: (NoteReviewViewModel.UiState) -> Boolean): NoteReviewViewModel.UiState =
        runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }

    private fun awaitCondition(check: () -> Boolean) = runBlocking {
        withTimeout(10_000) {
            while (!check()) kotlinx.coroutines.delay(20)
        }
    }

    private fun awaitRequests(count: Int) = runBlocking {
        withTimeout(10_000) {
            while (server.requestCount < count) kotlinx.coroutines.delay(20)
        }
    }

    private fun drainRequestPaths(): List<String> {
        val paths = mutableListOf<String>()
        while (true) {
            val recorded = server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
            paths.add(recorded.path ?: "")
        }
        return paths
    }

    @Test
    fun buy_startsThePollTheMomentTheInvoiceExists_beforeAnyPaymentAction() {
        landUpsell(pollIntervalMs = 60_000) // one immediate check, then parked
        server.enqueue(invoiceResponse())
        server.enqueue(statusResponse("pending"))

        vm.startPayment(api, signer)
        awaitPhase(NoteReview.Phase.PAYING)

        // Without ANY payment interaction, the status poll has already
        // fired: choose + invoice mint + first status check.
        awaitRequests(3)
        val paths = drainRequestPaths()
        assertTrue(paths[1].startsWith("/api/zappy/note-review/credit-invoice"))
        assertTrue(paths[2].startsWith("/api/zappy/note-review/credit-status"))
        assertEquals("lnbc210n1cheffy", vm.state.value.invoice?.bolt11)
    }

    @Test
    fun buyWhileALiveInvoiceExists_rePresentsIt_neverMintsASecond() {
        landUpsell(pollIntervalMs = 60_000)
        server.enqueue(invoiceResponse(bolt11 = "lnbc-first"))
        server.enqueue(statusResponse("pending"))

        vm.startPayment(api, signer)
        awaitPhase(NoteReview.Phase.PAYING)
        awaitRequests(3)

        vm.backFromPaying() // poll stops, invoice stays live
        assertEquals(NoteReview.Phase.UPSELL, vm.state.value.phase)
        assertEquals("lnbc-first", vm.state.value.invoice?.bolt11)

        // Buy again: NO invoice response is queued — only a status
        // response. A second mint would hang; the reuse path polls the
        // SAME invoice instead (invariant 5).
        server.enqueue(statusResponse("pending"))
        vm.startPayment(api, signer)
        awaitPhase(NoteReview.Phase.PAYING)
        awaitRequests(4)

        assertEquals("lnbc-first", vm.state.value.invoice?.bolt11)
        val paths = drainRequestPaths()
        assertEquals(1, paths.count { it.startsWith("/api/zappy/note-review/credit-invoice") })
        assertTrue(paths.last().startsWith("/api/zappy/note-review/credit-status"))
    }

    @Test
    fun expiredObservation_returnsToUpsell_andTheNextBuyMintsFresh() {
        landUpsell(pollIntervalMs = 60_000)
        server.enqueue(invoiceResponse(bolt11 = "lnbc-old"))
        server.enqueue(statusResponse("expired"))

        vm.startPayment(api, signer)
        val s = awaitPhase(NoteReview.Phase.UPSELL)

        assertEquals(NoteReview.INVOICE_EXPIRED_LINE, s.payError)
        assertNull(s.invoice) // dead invoice cleared — fresh mint is legal

        server.enqueue(invoiceResponse(bolt11 = "lnbc-new"))
        server.enqueue(statusResponse("pending"))
        vm.startPayment(api, signer)
        awaitPhase(NoteReview.Phase.PAYING)
        awaitRequests(5)
        assertEquals("lnbc-new", vm.state.value.invoice?.bolt11)
        assertEquals(2, drainRequestPaths().count { it.startsWith("/api/zappy/note-review/credit-invoice") })
    }

    @Test
    fun paidObservation_creditsAndAutoRunsThePendingMode() {
        // Web parity: beginPolling's credited branch runs `run(mode)` —
        // "Straight into drafting — they paid for this draft."
        landUpsell(pollIntervalMs = 50)
        server.enqueue(invoiceResponse())
        server.enqueue(statusResponse("pending"))
        server.enqueue(statusResponse("paid", balance = 1))
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"Paid draft","mode":"comment"}"""))

        vm.startPayment(api, signer)
        val s = awaitPhase(NoteReview.Phase.DRAFT)

        assertEquals("Paid draft", s.draft)
        assertEquals(NoteReviewMode.COMMENT, s.mode) // the pending mode ran
        assertEquals(1, s.creditsRemaining)          // balance acknowledged
        assertNull(s.invoice)                        // settled invoice cleared
    }

    @Test
    fun transientPollFailure_keepsPollingUntilTheServerDecides() {
        landUpsell(pollIntervalMs = 50)
        server.enqueue(invoiceResponse())
        server.enqueue(jsonResponse(503, """{"ok":false,"error":"Payment check hiccuped — trying again shortly."}"""))
        server.enqueue(statusResponse("pending"))
        server.enqueue(statusResponse("paid", balance = 1))
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"Paid draft","mode":"comment"}"""))

        vm.startPayment(api, signer)
        val s = awaitPhase(NoteReview.Phase.DRAFT)
        assertEquals(1, s.creditsRemaining)
    }

    @Test
    fun sheetClose_cancelsAHangingPoll_withoutLeakingTicks() {
        landUpsell(pollIntervalMs = 50)
        server.enqueue(invoiceResponse())
        // NO status response queued: the poll's first check hangs on the
        // wire — the worst case for a leak.
        vm.startPayment(api, signer)
        awaitPhase(NoteReview.Phase.PAYING)
        awaitRequests(3) // choose + invoice + the hanging status check

        vm.onSheetClosed()

        // Release the hanging check; a live loop would tick again within
        // 50ms — a cancelled one processes the response and stops.
        server.enqueue(statusResponse("pending"))
        Thread.sleep(400)
        assertEquals(3, server.requestCount)
        // And the observed status never mutated the closed session.
        assertEquals(NoteReview.Phase.PAYING, vm.state.value.phase)
    }

    @Test
    fun buy_isANoOpOutsideUpsell() {
        vm.startPayment(api, signer) // fresh CHOOSE
        assertEquals(NoteReview.Phase.CHOOSE, vm.state.value.phase)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun invoiceMintFailure_fallsBackToUpsellWithTheServerLine() {
        landUpsell(pollIntervalMs = 60_000)
        server.enqueue(jsonResponse(429, """{"ok":false,"code":"RATE_LIMITED","error":"Too many invoices — give the last one a moment.","retryAfter":60}"""))

        vm.startPayment(api, signer)
        val s = awaitPhase(NoteReview.Phase.UPSELL)
        assertEquals("Too many invoices — give the last one a moment.", s.payError)
        assertNull(s.invoice)
    }

    @Test
    fun draftResponsesUpdateTheVisibleBalance_andNotMemberIsAuthoritative() {
        vm.applyResult(NoteReviewResult.Success("draft one", 4))
        assertEquals(4, vm.state.value.creditsRemaining)

        // A follow-up without the additive field keeps the last balance…
        vm.applyResult(NoteReviewResult.Success("draft two", null))
        assertEquals(4, vm.state.value.creditsRemaining)

        // …and a NotMember while the balance was believed positive still
        // returns to the upsell card — the server is authoritative.
        vm.applyResult(NoteReviewResult.NotMember)
        assertEquals(NoteReview.Phase.UPSELL, vm.state.value.phase)
    }

    // --- Phase 5b: in-app wallet routing ---

    @Test
    fun inAppSuccess_showsWalletWaiting_andThePollStillDoesTheCrediting() {
        landUpsell(pollIntervalMs = 50, inAppPayTimeoutMs = 5_000)
        val wallet = FakeWalletProvider { Result.success("preimage") }
        server.enqueue(invoiceResponse(bolt11 = "lnbc-wallet"))
        server.enqueue(statusResponse("pending"))
        server.enqueue(statusResponse("paid", balance = 1))
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"Paid draft","mode":"comment"}"""))

        vm.startPayment(api, signer, prefs = null, wallet = wallet)

        // Distinct wallet-waiting state — never the QR view.
        val paying = awaitState { it.inAppPayAttempted }
        assertFalse(paying.inAppPayFailed)

        // Advisory success — the credit still arrives via the poll.
        val s = awaitPhase(NoteReview.Phase.DRAFT)
        assertEquals(1, s.creditsRemaining)
        assertEquals(listOf("lnbc-wallet"), wallet.paidInvoices)
    }

    @Test
    fun inAppTimeout_revealsTheFallbackWithTheSameBolt11_neverASecondInvoice() {
        landUpsell(pollIntervalMs = 60_000, inAppPayTimeoutMs = 150)
        val wallet = FakeWalletProvider {
            kotlinx.coroutines.delay(5_000) // never answers inside the window
            Result.success("late")
        }
        server.enqueue(invoiceResponse(bolt11 = "lnbc-slow"))
        server.enqueue(statusResponse("pending"))

        vm.startPayment(api, signer, prefs = null, wallet = wallet)
        val s = awaitState { it.inAppPayFailed }

        assertEquals(NoteReview.Phase.PAYING, s.phase)
        assertEquals("lnbc-slow", s.invoice?.bolt11) // SAME invoice for the QR fallback
        awaitCondition { server.requestCount >= 3 }
        assertEquals(1, drainRequestPaths().count { it.startsWith("/api/zappy/note-review/credit-invoice") })
    }

    @Test
    fun inAppThrow_revealsTheFallback() {
        landUpsell(pollIntervalMs = 60_000, inAppPayTimeoutMs = 5_000)
        val wallet = FakeWalletProvider { throw IllegalStateException("wallet exploded") }
        server.enqueue(invoiceResponse(bolt11 = "lnbc-boom"))
        server.enqueue(statusResponse("pending"))

        vm.startPayment(api, signer, prefs = null, wallet = wallet)
        val s = awaitState { it.inAppPayFailed }
        assertEquals("lnbc-boom", s.invoice?.bolt11)
    }

    @Test
    fun noInAppWallet_goesStraightToTheExternalAffordances() {
        landUpsell(pollIntervalMs = 60_000)
        server.enqueue(invoiceResponse())
        server.enqueue(statusResponse("pending"))

        vm.startPayment(api, signer, prefs = null, wallet = null)
        val s = awaitState { it.phase == NoteReview.Phase.PAYING && it.invoice != null }
        assertFalse(s.inAppPayAttempted)
        assertFalse(s.inAppPayFailed)
    }

    @Test
    fun advisoryFailure_doesNotBlockTheCredit_thePollIsAuthoritative() {
        // The wallet says the payment failed, but the server observed it
        // paid (e.g. it settled through another rail) — crediting comes
        // solely from the poll.
        landUpsell(pollIntervalMs = 50, inAppPayTimeoutMs = 5_000)
        val wallet = FakeWalletProvider { Result.failure(Exception("no route")) }
        server.enqueue(invoiceResponse())
        server.enqueue(statusResponse("pending"))
        server.enqueue(statusResponse("paid", balance = 1))
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"Paid draft","mode":"comment"}"""))

        vm.startPayment(api, signer, prefs = null, wallet = wallet)
        val s = awaitPhase(NoteReview.Phase.DRAFT)
        assertEquals(1, s.creditsRemaining)
    }

    @Test
    fun fastSettle_leavesNoTimeoutBehind() {
        // The 30s race must clean up when the wallet answers fast — no
        // late flip to the fallback after the window would have elapsed.
        landUpsell(pollIntervalMs = 60_000, inAppPayTimeoutMs = 150)
        val wallet = FakeWalletProvider { Result.success("instant") }
        server.enqueue(invoiceResponse())
        server.enqueue(statusResponse("pending"))

        vm.startPayment(api, signer, prefs = null, wallet = wallet)
        awaitState { it.inAppPayAttempted }
        awaitCondition { wallet.paidInvoices.isNotEmpty() }

        Thread.sleep(400) // well past the 150ms window
        val s = vm.state.value
        assertEquals(NoteReview.Phase.PAYING, s.phase)
        assertFalse("a leaked timeout flipped the state", s.inAppPayFailed)
        assertTrue(s.inAppPayAttempted)
    }

    // --- Phase 5b: pending-invoice resume ---

    private val storedInvoice = cooking.zap.app.repo.StoredInvoice(
        invoiceId = "rr-stored",
        bolt11 = "lnbc-stored",
        expiresAtMillis = farFutureMs,
    )

    @Test
    fun resume_paid_creditsWithBannerAndClearsTheStore() {
        val store = FakeInvoiceStore(stored = storedInvoice)
        server.enqueue(statusResponse("paid", balance = 2))

        vm.open(parent = parentEvent, imageUrls = listOf(imageUrl), api = api, signer = signer, prefs = store)
        val s = awaitState { it.resumeAck }

        assertEquals(2, s.creditsRemaining)
        assertEquals(NoteReview.Phase.CHOOSE, s.phase) // never gated the sheet
        assertNull(store.stored)
    }

    @Test
    fun resume_expired_clearsSilently() {
        val store = FakeInvoiceStore(stored = storedInvoice)
        server.enqueue(statusResponse("expired"))

        vm.open(parent = parentEvent, imageUrls = listOf(imageUrl), api = api, signer = signer, prefs = store)
        awaitCondition { store.stored == null }

        val s = vm.state.value
        assertFalse(s.resumeAck)
        assertNull(s.creditsRemaining)
    }

    @Test
    fun resume_checkFailure_keepsThePotentiallyPaidInvoice() {
        val store = FakeInvoiceStore(stored = storedInvoice)
        server.enqueue(jsonResponse(503, """{"ok":false,"error":"Payment check hiccuped — trying again shortly."}"""))

        vm.open(parent = parentEvent, imageUrls = listOf(imageUrl), api = api, signer = signer, prefs = store)
        awaitCondition { server.requestCount >= 1 }
        Thread.sleep(150) // let the resume coroutine finish deciding

        assertEquals(storedInvoice, store.stored)
        assertFalse(vm.state.value.resumeAck)
    }

    @Test
    fun resume_pending_isReusedAtBuyTime_neverTwoInvoices() {
        val store = FakeInvoiceStore(stored = storedInvoice)
        vm = NoteReviewViewModel(pollIntervalMs = 60_000)
        server.enqueue(statusResponse("pending")) // resume check keeps it

        vm.open(parent = parentEvent, imageUrls = listOf(imageUrl), api = api, signer = signer, prefs = store)
        awaitCondition { server.requestCount >= 1 }
        assertEquals(storedInvoice, store.stored)

        server.enqueue(jsonResponse(403, """{"ok":false,"code":"NOT_MEMBER","error":"members or sats"}"""))
        vm.choose(NoteReviewMode.COMMENT, api, signer)
        awaitPhase(NoteReview.Phase.UPSELL)

        // Buy: NO invoice response queued — a second mint would hang. The
        // buy-time resolution reuses the STORED invoice (with its bolt11).
        server.enqueue(statusResponse("pending")) // buy-time resolution
        server.enqueue(statusResponse("pending")) // poll's immediate check
        vm.startPayment(api, signer, prefs = store, wallet = null)
        val s = awaitState { it.phase == NoteReview.Phase.PAYING && it.invoice != null }

        assertEquals("lnbc-stored", s.invoice?.bolt11)
        assertEquals(storedInvoice, store.stored) // still persisted, not overwritten
        assertEquals(0, drainRequestPaths().count { it.startsWith("/api/zappy/note-review/credit-invoice") })
    }

    @Test
    fun mintPersistsTheInvoice_andThePollClearsItOnPaid() {
        landUpsell(pollIntervalMs = 50)
        val store = FakeInvoiceStore()
        server.enqueue(invoiceResponse(bolt11 = "lnbc-mint"))
        server.enqueue(statusResponse("pending"))
        server.enqueue(statusResponse("paid", balance = 1))
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"Paid draft","mode":"comment"}"""))

        vm.startPayment(api, signer, prefs = store, wallet = null)
        awaitCondition { store.stored?.bolt11 == "lnbc-mint" } // persisted on mint

        awaitPhase(NoteReview.Phase.DRAFT)
        assertNull(store.stored) // cleared the moment the poll observed paid
    }

    @Test
    fun pollObservedExpiry_clearsThePersistedInvoice() {
        landUpsell(pollIntervalMs = 60_000)
        val store = FakeInvoiceStore()
        server.enqueue(invoiceResponse())
        server.enqueue(statusResponse("expired"))

        vm.startPayment(api, signer, prefs = store, wallet = null)
        val s = awaitPhase(NoteReview.Phase.UPSELL)

        assertEquals(NoteReview.INVOICE_EXPIRED_LINE, s.payError)
        assertNull(store.stored)
    }

    private class FakeWalletProvider(
        private val payBehavior: suspend (String) -> Result<String>,
    ) : cooking.zap.app.repo.WalletProvider {
        val paidInvoices = java.util.concurrent.CopyOnWriteArrayList<String>()
        override val balance = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
        override val isConnected = kotlinx.coroutines.flow.MutableStateFlow(false)
        override val statusLog = kotlinx.coroutines.flow.MutableSharedFlow<String>()
        override val paymentReceived = kotlinx.coroutines.flow.MutableSharedFlow<Long>()
        override val transactionsChanged = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        override fun hasConnection(): Boolean = true
        override fun connect() {}
        override fun disconnect() {}
        override suspend fun fetchBalance(): Result<Long> = Result.success(0)
        override suspend fun payInvoice(bolt11: String): Result<String> {
            paidInvoices.add(bolt11)
            return payBehavior(bolt11)
        }
        override suspend fun makeInvoice(amountMsats: Long, description: String, expirySecs: Int): Result<String> =
            Result.failure(UnsupportedOperationException())
        override suspend fun listTransactions(limit: Int, offset: Int): Result<List<cooking.zap.app.repo.WalletTransaction>> =
            Result.success(emptyList())
    }

    private class FakeInvoiceStore(
        var stored: cooking.zap.app.repo.StoredInvoice? = null,
    ) : cooking.zap.app.repo.PendingInvoiceStore {
        override fun storePendingInvoice(invoice: cooking.zap.app.repo.StoredInvoice) {
            stored = invoice
        }
        override fun loadPendingInvoice(): cooking.zap.app.repo.StoredInvoice? = stored
        override fun clearPendingInvoice() {
            stored = null
        }
    }

    private class FakeDisclosurePrefs : DisclosurePreferences {
        private val stored = mutableMapOf<NoteReviewMode, Boolean>()
        val saves = mutableListOf<Pair<NoteReviewMode, Boolean>>()

        fun store(mode: NoteReviewMode, enabled: Boolean) {
            stored[mode] = enabled
        }

        override fun isDisclosureEnabled(mode: NoteReviewMode): Boolean =
            stored[mode] ?: NoteReview.defaultDisclosure(mode)

        override fun setDisclosureEnabled(mode: NoteReviewMode, enabled: Boolean) {
            saves.add(mode to enabled)
            stored[mode] = enabled
        }
    }

    private class FakePublisher(
        var nextOutcome: NoteReviewReplyPublisher.Outcome,
        private val hang: Boolean = false,
    ) : NoteReviewReplyPublisher {
        val publishCalls = mutableListOf<Pair<String, NostrEvent>>()
        val publishSignedCalls = mutableListOf<NostrEvent>()
        private val never = CompletableDeferred<Unit>()

        override suspend fun publish(
            content: String,
            parent: NostrEvent,
            signer: NostrSigner,
            clientTagEnabled: Boolean,
        ): NoteReviewReplyPublisher.Outcome {
            publishCalls.add(content to parent)
            if (hang) never.await()
            return nextOutcome
        }

        override suspend fun publishSigned(
            event: NostrEvent,
            parent: NostrEvent,
        ): NoteReviewReplyPublisher.Outcome {
            publishSignedCalls.add(event)
            if (hang) never.await()
            return nextOutcome
        }
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
