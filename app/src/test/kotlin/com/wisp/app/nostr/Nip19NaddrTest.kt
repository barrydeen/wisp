package com.wisp.app.nostr

import org.junit.Test
import kotlin.test.assertEquals

class Nip19NaddrTest {
    @Test
    fun `naddr encode round-trips through decode`() {
        val author = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        val naddr = Nip19.naddrEncode(30023, author, "my-article", listOf("wss://relay.example.com"))
        val decoded = Nip19.naddrDecode(naddr)
        assertEquals("my-article", decoded.dTag)
        assertEquals(author, decoded.author)
        assertEquals(30023, decoded.kind)
        assertEquals(listOf("wss://relay.example.com"), decoded.relays)
    }
}
