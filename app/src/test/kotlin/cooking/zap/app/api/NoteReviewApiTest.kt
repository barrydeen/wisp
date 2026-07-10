package cooking.zap.app.api

import cooking.zap.app.nostr.FakeNip98Signer
import cooking.zap.app.nostr.Nip98
import cooking.zap.app.nostr.Nip98HeaderCache
import cooking.zap.app.nostr.SignerRejectedException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Cheffy Note Review API layer (CHEFFY_NOTE_REVIEW_PLAN.md, Phase 1).
 *
 * Pure mapping tests exercise the response-code → sealed-type contract
 * against the server's real body shapes
 * (frontend `src/routes/api/zappy/note-review/…`). MockWebServer tests
 * pin the HTTP-level invariants: noteText trim+cap BEFORE signing,
 * payload-hash == exact body bytes, credit-status `?id=` on the URL but
 * not in the `u` tag, header reuse across polls, and the
 * 401-on-cached-header re-sign-and-retry (Phase 0 decision 1).
 */
class NoteReviewApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ZapCookingApi
    private lateinit var signer: FakeNip98Signer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
        signer = FakeNip98Signer()
        api = ZapCookingApi(
            baseUrl = baseUrl,
            client = OkHttpClient(),
            nip98Cache = Nip98HeaderCache(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- Response-code → sealed-type mapping (pure) ---

    @Test
    fun map_success_withCreditsRemaining() {
        val result = ZapCookingApi.mapNoteReviewResponse(
            200, """{"ok":true,"output":"Lovely crumb!","mode":"comment","creditsRemaining":2}"""
        )
        assertEquals(NoteReviewResult.Success("Lovely crumb!", 2), result)
    }

    @Test
    fun map_success_memberPath_hasNoCredits() {
        val result = ZapCookingApi.mapNoteReviewResponse(
            200, """{"ok":true,"output":"Draft","mode":"recipe"}"""
        )
        assertEquals(NoteReviewResult.Success("Draft", null), result)
    }

    @Test
    fun map_403NotMember() {
        val result = ZapCookingApi.mapNoteReviewResponse(
            403, """{"ok":false,"code":"NOT_MEMBER","error":"Cheffy photo review is available to Pro Kitchen members — or 21 sats a draft."}"""
        )
        assertEquals(NoteReviewResult.NotMember, result)
    }

    @Test
    fun map_503MembershipUnavailable_isNotAnUpsell() {
        val result = ZapCookingApi.mapNoteReviewResponse(
            503, """{"ok":false,"code":"MEMBERSHIP_UNAVAILABLE","error":"Cheffy can't check your membership right now."}"""
        )
        assertEquals(NoteReviewResult.MembershipUnavailable, result)
    }

    @Test
    fun map_429RateLimited_carriesRetryAfter() {
        val result = ZapCookingApi.mapNoteReviewResponse(
            429, """{"ok":false,"code":"RATE_LIMITED","error":"Cheffy needs a breather.","retryAfter":1800}"""
        )
        assertEquals(NoteReviewResult.RateLimited(1800), result)
    }

    @Test
    fun map_notFoodAndImageUnreadable_collapseToDeadEnd() {
        // Collapsed by design: the UI treats them identically and must
        // never echo the server's line (NOT_FOOD fires for CDN fallback
        // images on dead links too).
        assertEquals(
            NoteReviewResult.DeadEnd,
            ZapCookingApi.mapNoteReviewResponse(
                422, """{"ok":false,"code":"NOT_FOOD","error":"That doesn't look like food."}"""
            )
        )
        assertEquals(
            NoteReviewResult.DeadEnd,
            ZapCookingApi.mapNoteReviewResponse(
                422, """{"ok":false,"code":"IMAGE_UNREADABLE","error":"Cheffy couldn't get a good look at that photo."}"""
            )
        )
    }

    @Test
    fun map_statusCodeFallback_whenBodyIsUnparseable() {
        assertEquals(NoteReviewResult.NotMember, ZapCookingApi.mapNoteReviewResponse(403, "<html>"))
        assertEquals(NoteReviewResult.MembershipUnavailable, ZapCookingApi.mapNoteReviewResponse(503, ""))
        assertEquals(NoteReviewResult.RateLimited(null), ZapCookingApi.mapNoteReviewResponse(429, "nope"))
        assertEquals(NoteReviewResult.DeadEnd, ZapCookingApi.mapNoteReviewResponse(422, "nope"))
    }

    @Test
    fun map_500_isError_withServerLine() {
        val result = ZapCookingApi.mapNoteReviewResponse(
            500, """{"ok":false,"error":"Cheffy could not finish that one. Please try again."}"""
        )
        assertEquals(NoteReviewResult.Error("Cheffy could not finish that one. Please try again."), result)
    }

    @Test
    fun map_okTrueButEmptyOutput_isError() {
        val result = ZapCookingApi.mapNoteReviewResponse(200, """{"ok":true,"output":"  "}""")
        assertTrue(result is NoteReviewResult.Error)
    }

    @Test
    fun map_creditInvoice_success_expiresAtIsMsEpoch() {
        val result = ZapCookingApi.mapCreditInvoiceResponse(
            200, """{"ok":true,"invoiceId":"rr-123","bolt11":"lnbc210n1...","expiresAt":1767000000000}"""
        )
        assertEquals(CreditInvoiceResult.Success("rr-123", "lnbc210n1...", 1_767_000_000_000L), result)
    }

    @Test
    fun map_creditInvoice_missingBolt11_isError() {
        val result = ZapCookingApi.mapCreditInvoiceResponse(
            200, """{"ok":true,"invoiceId":"rr-123","expiresAt":1767000000000}"""
        )
        assertTrue(result is CreditInvoiceResult.Error)
    }

    @Test
    fun map_creditInvoice_409CreditsNotNeeded_isErrorWithServerLine() {
        val result = ZapCookingApi.mapCreditInvoiceResponse(
            409, """{"ok":false,"code":"CREDITS_NOT_NEEDED","error":"Drafting is currently free — no credits needed."}"""
        )
        assertEquals(
            CreditInvoiceResult.Error("Drafting is currently free — no credits needed."),
            result
        )
    }

    @Test
    fun map_creditStatus_allWireStatuses() {
        assertEquals(
            CreditStatusResult.Success(CreditStatus.PAID, 3),
            ZapCookingApi.mapCreditStatusResponse(200, """{"ok":true,"status":"paid","balance":3}""")
        )
        assertEquals(
            CreditStatusResult.Success(CreditStatus.PENDING, 0),
            ZapCookingApi.mapCreditStatusResponse(200, """{"ok":true,"status":"pending","balance":0}""")
        )
        assertEquals(
            CreditStatusResult.Success(CreditStatus.EXPIRED, 1),
            ZapCookingApi.mapCreditStatusResponse(200, """{"ok":true,"status":"expired","balance":1}""")
        )
    }

    @Test
    fun map_creditStatus_unknownStatus_isError_notExpired() {
        // Invariant 6: only a value the client understands may drive the
        // pending-invoice state machine — unknown must NOT read as expired.
        val result = ZapCookingApi.mapCreditStatusResponse(200, """{"ok":true,"status":"settling","balance":0}""")
        assertTrue(result is CreditStatusResult.Error)
    }

    @Test
    fun map_creditStatus_503CheckHiccup_isError() {
        val result = ZapCookingApi.mapCreditStatusResponse(
            503, """{"ok":false,"error":"Payment check hiccuped — trying again shortly."}"""
        )
        assertTrue(result is CreditStatusResult.Error)
    }

    // --- HTTP-level invariants (MockWebServer) ---

    @Test
    fun noteText_isTrimmedAndCappedBeforeSigning_andPayloadHashMatchesSentBytes() = runBlocking {
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"Draft","mode":"comment"}"""))

        val longNote = "  " + "x".repeat(1500) + "  "
        val result = api.requestNoteReview(
            imageUrl = "https://image.nostr.build/abc.jpg",
            mode = NoteReviewMode.COMMENT,
            noteText = longNote,
            noteId = "ab".repeat(32),
            signer = signer,
        )
        assertEquals(NoteReviewResult.Success("Draft", null), result)

        val recorded = server.takeRequest()
        assertEquals("/api/zappy/note-review", recorded.path)
        assertEquals("POST", recorded.method)

        // The body's noteText is the trimmed, 1000-char cap — never the raw input.
        val bodyBytes = recorded.body.readByteArray()
        val sentBody = Json.parseToJsonElement(String(bodyBytes, Charsets.UTF_8)).jsonObject
        val sentNote = sentBody["noteText"]!!.jsonPrimitive.content
        assertEquals(1000, sentNote.length)
        assertEquals("x".repeat(1000), sentNote)
        assertEquals("comment", sentBody["mode"]!!.jsonPrimitive.content)

        // Payload-hash/body-bytes identity: the signed payload tag is the
        // sha256 of the EXACT bytes that went on the wire.
        val authEvent = decodeAuthEvent(recorded.getHeader("Authorization")!!)
        assertEquals(Nip98.sha256Hex(bodyBytes), tagValue(authEvent, "payload"))
        assertEquals("$baseUrl/api/zappy/note-review", tagValue(authEvent, "u"))
        assertEquals("POST", tagValue(authEvent, "method"))
        assertEquals(27235, authEvent["kind"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun blankNoteText_isOmittedFromTheBody() = runBlocking {
        server.enqueue(jsonResponse(200, """{"ok":true,"output":"Draft","mode":"comment"}"""))

        api.requestNoteReview(
            imageUrl = "https://image.nostr.build/abc.jpg",
            mode = NoteReviewMode.COMMENT,
            noteText = "   ",
            signer = signer,
        )

        val sentBody = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNull(sentBody["noteText"])
        assertNull(sentBody["noteId"])
    }

    @Test
    fun creditInvoice_bodyIsExactlyEmptyJsonObject() = runBlocking {
        server.enqueue(
            jsonResponse(200, """{"ok":true,"invoiceId":"rr-1","bolt11":"lnbc1...","expiresAt":1767000000000}""")
        )

        val result = api.requestCreditInvoice(signer)
        assertEquals(CreditInvoiceResult.Success("rr-1", "lnbc1...", 1_767_000_000_000L), result)

        val recorded = server.takeRequest()
        assertEquals("/api/zappy/note-review/credit-invoice", recorded.path)
        val bodyBytes = recorded.body.readByteArray()
        assertEquals("{}", String(bodyBytes, Charsets.UTF_8))
        // And the header binds those exact two bytes.
        val authEvent = decodeAuthEvent(recorded.getHeader("Authorization")!!)
        assertEquals(Nip98.sha256Hex(bodyBytes), tagValue(authEvent, "payload"))
    }

    @Test
    fun creditStatus_urlCarriesQuery_uTagExcludesIt_andHeaderIsSharedAcrossIds() = runBlocking {
        server.enqueue(jsonResponse(200, """{"ok":true,"status":"pending","balance":0}"""))
        server.enqueue(jsonResponse(200, """{"ok":true,"status":"pending","balance":0}"""))

        api.checkCreditStatus(signer, "invoice-a")
        api.checkCreditStatus(signer, "invoice-b")

        val first = server.takeRequest()
        val second = server.takeRequest()

        // The request URL keeps the query…
        assertEquals("/api/zappy/note-review/credit-status?id=invoice-a", first.path)
        assertEquals("/api/zappy/note-review/credit-status?id=invoice-b", second.path)

        // …the signed u tag drops it (normalizeUrl on both sides)…
        val expectedU = "$baseUrl/api/zappy/note-review/credit-status"
        assertEquals(expectedU, tagValue(decodeAuthEvent(first.getHeader("Authorization")!!), "u"))
        assertEquals(expectedU, tagValue(decodeAuthEvent(second.getHeader("Authorization")!!), "u"))
        assertNull(tagValue(decodeAuthEvent(first.getHeader("Authorization")!!), "payload"))

        // …so the poll reuses ONE signed header across differing invoice ids.
        assertEquals(first.getHeader("Authorization"), second.getHeader("Authorization"))
        assertEquals(1, signer.signCount)
    }

    @Test
    fun http401OnCachedHeader_invalidatesResignsOnce_andRetriesOnce() = runBlocking {
        // Poll 1 succeeds and warms the cache.
        server.enqueue(jsonResponse(200, """{"ok":true,"status":"pending","balance":0}"""))
        // Poll 2: the cached header is rejected (e.g. skew the client
        // clock can't see) → silent re-sign → retry succeeds.
        server.enqueue(jsonResponse(401, """{"ok":false,"error":"Authentication required"}"""))
        server.enqueue(jsonResponse(200, """{"ok":true,"status":"paid","balance":1}"""))

        val first = api.checkCreditStatus(signer, "invoice-a")
        val second = api.checkCreditStatus(signer, "invoice-a")

        assertEquals(CreditStatusResult.Success(CreditStatus.PENDING, 0), first)
        assertEquals(CreditStatusResult.Success(CreditStatus.PAID, 1), second)
        assertEquals(3, server.requestCount)
        assertEquals(2, signer.signCount) // initial sign + one silent re-sign

        val poll1 = server.takeRequest()
        val poll2Rejected = server.takeRequest()
        val poll2Retry = server.takeRequest()
        assertEquals(poll1.getHeader("Authorization"), poll2Rejected.getHeader("Authorization"))
        assertNotEquals(poll2Rejected.getHeader("Authorization"), poll2Retry.getHeader("Authorization"))
    }

    @Test
    fun clearAuthCache_forcesAFreshSignOnTheNextRequest() = runBlocking {
        // Account-switch hook (audit B2): FeedViewModel.reloadForNewAccount
        // calls this so no cached identity assertion survives a switch.
        server.enqueue(jsonResponse(200, """{"ok":true,"status":"pending","balance":0}"""))
        server.enqueue(jsonResponse(200, """{"ok":true,"status":"pending","balance":0}"""))
        server.enqueue(jsonResponse(200, """{"ok":true,"status":"pending","balance":0}"""))

        api.checkCreditStatus(signer, "invoice-a")
        api.checkCreditStatus(signer, "invoice-b")
        assertEquals(1, signer.signCount) // shared header across polls

        api.clearAuthCache()
        api.checkCreditStatus(signer, "invoice-c")
        assertEquals(2, signer.signCount) // fresh sign after the switch
    }

    @Test
    fun http401OnFreshHeader_isNotRetried() = runBlocking {
        server.enqueue(jsonResponse(401, """{"ok":false,"error":"Authentication required"}"""))

        val result = api.checkCreditStatus(signer, "invoice-a")

        assertTrue(result is CreditStatusResult.Error)
        assertEquals(1, server.requestCount)
        assertEquals(1, signer.signCount)
    }

    @Test
    fun signerRejection_mapsToSignFailed_withoutTouchingTheNetwork() = runBlocking {
        signer.failure = SignerRejectedException("declined")

        val review = api.requestNoteReview(
            imageUrl = "https://image.nostr.build/abc.jpg",
            mode = NoteReviewMode.RECIPE,
            signer = signer,
        )
        val invoice = api.requestCreditInvoice(signer)
        val status = api.checkCreditStatus(signer, "invoice-a")

        assertEquals(NoteReviewResult.SignFailed, review)
        assertEquals(CreditInvoiceResult.SignFailed, invoice)
        assertEquals(CreditStatusResult.SignFailed, status)
        assertEquals(0, server.requestCount)
    }

    // --- helpers ---

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    /** Decode the `Authorization: Nostr <base64>` header back to the signed event JSON. */
    private fun decodeAuthEvent(header: String): JsonObject {
        assertTrue(header.startsWith("Nostr "))
        val decoded = String(Base64.getDecoder().decode(header.removePrefix("Nostr ")), Charsets.UTF_8)
        return Json.parseToJsonElement(decoded).jsonObject
    }

    private fun tagValue(event: JsonObject, name: String): String? =
        event["tags"]!!.jsonArray
            .map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
            .firstOrNull { it.firstOrNull() == name }
            ?.getOrNull(1)
}
