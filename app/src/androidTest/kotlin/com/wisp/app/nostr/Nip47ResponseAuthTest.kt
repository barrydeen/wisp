package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NWC response/info authentication regression tests. NWC traffic runs over a
 * dedicated relay connection that bypasses RelayPool's signature gate, so
 * [Nip47.responseFailureReason] and [Nip47.infoFailureReason] are the
 * authentication boundary for everything that can complete a pending wallet
 * request. Uses synthetic keys and real Schnorr operations.
 *
 * The file deliberately avoids Android-only APIs so the identical source runs
 * in the isolated JVM harness used for local validation.
 */
class Nip47ResponseAuthTest {
    private val wallet = Keys.generate()   // wallet service
    private val client = Keys.generate()   // this client
    private val attacker = Keys.generate()
    private val otherClient = Keys.generate()

    private val connection = Nip47.NwcConnection(
        walletServicePubkey = wallet.pubkey,
        relayUrl = "wss://relay.a",
        clientSecret = client.privkey
    )

    private fun signedResponse(
        signer: Keys.Keypair,
        recipient: ByteArray = client.pubkey,
        kind: Int = 23195,
        content: String = "ciphertext"
    ): NostrEvent = NostrEvent.create(
        signer.privkey, signer.pubkey, kind, content,
        listOf(listOf("p", recipient.toHex()))
    )

    // ─── Response authentication ────────────────────────────────────────────

    @Test
    fun validResponseFromWalletServiceToThisClientIsAccepted() {
        val event = signedResponse(wallet)
        assertNull(Nip47.responseFailureReason(connection, event))
    }

    @Test
    fun responseWithInvalidSignatureIsRejected() {
        val unsigned = NostrEvent.createUnsigned(
            wallet.pubkey.toHex(), 23195, "ciphertext",
            listOf(listOf("p", client.pubkey.toHex()))
        )
        assertEquals("invalid signature", Nip47.responseFailureReason(connection, unsigned))
    }

    @Test
    fun responseFromWrongWalletServiceIsRejected() {
        val event = signedResponse(attacker)
        assertEquals("unexpected author", Nip47.responseFailureReason(connection, event))
    }

    @Test
    fun responseAddressedToAnotherClientIsRejected() {
        val event = signedResponse(wallet, recipient = otherClient.pubkey)
        assertEquals("recipient mismatch", Nip47.responseFailureReason(connection, event))
    }

    @Test
    fun responseWithoutRecipientTagIsRejected() {
        val event = NostrEvent.create(
            wallet.privkey, wallet.pubkey, 23195, "ciphertext"
        )
        assertEquals("missing recipient tag", Nip47.responseFailureReason(connection, event))
    }

    @Test
    fun nonResponseKindsAreRejected() {
        val event = signedResponse(wallet, kind = 4)
        assertEquals("not a response event", Nip47.responseFailureReason(connection, event))
    }

    @Test
    fun tamperedResponsePayloadIsRejected() {
        val event = signedResponse(wallet)
        assertEquals(
            "invalid signature",
            Nip47.responseFailureReason(connection, event.copy(content = "tampered"))
        )
        assertEquals(
            "invalid signature",
            Nip47.responseFailureReason(connection, event.copy(id = "00".repeat(32)))
        )
    }

    // ─── Info event authentication ──────────────────────────────────────────

    @Test
    fun validInfoEventFromWalletServiceIsAccepted() {
        val event = NostrEvent.create(
            wallet.privkey, wallet.pubkey, 13194, "{}",
            listOf(listOf("encryption", "nip44_v2"))
        )
        assertNull(Nip47.infoFailureReason(connection, event))
    }

    @Test
    fun fakeInfoEventFromAttackerIsRejected() {
        // A hostile relay can serve any event claiming the wallet's pubkey; the
        // relay-side authors filter is not trusted. An attacker-signed fake
        // must be rejected even though its own signature is valid.
        val event = NostrEvent.create(
            attacker.privkey, attacker.pubkey, 13194, "{}",
            listOf(listOf("encryption", "nip04"))
        )
        assertEquals("unexpected author", Nip47.infoFailureReason(connection, event))
    }

    @Test
    fun unsignedInfoEventIsRejected() {
        val event = NostrEvent.createUnsigned(
            wallet.pubkey.toHex(), 13194, "{}",
            listOf(listOf("encryption", "nip44_v2"))
        )
        assertEquals("invalid signature", Nip47.infoFailureReason(connection, event))
    }

    @Test
    fun tamperedInfoEventIsRejected() {
        val event = NostrEvent.create(
            wallet.privkey, wallet.pubkey, 13194, "{}",
            listOf(listOf("encryption", "nip44_v2"))
        )
        assertEquals(
            "invalid signature",
            Nip47.infoFailureReason(connection, event.copy(content = "{\"encryption\":\"nip04 v2\"}"))
        )
    }
}
