package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the on-wire REQ frame for Nourish `#l` queries. Device evidence on
 * PR #161: pantry indexes `#l` (nak returns hits) but Android's pantry path
 * returned 0 for every labeled REQ — serialization must keep `"#l"` verbatim.
 */
class ClientMessageReqFrameTest {

    @Test
    fun labeledNourishReq_containsHashLByteForByte() {
        val filter = NourishDiscovery.buildNourishAnalysisFilter("protein:30plus")
        val frame = ClientMessage.req("test-sub", filter)

        // Exact tag fragment — not a renamed/escaped form.
        assertTrue(
            "frame must contain #l array verbatim, got: $frame",
            frame.contains("\"#l\":[\"protein:30plus\"]"),
        )
        assertTrue(frame.startsWith("[\"REQ\",\"test-sub\","))
        assertTrue(frame.contains("\"kinds\":[30078]"))
        assertTrue(frame.contains("\"authors\":[\"${NourishParser.SERVICE_PUBKEY}\"]"))
    }

    @Test
    fun unlabeledNourishReq_omitsHashL() {
        val frame = ClientMessage.req(
            "test-sub",
            NourishDiscovery.buildNourishAnalysisFilter(null),
        )
        assertTrue("unlabeled frame must not contain #l, got: $frame", !frame.contains("\"#l\""))
    }

    @Test
    fun filterToJsonObject_lTagsKeyIsHashL() {
        val json = NourishDiscovery.buildNourishAnalysisFilter("protein:30plus").toJsonObject()
        assertEquals(
            """["protein:30plus"]""",
            json["#l"].toString(),
        )
        // Ensure we didn't accidentally write a non-NIP key.
        assertTrue(json.containsKey("#l"))
        assertTrue(!json.containsKey("lTags"))
        assertTrue(!json.containsKey("l"))
    }
}
