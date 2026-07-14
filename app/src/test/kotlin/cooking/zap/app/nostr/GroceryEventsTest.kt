package cooking.zap.app.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural byte-parity gate for the grocery event layer (arc PR 3, parity
 * layer 1): the tag array, payload JSON key order, id format, ciphertext
 * detector, and read-acceptance rules are pinned against the web contract
 * (frontend groceryService.ts). Signing/encryption run through a fake signer —
 * real crypto is JNI-backed and exercised on-device; what this suite proves is
 * that everything AROUND the crypto is byte-identical to web.
 */
class GroceryEventsTest {

    private val pubkey = "ab".repeat(32)

    private class FakeSigner(
        override val pubkeyHex: String,
    ) : NostrSigner {
        var nip44DecryptCalls = 0
        var nip04DecryptCalls = 0
        var lastEncryptPeer: String? = null

        override suspend fun signEvent(
            kind: Int,
            content: String,
            tags: List<List<String>>,
            createdAt: Long,
        ): NostrEvent = NostrEvent(
            id = "ee".repeat(32),
            pubkey = pubkeyHex,
            created_at = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = "0".repeat(128),
        )

        override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String {
            lastEncryptPeer = peerPubkeyHex
            return "enc44:$plaintext"
        }

        override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String {
            nip44DecryptCalls++
            require(ciphertext.startsWith("enc44:")) { "not a fake nip44 payload" }
            return ciphertext.removePrefix("enc44:")
        }

        override suspend fun nip04Decrypt(ciphertext: String, peerPubkeyHex: String): String {
            nip04DecryptCalls++
            return ciphertext.removePrefix("enc04:").substringBefore("?iv=")
        }
    }

    private fun sampleList(
        recipeLinks: List<String> = listOf("30023:$pubkey:bread"),
        notes: String? = "from web",
        itemRecipeId: String? = "30023:$pubkey:bread",
    ) = GroceryEvents.GroceryList(
        id = "m3abc-x1y2z3",
        title = "Weekly Shop",
        items = listOf(
            GroceryEvents.GroceryItem(
                id = "i1",
                name = "flour",
                quantity = "2 cup",
                category = "pantry",
                checked = false,
                recipeId = itemRecipeId,
                addedAt = 1_700_000_001,
            )
        ),
        recipeLinks = recipeLinks,
        notes = notes,
        createdAt = 1_700_000_000,
        updatedAt = 1_700_000_123,
    )

    // ---- tags: the literal web tag array -----------------------------------

    @Test
    fun buildTags_matchesWebTagArrayLiterally() {
        val list = sampleList(
            recipeLinks = listOf("30023:$pubkey:bread", "30023:$pubkey:soup"),
        )
        assertEquals(
            listOf(
                listOf("d", "grocery-m3abc-x1y2z3"),
                listOf("client", "Zap Cooking"),
                listOf("a", "30023:$pubkey:bread"),
                listOf("a", "30023:$pubkey:soup"),
            ),
            GroceryEvents.buildTags(list),
        )
    }

    @Test
    fun buildTags_noLinks_isJustDAndClient() {
        assertEquals(
            listOf(
                listOf("d", "grocery-m3abc-x1y2z3"),
                listOf("client", "Zap Cooking"),
            ),
            GroceryEvents.buildTags(sampleList(recipeLinks = emptyList())),
        )
    }

    // ---- payload: web JSON.stringify byte order ----------------------------

    @Test
    fun encodePayload_byteMatchesWebJsonStringify() {
        val expected = """{"id":"m3abc-x1y2z3","title":"Weekly Shop",""" +
            """"items":[{"id":"i1","name":"flour","quantity":"2 cup","category":"pantry",""" +
            """"checked":false,"recipeId":"30023:$pubkey:bread","addedAt":1700000001}],""" +
            """"notes":"from web","createdAt":1700000000,"updatedAt":1700000123}"""
        assertEquals(expected, GroceryEvents.encodePayload(sampleList()))
    }

