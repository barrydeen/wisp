package com.wisp.app.repo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spark wallets hold tokens alongside sats, and Payment.amount is documented
 * as "satoshis OR token base units". This app offers no token conversion, but
 * a wallet restored from the same seed in an app that does hands us those
 * payments anyway - and reading their base units as sats turned a 15.77 USDB
 * transfer into "15,766,673 sats".
 *
 * Ported from wisp-ios#451.
 */
class TokenAmountsTest {

    // --- Decimal shifting ---

    @Test
    fun `scales by the tokens decimals`() {
        // USDB-shaped: 6 decimals.
        assertEquals("15.766673", TokenAmounts.scale("15766673", 6))
    }

    @Test
    fun `trailing zeros are dropped`() {
        assertEquals("150", TokenAmounts.scale("150000000", 6))
        assertEquals("1.5", TokenAmounts.scale("1500000", 6))
    }

    /** Amounts under one whole unit need a leading zero, not ".5". */
    @Test
    fun `amounts under one unit keep their leading zero`() {
        assertEquals("0.5", TokenAmounts.scale("500000", 6))
        assertEquals("0.000001", TokenAmounts.scale("1", 6))
    }

    @Test
    fun `zero decimals passes through`() {
        assertEquals("42", TokenAmounts.scale("42", 0))
    }

    @Test
    fun `zero is zero`() {
        assertEquals("0", TokenAmounts.scale("0", 6))
    }

    /**
     * Base units are u128. Anything routed through Long would wrap silently,
     * so the scaler works on the decimal string.
     */
    @Test
    fun `amounts beyond Long survive`() {
        val huge = "340282366920938463463374607431768211455"  // u128 max
        assertEquals("3402823669209384634633746074317682114.55", TokenAmounts.scale(huge, 2))
    }

    /** Never crash or fabricate a number on unexpected input. */
    @Test
    fun `non numeric input is returned unchanged`() {
        assertEquals("abc", TokenAmounts.scale("abc", 6))
        assertEquals("", TokenAmounts.scale("", 6))
    }

    // --- Row-sized display ---

    /** USDB is dollars; six places is unreadable and wrong for what it means. */
    @Test
    fun `compact form rounds to two places`() {
        assertEquals("15.77", TokenAmounts.compact("15.766673"))
        assertEquals("15.80", TokenAmounts.compact("15.802229"))
    }

    @Test
    fun `whole amounts pass through without a decimal point`() {
        assertEquals("150", TokenAmounts.compact("150"))
        assertEquals("150.00", TokenAmounts.compact("150.0"))
    }

    /** Dust must never render as "0.00" - that reads as nothing arriving. */
    @Test
    fun `dust keeps full precision`() {
        assertEquals("0.000001", TokenAmounts.compact("0.000001"))
    }

    @Test
    fun `real zero is allowed to be zero`() {
        assertEquals("0.00", TokenAmounts.compact("0.0"))
    }

    @Test
    fun `compact form groups thousands`() {
        assertEquals("1,234.50", TokenAmounts.compact("1234.5"))
    }

    // --- Model ---

    @Test
    fun `a sats row is not a token transfer`() {
        val tx = WalletTransaction(
            type = "incoming", description = null, paymentHash = "h",
            amountMsats = 21_000_000L, createdAt = 0L, settledAt = null
        )
        assertEquals(false, tx.isTokenTransfer)
        assertEquals(null, tx.assetAmountCompact)
    }

    /** The row shows the compact form; the expanded detail keeps every digit. */
    @Test
    fun `a token row exposes both precisions`() {
        val tx = WalletTransaction(
            type = "incoming", description = null, paymentHash = "h",
            amountMsats = 0L, createdAt = 0L, settledAt = null,
            assetTicker = "USDB", assetAmount = "15.766673"
        )
        assertEquals(true, tx.isTokenTransfer)
        assertEquals("15.77", tx.assetAmountCompact)
        assertEquals("15.766673", tx.assetAmount)
    }
}
