package cooking.zap.app.repo

import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.repo.RecipeBookmarkRepository.Companion.DEFAULT_LIST_DTAG
import cooking.zap.app.repo.RecipeBookmarkRepository.Companion.DEFAULT_LIST_TITLE
import cooking.zap.app.repo.RecipeBookmarkRepository.MutationPlan
import cooking.zap.app.repo.RecipeBookmarkRepository.RelayListCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function gate for the cold-cache first-write guard (the bookmark
 * overwrite hazard): [RecipeBookmarkRepository.classifyRelayListCheck] decides
 * what a relay check means, and [RecipeBookmarkRepository.planMutation] is the
 * whole mutation decision — including the invariant that an unconfirmed cold
 * cache REJECTS the write (only a [MutationPlan.Publish] ever reaches a signer
 * or a relay). The live REQ/EOSE plumbing mirrors queryRecipeLists and is
 * exercised on-device, per this suite's convention.
 */
class RecipeBookmarkRepositoryTest {

    private val author = "bb".repeat(32)
    private val coordA = "30023:$author:tuscan-peposo"
    private val coordB = "30023:$author:shakshuka"
    private val coordC = "30023:$author:meatloaf"

    private fun listEvent(
        tags: List<List<String>>,
        content: String = "",
        createdAt: Long = 1_700_000_000L,
        id: String = "aa".repeat(32),
    ): NostrEvent = NostrEvent(
        id = id,
        pubkey = author,
        created_at = createdAt,
        kind = RecipeBookmarkRepository.LIST_KIND,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )

    // ---- classifyRelayListCheck ------------------------------------------

    @Test
    fun classify_streamedEventWinsRegardlessOfEose() {
        val event = listEvent(tags = listOf(listOf("d", DEFAULT_LIST_DTAG)))
        assertEquals(RelayListCheck.Found(event), RecipeBookmarkRepository.classifyRelayListCheck(event, eoseCount = 0))
        assertEquals(RelayListCheck.Found(event), RecipeBookmarkRepository.classifyRelayListCheck(event, eoseCount = 3))
    }

    @Test
    fun classify_absenceIsOnlyConfirmedByAtLeastOneEose() {
        assertEquals(RelayListCheck.ConfirmedAbsent, RecipeBookmarkRepository.classifyRelayListCheck(null, eoseCount = 1))
        assertEquals(RelayListCheck.ConfirmedAbsent, RecipeBookmarkRepository.classifyRelayListCheck(null, eoseCount = 5))
    }

    @Test
    fun classify_noEventAndNoEoseIsUnconfirmed() {
        assertEquals(RelayListCheck.Unconfirmed, RecipeBookmarkRepository.classifyRelayListCheck(null, eoseCount = 0))
    }

    // ---- cold cache, list exists on relays -------------------------------

    @Test
    fun coldSave_relayVersionBecomesBase_savedItemAppended_neverCreates() {
        // The user's real Saved list as fetched from relays: web-set metadata,
        // an unknown tag, a client tag, and two existing recipes.
        val relayList = listEvent(
            tags = listOf(
                listOf("d", DEFAULT_LIST_DTAG),
                listOf("title", "Saved"),
                listOf("summary", "my keepers"),
                listOf("image", "https://example.com/cover.jpg"),
                listOf("client", "Zap Cooking Web"),
                listOf("a", coordA),
                listOf("a", coordB),
            ),
            content = "web content",
        )

        val plan = RecipeBookmarkRepository.planMutation(
            base = relayList,
            absenceConfirmed = false,
            dTag = DEFAULT_LIST_DTAG,
            coord = coordC,
            desired = null,
            seedTitleIfNew = DEFAULT_LIST_TITLE,
        )

        assertTrue(plan is MutationPlan.Publish)
        plan as MutationPlan.Publish
        // No create: the relay version is the carry-forward base — metadata and
        // unknown tags survive, existing recipes are kept, the new one appended.
        assertEquals(
            listOf(
                listOf("d", DEFAULT_LIST_DTAG),
                listOf("title", "Saved"),
                listOf("summary", "my keepers"),
                listOf("image", "https://example.com/cover.jpg"),
                listOf("a", coordA),
                listOf("a", coordB),
                listOf("a", coordC),
            ),
            plan.tags,
        )
        assertEquals("web content", plan.content)
        assertEquals(true, plan.membership)
    }

    // ---- cold cache, relay-confirmed absent ------------------------------

