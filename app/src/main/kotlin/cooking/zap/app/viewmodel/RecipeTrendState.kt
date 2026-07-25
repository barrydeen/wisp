package cooking.zap.app.viewmodel

import cooking.zap.app.api.RecipeWeek
import cooking.zap.app.util.RecipeTrend

/**
 * UI state for the Recipes header trend pill.
 *
 * Three distinct states, deliberately not collapsed into a nullable or a
 * boolean: the header reserves space while [Loading], fills it on [Visible],
 * and collapses on [Hidden], so it must be able to tell "not yet" from
 * "never".
 */
sealed interface RecipeTrendState {

    /** No answer yet. The header holds the pill's space open. */
    data object Loading : RecipeTrendState

    /** Nothing to show — empty series, an all-zero window, or a failure with nothing cached. */
    data object Hidden : RecipeTrendState

    /**
     * [count] is the rolling [RecipeTrend.WINDOW_WEEKS]-week sum — the only
     * number the header shows. [weeks] is the full series for the sparkline.
     */
    data class Visible(val count: Int, val weeks: List<RecipeWeek>) : RecipeTrendState

    companion object {
        /**
         * Fold a fetch result into the next state. Pure.
         *
         * [weeks] is whatever the cache could supply — freshly fetched,
         * previously retained, or null when nothing has ever been fetched
         * successfully.
         *
         * **[Hidden] is reachable from [Loading] always, and from [Visible]
         * only on a well-formed all-zero success.** The distinction is between
         * an answer and the absence of one:
         *
         * - Null (failure) or an empty `weeks` array never collapses a visible
         *   pill. The endpoint is zero-filled by design, so a well-formed
         *   answer always carries its full bucket set — an empty array means
         *   something went wrong upstream that didn't rise to a 502. That's
         *   degradation, not signal, and a failed pull-to-refresh must not
         *   yank the header out from under the user's thumb.
         * - A populated series whose window sums to zero **is** a real answer,
         *   and the honest render of "nothing published in three weeks" is
         *   nothing. Showing a stale count there would be exactly the false
         *   claim the header copy is worded to avoid. Reaching it requires the
         *   platform's entire three-week output to age out between two fetches
         *   ~6h apart, so the reflow it costs is rare and well spent.
         */
        fun reduce(weeks: List<RecipeWeek>?, current: RecipeTrendState): RecipeTrendState {
            // No answer at all — hold whatever is already on screen.
            if (weeks.isNullOrEmpty()) return if (current is Visible) current else Hidden
            val count = RecipeTrend.recentCount(weeks)
            // A real answer, including a real zero.
            return if (count > 0) Visible(count, weeks) else Hidden
        }
    }
}
