package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the NIP-22 tag semantics PostCard and the profile Comments tab depend on:
 * uppercase tags name the root scope, lowercase name the immediate parent, and an
 * external (`I`) root is what earns a comment its link-preview card.
 *
 * Ports wisp-ios `Nip22CommentTests`.
 */
class Nip22Test {

    private fun comment(tags: List<List<String>>, kind: Int = 1111): NostrEvent =
        NostrEvent(id = "c", pubkey = "author", created_at = 0, kind = kind, tags = tags, content = "", sig = "")

    /** The reported case: a comment on a web page carries I/K/i/k and no p-tags. */
    @Test fun webRootIsExtracted() {
        val url = "https://bitcoinmagazine.com/guides/some-article"
        val e = comment(listOf(listOf("I", url), listOf("K", "web"), listOf("i", url), listOf("k", "web")))
        val root = Nip22.externalRoot(e)
        assertEquals(url, root?.value)
        assertEquals("web", root?.kind)
        assertEquals(url, root?.openableUri?.toString())
        assertEquals("bitcoinmagazine.com", root?.displayHost)
    }

    /** `www.` is stripped so the source label reads as the brand. */
    @Test fun displayHostDropsWww() {
        val e = comment(listOf(listOf("I", "https://www.example.com/a"), listOf("K", "web")))
        assertEquals("example.com", Nip22.externalRoot(e)?.displayHost)
    }

    /** A comment rooted on a nostr event must NOT produce an external ref. */
    @Test fun eventRootedCommentHasNoExternalRoot() {
        val e = comment(listOf(
            listOf("E", "rootid", "wss://r", "rootpk"), listOf("K", "1063"),
            listOf("e", "parentid", "wss://r", "parentpk"), listOf("k", "1111"),
        ))
        assertNull(Nip22.externalRoot(e))
    }

    /** Only kind 1111 is a comment; a kind-1 carrying an `I` tag isn't. */
    @Test fun nonCommentKindIsIgnored() {
        val e = comment(listOf(listOf("I", "https://example.com"), listOf("K", "web")), kind = 1)
        assertNull(Nip22.externalRoot(e))
        assertFalse(Nip22.isComment(e))
    }

    /** A non-URL identifier isn't openable on its own, but the tag's hint is. */
    @Test fun podcastGuidUsesHintForOpenableUri() {
        val e = comment(listOf(
            listOf("I", "podcast:item:guid:d98d189b", "https://fountain.fm/episode/z1y9"),
            listOf("K", "podcast:item:guid"),
        ))
        val root = Nip22.externalRoot(e)
        assertEquals("https://fountain.fm/episode/z1y9", root?.openableUri?.toString())
        assertEquals("fountain.fm", root?.displayHost)
    }

    /** With no hint, a bare identifier yields no link. */
    @Test fun bareIdentifierHasNoOpenableUri() {
        val e = comment(listOf(listOf("I", "isbn:9780262033848"), listOf("K", "isbn")))
        val root = Nip22.externalRoot(e)
        assertNull(root?.openableUri)
        assertNull(root?.displayHost)
    }

    /** Non-http schemes must not be treated as openable web links. */
    @Test fun nonHttpSchemeIsNotOpenable() {
        val e = comment(listOf(listOf("I", "javascript:alert(1)"), listOf("K", "web")))
        assertNull(Nip22.externalRoot(e)?.openableUri)
    }

    /** Top-level comment: the parent mirrors the root. */
    @Test fun topLevelParentMirrorsRoot() {
        val url = "https://example.com/a"
        val e = comment(listOf(listOf("I", url), listOf("K", "web"), listOf("i", url), listOf("k", "web")))
        assertEquals(url, Nip22.externalParent(e)?.value)
    }