    @Test
    fun coldSave_confirmedAbsent_createsFreshSeededList() {
        val plan = RecipeBookmarkRepository.planMutation(
            base = null,
            absenceConfirmed = true,
            dTag = DEFAULT_LIST_DTAG,
            coord = coordA,
            desired = null,
            seedTitleIfNew = DEFAULT_LIST_TITLE,
        )

        assertTrue(plan is MutationPlan.Publish)
        plan as MutationPlan.Publish
        // Default list: d + seeded title + the one recipe — and never a `t` tag.
        assertEquals(
            listOf(
                listOf("d", DEFAULT_LIST_DTAG),
                listOf("title", DEFAULT_LIST_TITLE),
                listOf("a", coordA),
            ),
            plan.tags,
        )
        assertEquals("", plan.content)
        assertEquals(true, plan.membership)
    }

    @Test
    fun coldSave_confirmedAbsent_namedCollectionGetsRecipeTTag() {
        val plan = RecipeBookmarkRepository.planMutation(
            base = null,
            absenceConfirmed = true,
            dTag = "weeknight",
            coord = coordA,
            desired = null,
            seedTitleIfNew = null,
        )

        assertTrue(plan is MutationPlan.Publish)
        plan as MutationPlan.Publish
        assertEquals(
            listOf(
                listOf("d", "weeknight"),
                listOf("title", "weeknight"),
                listOf("t", RecipeBookmarkRepository.COLLECTION_TAG),
                listOf("a", coordA),
            ),
            plan.tags,
        )
    }

    // ---- cold cache, unconfirmed → rejected ------------------------------

    @Test
    fun coldSave_unconfirmed_isRejected_nothingPublishable() {
        val plan = RecipeBookmarkRepository.planMutation(
            base = null,
            absenceConfirmed = false,
            dTag = DEFAULT_LIST_DTAG,
            coord = coordA,
            desired = null,
            seedTitleIfNew = DEFAULT_LIST_TITLE,
        )
        // Only a Publish plan carries tags to sign — Rejected means, by
        // construction, nothing reaches the signer or the relays.
        assertEquals(MutationPlan.RejectedUnconfirmed, plan)
    }

    @Test
    fun coldRemove_unconfirmed_isAlsoRejected() {
        // Removing from an unfetched list is just as unsafe (it would publish a
        // fresh empty list); the guard applies to every cold mutation.
        val plan = RecipeBookmarkRepository.planMutation(
            base = null,
            absenceConfirmed = false,
            dTag = DEFAULT_LIST_DTAG,
            coord = coordA,
            desired = false,
            seedTitleIfNew = DEFAULT_LIST_TITLE,
        )
        assertEquals(MutationPlan.RejectedUnconfirmed, plan)
    }

    // ---- warm path unchanged ---------------------------------------------

    @Test
    fun warmToggle_carryForwardBehaviorUnchanged() {
        // Named collection already in memory: toggle an existing coord off.
        // Pins the pre-fix literal tag shape — the warm path must be byte-stable.
        val base = listEvent(
            tags = listOf(
                listOf("d", "soups"),
                listOf("title", "Soups"),
                listOf("t", "zapcooking"),
                listOf("a", coordA),
                listOf("a", coordB),
            ),
        )

        val plan = RecipeBookmarkRepository.planMutation(
            base = base,
            absenceConfirmed = false,
            dTag = "soups",
            coord = coordA,
            desired = null,
            seedTitleIfNew = null,
        )

        assertTrue(plan is MutationPlan.Publish)
        plan as MutationPlan.Publish
        assertEquals(
            listOf(
                listOf("d", "soups"),
                listOf("title", "Soups"),
                listOf("t", "zapcooking"),
                listOf("a", coordB),
            ),
            plan.tags,
        )
        assertEquals(false, plan.membership)
    }

    @Test
    fun warmMutation_noOpWhenDesiredStateAlreadyHolds() {
        val base = listEvent(
            tags = listOf(
                listOf("d", DEFAULT_LIST_DTAG),
                listOf("title", "Saved"),
                listOf("a", coordA),
            ),
        )
        // Force-add an already-present coord → no publish, membership true.
        assertEquals(
            MutationPlan.NoOp(membership = true),
            RecipeBookmarkRepository.planMutation(base, false, DEFAULT_LIST_DTAG, coordA, desired = true, seedTitleIfNew = null),
        )
        // Force-remove an absent coord → no publish, membership false.
        assertEquals(
            MutationPlan.NoOp(membership = false),
            RecipeBookmarkRepository.planMutation(base, false, DEFAULT_LIST_DTAG, coordB, desired = false, seedTitleIfNew = null),
        )
    }
}
