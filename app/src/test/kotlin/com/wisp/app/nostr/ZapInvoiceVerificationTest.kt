package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate between the LNURL callback and the wallet: the provider chooses the
 * invoice, so it must be parsed, unexpired, hash-carrying, and amount-matched
 * before [ZapSender] commits funds. Pure JVM — no signatures involved, only
 * BOLT-11 decoding.
 */
class ZapInvoiceVerificationTest {
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
        if (bits > 0) out.add((acc shl (5 - bits)) and 31)
        return out.toIntArray()
    }

    private fun invoice(
        sats: Long,
        paymentHash: ByteArray?,
        timestamp: Long = System.currentTimeMillis() / 1000,
        expirySeconds: Int? = 3600,
        breakChecksum: Boolean = false
    ): String {
        val data = mutableListOf<Int>()
        val ts = if (timestamp >= 0) timestamp else 1_700_000_000L
        for (i in 6 downTo 0) data.add(((ts shr (5 * i)) and 31L).toInt())
        val p5 = bytesTo5(paymentHash ?: ByteArray(0))
        if (paymentHash != null) {
            data.add(1)
            data.add((p5.size shr 5) and 31)
            data.add(p5.size and 31)
            data.addAll(p5.toList())
        }
        if (expirySeconds != null) {
            val e5 = bytesTo5(byteArrayOf(expirySeconds.toByte()))
            data.add(6)
            data.add((e5.size shr 5) and 31)
            data.add(e5.size and 31)
            data.addAll(e5.toList())
        }
        repeat(104) { data.add(0) }
        // sats -> canonical nBTC hrp units (1 nBTC = 0.1 sat): 100 sats = 1000n.
        val hrp = "lnbc${sats * 10}n"
        val values = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 } +
            data + listOf(0, 0, 0, 0, 0, 0)
        val mod = polymod(values) xor (if (breakChecksum) 0 else 1)
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + checksum.toList()).map { charset[it] }.joinToString("")
    }

    private val paymentHash = ByteArray(32) { it.toByte() }

    @Test
    fun `matching unexpired invoice is acceptable`() {
        assertTrue(Nip57.isInvoiceAcceptable(invoice(100L, paymentHash), 100_000L))
    }

    @Test
    fun `inflated invoice amount is rejected`() {
        val inv = invoice(1_000L, paymentHash)
        assertFalse(Nip57.isInvoiceAcceptable(inv, 100_000L))
    }

    @Test
    fun `deflated invoice amount is rejected`() {
        val inv = invoice(1L, paymentHash)
        assertFalse(Nip57.isInvoiceAcceptable(inv, 100_000L))
    }

    @Test
    fun `expired invoice is rejected`() {
        val tenHoursAgo = System.currentTimeMillis() / 1000 - 10 * 3600
        val inv = invoice(100L, paymentHash, timestamp = tenHoursAgo)
        assertTrue(Bolt11.decode(inv)?.isExpired() == true)
        assertFalse(Nip57.isInvoiceAcceptable(inv, 100_000L))
    }

    @Test
    fun `invoice without payment hash is rejected`() {
        val inv = invoice(100L, null)
        assertNullHash(inv)
        assertFalse(Nip57.isInvoiceAcceptable(inv, 100_000L))
    }

    private fun assertNullHash(inv: String) {
        assertNull(Bolt11.decode(inv)?.paymentHash)
    }

    @Test
    fun `non-positive requested amount is rejected`() {
        val inv = invoice(100L, paymentHash)
        assertFalse(Nip57.isInvoiceAcceptable(inv, 0L))
        assertFalse(Nip57.isInvoiceAcceptable(inv, -1_000L))
    }

    @Test
    fun `undecodable invoice is rejected`() {
        assertFalse(Nip57.isInvoiceAcceptable("not an invoice", 100_000L))
    }

    @Test
    fun `sub-sat rounding in the request is tolerated`() {
        // 100_999 msat floors to 100 sats; the invoice is denominated in sats.
        val inv = invoice(100L, paymentHash)
        assertTrue(Nip57.isInvoiceAcceptable(inv, 100_999L))
    }

    @Test
    fun `corrupted checksum is rejected outright`() {
        val broken = invoice(100L, paymentHash, breakChecksum = true)
        assertEquals(null, Bolt11.decode(broken))
        assertFalse(Nip57.isInvoiceAcceptable(broken, 100_000L))
    }
}
