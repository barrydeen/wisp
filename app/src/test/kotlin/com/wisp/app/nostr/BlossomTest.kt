package com.wisp.app.nostr

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlossomTest {
    private fun eventWith(
        kind: Int = Blossom.KIND_SERVER_LIST,
        tags: List<List<String>> = emptyList()
    ): NostrEvent {
        return NostrEvent.createUnsigned(
            pubkeyHex = "0".repeat(64),
            kind = kind,
            content = "",
            tags = tags,
            createdAt = 1L
        )
    }

    @Test
    fun `throws IllegalArgumentException when event kind is not server list`() {
        val event = eventWith(
            kind = 1,
            tags = emptyList()
        )

        assertFailsWith<IllegalArgumentException> {
            Blossom.parseServerList(event)
        }
    }

    @Test
    fun `returns empty list when event has no tags`() {
        val event = eventWith(
            kind = Blossom.KIND_SERVER_LIST,
            tags = emptyList()
        )

        val result = Blossom.parseServerList(event)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `ignores empty too short and non server tags`() {
        val event = eventWith(
            kind = Blossom.KIND_SERVER_LIST,
            tags = listOf(
                emptyList(),
                listOf("server"),
                listOf("p", "abc"),
                listOf("serverish", "https://example.com")
            )
        )

        val result = Blossom.parseServerList(event)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `ignores server tags with invalid urls`() {
        val event = eventWith(
            kind = Blossom.KIND_SERVER_LIST,
            tags = listOf(
                listOf("server", ""),
                listOf("server", "not a url"),
                listOf("server", "ftp://example.com")
            )
        )

        val result = Blossom.parseServerList(event)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `returns url from valid server tag`() {
        val event = eventWith(
            kind = Blossom.KIND_SERVER_LIST,
            tags = listOf(
                listOf("server", "https://example.com")
            )
        )

        val result = Blossom.parseServerList(event)

        assertEquals(listOf("https://example.com/"), result)
    }

    @Test
    fun `returns url from valid server tag with extra fields`() {
        val event = eventWith(
            kind = Blossom.KIND_SERVER_LIST,
            tags = listOf(
                listOf("server", "https://example.com", "extra")
            )
        )

        val result = Blossom.parseServerList(event)

        assertEquals(listOf("https://example.com/"), result)
    }

    @Test
    fun `returns distinct valid urls in first occurrence order`() {
        val event = eventWith(
            kind = Blossom.KIND_SERVER_LIST,
            tags = listOf(
                listOf("server", "https://b.com"),
                listOf("p", "abc"),
                listOf("server", "not a url"),
                listOf("server", "https://a.com"),
                listOf("server", "https://b.com"),
                listOf("server", "https://c.com")
            )
        )

        val result = Blossom.parseServerList(event)

        assertEquals(
            listOf(
                "https://b.com/",
                "https://a.com/",
                "https://c.com/"
            ),
            result
        )
    }

    @Test
    fun `buildServerListTags throws IllegalArgumentException when urls are empty`() {
        val emptyUrls = emptyList<String>()
        assertFailsWith<IllegalArgumentException> { Blossom.buildServerListTags(emptyUrls) }
    }

    @Test
    fun `buildServerListTags builds server tags preserving order`() {
        val valid1 = "http://example.com"
        val valid2 = "https://example.com/"
        val singleServerTags = Blossom.buildServerListTags(listOf(valid1))
        assertEquals(
            listOf(
                listOf("server", valid1)
            ), singleServerTags
        )

        val manyServerTags = Blossom.buildServerListTags(listOf(valid2, valid1))
        assertEquals(
            listOf(
                listOf("server", valid2),
                listOf("server", valid1)
            ), manyServerTags
        )
    }

    private val hash = "b1674191a88ec5cdd733e4240a81803105dc412d6c6708d53ab94fc248f4f553"

    @Test
    fun `resolves BUD-10 uri to its first server hint`() {
        assertEquals(
            "https://cdn.example.com/$hash.png",
            Blossom.resolveBud10Uri("blossom:$hash.png?xs=cdn.example.com&xs=https://other.net&as=${"a".repeat(64)}&sz=1024")
        )
    }

    @Test
    fun `keeps explicit scheme and drops trailing slash on BUD-10 server`() {
        assertEquals(
            "https://blossom.syb.lol/$hash.jpg",
            Blossom.resolveBud10Uri("blossom:$hash.jpg?as=${"a".repeat(64)}&xs=https%3A%2F%2Fblossom.syb.lol%2F")
        )
    }

    @Test
    fun `returns null for BUD-10 uri without a server hint or with a bad hash`() {
        assertEquals(null, Blossom.resolveBud10Uri("blossom:$hash.png?as=${"a".repeat(64)}"))
        assertEquals(null, Blossom.resolveBud10Uri("blossom:abc.png?xs=cdn.example.com"))
    }
}
