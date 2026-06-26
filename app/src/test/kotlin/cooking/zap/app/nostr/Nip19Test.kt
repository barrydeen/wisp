package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip for [Nip19.naddrEncode] against the existing [Nip19.naddrDecode].
 *
 * Note: the real proof of correctness is **web interop** — a shared
 * `https://zap.cooking/r/{naddr}` link must open the recipe on zap.cooking.
 * This test only guards TLV layout regressions locally.
 */
class Nip19Test {

    private fun loadEvent(name: String): NostrEvent {
        val json = javaClass.getResource("/recipes/$name")
            ?.readText()
            ?: error("fixture /recipes/$name not found on test classpath")
        return NostrEvent.fromJson(json)
    }

    @Test
    fun naddrEncode_roundTrips_withRealRecipeFixture() {
        val event = loadEvent("tuscan_peposo.json")
        // Derive from the parsed event so the fixture meaningfully participates:
        // a changed `d`/pubkey/kind in the fixture flows through to this assertion.
        val dTag = event.tags.first { it.size >= 2 && it[0] == "d" }[1]
        val pubkey = event.pubkey
        val kind = event.kind

        val encoded = Nip19.naddrEncode(kind = kind, pubkeyHex = pubkey, dTag = dTag)
        assertTrue(encoded.startsWith("naddr1"))

        val decoded = Nip19.naddrDecode(encoded)
        assertEquals(dTag, decoded.dTag)
        assertEquals(pubkey, decoded.author)
        assertEquals(kind, decoded.kind)
        assertTrue(decoded.relays.isEmpty())
    }

    @Test
    fun naddrEncode_roundTrips_withOptionalRelays() {
        val dTag = "weeknight-dinners"
        val pubkey = "1852d83e2b9d12fa561071bfe159ff5ae510af1fc9b51b85539cb6a81486f207"
        val kind = 30001
        val relays = listOf("wss://relay.damus.io", "wss://nos.lol")

        val encoded = Nip19.naddrEncode(kind = kind, pubkeyHex = pubkey, dTag = dTag, relays = relays)
        val decoded = Nip19.naddrDecode(encoded)
        assertEquals(dTag, decoded.dTag)
        assertEquals(pubkey, decoded.author)
        assertEquals(kind, decoded.kind)
        assertEquals(relays, decoded.relays)
    }

    @Test
    fun recipeShare_buildsWebCanonicalUrlAndText() {
        val event = loadEvent("tuscan_peposo.json")
        val url = RecipeShare.shareUrl(event)
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://zap.cooking/r/naddr1"))

        val text = RecipeShare.shareText(event)
        assertEquals(
            "Tuscan Peposo (Black Pepper Beef Stew)\nShared on Zap Cooking\n$url",
            text,
        )
    }
}
