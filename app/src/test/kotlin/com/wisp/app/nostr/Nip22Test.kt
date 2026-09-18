package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NIP-22 root-scope handling. Fixtures are real events fetched from
 * relay.damus.io for thread root
 * `000018e0632cdb4fd89d2508ca256ec88e6c1d0915b04aaa96662581d9ecfad3`:
 * a top-level kind-1111 comment and a nested reply to that comment whose
 * lowercase `e` points at the parent comment and only the uppercase `E`
 * points at the thread root.
 */
class Nip22Test {

    private val rootId = "000018e0632cdb4fd89d2508ca256ec88e6c1d0915b04aaa96662581d9ecfad3"
    private val rootAuthor = "e2ccf7cf20403f3f2a4a55b328f0de3be38558a7d5f33632fdaaefc726c1c8eb"
    private val topCommentId = "bda4cc3aad268a925072375d473e29a5cc7735961565fd148c5393785c0ee973"
    private val topCommentAuthor = "bc28aad5b167f31dd37c66d8c95d400c6411d83275ed12c504f60965d1f9eec6"

    private fun event(kind: Int, tags: List<List<String>>) = NostrEvent(
        id = "test-id",
        pubkey = "test-pubkey",
        created_at = 1789745380L,
        kind = kind,
        tags = tags,
        content = "test",
        sig = "test-sig"
    )

    private val topLevelComment = event(
        kind = Nip22.KIND_COMMENT,
        tags = listOf(
            listOf("E", rootId, "", rootAuthor),
            listOf("K", "1"),
            listOf("P", rootAuthor),
            listOf("e", rootId, "", rootAuthor),
            listOf("k", "1"),
            listOf("p", rootAuthor)
        )
    )

    private val nestedReply = event(
        kind = Nip22.KIND_COMMENT,
        tags = listOf(
            listOf("E", rootId, "", rootAuthor),
            listOf("K", "1"),
            listOf("P", rootAuthor),
            listOf("e", topCommentId, "", topCommentAuthor),
            listOf("k", "1111"),
            listOf("p", topCommentAuthor)
        )
    )

    @Test
    fun rootScopeIdReadsUppercaseE() {
        assertEquals(rootId, Nip22.getRootScopeId(topLevelComment))
        assertEquals(rootId, Nip22.getRootScopeId(nestedReply))
    }

    @Test
    fun referencesRootAcceptsLowercaseEAndUppercaseE() {
        assertTrue(Nip22.referencesRoot(topLevelComment, rootId))
        // Nested reply has NO lowercase e pointing at the root — only E.
        assertTrue(Nip22.referencesRoot(nestedReply, rootId))
        assertFalse(Nip22.referencesRoot(nestedReply, "deadbeef"))
    }

    @Test
    fun replyTargetResolvesToParentCommentForNesting() {
        // rebuildTree nests under the parent when it is already known.
        assertEquals(topCommentId, Nip10.getReplyTarget(nestedReply))
        assertEquals(rootId, Nip10.getReplyTarget(topLevelComment))
    }

    @Test
    fun filterSerializesUppercaseE() {
        val filter = Filter(
            kinds = listOf(Nip22.KIND_COMMENT),
            bigETags = listOf(rootId)
        )
        val json = filter.toJsonObject().toString()
        assertTrue(json.contains("\"#E\""))
        assertTrue(json.contains(rootId))
    }

    @Test
    fun kind1WithoutETagHasNoRootScope() {
        val note = event(
            kind = 1,
            tags = listOf(listOf("e", rootId, "", "reply"))
        )
        assertEquals(null, Nip22.getRootScopeId(note))
        assertTrue(Nip22.referencesRoot(note, rootId))
    }
}
