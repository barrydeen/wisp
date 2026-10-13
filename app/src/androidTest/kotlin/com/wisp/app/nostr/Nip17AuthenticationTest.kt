package com.wisp.app.nostr

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses synthetic keys and real NIP-44/Schnorr operations without relay traffic. */
class Nip17AuthenticationTest {
    private val sender = key(1)
    private val recipient = key(2)
    private val other = key(3)
    private val wrapper = key(4)
    private val timestamp = 1_700_000_000L

    private fun key(value: Int) = Keys.fromPrivkey(ByteArray(32) { value.toByte() })

    private fun rumor(kind: Int = 14, author: Keys.Keypair = sender): Nip17.Rumor =
        Nip17.buildRumor(
            senderPubkeyHex = author.pubkey.toHex(),
            message = "synthetic message",
            pTag = recipient.pubkey.toHex(),
            replyTags = listOf(listOf("e", "ab".repeat(32), "", "reply")),
            kind = kind,
            createdAt = timestamp
        )

    private fun seal(
        rumorJson: String,
        recipientKey: Keys.Keypair = recipient,
        kind: Int = 13,
        tags: List<List<String>> = emptyList()
    ): NostrEvent {
        val conversationKey = Nip44.getConversationKey(sender.privkey, recipientKey.pubkey)
        val encrypted = try {
            Nip44.encrypt(rumorJson, conversationKey)
        } finally {
            conversationKey.wipe()
        }
        return NostrEvent.create(sender.privkey, sender.pubkey, kind, encrypted, tags, timestamp - 100)
    }

    private fun wrap(
        sealJson: String,
        recipientKey: Keys.Keypair = recipient,
        kind: Int = 1059
    ): NostrEvent {
        val conversationKey = Nip44.getConversationKey(wrapper.privkey, recipientKey.pubkey)
        val encrypted = try {
            Nip44.encrypt(sealJson, conversationKey)
        } finally {
            conversationKey.wipe()
        }
        return NostrEvent.create(
            wrapper.privkey, wrapper.pubkey, kind, encrypted,
            listOf(listOf("p", recipientKey.pubkey.toHex())), timestamp - 200
        )
    }

    private fun giftWrap(
        rumorJson: String = Nip17.rumorToJson(rumor()),
        recipientKey: Keys.Keypair = recipient
    ): NostrEvent = wrap(seal(rumorJson, recipientKey).toJson(), recipientKey)

    private fun withField(rumorJson: String, name: String, value: JsonElement?): String {
        val fields = Json.parseToJsonElement(rumorJson).jsonObject.toMutableMap()
        if (value == null) fields.remove(name) else fields[name] = value
        return JsonObject(fields).toString()
    }

    private fun assertRejected(giftWrap: NostrEvent) = runBlocking {
        assertNull("local-key unwrap", Nip17.unwrapGiftWrap(recipient.privkey, giftWrap))
        assertNull(
            "signer unwrap",
            Nip17.unwrapGiftWrapRemote(LocalSigner(recipient.privkey, recipient.pubkey), giftWrap)
        )
    }

    private fun assertAccepted(
        giftWrap: NostrEvent,
        expected: Nip17.Rumor,
        recipientKey: Keys.Keypair = recipient
    ) = runBlocking {
        assertTrue(giftWrap.verifySignature())
        assertEquals(expected, Nip17.unwrapGiftWrap(recipientKey.privkey, giftWrap))
        assertEquals(
            expected,
            Nip17.unwrapGiftWrapRemote(LocalSigner(recipientKey.privkey, recipientKey.pubkey), giftWrap)
        )
    }

    @Test fun acceptsAllSupportedRumorKinds() {
        for (kind in listOf(14, 15, 1, 7)) {
            val expected = rumor(kind)
            assertAccepted(giftWrap(Nip17.rumorToJson(expected)), expected)
        }
    }

    @Test fun acceptsMessagesCreatedByBothSendPaths() = runBlocking {
        val expected = rumor()
        val local = Nip17.createGiftWrap(
            sender.privkey, sender.pubkey, recipient.pubkey, expected.content,
            replyTags = expected.tags.drop(1), createdAt = timestamp
        )
        val remote = Nip17.createGiftWrapRemote(
            LocalSigner(sender.privkey, sender.pubkey), recipient.pubkey.toHex(), expected.content,
            replyTags = expected.tags.drop(1), createdAt = timestamp
        )
        assertAccepted(local, expected)
        assertAccepted(remote, expected)
    }

    @Test fun acceptsSenderSelfCopy() {
        val expected = rumor()
        assertAccepted(giftWrap(Nip17.rumorToJson(expected), sender), expected, sender)
    }

    @Test fun acceptsGroupRecipientsAndUnicode() {
        val expected = rumor().copy(
            content = "hello 👋\n\"quoted\" \\ slash",
            tags = rumor().tags + listOf(listOf("p", other.pubkey.toHex()))
        )
        val json = Nip17.rumorToJson(expected)
        assertAccepted(giftWrap(json), expected)
        assertAccepted(giftWrap(json, other), expected, other)
    }

    @Test fun acceptsUnsignedRumorWithEmptySignature() {
        val expected = rumor()
        val json = withField(Nip17.rumorToJson(expected), "sig", JsonPrimitive(""))
        assertAccepted(giftWrap(json), expected)
    }

