package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

/**
 * Tag/invoice consistency rules of NIP-57 receipt validation, exercised on the
 * JVM classpath via [Nip57.receiptInconsistency] (signature checks live in
 * [Nip57.parseValidatedZapRequest] and are covered by the instrumented suite,
 * because secp256k1 JNI is unavailable in JVM unit tests).
 */
class Nip57ReceiptConsistencyTest {
    private val recipient = "22".repeat(32)
    private val sender = "33".repeat(32)
    private val eventId = "aa".repeat(32)

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

    private fun invoice(amountMsat: Long, tags: List<Pair<Int, ByteArray>>): String {
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
        val hrp = "lnbc${amountMsat / 100}n"
        val values = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 } +
            data + listOf(0, 0, 0, 0, 0, 0)
        val mod = polymod(values) xor 1
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + checksum.toList()).map { charset[it] }.joinToString("")
    }

    private fun requestJson(tags: List<List<String>>): String =
        NostrEvent.createUnsigned(sender, Nip57.KIND_ZAP_REQUEST, "note", tags, 1_700_000_100L).toJson()

    private fun receipt(description: String, tags: List<List<String>>): NostrEvent =
        NostrEvent.createUnsigned(
            sender, Nip57.KIND_ZAP_RECEIPT, "",
            listOf(
                listOf("bolt11", invoice(100_000L, listOf(23 to sha256Bytes(description)))),
                listOf("description", description)
            ) + tags,
            1_700_000_200L
        )

    private fun consistentReceipt(requestTags: List<List<String>>): NostrEvent {
        val description = requestJson(requestTags)
        return receipt(description, listOf(
            listOf("p", recipient),
            listOf("e", eventId)
        ))
    }

    @Test
    fun `consistent receipt has no inconsistency`() {
        val receipt = consistentReceipt(listOf(
            listOf("p", recipient),
            listOf("e", eventId),
            listOf("relays", "wss://relay.a"),
            listOf("amount", "100000")
        ))
        val request = NostrEvent.fromJson(receipt.tags.first { it[0] == "description" }[1])
        val description = receipt.tags.first { it[0] == "description" }[1]
        assertNull(Nip57.receiptInconsistency(receipt, request, description))
    }

    @Test
    fun `request p tag must match receipt p tag`() {
        val description = requestJson(listOf(listOf("p", sender), listOf("e", eventId)))
        val r = receipt(description, listOf(listOf("p", recipient), listOf("e", eventId)))
        val request = NostrEvent.fromJson(description)
        assertEquals(
            "p tag mismatch",
            Nip57.receiptInconsistency(r, request, description)
        )
    }

    @Test
    fun `receipt e tag on a request without one is inconsistent`() {
        // A genuine profile-zap request carries no e tag; adding e to the
        // receipt would retarget it onto an event.
        val description = requestJson(listOf(listOf("p", recipient)))
        val r = receipt(description, listOf(listOf("p", recipient), listOf("e", eventId)))
        val request = NostrEvent.fromJson(description)
        assertEquals(
            "e tag presence mismatch",
            Nip57.receiptInconsistency(r, request, description)
        )
    }

    @Test
    fun `request e tag without a receipt e tag is inconsistent`() {
        val description = requestJson(listOf(listOf("p", recipient), listOf("e", eventId)))
        val r = receipt(description, listOf(listOf("p", recipient)))
        val request = NostrEvent.fromJson(description)
        assertEquals(
            "e tag presence mismatch",
            Nip57.receiptInconsistency(r, request, description)
        )
    }

    @Test
    fun `symmetric e tag absence is consistent`() {
        val description = requestJson(listOf(listOf("p", recipient)))
        val r = receipt(description, listOf(listOf("p", recipient)))
        val request = NostrEvent.fromJson(description)
        assertNull(Nip57.receiptInconsistency(r, request, description))
    }

    @Test
    fun `receipt P tag must match the request author`() {
        val description = requestJson(listOf(listOf("p", recipient), listOf("e", eventId)))
        val r = NostrEvent.createUnsigned(
            sender, Nip57.KIND_ZAP_RECEIPT, "",
            listOf(
                listOf("bolt11", invoice(100_000L, listOf(23 to sha256Bytes(description)))),
                listOf("description", description),
                listOf("p", recipient),
                listOf("e", eventId),
                listOf("P", "44".repeat(32))
            ),
            1_700_000_200L
        )
        val request = NostrEvent.fromJson(description)
        assertEquals("P tag mismatch", Nip57.receiptInconsistency(r, request, description))
    }

    @Test
    fun `receipt P tag matching the request author is consistent`() {
        val description = requestJson(listOf(listOf("p", recipient), listOf("e", eventId)))
        val r = NostrEvent.createUnsigned(
            sender, Nip57.KIND_ZAP_RECEIPT, "",
            listOf(
                listOf("bolt11", invoice(100_000L, listOf(23 to sha256Bytes(description)))),
                listOf("description", description),
                listOf("p", recipient),
                listOf("e", eventId),
                listOf("P", sender.uppercase())
            ),
            1_700_000_200L
        )
        val request = NostrEvent.fromJson(description)
        assertNull(Nip57.receiptInconsistency(r, request, description))
    }

    @Test
    fun `request e tag must match receipt e tag`() {
        val description = requestJson(listOf(listOf("p", recipient), listOf("e", "bb".repeat(32))))
        val r = receipt(description, listOf(listOf("p", recipient), listOf("e", eventId)))
        val request = NostrEvent.fromJson(description)
        assertEquals("e tag mismatch", Nip57.receiptInconsistency(r, request, description))
    }

    @Test
    fun `receipt without p tag is inconsistent`() {
        val description = requestJson(listOf(listOf("p", recipient), listOf("e", eventId)))
        val r = NostrEvent.createUnsigned(
            sender, Nip57.KIND_ZAP_RECEIPT, "",
            listOf(
                listOf("bolt11", invoice(100_000L, listOf(23 to sha256Bytes(description)))),
                listOf("description", description)
            ),
            1_700_000_200L
        )
        val request = NostrEvent.fromJson(description)
        assertEquals("receipt has no p tag", Nip57.receiptInconsistency(r, request, description))
    }

    @Test
    fun `request without p tag is inconsistent`() {
        val description = requestJson(listOf(listOf("e", eventId)))
        val r = receipt(description, listOf(listOf("p", recipient), listOf("e", eventId)))
        val request = NostrEvent.fromJson(description)
        assertEquals("request has no p tag", Nip57.receiptInconsistency(r, request, description))
    }

    @Test
    fun `receipt without bolt11 is inconsistent`() {
        val description = requestJson(listOf(listOf("p", recipient)))
        val r = NostrEvent.createUnsigned(
            sender, Nip57.KIND_ZAP_RECEIPT, "",
            listOf(
                listOf("description", description),
                listOf("p", recipient)
            ),
            1_700_000_200L
        )
        val request = NostrEvent.fromJson(description)
        assertEquals("receipt has no bolt11", Nip57.receiptInconsistency(r, request, description))
    }

    @Test
    fun `invoice without description hash is inconsistent`() {
        val description = requestJson(listOf(listOf("p", recipient), listOf("e", eventId)))
        val r = NostrEvent.createUnsigned(
            sender, Nip57.KIND_ZAP_RECEIPT, "",
            listOf(
                listOf("bolt11", invoice(100_000L, listOf(1 to sha256Bytes("preimage")))),
                listOf("description", description),
                listOf("p", recipient),
                listOf("e", eventId)
            ),
            1_700_000_200L
        )
        val request = NostrEvent.fromJson(description)
        assertEquals(
            "invoice has no description hash",
            Nip57.receiptInconsistency(r, request, description)
        )
    }

    @Test
    fun `description hash mismatch is inconsistent`() {
        val description = requestJson(listOf(listOf("p", recipient), listOf("e", eventId)))
        val r = NostrEvent.createUnsigned(
            sender, Nip57.KIND_ZAP_RECEIPT, "",
            listOf(
                listOf("bolt11", invoice(100_000L, listOf(23 to sha256Bytes("unrelated")))),
                listOf("description", description),
                listOf("p", recipient),
                listOf("e", eventId)
            ),
            1_700_000_200L
        )
        val request = NostrEvent.fromJson(description)
        assertEquals(
            "description hash mismatch",
            Nip57.receiptInconsistency(r, request, description)
        )
    }

    @Test
    fun `hex pubkey comparison is case-insensitive`() {
        // Mixed-case hex between request and receipt must still match.
        val description = requestJson(listOf(listOf("p", recipient.uppercase()), listOf("e", eventId)))
        val r = receipt(description, listOf(listOf("p", recipient), listOf("e", eventId)))
        val request = NostrEvent.fromJson(description)
        assertNull(Nip57.receiptInconsistency(r, request, description))
    }

    @Test
    fun `request parses back to identical event`() {
        val description = requestJson(listOf(listOf("p", recipient)))
        val request = NostrEvent.fromJson(description)
        assertNotNull(request)
        assertEquals(Nip57.KIND_ZAP_REQUEST, request.kind)
        assertEquals(sender, request.pubkey)
    }
}
