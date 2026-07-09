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