    @Test fun acceptsEmptyMessageContent() {
        val expected = rumor().copy(content = "")
        assertAccepted(giftWrap(Nip17.rumorToJson(expected)), expected)
    }

    @Test fun rejectsSpoofedAuthorForAllSupportedKinds() {
        for (kind in listOf(14, 15, 1, 7)) {
            // The seal is signed by sender; only the rumor claims another identity.
            val giftWrap = giftWrap(Nip17.rumorToJson(rumor(kind, other)))
            assertTrue(giftWrap.verifySignature())
            assertRejected(giftWrap)
        }
    }

    @Test fun rejectsInvalidOuterSignatureBeforeCallingSigner() = runBlocking {
        val invalid = giftWrap().copy(sig = "00".repeat(64))
        val delegate = LocalSigner(recipient.privkey, recipient.pubkey)
        var decryptCalls = 0
        val signer = object : NostrSigner by delegate {
            override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String {
                decryptCalls++
                return delegate.nip44Decrypt(ciphertext, peerPubkeyHex)
            }
        }
        assertFalse(invalid.verifySignature())
        assertNull(Nip17.unwrapGiftWrap(recipient.privkey, invalid))
        assertNull(Nip17.unwrapGiftWrapRemote(signer, invalid))
        assertEquals(0, decryptCalls)
    }

    @Test fun rejectsMismatchedOuterId() {
        assertRejected(giftWrap().copy(id = "00".repeat(32)))
    }

    @Test fun rejectsWrongOuterKind() {
        assertRejected(wrap(seal(Nip17.rumorToJson(rumor())).toJson(), kind = 1))
    }

    @Test fun rejectsInvalidSealSignatureBeforeDecryptingRumor() = runBlocking {
        val invalidSeal = seal(Nip17.rumorToJson(rumor())).copy(sig = "00".repeat(64))
        val giftWrap = wrap(invalidSeal.toJson())
        assertTrue(giftWrap.verifySignature())
        assertRejected(giftWrap)
        val delegate = LocalSigner(recipient.privkey, recipient.pubkey)
        var decryptCalls = 0
        val signer = object : NostrSigner by delegate {
            override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String {
                decryptCalls++
                return delegate.nip44Decrypt(ciphertext, peerPubkeyHex)
            }
        }
        assertNull(Nip17.unwrapGiftWrapRemote(signer, giftWrap))
        assertEquals(1, decryptCalls)
    }

    @Test fun rejectsMismatchedSealId() {
        val invalidSeal = seal(Nip17.rumorToJson(rumor())).copy(id = "00".repeat(32))
        assertRejected(wrap(invalidSeal.toJson()))
    }

    @Test fun rejectsWrongSealKind() {
        assertRejected(wrap(seal(Nip17.rumorToJson(rumor()), kind = 1).toJson()))
    }

    @Test fun rejectsNonEmptySealTags() {
        val invalidSeal = seal(Nip17.rumorToJson(rumor()), tags = listOf(listOf("p", recipient.pubkey.toHex())))
        assertTrue(invalidSeal.verifySignature())
        assertRejected(wrap(invalidSeal.toJson()))
    }

    @Test fun rejectsMalformedSealJson() {
        assertRejected(wrap("not json"))
    }

    @Test fun rejectsMismatchedRumorIdForAllSupportedKinds() {
        for (kind in listOf(14, 15, 1, 7)) {
            val json = withField(Nip17.rumorToJson(rumor(kind)), "id", JsonPrimitive("00".repeat(32)))
            assertRejected(giftWrap(json))
        }
    }

    @Test fun rejectsMissingRequiredRumorFields() {
        val json = Nip17.rumorToJson(rumor())
        for (field in listOf("id", "pubkey", "created_at", "kind", "tags", "content")) {
            assertRejected(giftWrap(withField(json, field, null)))
        }
    }

    @Test fun rejectsNonStringRumorContentEvenWithMatchingId() {
        val expected = rumor().copy(content = "123")
        val json = withField(Nip17.rumorToJson(expected), "content", JsonPrimitive(123))
        assertRejected(giftWrap(json))
    }

    @Test fun rejectsNonStringTagValuesEvenWithMatchingId() {
        val expected = rumor().copy(tags = listOf(listOf("p", "123")))
        val json = withField(
            Nip17.rumorToJson(expected), "tags", Json.parseToJsonElement("[[\"p\",123]]")
        )
        assertRejected(giftWrap(json))
    }

    @Test fun rejectsSignedRumors() {
        val expected = rumor()
        val signed = NostrEvent.create(
            sender.privkey, sender.pubkey, expected.kind, expected.content, expected.tags, timestamp
        )
        assertTrue(signed.verifySignature())
        assertRejected(giftWrap(signed.toJson()))
    }

    @Test fun rejectsUnsupportedRumorKind() {
        assertRejected(giftWrap(Nip17.rumorToJson(rumor(3))))
    }

    @Test fun rejectsMalformedRumorJson() {
        for (json in listOf("not json", "[]", "{}", "null")) assertRejected(giftWrap(json))
    }

    @Test fun rejectsGiftWrapEncryptedToAnotherRecipient() {
        assertNotNull(giftWrap(recipientKey = other))
        assertRejected(giftWrap(recipientKey = other))
    }
}
