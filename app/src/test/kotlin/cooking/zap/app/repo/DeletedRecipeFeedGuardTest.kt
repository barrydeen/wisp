package cooking.zap.app.repo

import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.RecipeParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for the **feed-insert guard** in [EventRepository.addEvent]'s kind-30023
 * branch — the half of a recipe delete that runs on *other people's* devices.
 *
 * A recipe is deleted by publishing a blanked replacement at the same address
 * (`["deleted","true"]`, empty content) alongside the kind-5 request. Every
 * *recipe* surface drops the replacement for free, because they gate on
 * `RecipeFormats.forEvent`. The *article* surfaces do not ask: `FeedScreen` and
 * `UserProfileScreen` route on `kind == 30023` alone, so without the guard the
 * tombstone arrives from a relay and renders as an article card titled
 * "[Deleted]" in the home feed of everyone who follows the author.
 *
 * [cooking.zap.app.nostr.RecipeDeletionTest] pins the predicate. This pins the
 * **wiring**: that the branch consults it, that it still caches what it refuses
 * to show, and that it is not swallowing ordinary longform. Those three were
 * previously read but never run.
 *
 * **Why this is reachable from the JVM suite.** Kind 30023 touches no Android
 * API on this path — `addEvent`'s `Log` calls are gated on kinds 1018 and
 * 20/21/22, as are `binaryInsert`'s, and every collaborator (`muteRepo`,
 * `eventPersistence`, `contactRepo`, …) is null here. Two things it must work
 * around, both noted at their use below: `getNewestFeedEventTimestamp` reaches
 * `android.util.LruCache` as soon as the feed is non-empty, and `binaryInsert`
 * reaches it via its binary search from the *second* insert onward. So each
 * test drives one event into its own repository.
 */
class DeletedRecipeFeedGuardTest {

    private val author = "ab".repeat(32)
    private val stamp = 1_700_000_000L

    private val recipeBody =
        "## Ingredients\n\n- flour\n- water\n\n## Directions\n\n1. Mix.\n2. Bake."

    /** The event a delete publishes at the recipe's own address. */
    private fun tombstone(): NostrEvent = NostrEvent(
        id = "22".repeat(32),
        pubkey = author,
        created_at = stamp,
        kind = RecipeParser.RECIPE_KIND,
        tags = listOf(
            listOf("d", "tuscan-peposo"),
            listOf("deleted", "true"),
            listOf("title", "[Deleted]"),
        ),
        content = "",
        sig = "0".repeat(128),
    )

    /** The recipe itself — a kind-30023 that must reach the feed. */
    private fun recipe(): NostrEvent = NostrEvent(
        id = "11".repeat(32),
        pubkey = author,
        created_at = stamp,
        kind = RecipeParser.RECIPE_KIND,
        tags = listOf(
            listOf("d", "tuscan-peposo"),
            listOf("t", "zapcooking"),
            listOf("title", "Tuscan Peposo"),
        ),
        content = recipeBody,
        sig = "0".repeat(128),
    )

    /**
     * True iff nothing is in `feedList`.
     *
     * `getNewestFeedEventTimestamp` is `maxOfOrNull { effectiveSortTime(it) }`:
     * on an empty list the lambda never runs and it returns null, and on a
     * non-empty one it reaches `android.util.LruCache.get`, which throws in the
     * JVM suite. So *both* outcomes are evidence, and a throw means an event
     * got in — which is why it maps to false rather than propagating.
     */
    private fun EventRepository.feedIsEmpty(): Boolean =
        runCatching { getNewestFeedEventTimestamp() == null }.getOrDefault(false)

    @Test
    fun blankedReplacement_neverEntersTheFeed() {
        val repo = EventRepository()

        repo.addEvent(tombstone())

        // binaryInsert appends synchronously inside addEvent, so by the time
        // this returns the feed either holds the tombstone or never took it.
        assertTrue(
            "the blanked replacement entered the feed — it renders as a " +
                "\"[Deleted]\" article card on FeedScreen/UserProfileScreen",
            repo.feedIsEmpty(),
        )
    }

    @Test
    fun blankedReplacement_isStillCached() {
        val repo = EventRepository()
        val stone = tombstone()

        repo.addEvent(stone)

        // Refused by the feed, kept by the cache: an addressable lookup should
        // resolve to the tombstone rather than to a stale copy of the recipe.
        assertNotNull(
            "the tombstone was dropped from the cache, not just from the feed",
            repo.getEvent(stone.id),
        )
    }

    @Test
    fun anUndeletedRecipe_stillEntersTheFeed() {
        val repo = EventRepository()
        val live = recipe()

        repo.addEvent(live)

        // The guard keys on the `deleted` marker, not on kind 30023. Read back
        // through the `feed` flow rather than the timestamp: the feed is now
        // non-empty, which is exactly the case the timestamp reader cannot
        // survive here. The flow is emitted after a 50ms settle window.
        val feed = runBlocking {
            withTimeout(5_000) { repo.feed.first { it.isNotEmpty() } }
        }
        assertEquals(
            "the guard is swallowing ordinary kind-30023 events",
            listOf(live.id),
            feed.map { it.id },
        )
    }
}
