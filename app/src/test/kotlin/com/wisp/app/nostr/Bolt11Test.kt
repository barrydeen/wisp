package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks BOLT-11 decoding used by NIP-57 receipt validation: the type-23
 * description hash (the commitment that binds a zap receipt's invoice to its
 * embedded request) and exact millisatoshi amounts.
 *
 * Invoices are handcrafted: bech32 checksums are real, but the 104-char
 * signature is a placeholder because [Bolt11.decode] does not verify it.
 */
class Bolt11Test {
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

    private fun buildInvoice(hrp: String, tags: List<Pair<Int, ByteArray>>): String {
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
        val values = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 } +
            data + listOf(0, 0, 0, 0, 0, 0)
        val mod = polymod(values) xor 1
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + checksum.toList()).map { charset[it] }.joinToString("")
    }

    private fun hash32(hexSeed: String): ByteArray {
        val out = ByteArray(32)
        for (i in out.indices) out[i] = hexSeed[i % hexSeed.length].code.toByte()
        return out
    }

    @Test
    fun `decodes the type-23 description hash`() {
        val hash = hash32("deadbeef")
        val invoice = buildInvoice("lnbc1000n", listOf(23 to hash))
        val decoded = Bolt11.decode(invoice)
        assertEquals(hash.toHex(), decoded?.descriptionHash)
    }

    @Test
    fun `description hash is absent when the invoice carries only plaintext description`() {
        val invoice = buildInvoice("lnbc1000n", listOf(13 to "coffee".toByteArray()))
        val decoded = Bolt11.decode(invoice)
        assertEquals("coffee", decoded?.description)
        assertNull(decoded?.descriptionHash)
    }

    @Test
    fun `description hash coexists with other tagged fields`() {
        val invoice = buildInvoice(
            "lnbc1000n",
            listOf(1 to hash32("1234"), 23 to hash32("abcd"), 13 to "desc".toByteArray())
        )
        val decoded = Bolt11.decode(invoice)
        assertEquals(hash32("1234").toHex(), decoded?.paymentHash)
        assertEquals(hash32("abcd").toHex(), decoded?.descriptionHash)
        assertEquals("desc", decoded?.description)
    }

    @Test
    fun `decode still rejects malformed strings`() {
        assertNull(Bolt11.decode(""))
        assertNull(Bolt11.decode("lnbc1"))
        assertNull(Bolt11.decode("zzz1000n1@@@@@"))
    }

    @Test
    fun `millisatoshi amounts decode exactly in every unit`() {
        val invoice = buildInvoice("lnbc100000000000n", listOf(23 to hash32("00")))
        assertEquals("100000000000", invoice.drop(4).take(12))

        assertEquals(100L, Bolt11.decodeAmountMsat("lnbc1n1@@@@@"))
        assertEquals(1_000L, Bolt11.decodeAmountMsat("lnbc10n1@@@@@"))
        assertEquals(100_000L, Bolt11.decodeAmountMsat("lnbc1u1@@@@@"))
        assertEquals(100_000_000L, Bolt11.decodeAmountMsat("lnbc1m1@@@@@"))
        // Whole-BTC amounts have no unit suffix: amount "1" then the bech32 separator "1".
        assertEquals(100_000_000_000L, Bolt11.decodeAmountMsat("lnbc11@@@@@"))
        assertEquals(100L, Bolt11.decodeAmountMsat("lnbc1000p1@@@@@"))
        assertEquals(null, Bolt11.decodeAmountMsat("lnbc1xyz1@@@@@"))
    }

    @Test
    fun `overflowing amounts are rejected instead of wrapping`() {
        // 92_233_720_369 whole BTC x 10^11 wraps Long and previously produced a
        // small positive value; it must be rejected outright.
        assertNull(Bolt11.decodeAmountMsat("lnbc92233720369@@@@@"))
        assertNull(Bolt11.decodeAmountMsat("lnbc99999999999m@@@@@"))
        assertNull(Bolt11.decodeAmountMsat("lnbc99999999999999999999n1@@@@@"))
    }

    @Test
    fun `maximum representable amount still parses`() {
        // 92_233_720 whole BTC x 10^11 is the largest whole-BTC amount below Long.MAX;
        // one more digit overflows and must be rejected.
        assertEquals(9_223_372_000_000_000_000L, Bolt11.decodeAmountMsat("lnbc922337201@@@@@"))
        assertNull(Bolt11.decodeAmountMsat("lnbc922337203681@@@@@"))
    }

    @Test
    fun `zero amounts are rejected`() {
        assertNull(Bolt11.decodeAmountMsat("lnbc0n1@@@@@"))
        assertNull(Bolt11.decodeAmountMsat("lnbc0@@@@@"))
    }

    @Test
    fun `pico amounts below one millisatoshi are rejected`() {
        assertNull(Bolt11.decodeAmountMsat("lnbc5p1@@@@@"))
        assertEquals(100L, Bolt11.decodeAmountMsat("lnbc1000p1@@@@@"))
    }

    @Test
    fun `non-mainnet currency prefixes are rejected for zaps`() {
        assertNull(Bolt11.decodeAmountMsat("lntb1u1@@@@@"))
        assertNull(Bolt11.decodeAmountMsat("lnbcrt1u1@@@@@"))
        assertNull(Bolt11.decodeAmountMsat("lnsb1u1@@@@@"))
        assertEquals(100_000L, Bolt11.decodeAmountMsat("lnbc1u1@@@@@"))
    }

    @Test
    fun `lightning prefix is stripped for amount decoding`() {
        assertEquals(100_000L, Bolt11.decodeAmountMsat("lightning:lnbc1u1@@@@@"))
        assertEquals(100_000_000_000L, Bolt11.decodeAmountMsat("LIGHTNING:lnbc11@@@@@"))
    }
}
