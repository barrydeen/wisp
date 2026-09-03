package com.wisp.app.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the Withdraw on-chain confirmation screen, and the
 * BIP-21 address normalization. Pure, and worth pinning: these numbers are
 * what a user reads before irreversibly emptying their wallet.
 *
 * Ported from wisp-ios#452.
 */
class WithdrawOnchainTest {

    private fun quote(
        spend: Long,
        fee: Long,
        speed: WithdrawOnchainSpeed = WithdrawOnchainSpeed.MEDIUM
    ) = WithdrawOnchainQuote("bc1qexample", spend, fee, speed)

    // ---- Quote arithmetic ----

    /** The fee comes OUT of the balance (FEES_INCLUDED). */
    @Test
    fun `net is balance minus fee`() {
        assertEquals(97_500L, quote(100_000, 2_500).netSats)
    }

    /** Never advertise a negative amount. */
    @Test
    fun `net clamps at zero`() {
        assertEquals(0L, quote(1_000, 5_000).netSats)
    }

    @Test
    fun `a fee that eats everything is uneconomical`() {
        assertTrue(quote(1_000, 5_000).isUneconomical)
        assertTrue(quote(1_000, 1_000).isUneconomical)
    }

    @Test
    fun `an ordinary drain is economical`() {
        assertFalse(quote(100_000, 2_500).isUneconomical)
    }

    @Test
    fun `fee percent is share of balance`() {
        assertEquals(10.0, quote(100_000, 10_000).feePercent, 0.001)
        assertEquals(25.0, quote(20_000, 5_000).feePercent, 0.001)
    }

    /** No division by zero on an empty wallet. */
    @Test
    fun `fee percent on an empty balance is zero`() {
        assertEquals(0.0, quote(0, 500).feePercent, 0.001)
    }

    /**
     * Equality gates execution: executeWithdrawOnchain refuses unless the held
     * quote matches the one the user confirmed, so a drifted screen can't send
     * different terms than were signed off.
     */
    @Test
    fun `quotes differing in any field are not equal`() {
        val base = quote(100_000, 2_500)
        assertTrue(base != quote(100_001, 2_500))
        assertTrue(base != quote(100_000, 2_600))
        assertTrue(base != quote(100_000, 2_500, WithdrawOnchainSpeed.FAST))
        assertTrue(base != WithdrawOnchainQuote("bc1qother", 100_000, 2_500, WithdrawOnchainSpeed.MEDIUM))
        assertEquals(base, quote(100_000, 2_500))
    }

    @Test
    fun `every speed is labelled and explained`() {
        WithdrawOnchainSpeed.entries.forEach {
            assertTrue(it.label.isNotEmpty())
            assertTrue(it.detail.isNotEmpty())
        }
    }

    // ---- Remainder ----

    @Test
    fun `nothing stranded is empty`() {
        assertTrue(WithdrawOnchainRemainder().isEmpty)
    }

    /** Tokens under the conversion floor can't move at any price. */
    @Test
    fun `stranded tokens are not empty`() {
        assertFalse(WithdrawOnchainRemainder(strandedTokens = mapOf("USDB" to "0.34")).isEmpty)
    }

    @Test
    fun `stranded sats are not empty`() {
        assertFalse(WithdrawOnchainRemainder(strandedSats = 120).isEmpty)
    }

    // ---- BIP-21 normalization ----

    @Test
    fun `a bare address passes through`() {
        assertEquals("bc1qexample", normalizeBitcoinAddress("bc1qexample"))
    }

    @Test
    fun `strips the bip21 scheme`() {
        assertEquals("bc1qexample", normalizeBitcoinAddress("bitcoin:bc1qexample"))
    }

    /** Some wallets emit an uppercase scheme. */
    @Test
    fun `scheme match is case insensitive`() {
        assertEquals("bc1qexample", normalizeBitcoinAddress("BITCOIN:bc1qexample"))
    }

    /**
     * The amount is discarded on purpose: this screen always sends the whole
     * balance, so honoring a requested amount would contradict the button.
     */
    @Test
    fun `drops query parameters`() {
        assertEquals(
            "bc1qexample",
            normalizeBitcoinAddress("bitcoin:bc1qexample?amount=0.01&label=x")
        )
    }

    @Test
    fun `trims whitespace and newlines`() {
        assertEquals("bc1qexample", normalizeBitcoinAddress("  bc1qexample\n"))
    }

    /** "bitcoin" mid-string is not a scheme - an address must not be mangled. */
    @Test
    fun `only an anchored scheme is stripped`() {
        assertEquals("bc1qbitcoin:x", normalizeBitcoinAddress("bc1qbitcoin:x"))
    }

    /** Shorter than the scheme itself must not throw. */
    @Test
    fun `short input is safe`() {
        assertEquals("bc1", normalizeBitcoinAddress("bc1"))
        assertEquals("", normalizeBitcoinAddress(""))
    }
}
