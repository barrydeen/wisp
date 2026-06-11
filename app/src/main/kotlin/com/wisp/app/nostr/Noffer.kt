package com.wisp.app.nostr

/**
 * CLINK Offers (`noffer1…`) — a Nostr-native successor to LNURL-pay.
 *
 * Spec: https://github.com/shocknet/CLINK/blob/main/specs/clink-offers.md
 *
 * A `noffer1…` bech32 string carries TLVs describing a static payment offer:
 *
 *   TLV 0 — 32-byte service pubkey (hex)
 *   TLV 1 — recommended relay URL (utf-8) where the service listens
 *   TLV 2 — opaque offer identifier (utf-8)
 *   TLV 3 — (opt) pricing type: 0=Fixed, 1=Variable, 2=Spontaneous (default)
 *   TLV 4 — (opt) price in sats (big-endian integer)
 *   TLV 5 — (opt) currency code (utf-8; only meaningful with Variable)
 *
 * The payer NIP-44 encrypts a kind-21001 request to the service pubkey and the
 * service replies with an encrypted kind-21001 carrying a bolt11 invoice —
 * see `NofferClient`.
 */
enum class NofferPricing {
    FIXED,
    VARIABLE,
    SPONTANEOUS;

    companion object {
        /**
         * TLV 3 byte → pricing type. Matches the CLINK spec and the zap.cooking
         * reference decoder: 0=Fixed, 1=Variable, anything else (incl. 2) =
         * Spontaneous. Absent TLV 3 also defaults to Spontaneous.
         */
        fun fromByte(byte: Int): NofferPricing = when (byte) {
            0 -> FIXED
            1 -> VARIABLE
            else -> SPONTANEOUS
        }
    }
}

data class NofferData(
    /** 32-byte service pubkey, hex-encoded lowercase. (TLV 0) */
    val pubkey: String,
    /** Recommended relay URL where the service listens. (TLV 1) */
    val relay: String,
    /** Opaque offer identifier the service uses to look up the offer. (TLV 2) */
    val offerId: String,
    /** Pricing type — defaults to SPONTANEOUS when TLV 3 is absent. */
    val pricing: NofferPricing,
    /** Price in sats. (TLV 4) Present for Fixed offers and as a hint for Variable. */
    val price: Long?,
    /** Currency code (TLV 5) — only meaningful when pricing == VARIABLE. */
    val currency: String?,
    /**
     * The bare `noffer1…` string (no `nostr:` prefix) this was decoded from.
     * Kept so callers can re-display / QR-encode the offer verbatim — the spec
     * requires QR payloads to be exactly the bech32 string.
     */
    val raw: String
) {
    /**
     * True when the payer must (or may) supply an amount: Spontaneous always
     * needs one; Variable lets the payer hint at one (the service decides);
     * Fixed bakes the amount into the offer.
     */
    val acceptsAmount: Boolean
        get() = pricing != NofferPricing.FIXED
}

/**
 * Typed failure surfaced by the CLINK service (or the client on timeout /
 * transport failure). `code` follows the spec's error table; `code == 0`
 * marks a local/transport failure with no service code.
 */
class NofferException(
    val code: Int,
    message: String,
    val rangeMin: Long? = null,
    val rangeMax: Long? = null,
    val latest: String? = null
) : Exception(message)

object Noffer {

    private val nofferRegex = Regex(
        """^(nostr:)?noffer1[023456789acdefghjklmnpqrstuvwxyz]{20,}$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Lightweight detector — checks shape only, does not decode TLVs. Use this
     * in segment / paste parsers where you just need "is this noffer-shaped";
     * call [decode] for real use.
     */
    fun isNofferString(s: String?): Boolean {
        if (s == null) return false
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return false
        return nofferRegex.matches(trimmed)
    }

    /**
     * Strip a leading `nostr:` prefix (and surrounding whitespace) and return
     * the bare `noffer1…` token. Use before building a QR payload — the spec
     * requires the QR to be exactly the bech32 string with no scheme prefix.
     */
    fun stripNostrPrefix(noffer: String): String {
        val trimmed = noffer.trim()
        return if (trimmed.startsWith("nostr:", ignoreCase = true)) {
            trimmed.substring("nostr:".length)
        } else {
            trimmed
        }
    }

    /**
     * Decode a `noffer1…` (or `nostr:noffer1…`) string into its TLV fields.
     * Throws on wrong HRP, non-bech32 input, truncated TLVs, or a missing /
     * wrong-length required TLV (0/1/2).
     */
    fun decode(input: String): NofferData {
        val cleaned = stripNostrPrefix(input)
        require(cleaned.lowercase().startsWith("noffer1")) { "Not a noffer string" }
        val (hrp, data) = Nip19.bech32Decode(cleaned)
        require(hrp == "noffer") { "Expected noffer, got $hrp" }

        val tlvs = parseTlvs(data)

        val pubkeyTlv = tlvs.firstOrNull { it.first == 0 }
        require(pubkeyTlv != null && pubkeyTlv.second.size == 32) { "noffer missing service pubkey" }
        val relay = tlvs.firstOrNull { it.first == 1 }?.second?.toString(Charsets.UTF_8)
        require(!relay.isNullOrEmpty()) { "noffer missing relay" }
        val offerTlv = tlvs.firstOrNull { it.first == 2 }
        require(offerTlv != null) { "noffer missing offer id" }
        val offerId = offerTlv.second.toString(Charsets.UTF_8)

        val pricing = tlvs.firstOrNull { it.first == 3 }?.second?.firstOrNull()
            ?.let { NofferPricing.fromByte(it.toInt() and 0xFF) }
            ?: NofferPricing.SPONTANEOUS

        val price = tlvs.firstOrNull { it.first == 4 }?.second
            ?.takeIf { it.isNotEmpty() }
            ?.let { bigEndianLong(it) }

        val currency = tlvs.firstOrNull { it.first == 5 }?.second
            ?.takeIf { it.isNotEmpty() }
            ?.toString(Charsets.UTF_8)

        return NofferData(
            pubkey = pubkeyTlv.second.toHex(),
            relay = relay,
            offerId = offerId,
            pricing = pricing,
            price = price,
            currency = currency,
            raw = cleaned
        )
    }

    /** Decode tolerantly, returning null instead of throwing. */
    fun decodeOrNull(input: String): NofferData? = try {
        decode(input)
    } catch (_: Exception) {
        null
    }

    // --- TLV parsing ---

    private fun parseTlvs(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val tlvs = mutableListOf<Pair<Int, ByteArray>>()
        var i = 0
        while (i < bytes.size) {
            require(i + 2 <= bytes.size) { "Truncated TLV" }
            val type = bytes[i].toInt() and 0xFF
            val length = bytes[i + 1].toInt() and 0xFF
            require(i + 2 + length <= bytes.size) { "Truncated TLV value" }
            tlvs.add(type to bytes.copyOfRange(i + 2, i + 2 + length))
            i += 2 + length
        }
        return tlvs
    }

    private fun bigEndianLong(bytes: ByteArray): Long {
        var n = 0L
        for (b in bytes) n = n * 256 + (b.toInt() and 0xFF)
        return n
    }
}
