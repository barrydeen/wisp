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
}