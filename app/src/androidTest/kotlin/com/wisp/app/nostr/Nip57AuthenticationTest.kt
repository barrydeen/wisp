package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * NIP-57 receipt authentication regression tests. Receipts are ordinary relay
 * events anyone can author, so every getter that exposes sender, message,
 * amount, relays or poll votes must draw them from a validated embedded
 * request: signature-valid, addressing the same recipient/event as the
 * receipt, with the invoice's description hash committing to the exact
 * request string. Uses synthetic keys and real Schnorr/BOLT-11 operations.
 *
 * The file deliberately avoids Android-only APIs so the identical source
 * runs in the isolated JVM harness used for local validation.
 */
class Nip57AuthenticationTest {
    private val sender = Keys.generate()          // genuine zapper "V"
    private val recipient = Keys.generate()       // zap target
    private val wallet = Keys.generate()          // receipt author (wallet service)
    private val attacker = Keys.generate()

    private val targetEvent = NostrEvent.create(
        recipient.privkey, recipient.pubkey, 1, "zap me", emptyList(), 1_700_000_100L
    )
    private val eventId = targetEvent.id

    private val amountMsat = 100_000L             // 100 sats

    // ─── BOLT-11 test fixtures ──────────────────────────────────────────────

    private val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (v in values) {
            val b = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if ((b shr i) and 1 == 1) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun bytesTo5(bytes: ByteArray): IntArray {
        val out = mutableListOf<Int>()
        var acc = 0
        var bits = 0
        for (b in bytes) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.add((acc shr bits) and 31)
            }
        }
        // Pad the trailing partial group, per bech32 convertbits(..., pad=true):
        // the h field is canonically 52 chars for 32 bytes.
        if (bits > 0) out.add((acc shl (5 - bits)) and 31)
        return out.toIntArray()
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun sha256Bytes(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    /** msat → canonical lnbc hrp amount fragment ("" whole-BTC, m, u, n, p). */
    private fun hrpAmountFor(msat: Long): String {
        return when {
            msat % 100_000_000_000L == 0L -> (msat / 100_000_000_000L).toString()
            msat % 100_000_000L == 0L -> "${msat / 100_000_000L}m"
            msat % 100_000L == 0L -> "${msat / 100_000L}u"
            msat % 100L == 0L -> "${msat / 100L}n"
            else -> "${msat * 10}p"
        }
    }

    /** Spec-valid BOLT-11 string with real checksum; signature is a placeholder. */
    private fun buildInvoice(amountMsat: Long, tags: List<Pair<Int, ByteArray>>): String {
        val data = mutableListOf<Int>()
        val ts = 1_700_000_000L
        for (i in 6 downTo 0) data.add(((ts shr (5 * i)) and 31L).toInt())
        for ((type, payload) in tags) {
            val p5 = bytesTo5(payload)
            data.add(type)
            data.add((p5.size shr 5) and 31)
            data.add(p5.size and 31)
            data.addAll(p5.toList())
        }
        repeat(104) { data.add(0) }
        val hrp = "lnbc${hrpAmountFor(amountMsat)}"
        val values = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 } +
            data + listOf(0, 0, 0, 0, 0, 0)
        val mod = polymod(values) xor 1
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + checksum.toList()).map { charset[it] }.joinToString("")
    }

    /** Invoice carrying the description hash (type 23) of [description]. */
    private fun zapInvoice(description: String, amountMsat: Long): String =
        buildInvoice(amountMsat, listOf(23 to sha256Bytes(description)))

    // ─── Receipt/request builders ───────────────────────────────────────────

    private fun buildZapRequest(
        signer: Keys.Keypair,
        claimedRecipient: String = recipient.pubkey.toHex(),
        event: String? = eventId,
        msat: Long = amountMsat,
        message: String = "nice post"
    ): NostrEvent = Nip57.buildZapRequest(
        senderPrivkey = signer.privkey,
        senderPubkey = signer.pubkey,
        recipientPubkey = claimedRecipient,
        eventId = event,
        amountMsats = msat,
        relayUrls = listOf("wss://relay.a", "wss://relay.b"),
        lnurl = "lnurl1dp",
        message = message
    )

    private fun buildReceipt(
        request: NostrEvent,
        invoice: String,
        pOverride: String? = null,
        eOverride: String? = null,
        dropBolt11: Boolean = false,
        extraTags: List<List<String>> = emptyList()
    ): NostrEvent {
        val description = request.toJson()
        val tags = mutableListOf<List<String>>()
        if (!dropBolt11) tags.add(listOf("bolt11", invoice))
        tags.add(listOf("description", description))
        tags.add(listOf("p", pOverride ?: (request.tags.firstOrNull { it[0] == "p" }?.get(1)
            ?: recipient.pubkey.toHex())))
        val requestE = eOverride ?: request.tags.firstOrNull { it[0] == "e" }?.get(1)
        if (requestE != null) tags.add(listOf("e", requestE))
        tags.addAll(extraTags)
        return NostrEvent.create(wallet.privkey, wallet.pubkey, Nip57.KIND_ZAP_RECEIPT, "", tags)
    }

    private fun validReceipt(): NostrEvent {
        val request = buildZapRequest(sender)
        return buildReceipt(request, zapInvoice(request.toJson(), amountMsat))
    }

    // ─── Validation ─────────────────────────────────────────────────────────

    @Test
    fun validReceiptExposesSenderMessageAmountAndRelays() {
        val receipt = validReceipt()
        assertEquals(sender.pubkey.toHex(), Nip57.getZapperPubkey(receipt))
        assertEquals("nice post", Nip57.getZapMessage(receipt))
        assertEquals(amountMsat / 1000, Nip57.getZapAmountSats(receipt))
        assertEquals(listOf("wss://relay.a", "wss://relay.b"), Nip57.getZapRequestRelays(receipt))
        assertTrue(Nip57.parseValidatedZapRequest(receipt) != null)
    }

    @Test
    fun receiptWithForgedSenderRequestIsRejected() {
        // Attacker signs their own request that claims V's pubkey as the author.
        val forged = NostrEvent.create(
            attacker.privkey, attacker.pubkey, Nip57.KIND_ZAP_REQUEST, "steal credit",
            listOf(
                listOf("p", recipient.pubkey.toHex()),
                listOf("e", eventId),
                listOf("amount", amountMsat.toString())
            )
        )
        // Swap in the victim pubkey without a matching signature.
        val spoofed = forged.copy(pubkey = sender.pubkey.toHex())
        val receipt = buildReceipt(spoofed, zapInvoice(spoofed.toJson(), amountMsat))
        assertNull(Nip57.getZapperPubkey(receipt))
        assertNull(Nip57.parseValidatedZapRequest(receipt))
        assertEquals(0, Nip57.getZapAmountSats(receipt))
    }

    @Test
    fun unsignedEmbeddedRequestIsRejected() {
        val unsigned = NostrEvent.createUnsigned(
            sender.pubkey.toHex(), Nip57.KIND_ZAP_REQUEST, "hi",
            listOf(
                listOf("p", recipient.pubkey.toHex()),
                listOf("e", eventId),
                listOf("amount", amountMsat.toString())
            )
        )
        val receipt = buildReceipt(unsigned, zapInvoice(unsigned.toJson(), amountMsat))
        assertNull(Nip57.getZapperPubkey(receipt))
    }

    @Test
    fun invoiceAmountMustMatchTheSignedRequestAmountTag() {
        val request = buildZapRequest(sender, msat = amountMsat)
        val inflated = zapInvoice(request.toJson(), 1_000_000L)
        val receipt = buildReceipt(request, inflated)
        // Sender still resolves (spec-level receipt checks pass) but the
        // inflated amount is not counted — consumers gate on sats > 0.
        assertEquals(sender.pubkey.toHex(), Nip57.getZapperPubkey(receipt))
        assertEquals(0, Nip57.getZapAmountSats(receipt))
    }

    @Test
    fun receiptWithoutAmountTaggedRequestAcceptsInvoiceAmount() {
        val request = NostrEvent.create(
            sender.privkey, sender.pubkey, Nip57.KIND_ZAP_REQUEST, "no amount tag",
            listOf(
                listOf("p", recipient.pubkey.toHex()),
                listOf("e", eventId)
            )
        )
        val receipt = buildReceipt(request, zapInvoice(request.toJson(), amountMsat))
        assertEquals(amountMsat / 1000, Nip57.getZapAmountSats(receipt))
    }

    @Test
    fun receiptPTagMustMatchTheRequest() {
        val request = buildZapRequest(sender)
        val receipt = buildReceipt(
            request, zapInvoice(request.toJson(), amountMsat),
            pOverride = attacker.pubkey.toHex()
        )
        assertNull(Nip57.getZapperPubkey(receipt))
    }

    @Test
    fun receiptETagMustMatchTheRequest() {
        val request = buildZapRequest(sender)
        val otherEvent = NostrEvent.create(
            recipient.privkey, recipient.pubkey, 1, "different note"
        ).id
        val receipt = buildReceipt(
            request, zapInvoice(request.toJson(), amountMsat),
            eOverride = otherEvent
        )
        assertNull(Nip57.getZapperPubkey(receipt))
        assertNotEquals(eventId, otherEvent)
    }

    @Test
    fun receiptWithoutBolt11IsRejected() {
        val request = buildZapRequest(sender)
        val receipt = buildReceipt(request, zapInvoice(request.toJson(), amountMsat), dropBolt11 = true)
        assertNull(Nip57.getZapperPubkey(receipt))
        assertEquals(0, Nip57.getZapAmountSats(receipt))
    }

    @Test
    fun invoiceWithoutDescriptionHashIsRejected() {
        val request = buildZapRequest(sender)
        val noHashInvoice = buildInvoice(amountMsat, listOf(1 to sha256Bytes("payment preimage")))
        val receipt = buildReceipt(request, noHashInvoice)
        assertNull(Nip57.getZapperPubkey(receipt))
    }

    @Test
    fun descriptionHashMustCommitToTheEmbeddedRequest() {
        val request = buildZapRequest(sender)
        // Invoice hashes a different string than the receipt's description tag.
        val wrongHash = buildInvoice(amountMsat, listOf(23 to sha256Bytes("unrelated")))
        val receipt = buildReceipt(request, wrongHash)
        assertNull(Nip57.getZapperPubkey(receipt))
    }

    @Test
    fun nonZapKindEmbeddedRequestIsRejected() {
        val notAZapRequest = NostrEvent.create(
            sender.privkey, sender.pubkey, 1, "just a note"
        )
        val receipt = buildReceipt(notAZapRequest, zapInvoice(notAZapRequest.toJson(), amountMsat))
        assertNull(Nip57.getZapperPubkey(receipt))
    }

    @Test
    fun profileZapRequestCannotBeRetargetedOntoAnEvent() {
        // Genuine profile-zap request (no e tag); a receipt that adds e would
        // redirect V's profile zap onto an arbitrary note.
        val request = buildZapRequest(sender, event = null)
        val receipt = buildReceipt(
            request, zapInvoice(request.toJson(), amountMsat),
            eOverride = eventId
        )
        assertNull(Nip57.getZapperPubkey(receipt))
        assertNull(Nip57.parseValidatedZapRequest(receipt))
    }

    @Test
    fun profileZapReceiptWithoutETagsValidates() {
        val request = buildZapRequest(sender, event = null)
        val receipt = buildReceipt(request, zapInvoice(request.toJson(), amountMsat))
        assertEquals(sender.pubkey.toHex(), Nip57.getZapperPubkey(receipt))
        assertEquals(amountMsat / 1000, Nip57.getZapAmountSats(receipt))
    }

    @Test
    fun receiptPTagMismatchIsRejected() {
        val request = buildZapRequest(sender)
        val description = request.toJson()
        val receipt = NostrEvent.create(wallet.privkey, wallet.pubkey, Nip57.KIND_ZAP_RECEIPT, "", listOf(
            listOf("bolt11", zapInvoice(description, amountMsat)),
            listOf("description", description),
            listOf("p", recipient.pubkey.toHex()),
            listOf("e", eventId),
            listOf("P", attacker.pubkey.toHex())
        ))
        assertNull(Nip57.getZapperPubkey(receipt))
    }

    @Test
    fun malformedRequestAmountTagRejectsTheAmount() {
        val request = NostrEvent.create(
            sender.privkey, sender.pubkey, Nip57.KIND_ZAP_REQUEST, "malformed amount",
            listOf(
                listOf("p", recipient.pubkey.toHex()),
                listOf("e", eventId),
                listOf("amount", "abc")
            )
        )
        val receipt = buildReceipt(request, zapInvoice(request.toJson(), amountMsat))
        // Sender identity still resolves; only the amount is refused.
        assertEquals(sender.pubkey.toHex(), Nip57.getZapperPubkey(receipt))
        assertEquals(0, Nip57.getZapAmountSats(receipt))
    }

    @Test
    fun negativeOrZeroRequestAmountTagRejectsTheAmount() {
        for (bad in listOf("-5", "0")) {
            val request = NostrEvent.create(
                sender.privkey, sender.pubkey, Nip57.KIND_ZAP_REQUEST, "bad amount",
                listOf(
                    listOf("p", recipient.pubkey.toHex()),
                    listOf("e", eventId),
                    listOf("amount", bad)
                )
            )
            val receipt = buildReceipt(request, zapInvoice(request.toJson(), amountMsat))
            assertEquals(0, Nip57.getZapAmountSats(receipt))
        }
    }

    @Test
    fun duplicateRequestAmountTagsRejectTheReceipt() {
        val request = NostrEvent.create(
            sender.privkey, sender.pubkey, Nip57.KIND_ZAP_REQUEST, "two amounts",
            listOf(
                listOf("p", recipient.pubkey.toHex()),
                listOf("e", eventId),
                listOf("amount", amountMsat.toString()),
                listOf("amount", (amountMsat * 2).toString())
            )
        )
        val receipt = buildReceipt(request, zapInvoice(request.toJson(), amountMsat))
        assertNull(Nip57.getZapperPubkey(receipt))
        assertEquals(0, Nip57.getZapAmountSats(receipt))
    }

    @Test
    fun tamperedReceiptFailsOuterAuthentication() {
        val receipt = validReceipt()
        assertNull(Nip57.parseValidatedZapRequest(receipt.copy(id = "00".repeat(32))))
        assertNull(Nip57.parseValidatedZapRequest(receipt.copy(sig = "f".repeat(128))))
        assertNull(Nip57.parseValidatedZapRequest(receipt.copy(content = "tampered")))
    }

    @Test
    fun replayedGenuineRequestWithIntactBindingStillValidates() {
        // NIP-57 cannot distinguish a re-announced (request, invoice) pair from
        // a genuine receipt: the signature, tags and hash all match. The replay
        // can only ever restate V's real historical zap for the same target.
        val request = buildZapRequest(sender)
        val invoice = zapInvoice(request.toJson(), amountMsat)
        val receipt1 = buildReceipt(request, invoice)
        val receipt2 = buildReceipt(request, invoice)
        assertEquals(Nip57.getZapperPubkey(receipt1), Nip57.getZapperPubkey(receipt2))
        assertTrue(Nip57.getZapperPubkey(receipt2) != null)
    }

    // ─── Private zap round trip ─────────────────────────────────────────────

    @Test
    fun privateZapRoundTripPassesValidationAndDecryptsSender() {
        val request = Nip57.buildPrivateZapRequest(
            senderPrivkey = sender.privkey,
            senderPubkey = sender.pubkey,
            recipientPubkey = recipient.pubkey.toHex(),
            eventId = eventId,
            eventCreatedAt = 1_700_000_100L,
            amountMsats = amountMsat,
            relayUrls = listOf("wss://relay.a"),
            lnurl = "lnurl1dp",
            message = "secret zap"
        )
        val receipt = buildReceipt(request, zapInvoice(request.toJson(), amountMsat))
        assertTrue(Nip57.isPrivateZap(receipt))
        val decrypted = Nip57.decryptPrivateZap(receipt, recipient.privkey)
        assertEquals(sender.pubkey.toHex(), decrypted?.senderPubkey)
        assertEquals("secret zap", decrypted?.message)
        // Sender can re-derive their own outgoing private zap.
        val own = Nip57.decryptOwnOutgoingPrivateZap(receipt, sender.privkey, targetEvent)
        assertEquals(sender.pubkey.toHex(), own?.senderPubkey)
    }

    @Test
    fun privateZapFromAttackerCannotSpoofTheInnerSender() {
        // Attacker encrypts an inner event that claims V as sender; the inner
        // signature check must reject it after the anon envelope decrypts.
        val spoofedInner = NostrEvent.create(
            attacker.privkey, attacker.pubkey, Nip57.KIND_PRIVATE_ZAP_EVENT, "from V (not)",
            listOf(listOf("p", recipient.pubkey.toHex()))
        ).copy(pubkey = sender.pubkey.toHex())
        val ephPriv = Keys.generate()
        val ephPub = ephPriv.pubkey
        val shared = Nip04.computeSharedSecret(ephPriv.privkey, recipient.pubkey)
        val (ct, iv) = Nip04.encryptRaw(spoofedInner.toJson(), shared)
        val anon = Nip19.bech32Encode("pzap", ct) + "_" + Nip19.bech32Encode("iv", iv)
        val request = NostrEvent.create(
            ephPriv.privkey, ephPub, Nip57.KIND_ZAP_REQUEST, "",
            listOf(
                listOf("p", recipient.pubkey.toHex()),
                listOf("e", eventId),
                listOf("relays", "wss://relay.a"),
                listOf("amount", amountMsat.toString()),
                listOf("anon", anon)
            )
        )
        val receipt = buildReceipt(request, zapInvoice(request.toJson(), amountMsat))
        assertNull(Nip57.decryptPrivateZap(receipt, recipient.privkey))
    }

    // ─── Polls ──────────────────────────────────────────────────────────────

    @Test
    fun pollOptionIsExtractedFromAValidatedReceipt() {
        val request = Nip57.buildZapRequest(
            senderPrivkey = sender.privkey,
            senderPubkey = sender.pubkey,
            recipientPubkey = recipient.pubkey.toHex(),
            eventId = eventId,
            amountMsats = amountMsat,
            relayUrls = listOf("wss://relay.a"),
            lnurl = "lnurl1dp",
            message = "",
            extraTags = listOf(listOf("poll_option", "2"))
        )
        val receipt = buildReceipt(request, zapInvoice(request.toJson(), amountMsat))
        assertEquals(2, Nip69.getZapPollOptionFromZapReceipt(receipt))
    }

    @Test
    fun pollOptionFromAForgedReceiptIsRejected() {
        val forged = NostrEvent.create(
            attacker.privkey, attacker.pubkey, Nip57.KIND_ZAP_RECEIPT, "",
            listOf(
                listOf("bolt11", zapInvoice("whatever", amountMsat)),
                listOf("description", NostrEvent.createUnsigned(
                    sender.pubkey.toHex(), Nip57.KIND_ZAP_REQUEST, "",
                    listOf(listOf("p", recipient.pubkey.toHex()), listOf("poll_option", "1"))
                ).toJson()),
                listOf("p", recipient.pubkey.toHex()),
                listOf("e", eventId)
            )
        )
        assertNull(Nip69.getZapPollOptionFromZapReceipt(forged))
    }

    // ─── Invoice amount decoding ────────────────────────────────────────────

    @Test
    fun bolt11AmountsDecodeExactlyInEveryUnit() {
        for (msat in listOf(100L, 1_000L, 100_000L, 10_000_000L, 100_000_000_000L)) {
            val request = buildZapRequest(sender, msat = msat)
            val receipt = buildReceipt(request, zapInvoice(request.toJson(), msat))
            assertEquals(msat / 1000, Nip57.getZapAmountSats(receipt))
        }
    }

    @Test
    fun nonZapEventsAreIgnoredByTheValidator() {
        assertNull(Nip57.parseValidatedZapRequest(validReceipt().copy(kind = 1)))
    }
}