    @Test
    fun encodePayload_omitsAbsentOptionals_likeJsonStringifyDropsUndefined() {
        val payload = GroceryEvents.encodePayload(sampleList(notes = null, itemRecipeId = null))
        assertFalse(payload.contains("\"notes\""))
        assertFalse(payload.contains("\"recipeId\""))
        // ...but defaults that web serializes stay present.
        assertTrue(payload.contains("\"checked\":false"))
    }

    // ---- id format ----------------------------------------------------------

    @Test
    fun generateListId_matchesWebFormat() {
        val id = GroceryEvents.generateListId()
        // web: Date.now().toString(36) + '-' + Math.random().toString(36).substring(2, 8)
        assertTrue("unexpected id: $id", Regex("^[0-9a-z]+-[0-9a-z]{6}$").matches(id))
        assertEquals("grocery-$id", GroceryEvents.dTagFor(id))
        assertEquals(id, GroceryEvents.listIdFromDTag(GroceryEvents.dTagFor(id)))
    }

    @Test
    fun listIdFromDTag_rejectsForeignDTags() {
        assertNull(GroceryEvents.listIdFromDTag("mealplan-2026-W29"))
        assertNull(GroceryEvents.listIdFromDTag("spark-wallet-backup:abc"))
    }

    // ---- ciphertext detector ------------------------------------------------

    @Test
    fun detector_ivEnvelopeIsNip04_elseNip44() {
        assertEquals(EncryptionMethod.NIP04, detectEncryptionMethod("b64stuff?iv=b64iv"))
        assertEquals(EncryptionMethod.NIP44, detectEncryptionMethod("AgAAAbase64nip44payload"))
    }

    // ---- write side ----------------------------------------------------------

    @Test
    fun createListEvent_selfEncrypts_kind30078_contractTags() = runBlocking {
        val signer = FakeSigner(pubkey)
        val list = sampleList()
        val event = GroceryEvents.createListEvent(signer, list)

        assertEquals(30078, event.kind)
        assertEquals(pubkey, signer.lastEncryptPeer) // encrypted to SELF
        assertEquals("enc44:" + GroceryEvents.encodePayload(list), event.content)
        assertEquals(GroceryEvents.buildTags(list), event.tags)
    }

    // ---- read side ------------------------------------------------------------

    @Test
    fun decrypt_roundTripsThroughEventAndTags() = runBlocking {
        val signer = FakeSigner(pubkey)
        val list = sampleList(recipeLinks = listOf("30023:$pubkey:bread", "30023:$pubkey:soup"))
        val event = GroceryEvents.createListEvent(signer, list)

        val decoded = GroceryEvents.decryptListEvent(event, signer)!!
        assertEquals(list, decoded) // recipeLinks reconstructed from tags, not payload
        assertEquals(1, signer.nip44DecryptCalls)
        assertEquals(0, signer.nip04DecryptCalls)
    }

    @Test
    fun decrypt_legacyNip04Envelope_dispatchesToNip04() = runBlocking {
        val signer = FakeSigner(pubkey)
        val payload = GroceryEvents.encodePayload(sampleList(recipeLinks = emptyList()))
        val event = NostrEvent(
            id = "aa".repeat(32),
            pubkey = pubkey,
            created_at = 1_700_000_500,
            kind = 30078,
            tags = listOf(listOf("d", "grocery-m3abc-x1y2z3")),
            content = "enc04:$payload?iv=someiv",
            sig = "0".repeat(128),
        )
        val decoded = GroceryEvents.decryptListEvent(event, signer)!!
        assertEquals("Weekly Shop", decoded.title)
        assertEquals(0, signer.nip44DecryptCalls)
        assertEquals(1, signer.nip04DecryptCalls)
    }

