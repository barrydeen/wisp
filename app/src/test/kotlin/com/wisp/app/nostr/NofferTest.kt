package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the CLINK `noffer1…` decoder. We don't have a publicly-shared
 * production noffer to pin as a fixture, so each test encodes a synthetic
 * offer via the app's own bech32 encoder and round-trips it through
 * [Noffer.decode], pinning the TLV byte layout from the CLINK spec.
 */
class NofferTest {

    private val pubkeyHex = "ee6ea13ab9fe5c4a68eaf9b1a34fe014a66b40117c50ee2a614f4cda959b6e74"
    private val relay = "wss://relay.example.com"
    private val offerId = "tip-jar"

    private fun encode(tlvs: List<Pair<Int, ByteArray>>): String {
        val bytes = mutableListOf<Byte>()
        for ((type, value) in tlvs) {
            bytes.add(type.toByte())
            bytes.add(value.size.toByte())
            for (b in value) bytes.add(b)
        }
        return Nip19.bech32Encode("noffer", bytes.toByteArray())
    }

    private val pubkeyBytes get() = pubkeyHex.hexToByteArray()
    private val relayBytes get() = relay.toByteArray(Charsets.UTF_8)
    private val offerBytes get() = offerId.toByteArray(Charsets.UTF_8)

    @Test
    fun decodesMinimumTlvsAndDefaultsToSpontaneous() {
        val noffer = encode(listOf(0 to pubkeyBytes, 1 to relayBytes, 2 to offerBytes))
        val decoded = Noffer.decode(noffer)
        assertEquals(pubkeyHex, decoded.pubkey)
        assertEquals(relay, decoded.relay)
        assertEquals(offerId, decoded.offerId)
        assertEquals(NofferPricing.SPONTANEOUS, decoded.pricing)
        assertNull(decoded.price)
        assertNull(decoded.currency)
    }

    @Test
    fun decodesFixedPricingWithPrice() {
        val noffer = encode(listOf(
            0 to pubkeyBytes,
            1 to relayBytes,
            2 to offerBytes,
            3 to byteArrayOf(0),                       // Fixed
            4 to byteArrayOf(0x27, 0x10)               // 10000 big-endian
        ))
        val decoded = Noffer.decode(noffer)
        assertEquals(NofferPricing.FIXED, decoded.pricing)
        assertEquals(10_000L, decoded.price)
    }

    @Test
    fun decodesVariablePricingWithCurrency() {
        val noffer = encode(listOf(
            0 to pubkeyBytes,
            1 to relayBytes,
            2 to offerBytes,
            3 to byteArrayOf(1),                       // Variable
            5 to "USD".toByteArray(Charsets.UTF_8)
        ))
        val decoded = Noffer.decode(noffer)
        assertEquals(NofferPricing.VARIABLE, decoded.pricing)
        assertEquals("USD", decoded.currency)
    }

    @Test
    fun acceptsNostrUriPrefix() {
        val noffer = encode(listOf(0 to pubkeyBytes, 1 to relayBytes, 2 to offerBytes))
        assertEquals(pubkeyHex, Noffer.decode("nostr:$noffer").pubkey)
        assertEquals(pubkeyHex, Noffer.decode("NOSTR:$noffer").pubkey)
        // `raw` always strips the scheme prefix.
        assertEquals(noffer, Noffer.decode("nostr:$noffer").raw)
    }

    @Test
    fun rejectsWrongHrp() {
        assertNull(Noffer.decodeOrNull("npub1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
    }

    @Test
    fun rejectsMissingRequiredTlvs() {
        val noffer = encode(listOf(0 to pubkeyBytes)) // missing relay + offer id
        assertNull(Noffer.decodeOrNull(noffer))
    }

    @Test
    fun rejectsWrongLengthPubkey() {
        val noffer = encode(listOf(
            0 to ByteArray(16),                        // 16-byte pubkey is invalid
            1 to relayBytes,
            2 to offerBytes
        ))
        assertNull(Noffer.decodeOrNull(noffer))
    }

    @Test
    fun isNofferStringMatchesShapeOnly() {
        val noffer = encode(listOf(0 to pubkeyBytes, 1 to relayBytes, 2 to offerBytes))
        assertTrue(Noffer.isNofferString(noffer))
        assertTrue(Noffer.isNofferString("nostr:$noffer"))
        assertFalse(Noffer.isNofferString("npub1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        assertFalse(Noffer.isNofferString(""))
        assertFalse(Noffer.isNofferString(null))
    }

    @Test
    fun stripNostrPrefixWorks() {
        assertEquals("noffer1abc", Noffer.stripNostrPrefix("nostr:noffer1abc"))
        assertEquals("noffer1abc", Noffer.stripNostrPrefix("NOSTR:noffer1abc"))
        assertEquals("noffer1abc", Noffer.stripNostrPrefix("  nostr:noffer1abc  "))
        assertEquals("noffer1abc", Noffer.stripNostrPrefix("noffer1abc"))
    }
}
