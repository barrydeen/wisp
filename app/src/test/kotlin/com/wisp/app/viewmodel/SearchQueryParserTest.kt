package com.wisp.app.viewmodel

import com.wisp.app.nostr.Nip19
import com.wisp.app.nostr.NostrUriData
import com.wisp.app.nostr.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryParserTest {

    private val pubkeyBytes = ByteArray(32) { (it + 1).toByte() }
    private val pubkeyHex = pubkeyBytes.toHex()
    private val eventBytes = ByteArray(32) { (it + 5).toByte() }
    private val eventHex = eventBytes.toHex()

    private fun tlv(type: Int, value: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), value.size.toByte()) + value

    private fun naddrEncode(kind: Int, author: ByteArray, dTag: String): String {
        val kindBytes = byteArrayOf(
            (kind shr 24).toByte(),
            (kind shr 16).toByte(),
            (kind shr 8).toByte(),
            kind.toByte()
        )
        val payload = tlv(0x03, kindBytes) + tlv(0x02, author) + tlv(0x00, dTag.toByteArray())
        return Nip19.bech32Encode("naddr", payload)
    }

    @Test
    fun `bare npub resolves to profile entity`() {
        val npub = Nip19.npubEncode(pubkeyBytes)
        assertEquals(
            DirectTarget.Entity(NostrUriData.ProfileRef(pubkeyHex)),
            SearchQueryParser.parse(npub)
        )
    }

    @Test
    fun `nostr prefixed npub resolves to profile entity`() {
        val npub = Nip19.npubEncode(pubkeyBytes)
        assertEquals(
            DirectTarget.Entity(NostrUriData.ProfileRef(pubkeyHex)),
            SearchQueryParser.parse("nostr:$npub")
        )
    }

    @Test
    fun `uppercase bech32 is lowercased before decoding`() {
        val npub = Nip19.npubEncode(pubkeyBytes).uppercase()
        assertEquals(
            DirectTarget.Entity(NostrUriData.ProfileRef(pubkeyHex)),
            SearchQueryParser.parse(npub)
        )
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val npub = Nip19.npubEncode(pubkeyBytes)
        assertEquals(
            DirectTarget.Entity(NostrUriData.ProfileRef(pubkeyHex)),
            SearchQueryParser.parse("  $npub\n")
        )
    }

    @Test
    fun `nprofile keeps relay hints`() {
        val nprofile = Nip19.nprofileEncode(pubkeyHex, listOf("wss://relay.damus.io"))
        assertEquals(
            DirectTarget.Entity(NostrUriData.ProfileRef(pubkeyHex, listOf("wss://relay.damus.io"))),
            SearchQueryParser.parse(nprofile)
        )
    }

    @Test
    fun `nevent resolves to note ref with author and relays`() {
        val nevent = Nip19.neventEncode(eventBytes, listOf("wss://a.example"), pubkeyBytes)
        assertEquals(
            DirectTarget.Entity(NostrUriData.NoteRef(eventHex, listOf("wss://a.example"), pubkeyHex)),
            SearchQueryParser.parse(nevent)
        )
    }

    @Test
    fun `note resolves to note ref`() {
        val note = Nip19.bech32Encode("note", eventBytes)
        assertEquals(
            DirectTarget.Entity(NostrUriData.NoteRef(eventHex)),
            SearchQueryParser.parse(note)
        )
    }

    @Test
    fun `naddr resolves kind author and d tag`() {
        val naddr = naddrEncode(30023, pubkeyBytes, "my-article")
        assertEquals(
            DirectTarget.Entity(NostrUriData.AddressRef("my-article", emptyList(), pubkeyHex, 30023)),
            SearchQueryParser.parse(naddr)
        )
    }

    @Test
    fun `entity embedded in a web url is extracted`() {
        val npub = Nip19.npubEncode(pubkeyBytes)
        assertEquals(
            DirectTarget.Entity(NostrUriData.ProfileRef(pubkeyHex)),
            SearchQueryParser.parse("https://njump.me/$npub")
        )
        val note = Nip19.bech32Encode("note", eventBytes)
        assertEquals(
            DirectTarget.Entity(NostrUriData.NoteRef(eventHex)),
            SearchQueryParser.parse("https://primal.net/e/$note")
        )
    }

    @Test
    fun `64 char lowercase hex is a direct hex target`() {
        assertEquals(DirectTarget.Hex(pubkeyHex), SearchQueryParser.parse(pubkeyHex))
    }

    @Test
    fun `uppercase hex is normalized to lowercase`() {
        assertEquals(
            DirectTarget.Hex(pubkeyHex),
            SearchQueryParser.parse("nostr:" + pubkeyHex.uppercase())
        )
    }

    @Test
    fun `nip05 address is lowercased`() {
        assertEquals(
            DirectTarget.Nip05Address("bob@example.com"),
            SearchQueryParser.parse("Bob@Example.COM")
        )
    }

    @Test
    fun `nip05 root underscore identifier is accepted`() {
        assertEquals(
            DirectTarget.Nip05Address("_@example.com"),
            SearchQueryParser.parse("_@example.com")
        )
    }

    @Test
    fun `nip05 subdomain is accepted`() {
        assertEquals(
            DirectTarget.Nip05Address("bob@mail.example.org"),
            SearchQueryParser.parse("bob@mail.example.org")
        )
    }

    @Test
    fun `plain text returns null`() {
        assertNull(SearchQueryParser.parse("kittens"))
        assertNull(SearchQueryParser.parse("kittens please"))
    }

    @Test
    fun `empty and blank queries return null`() {
        assertNull(SearchQueryParser.parse(""))
        assertNull(SearchQueryParser.parse("   "))
        assertNull(SearchQueryParser.parse("\n"))
    }

    @Test
    fun `url without an embedded entity returns null`() {
        assertNull(SearchQueryParser.parse("https://example.com/nostr"))
    }

    @Test
    fun `nip05 without tld returns null`() {
        assertNull(SearchQueryParser.parse("bob@example"))
        assertNull(SearchQueryParser.parse("bob@"))
    }

    @Test
    fun `bech32 without data part returns null`() {
        assertNull(SearchQueryParser.parse("npub1"))
    }

    @Test
    fun `bech32 with bad checksum returns null`() {
        assertNull(SearchQueryParser.parse("note1qqqxx"))
    }

    @Test
    fun `non hex 64 char string returns null`() {
        assertNull(SearchQueryParser.parse("g".repeat(64)))
        assertNull(SearchQueryParser.parse(pubkeyHex.dropLast(1) + "g"))
    }
}
