package cooking.zap.app.api

import cooking.zap.app.mealplan.MealPlanGeneration
import cooking.zap.app.nostr.FakeNip98Signer
import cooking.zap.app.nostr.Nip98
import cooking.zap.app.nostr.Nip98HeaderCache
import cooking.zap.app.nostr.SignerRejectedException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.Collections

/**
 * Cheffy meal-plan API layer (`POST /api/zappy/meal-plan`).
 *
 * Pure mapping tests pin the code-first / status-fallback contract against
 * the server's real shapes (frontend `src/routes/api/zappy/meal-plan/+server.ts`
 * at commit 8caef33). MockWebServer tests pin pre-flight, NIP-98 payload
 * identity, Android-only fields staying off the wire, local re-validation
 * (I4), and the [ZapCookingApi.requestMealPlan] `onAuthHeaderReady` seam.
 */
class MealPlanApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ZapCookingApi
    private lateinit var signer: FakeNip98Signer
    private lateinit var baseUrl: String

    private val author = "cd".repeat(32)

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
    fun map_401_isSignInRequired() {
        val result = ZapCookingApi.mapMealPlanResponse(
            401, """{"ok":false,"error":"Authentication required"}""",
        )
        assertEquals(MealPlanResult.SignInRequired, result)
    }

    @Test
    fun map_403NotMember_passesServerCopy() {
        val result = ZapCookingApi.mapMealPlanResponse(
            403,
            """{"ok":false,"error":"Cheffy is available to Cook+ members.","code":"NOT_MEMBER"}""",
        )
        assertEquals(
            MealPlanResult.MembersOnly("Cheffy is available to Cook+ members."),
            result,
        )
    }

    @Test
    fun map_429RateLimited_withRetryAfter() {
        val result = ZapCookingApi.mapMealPlanResponse(
            429,
            """{"ok":false,"code":"RATE_LIMITED","error":"Cheffy is a little busy. Try again in a bit.","retryAfter":3600}""",
        )
        assertEquals(MealPlanResult.RateLimited(3600), result)
    }

    @Test
    fun map_429RateLimited_withoutRetryAfter() {
        val result = ZapCookingApi.mapMealPlanResponse(
            429,
            """{"ok":false,"code":"RATE_LIMITED","error":"Cheffy is a little busy. Try again in a bit."}""",
        )
        assertEquals(MealPlanResult.RateLimited(null), result)
    }

    @Test
    fun map_400NoCandidates_isNoCandidates_regardlessOfStatus() {
        val copy = "None of these recipes fit breakfast. Try adding breakfast recipes, or pick a different meal."
        val body = """{"ok":false,"error":"$copy","code":"no-candidates"}"""
        assertEquals(
            MealPlanResult.NoCandidates(copy),
            ZapCookingApi.mapMealPlanResponse(400, body),
        )
        // The code, not the status, picks this bucket — a status-only mapper
        // would throw the slot-aware copy away (or mis-bucket a 422).
        assertEquals(
            MealPlanResult.NoCandidates(copy),
            ZapCookingApi.mapMealPlanResponse(422, body),
        )
    }

    @Test
    fun map_400InvalidWeek_isInvalidRequest() {
        val result = ZapCookingApi.mapMealPlanResponse(
            400,
            """{"ok":false,"error":"A valid week is required.","code":"invalid-week"}""",
        )
        assertEquals(
            MealPlanResult.InvalidRequest("invalid-week", "A valid week is required."),
            result,
        )
    }

    @Test
    fun map_422UnknownRecipe_isRejected() {
        val result = ZapCookingApi.mapMealPlanResponse(
            422,
            """{"ok":false,"error":"Cheffy returned a recipe that is not in Zap Cooking.","code":"unknown-recipe"}""",
        )
        assertEquals(
            MealPlanResult.Rejected(
                "unknown-recipe",
                "Cheffy returned a recipe that is not in Zap Cooking.",
            ),
            result,
        )
    }

    @Test
    fun map_422IneligibleSlot_isRejected() {
        val result = ZapCookingApi.mapMealPlanResponse(
            422,
            """{"ok":false,"error":"Cheffy assigned a recipe that does not fit that meal.","code":"ineligible-slot"}""",
        )
        assertEquals(
            MealPlanResult.Rejected(
                "ineligible-slot",
                "Cheffy assigned a recipe that does not fit that meal.",
            ),
            result,
        )
    }

    @Test
    fun map_500_isFailed_withServerLine() {
        val result = ZapCookingApi.mapMealPlanResponse(
            500,
            """{"ok":false,"error":"Cheffy could not finish that plan. Please try again."}""",
        )
        assertEquals(
            MealPlanResult.Failed("Cheffy could not finish that plan. Please try again."),
            result,
        )
    }

    @Test
    fun map_emptyBody_isFailed() {
        val result = ZapCookingApi.mapMealPlanResponse(500, "")
        assertTrue(result is MealPlanResult.Failed)
    }

    @Test
    fun map_htmlBody_proxyError_isFailed() {
        val result = ZapCookingApi.mapMealPlanResponse(
            502,
            "<html><h1>502 Bad Gateway</h1></html>",
        )
        assertTrue(result is MealPlanResult.Failed)
    }

    @Test
    fun map_unknownCode_bucketsByStatus() {
        val body = """{"ok":false,"code":"brand-new-code","error":"Something new from the server."}"""
        assertEquals(
            MealPlanResult.InvalidRequest("brand-new-code", "Something new from the server."),
            ZapCookingApi.mapMealPlanResponse(400, body),
        )
        assertEquals(
            MealPlanResult.Rejected("brand-new-code", "Something new from the server."),
            ZapCookingApi.mapMealPlanResponse(422, body),
        )
    }

    @Test
    fun map_200_okPlan_isOk_andDropsImage() {
        val a = coord("pasta")
        val result = ZapCookingApi.mapMealPlanResponse(
            200,
            """{"ok":true,"plan":{"meals":[{"day":"mon","slot":"dinner","a":"$a","title":"Weeknight Pasta","reason":"Easy bowl.","image":"file:///local.jpg"}]}}""",
        )
        val ok = result as MealPlanResult.Ok
        assertEquals(1, ok.meals.size)
        assertEquals(a, ok.meals[0].a)
        assertEquals("mon", ok.meals[0].day)
        assertEquals("dinner", ok.meals[0].slot)
        assertNull(ok.meals[0].image)
    }

    @Test
    fun map_statusCodeFallback_whenBodyIsUnparseable() {
        assertEquals(MealPlanResult.SignInRequired, ZapCookingApi.mapMealPlanResponse(401, "<html>"))
        val members = ZapCookingApi.mapMealPlanResponse(403, "nope")
        assertTrue(members is MealPlanResult.MembersOnly)
        assertEquals(MealPlanResult.RateLimited(null), ZapCookingApi.mapMealPlanResponse(429, "nope"))
        val invalid = ZapCookingApi.mapMealPlanResponse(400, "<html>")
        assertTrue(invalid is MealPlanResult.InvalidRequest)
        val rejected = ZapCookingApi.mapMealPlanResponse(422, "")
        assertTrue(rejected is MealPlanResult.Rejected)
    }

    // --- Serialization: wire shape, omitted optionals, payload identity ---

    @Test
    fun encode_omitsAbsentOptionals_andNeverEmitsImageOrNull() {
        val body = encodeMealPlanBody(sparseRequest())
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertNoJsonNulls(parsed)
        assertFalse(parsed.containsKey("occupiedSlots"))
        assertFalse(parsed.containsKey("fillSlots"))
        assertFalse(parsed.containsKey("excludeCoordinates"))
        assertFalse(parsed.containsKey("image"))

        val prefs = parsed["preferences"]!!.jsonObject
        assertFalse(prefs.containsKey("maxMinutes"))
        assertFalse(prefs.containsKey("servings"))
        assertFalse(prefs.containsKey("excludeIngredients"))
        assertFalse(prefs.containsKey("notes"))
        assertEquals(emptyList<String>(), prefs["styles"]!!.jsonArray.map { it.jsonPrimitive.content })

        val candidate = parsed["candidates"]!!.jsonArray[0].jsonObject
        val allowed = setOf("a", "title", "tags", "ingredients", "prepTime", "cookTime", "servings")
        assertTrue(candidate.keys.all { it in allowed })
        assertFalse(candidate.containsKey("image"))
        assertFalse(candidate.containsKey("prepTime"))
        assertFalse(candidate.containsKey("cookTime"))
        assertFalse(candidate.containsKey("servings"))
        assertEquals("fill-empty", parsed["strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun encode_includesPresentOptionals_withoutNulls() {
        val extra = coord("leftover-soup")
        val body = encodeMealPlanBody(
            sparseRequest().copy(
                preferences = MealPlanGeneration.MealPlanPreferences(
                    styles = listOf(MealPlanGeneration.PreferenceStyleId.EASY),
                    maxMinutes = 30,
                    servings = 4,
                    excludeIngredients = listOf("cilantro"),
                    notes = "Kid friendly",
                ),
                occupiedSlots = listOf(MealPlanGeneration.MealSlotRef("sun", "dinner")),
                fillSlots = listOf(MealPlanGeneration.MealSlotRef("mon", "dinner")),
                excludeCoordinates = listOf(extra),
                candidates = listOf(
                    recipe("pasta", "Weeknight Pasta").copy(
                        prepTime = "10 min",
                        cookTime = "20 min",
                        servings = "4",
                    ),
                ),
            ),
        )
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertNoJsonNulls(parsed)
        assertEquals("easy", parsed["preferences"]!!.jsonObject["styles"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(30, parsed["preferences"]!!.jsonObject["maxMinutes"]!!.jsonPrimitive.content.toInt())
        assertEquals("sun", parsed["occupiedSlots"]!!.jsonArray[0].jsonObject["day"]!!.jsonPrimitive.content)
        assertEquals("mon", parsed["fillSlots"]!!.jsonArray[0].jsonObject["day"]!!.jsonPrimitive.content)
        assertEquals(extra, parsed["excludeCoordinates"]!!.jsonArray[0].jsonPrimitive.content)
        val candidate = parsed["candidates"]!!.jsonArray[0].jsonObject
        assertEquals("10 min", candidate["prepTime"]!!.jsonPrimitive.content)
        assertFalse(candidate.containsKey("image"))
    }

    @Test
    fun signedBodyBytes_areTheExactBytesSent() = runBlocking {
        val request = sparseRequest()
        val expectedBody = encodeMealPlanBody(request)
        server.enqueue(jsonResponse(200, okPlanBody(request)))

        val result = api.requestMealPlan(request, signer)
        assertTrue(result is MealPlanResult.Ok)

        val recorded = server.takeRequest()
        assertEquals("/api/zappy/meal-plan", recorded.path)
        assertEquals("POST", recorded.method)

        val bodyBytes = recorded.body.readByteArray()
        val sentBody = String(bodyBytes, Charsets.UTF_8)
        assertEquals(expectedBody, sentBody)

        val authEvent = decodeAuthEvent(recorded.getHeader("Authorization")!!)
        assertEquals(Nip98.sha256Hex(bodyBytes), tagValue(authEvent, "payload"))
        assertEquals("$baseUrl/api/zappy/meal-plan", tagValue(authEvent, "u"))
        assertEquals("POST", tagValue(authEvent, "method"))
    }

    // --- Pre-flight: caller bugs must not cost a round trip or a signer prompt ---

    @Test
    fun preflight_49Candidates_isInvalidRequest_withZeroNetworkCalls() = runBlocking {
        val request = sparseRequest().copy(
            candidates = (1..49).map { recipe("r$it", "Recipe $it") },
        )
        val result = api.requestMealPlan(request, signer)
        assertEquals(
            MealPlanResult.InvalidRequest(
                "too-many-candidates",
                "Too many candidate recipes (max ${MealPlanGeneration.MAX_CANDIDATES}).",
            ),
            result,
        )
        assertEquals(0, server.requestCount)
        assertEquals(0, signer.signCount)
    }

    @Test
    fun preflight_emptyCandidates_isInvalidRequest_withZeroNetworkCalls() = runBlocking {
        val result = api.requestMealPlan(sparseRequest().copy(candidates = emptyList()), signer)
        assertEquals(
            MealPlanResult.InvalidRequest(
                "no-candidates",
                "No recipes were available to plan with.",
            ),
            result,
        )
        assertEquals(0, server.requestCount)
        assertEquals(0, signer.signCount)
    }

    // --- I4: client re-validates a 200 before returning Ok ---

    @Test
    fun revalidation_unknownCoordinateOn200_isRejectedNotOk() = runBlocking {
        val request = sparseRequest()
        val unknown = coord("invented")
        server.enqueue(
            jsonResponse(
                200,
                """{"ok":true,"plan":{"meals":[{"day":"mon","slot":"dinner","a":"$unknown","title":"Invented","reason":"Nope."}]}}""",
            ),
        )
        val result = api.requestMealPlan(request, signer)
        assertEquals(
            MealPlanResult.Rejected(
                "unknown-recipe",
                "Cheffy returned a recipe that is not in Zap Cooking.",
            ),
            result,
        )
        assertEquals(1, server.requestCount)
    }

    // --- onAuthHeaderReady seam ---

    @Test
    fun onAuthHeaderReady_firesOnce_beforeTheHttpCall() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                events.add("http")
                return jsonResponse(200, okPlanBody(sparseRequest()))
            }
        }
        val result = api.requestMealPlan(
            sparseRequest(),
            signer,
            onAuthHeaderReady = { events.add("auth") },
        )
        assertTrue(result is MealPlanResult.Ok)
        assertEquals(listOf("auth", "http"), events.toList())
        assertEquals(1, signer.signCount)
    }

    @Test
    fun signerRejection_mapsToSignFailed_withoutTouchingTheNetwork() = runBlocking {
        signer.failure = SignerRejectedException("declined")
        val result = api.requestMealPlan(sparseRequest(), signer)
        assertEquals(MealPlanResult.SignFailed, result)
        assertEquals(0, server.requestCount)
    }

    // --- helpers ---

    private fun coord(dTag: String) = "30023:$author:$dTag"

    private fun recipe(
        dTag: String,
        title: String,
        tags: List<String> = emptyList(),
        ingredients: List<String> = emptyList(),
    ) = MealPlanGeneration.RecipeCandidate(
        a = coord(dTag),
        title = title,
        tags = tags,
        ingredients = ingredients,
    )

    private fun sparseRequest() = MealPlanGeneration.MealPlanGenerationRequest(
        weekId = "2026-W29",
        days = listOf("mon"),
        mealSlots = listOf("dinner"),
        preferences = MealPlanGeneration.MealPlanPreferences(),
        strategy = MealPlanGeneration.MealPlanStrategy.FILL_EMPTY,
        candidates = listOf(recipe("pasta", "Weeknight Pasta")),
    )

    private fun okPlanBody(request: MealPlanGeneration.MealPlanGenerationRequest): String {
        val c = request.candidates.first()
        return """{"ok":true,"plan":{"meals":[{"day":"mon","slot":"dinner","a":"${c.a}","title":"${c.title}","reason":"Easy weeknight bowl."}]}}"""
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

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

    private fun assertNoJsonNulls(element: kotlinx.serialization.json.JsonElement) {
        when (element) {
            is JsonNull -> fail("null on the wire")
            is JsonObject -> element.values.forEach { assertNoJsonNulls(it) }
            is JsonArray -> element.forEach { assertNoJsonNulls(it) }
            else -> Unit
        }
    }
}
