package com.wisp.app.repo

/**
 * Confirmation speed for an on-chain withdrawal, mapped to the SDK's three
 * fee tiers. SDK-free so the UI and tests don't depend on the bindings.
 */
enum class WithdrawOnchainSpeed(val label: String, val detail: String) {
    SLOW("Economy", "Cheapest. May take hours to confirm."),
    MEDIUM("Standard", "Balanced fee and confirmation time."),
    FAST("Priority", "Highest fee. Confirms soonest.")
}

/**
 * What a withdrawal would cost and deliver, quoted before anything is signed.
 *
 * [spendSats] is the whole spendable balance and [feeSats] comes out of it -
 * the SDK's FeePolicy.FEES_INCLUDED - so [netSats] is what actually lands at
 * the destination. Quoting this way is the only honest way to drain: with
 * fees added on top, a send of the full balance can never succeed.
 */
data class WithdrawOnchainQuote(
    val address: String,
    val spendSats: Long,
    val feeSats: Long,
    val speed: WithdrawOnchainSpeed
) {
    val netSats: Long get() = (spendSats - feeSats).coerceAtLeast(0L)

    /**
     * True when the fee would consume everything. Spark's dust and fee floors
     * mean a small balance can be genuinely unspendable on-chain - better to
     * say so than to broadcast something that delivers zero.
     */
    val isUneconomical: Boolean get() = netSats <= 0L

    /**
     * Share of the balance eaten by fees, for the warning copy. Draining a
     * small balance can cost a large percentage, and that should be visible
     * before confirming rather than discovered after.
     */
    val feePercent: Double
        get() = if (spendSats <= 0L) 0.0 else (feeSats.toDouble() / spendSats.toDouble()) * 100.0
}

/**
 * Funds a withdrawal cannot move, reported after the fact.
 *
 * "Recover everything" is not literally achievable: tokens below the
 * provider's conversion floor cannot be converted at any price, and Spark
 * leaves worth less than their own exit cost are not worth spending. Naming
 * the remainder is more useful than implying it does not exist.
 */
data class WithdrawOnchainRemainder(
    /** Ticker to human-readable amount left behind, e.g. "USDB" to "0.34". */
    val strandedTokens: Map<String, String> = emptyMap(),
    val strandedSats: Long = 0L
) {
    val isEmpty: Boolean get() = strandedTokens.isEmpty() && strandedSats == 0L
}

/**
 * Pull a bare address out of whatever a wallet's QR actually encodes.
 *
 * Bitcoin QRs are usually BIP-21 URIs - "bitcoin:bc1q...?amount=0.01&label=x"
 * - and the SDK's parser wants the address alone. The amount is deliberately
 * discarded: this screen always sends the whole balance, so honoring a
 * requested amount would contradict what the button says.
 */
fun normalizeBitcoinAddress(raw: String): String {
    var value = raw.trim()
    // Anchored and case-insensitive: some wallets emit "BITCOIN:", and an
    // address containing "bitcoin:" mid-string must not be mangled.
    if (value.length >= 8 && value.substring(0, 8).equals("bitcoin:", ignoreCase = true)) {
        value = value.substring(8)
    }
    val query = value.indexOf('?')
    if (query >= 0) value = value.substring(0, query)
    return value.trim()
}
