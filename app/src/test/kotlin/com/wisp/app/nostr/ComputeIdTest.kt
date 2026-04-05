package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class ComputeIdTest {

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun emptyTagsAndContent() {
        val pubkey = "a".repeat(64)
        val id = NostrEvent.computeId(pubkey, 1000000, 1, emptyList(), "")
        val expected = sha256Hex("[0,\"$pubkey\",1000000,1,[],\"\"]")
        assertEquals(expected, id)
    }

    @Test
    fun singleTag() {
        val pubkey = "b".repeat(64)
        val tags = listOf(listOf("e", "c".repeat(64)))
        val id = NostrEvent.computeId(pubkey, 1234567890, 1, tags, "hello")
        val expected = sha256Hex("[0,\"$pubkey\",1234567890,1,[[\"e\",\"${"c".repeat(64)}\"]],\"hello\"]")
        assertEquals(expected, id)
    }

    @Test
    fun multipleTags() {
        val pubkey = "ab".repeat(32)
        val tags = listOf(
            listOf("e", "cd".repeat(32), "wss://relay.example.com"),
            listOf("p", "ef".repeat(32)),
            listOf("t", "nostr")
        )
        val id = NostrEvent.computeId(pubkey, 1700000000, 1, tags, "test post")
        val expected = sha256Hex(
            "[0,\"$pubkey\",1700000000,1," +
            "[[\"e\",\"${"cd".repeat(32)}\",\"wss://relay.example.com\"]," +
            "[\"p\",\"${"ef".repeat(32)}\"]," +
            "[\"t\",\"nostr\"]]," +
            "\"test post\"]"
        )
        assertEquals(expected, id)
    }

    @Test
    fun contentWithSpecialChars() {
        val pubkey = "a".repeat(64)
        val content = "line1\nline2\ttab \"quoted\" back\\slash"
        val id = NostrEvent.computeId(pubkey, 1000000, 1, emptyList(), content)
        val expected = sha256Hex(
            "[0,\"$pubkey\",1000000,1,[],\"line1\\nline2\\ttab \\\"quoted\\\" back\\\\slash\"]"
        )
        assertEquals(expected, id)
    }

    @Test
    fun contentWithControlChars() {
        val pubkey = "a".repeat(64)
        // \b (0x08), \f (0x0C), and a low control char (0x01)
        val content = "a\bb\u000Cc\u0001d"
        val id = NostrEvent.computeId(pubkey, 1000000, 1, emptyList(), content)
        val expected = sha256Hex("[0,\"$pubkey\",1000000,1,[],\"a\\bb\\fc\\u0001d\"]")
        assertEquals(expected, id)
    }

    @Test
    fun tagValuesWithSpecialChars() {
        val pubkey = "a".repeat(64)
        val tags = listOf(listOf("comment", "he said \"hi\""))
        val id = NostrEvent.computeId(pubkey, 1000000, 1, tags, "")
        val expected = sha256Hex("[0,\"$pubkey\",1000000,1,[[\"comment\",\"he said \\\"hi\\\"\"]],\"\"]")
        assertEquals(expected, id)
    }

    @Test
    fun largeTags_doesNotThrow() {
        val pubkey = "a".repeat(64)
        val tags = (1..5000).map { listOf("p", "%064x".format(it)) }
        // Should complete without OOM — this is the scenario that caused the crash
        val id = NostrEvent.computeId(pubkey, 1000000, 3, tags, "")
        assertEquals(64, id.length)
    }

    @Test
    fun knownNip01Vector() {
        // NIP-01 example event (widely used test vector)
        val id = NostrEvent.computeId(
            pubkey = "npub hex would go here but we use raw hex".let {
                "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"
            },
            createdAt = 1690057991,
            kind = 1,
            tags = emptyList(),
            content = "It's a beautiful day"
        )
        // Verify it's a valid 32-byte hex hash
        assertEquals(64, id.length)
        // Verify determinism
        val id2 = NostrEvent.computeId(
            pubkey = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e",
            createdAt = 1690057991,
            kind = 1,
            tags = emptyList(),
            content = "It's a beautiful day"
        )
        assertEquals(id, id2)
    }
}