    /** Reply to another comment: the parent is an event, so there's no external parent. */
    @Test fun replyToCommentHasEventParentNotExternal() {
        val url = "https://example.com/a"
        val e = comment(listOf(
            listOf("I", url), listOf("K", "web"),
            listOf("e", "parentcomment", "wss://r", "pk"), listOf("k", "1111"),
        ))
        assertEquals(url, Nip22.externalRoot(e)?.value)
        assertNull(Nip22.externalParent(e))
    }

    /** A reply stays kind-1111 and carries the root scope forward, targeting the parent. */
    @Test fun buildReplyTagsCarriesRootAndTargetsParent() {
        val url = "https://example.com/a"
        val parent = NostrEvent(
            id = "parentid", pubkey = "parentpk", created_at = 0, kind = 1111,
            tags = listOf(listOf("I", url), listOf("K", "web"), listOf("i", url), listOf("k", "web")),
            content = "", sig = ""
        )
        val tags = Nip22.buildReplyTags(parent)
        org.junit.Assert.assertNotNull("reply tags should be built for an external-rooted comment", tags)
        val replyTags = tags!!
        assertTrue(replyTags.contains(listOf("I", url)))
        assertTrue(replyTags.contains(listOf("K", "web")))
        // Parent is the comment itself — an event — so e/k/p, not a repeated `i`.
        assertTrue(replyTags.contains(listOf("e", "parentid", "", "parentpk")))
        assertTrue(replyTags.contains(listOf("k", "1111")))
        assertTrue(replyTags.contains(listOf("p", "parentpk")))
        assertFalse(replyTags.any { it.isNotEmpty() && it[0] == "i" })
    }

    /** Replying to a note that isn't an external-rooted comment is out of scope. */
    @Test fun buildReplyTagsReturnsNullForPlainNote() {
        val note = NostrEvent(id = "n", pubkey = "pk", created_at = 0, kind = 1, tags = emptyList(), content = "", sig = "")
        assertNull(Nip22.buildReplyTags(note))
    }

    /** A comment rooted on a nostr event by id (uppercase E) yields a ById ref. */
    @Test fun eventRootByEtag() {
        val e = comment(listOf(
            listOf("E", "rooteventid", "wss://r", "rootpk"), listOf("K", "1"),
            listOf("e", "parentid", "wss://r", "pk"), listOf("k", "1111"),
        ))
        val root = Nip22.eventRoot(e)
        assertEquals(Nip22.EventRootRef.ById("rooteventid", "wss://r", "rootpk"), root)
    }

    /** A comment rooted on an addressable (uppercase A) yields an Addressable ref. */
    @Test fun eventRootByAtag() {
        val e = comment(listOf(listOf("A", "30023:abc:dTag-1"), listOf("K", "30023")))
        val root = Nip22.eventRoot(e) as? Nip22.EventRootRef.Addressable
        assertEquals(30023, root?.kind)
        assertEquals("abc", root?.pubkey)
        assertEquals("dTag-1", root?.dTag)
    }

    /** A dTag containing `:` survives the split (drop(2).joinToString). */
    @Test fun eventRootAtagPreservesColonInDtag() {
        val e = comment(listOf(listOf("A", "30023:abc:foo:bar:baz"), listOf("K", "30023")))
        val root = Nip22.eventRoot(e) as? Nip22.EventRootRef.Addressable
        assertEquals("foo:bar:baz", root?.dTag)
    }

    /** An externally-rooted comment (I tag, no E/A) has no event root. */
    @Test fun externalRootedCommentHasNoEventRoot() {
        val e = comment(listOf(listOf("I", "https://example.com/a"), listOf("K", "web")))
        assertNull(Nip22.eventRoot(e))
    }

    /** E (by id) is preferred over A when both are present. */
    @Test fun eventRootPrefersEtagOverAtag() {
        val e = comment(listOf(
            listOf("E", "byid"), listOf("A", "30023:abc:d"),
        ))
        assertTrue(Nip22.eventRoot(e) is Nip22.EventRootRef.ById)
    }
}
