package cooking.zap.app.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the `GET /api/membership` shape bug (member testing).
 *
 * The endpoint is a BATCH read: it returns a JSON object keyed by lowercased
 * pubkey — `{ "<hex>": { active, tier, expiresAt? } }` — NOT the flat
 * `{ found, isActive, member }` shape of `check-status`. The old code
 * deserialized the body directly as [MembershipStatus], so real members
 * parsed as inactive and Sous Chef routed them to the upsell.
 *
 * These fixtures capture the REAL response shape (see the frontend
 * `src/routes/api/membership/+server.ts`), exercised through the exact
 * deserialize + map path [ZapCookingApi.getPublicMembership] now uses.
 */
class PublicMembershipShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun load(name: String) =
        json.decodeFromString(
            ZapCookingApi.BATCH_MEMBERSHIP_SERIALIZER,
            javaClass.getResource("/membership/$name")!!.readText(),
        )

    // The pubkey key in batch_active_member.json.
    private val activeHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val inactiveHex = "82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2"

    @Test
    fun keyPresent_activeMember_parsesActive() {
        val byPubkey = load("batch_active_member.json")
        val status = ZapCookingApi.mapBatchMembership(byPubkey, activeHex)

        assertTrue("member should be found", status.found)
        assertTrue("active member must map to isActive=true", status.isActive)
        assertEquals("founders", status.member?.tier)
        assertEquals("2035-01-01T00:00:00.000Z", status.member?.subscription_end)
    }

    @Test
    fun keyPresent_inactiveMember_parsesInactive() {
        val byPubkey = load("batch_inactive_member.json")
        val status = ZapCookingApi.mapBatchMembership(byPubkey, inactiveHex)

        assertTrue(status.found)
        assertFalse(status.isActive)
        assertEquals("member", status.member?.tier)
    }

    @Test
    fun emptyObject_missingKey_mapsToInactive() {
        val byPubkey = load("batch_empty.json")
        assertTrue("empty {} must deserialize to an empty map", byPubkey.isEmpty())

        val status = ZapCookingApi.mapBatchMembership(byPubkey, activeHex)
        assertFalse("missing key → not found", status.found)
        assertFalse("missing key → inactive (SousChef treats as unknown)", status.isActive)
    }

    @Test
    fun keyPresentButLookupDiffers_mapsToInactive() {
        // The response only carries the queried pubkey; a lookup for a
        // different pubkey (defensive) must not report the wrong member active.
        val byPubkey = load("batch_active_member.json")
        val status = ZapCookingApi.mapBatchMembership(byPubkey, inactiveHex)
        assertFalse(status.found)
        assertFalse(status.isActive)
    }

    @Test
    fun lookupIsLowercased_matchesServerNormalizedKey() {
        // mapBatchMembership normalizes the lookup key; uppercase hex still matches.
        val byPubkey = load("batch_active_member.json")
        val status = ZapCookingApi.mapBatchMembership(byPubkey, activeHex.uppercase())
        assertTrue(status.isActive)
    }
}
