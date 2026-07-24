package cooking.zap.app.repo

import cooking.zap.app.api.RecipeWeek
import cooking.zap.app.api.ZapCookingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Process-scoped, in-memory cache for the weekly new-recipe stats behind the
 * Recipes header trend pill.
 *
 * Lives outside the ViewModel on purpose: `RecipeFeedViewModel` is scoped to
 * the Recipes back-stack entry and is recreated on navigate-away-and-back, so
 * a cache held there would refetch on every return and could race a
 * pull-to-refresh into two concurrent requests. [shared] outlives every such
 * recreation; the ViewModel only projects it into UI state.
 *
 * Deliberately **not** persisted. A weekly number surviving an app restart
 * would show stale data for up to the TTL with no way for the user to tell;
 * one ~500-byte request on cold start is the cheaper trade.
 *
 * Three behaviours the callers depend on:
 * - **The TTL runs from the last success, not the last attempt.** A failed
 *   fetch never stamps the clock, so a user who opens the app on a dead
 *   connection isn't locked out of the data for the rest of the window.
 * - **A retained value is never lost to a later failure.** Once a displayable
 *   series is held, [weeks] keeps returning it rather than null — which is
 *   what lets the UI guarantee it can collapse the header at most once per
 *   process, before the user has seen anything.
 * - **Single-flight.** Concurrent callers serialize on [mutex]; the loser sees
 *   the winner's fresh cache and issues no second request.
 */
class RecipeTrendCache(
    private val ttlMillis: Long = TTL_MILLIS,
    private val forceFloorMillis: Long = FORCE_FLOOR_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()

    /**
     * Last non-empty response. Never cleared once set — only replaced.
     * Volatile for [peek], which reads it without taking [mutex].
     */
    @Volatile
    private var retained: List<RecipeWeek>? = null

    /** Stamped on success only (see class doc). 0 until the first success. */
    private var lastSuccessAtMillis = 0L

    /**
     * The retained series without suspending or fetching — null if nothing has
     * ever been fetched successfully.
     *
     * Lets a freshly constructed ViewModel seed its initial state
     * synchronously, so a back-nav re-entry renders the pill immediately
     * instead of flashing a placeholder while a cache hit resolves through a
     * coroutine.
     */
    fun peek(): List<RecipeWeek>? = retained

    /**
     * The freshest series available, or null if none has ever been fetched
     * successfully. Suspends for at most one in-flight request.
     *
     * Returns the cached value without a request when it is still inside the
     * TTL. [force] (pull-to-refresh — an explicit user ask) bypasses the TTL
     * but **not** [forceFloorMillis]: without that floor, a refresh landing
     * while a background fetch is in flight would block on the mutex and then
     * immediately issue a second request behind the winner, two requests
     * seconds apart for the same data. A value stamped within the last few
     * seconds is fresh enough to satisfy a force too.
     *
     * Any failure (HTTP error, network fault, malformed body) resolves to the
     * retained value, or null if there is none: callers treat "no value" as
     * "no trend", never as an error to surface. Cancellation propagates — a
     * cleared ViewModel is not a failed fetch.
     */
    suspend fun weeks(api: ZapCookingApi, force: Boolean = false): List<RecipeWeek>? =
        mutex.withLock {
            val cached = retained
            val window = if (force) forceFloorMillis else ttlMillis
            if (cached != null && nowMillis() - lastSuccessAtMillis < window) {
                return@withLock cached
            }
            try {
                val fresh = api.getRecipesByWeek()
                lastSuccessAtMillis = nowMillis()
                // An empty series is a successful answer, but not a displayable
                // one — keep whatever was retained rather than clobbering a good
                // value with nothing to draw.
                if (fresh.isNotEmpty()) retained = fresh
                retained
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Timestamp deliberately NOT advanced: the next call retries.
                retained
            }
        }

    companion object {
        /**
         * Six hours. The underlying buckets are weekly, so the number moves
         * slowly; this refreshes a couple of times across a normal day's usage
         * without polling a stats endpoint on every screen entry.
         */
        val TTL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

        /**
         * Freshness floor for [weeks] with `force = true`. Long enough to
         * absorb a pull-to-refresh arriving on the heels of a background
         * fetch, short enough that a deliberate second pull still refetches.
         */
        val FORCE_FLOOR_MILLIS: Long = TimeUnit.SECONDS.toMillis(5)

        /** The process-wide instance. Tests construct their own. */
        val shared: RecipeTrendCache by lazy { RecipeTrendCache() }
    }
}