    @Test
    fun candidateFilter_matchesWebIsOurEvent() {
        fun event(tags: List<List<String>>, kind: Int = 30078) = NostrEvent(
            id = "aa".repeat(32), pubkey = pubkey, created_at = 1, kind = kind,
            tags = tags, content = "x", sig = "0".repeat(128),
        )
        // grocery d-tag, no client tag → accepted
        assertTrue(GroceryEvents.isGroceryCandidate(event(listOf(listOf("d", "grocery-x")))))
        // client tag, foreign d-tag → accepted as candidate (web parity)…
        val mealplan = event(listOf(listOf("d", "mealplan-2026-W29"), listOf("client", "Zap Cooking")))
        assertTrue(GroceryEvents.isGroceryCandidate(mealplan))
        // …but decode drops it on the d-tag check.
        assertNull(GroceryEvents.listIdFrom(mealplan))
        // neither marker → rejected
        assertFalse(GroceryEvents.isGroceryCandidate(event(listOf(listOf("d", "other-thing")))))
        // wrong kind → rejected
        assertFalse(GroceryEvents.isGroceryCandidate(event(listOf(listOf("d", "grocery-x")), kind = 30023)))
    }

    @Test
    fun decrypt_failuresSkipPerList_neverThrow() = runBlocking {
        val signer = FakeSigner(pubkey)
        // Garbage ciphertext (fake signer requires the enc44 prefix) → null.
        val broken = NostrEvent(
            id = "aa".repeat(32), pubkey = pubkey, created_at = 1, kind = 30078,
            tags = listOf(listOf("d", "grocery-x")), content = "not-decryptable", sig = "0".repeat(128),
        )
        assertNull(GroceryEvents.decryptListEvent(broken, signer))
        // Decrypts but isn't JSON → null.
        val notJson = broken.copy(content = "enc44:not json at all")
        assertNull(GroceryEvents.decryptListEvent(notJson, signer))
        // Blank content → null.
        assertNull(GroceryEvents.decryptListEvent(broken.copy(content = ""), signer))
    }

    @Test
    fun decrypt_lenientFallbacks_matchWebDecryptGroceryEvent() = runBlocking {
        val signer = FakeSigner(pubkey)
        // Sparse payload: web falls back to listId / "Untitled List" / event.created_at.
        val event = NostrEvent(
            id = "aa".repeat(32), pubkey = pubkey, created_at = 1_700_000_777, kind = 30078,
            tags = listOf(listOf("d", "grocery-fallback-id")), content = "enc44:{}", sig = "0".repeat(128),
        )
        val decoded = GroceryEvents.decryptListEvent(event, signer)!!
        assertEquals("fallback-id", decoded.id)
        assertEquals("Untitled List", decoded.title)
        assertTrue(decoded.items.isEmpty())
        assertEquals(1_700_000_777, decoded.createdAt)
        assertEquals(1_700_000_777, decoded.updatedAt)
    }

    @Test
    fun decrypt_respectsGate() = runBlocking {
        val signer = FakeSigner(pubkey)
        val event = GroceryEvents.createListEvent(signer, sampleList())
        val gate = SignerDecryptGate()
        gate.recordDenial("other1")
        gate.recordDenial("other2") // tripped
        assertNull(GroceryEvents.decryptListEvent(event, signer, gate))
        assertEquals(0, signer.nip44DecryptCalls) // never reached the signer
    }

    // ---- deletion --------------------------------------------------------------

    @Test
    fun deletionEvent_isNip09AddressableWithKnownEventId() = runBlocking {
        val signer = FakeSigner(pubkey)
        val deletion = GroceryEvents.createDeletionEvent(signer, "m3abc-x1y2z3", eventId = "cc".repeat(32))
        assertEquals(5, deletion.kind)
        assertEquals("Deleted grocery list", deletion.content)
        assertEquals(
            listOf(
                listOf("a", "30078:$pubkey:grocery-m3abc-x1y2z3"),
                listOf("k", "30078"),
                listOf("e", "cc".repeat(32)),
            ),
            deletion.tags,
        )
    }

    // ---- Amber permissions -------------------------------------------------------

    @Test
    fun defaultPermissions_cover30078AndLegacyDecrypt() {
        assertTrue(RemoteSignerBridge.DEFAULT_PERMISSIONS.contains("""{"type":"sign_event","kind":30078}"""))
        assertTrue(RemoteSignerBridge.DEFAULT_PERMISSIONS.contains("""{"type":"nip04_decrypt"}"""))
    }
}
