package cooking.zap.app.repo

import cooking.zap.app.nostr.Nip10
import cooking.zap.app.nostr.Nip89
import cooking.zap.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tag-set parity between [RelayNoteReviewReplyPublisher.buildReplyTags]
 * and what `ComposeViewModel.publishNote` emits for the same reply
 * (finding 0.2 / Phase 3). A Note Review draft is a plain public reply —
 * no content warning, no `nostr:` mentions, no hashtags, no uploaded
 * media, no custom emoji — so publishNote's tag assembly reduces to
 * NIP-10 reply tags (ComposeViewModel.kt:851-854) followed by the NIP-89
 * client tag when the preference allows (:977-979). These tests build
 * that expectation the way publishNote does AND pin the literal tag
 * shapes, so drift in either place fails here first.
 */
class NoteReviewReplyPublisherTest {

    private val relayHint = "wss://relay.example.com"

    private fun event(
        id: String,
        pubkey: String,
        tags: List<List<String>> = emptyList(),
    ): NostrEvent = NostrEvent(
        id = id,
        pubkey = pubkey,
        created_at = 1_700_000_000L,
        kind = 1,
        tags = tags,
        content = "a dish photo note",
        sig = "0".repeat(128),
    )

    @Test
    fun topLevelParent_matchesPublishNoteTagAssembly() {
        val parent = event(id = "aa".repeat(32), pubkey = "bb".repeat(32))

        // publishNote for a plain reply: Nip10.buildReplyTags(replyTo, hint)
        // then Nip89.clientTag() — nothing else applies to a Note Review draft.
        val expected = mutableListOf<List<String>>()
        expected.addAll(Nip10.buildReplyTags(parent, relayHint))
        expected.add(Nip89.clientTag())

        val actual = RelayNoteReviewReplyPublisher.buildReplyTags(parent, relayHint, clientTagEnabled = true)
        assertEquals(expected, actual)

        // And the literal shape, so a change in either primitive is loud:
        // direct reply → parent is the root.
        assertEquals(
            listOf(
                listOf("e", parent.id, relayHint, "root"),
                listOf("p", parent.pubkey),
                listOf("client", "Zap Cooking"),
            ),
            actual,
        )
    }

    @Test
    fun threadedParent_keepsTheOriginalRootAndMarksParentAsReply() {
        val rootId = "cc".repeat(32)
        val parent = event(
            id = "aa".repeat(32),
            pubkey = "bb".repeat(32),
            tags = listOf(
                listOf("e", rootId, "wss://root.relay", "root"),
                listOf("e", "dd".repeat(32), "", "reply"),
                listOf("p", "ee".repeat(32)),
            ),
        )

        val expected = mutableListOf<List<String>>()
        expected.addAll(Nip10.buildReplyTags(parent, relayHint))
        expected.add(Nip89.clientTag())

        val actual = RelayNoteReviewReplyPublisher.buildReplyTags(parent, relayHint, clientTagEnabled = true)
        assertEquals(expected, actual)
        assertEquals(
            listOf(
                listOf("e", rootId, "wss://root.relay", "root"),
                listOf("e", parent.id, relayHint, "reply"),
                listOf("p", parent.pubkey),
                listOf("client", "Zap Cooking"),
            ),
            actual,
        )
    }

    @Test
    fun clientTagPreferenceOff_omitsTheClientTag() {
        val parent = event(id = "aa".repeat(32), pubkey = "bb".repeat(32))
        val actual = RelayNoteReviewReplyPublisher.buildReplyTags(parent, relayHint, clientTagEnabled = false)
        assertEquals(
            listOf(
                listOf("e", parent.id, relayHint, "root"),
                listOf("p", parent.pubkey),
            ),
            actual,
        )
    }
}
