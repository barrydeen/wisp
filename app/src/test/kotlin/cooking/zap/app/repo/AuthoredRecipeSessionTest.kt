package cooking.zap.app.repo

import cooking.zap.app.relay.RelayPool
import cooking.zap.app.relay.SubscriptionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * Regression gate for the **two independent authored sessions**: the signed-in
 * user's "My Recipes" grid ([RecipeRepository.authoredRecipes]) and the
 * other-user profile's Recipes tab ([RecipeRepository.profileAuthoredRecipes]).
 *
 * Before the split there was ONE shared slot, so opening a stranger's profile
 * cleared My Recipes and repainted it with the stranger's recipes — and the My
 * Recipes consumers cache "already loaded for this pubkey", so it never
 * recovered. These tests pin the isolation.
 *
 * The queries run on a dispatcher that never executes anything, so the relay
 * fan-out inside the coroutine never happens (no network in the unit suite).
 * That is exactly the interesting surface: the state transitions that DID the
 * clobbering — clearing the coordinate map, resetting the list, flipping the
 * loading flag — are all synchronous in `load()`, before the launch. Isolation
 * of the *filled* grids is exercised on-device.
 */
class AuthoredRecipeSessionTest {

    /** Swallows every dispatch — `scope.launch { }` bodies never run. */
    private object NeverRuns : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    private fun repo(): RecipeRepository {
        val pool = RelayPool(null)
        return RecipeRepository(
            relayPool = pool,
            eventRepo = EventRepository(),
            subManager = SubscriptionManager(pool),
            scope = CoroutineScope(Job() + NeverRuns),
            processingContext = NeverRuns,
        )
    }

    @Test
    fun sessions_areDistinctFlows() {
        val repo = repo()
        assertNotSame(repo.authoredRecipes, repo.profileAuthoredRecipes)
        assertNotSame(repo.isAuthoredLoading, repo.isProfileAuthoredLoading)
    }

    @Test
    fun openingAProfile_doesNotDisturbTheSelfSession() {
        val repo = repo()
        // The user's own My Recipes query is in flight...
        repo.loadAuthoredRecipes(alice)
        assertTrue(repo.isAuthoredLoading.value)

        // ...then they open Bob's profile and hit its Recipes tab.
        repo.loadProfileAuthoredRecipes(bob)

        // Bob loads in his own session; Alice's is untouched (this is the bug).
        assertTrue(repo.isProfileAuthoredLoading.value)
        assertTrue("profile query cancelled/cleared the self session", repo.isAuthoredLoading.value)
        assertEquals(emptyList<Any>(), repo.authoredRecipes.value)
    }

    @Test
    fun selfQuery_doesNotDisturbAnOpenProfileSession() {
        val repo = repo()
        repo.loadProfileAuthoredRecipes(bob)
        assertTrue(repo.isProfileAuthoredLoading.value)

        // The reverse direction: signing in / switching to My Recipes while a
        // profile grid is painted must not wipe the profile session either.
        repo.loadAuthoredRecipes(alice)

        assertTrue(repo.isAuthoredLoading.value)
        assertTrue("self query cancelled/cleared the profile session", repo.isProfileAuthoredLoading.value)
    }

    @Test
    fun blankPubkeyResetsOnlyItsOwnSession() {
        val repo = repo()
        repo.loadAuthoredRecipes(alice)
        repo.loadProfileAuthoredRecipes(bob)

        // Signing out resets the self session (blank pubkey) — the open
        // profile's grid is not its business.
        repo.loadAuthoredRecipes("")
        assertFalse(repo.isAuthoredLoading.value)
        assertTrue(repo.isProfileAuthoredLoading.value)

        // ...and leaving the profile resets only the profile session.
        repo.loadAuthoredRecipes(alice)
        repo.loadProfileAuthoredRecipes("   ")
        assertFalse(repo.isProfileAuthoredLoading.value)
        assertTrue(repo.isAuthoredLoading.value)
    }

    @Test
    fun clear_resetsBothSessions() {
        val repo = repo()
        repo.loadAuthoredRecipes(alice)
        repo.loadProfileAuthoredRecipes(bob)

        // Account switch drops everything — including any profile grid left
        // painted from the previous account's session.
        repo.clear()

        assertFalse(repo.isAuthoredLoading.value)
        assertFalse(repo.isProfileAuthoredLoading.value)
        assertEquals(emptyList<Any>(), repo.authoredRecipes.value)
        assertEquals(emptyList<Any>(), repo.profileAuthoredRecipes.value)
    }
}
