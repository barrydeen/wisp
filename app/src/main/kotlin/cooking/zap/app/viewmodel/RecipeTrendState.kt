package cooking.zap.app.viewmodel

import cooking.zap.app.api.RecipeWeek
import cooking.zap.app.ui.component.RecipeTrend

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
         * **[Hidden] is reachable only from [Loading].** Once [Visible], this
         * returns the existing state rather than collapsing, so a failed
         * pull-to-refresh (or a lull that drops the window to zero) can never
         * yank the header out from under the user's thumb. The practical
         * guarantee for the UI: a collapse happens at most once per process,
         * before anything has been shown.
         */
        fun reduce(weeks: List<RecipeWeek>?, current: RecipeTrendState): RecipeTrendState {
            val count = weeks?.let { RecipeTrend.recentCount(it) } ?: 0
            if (count > 0 && !weeks.isNullOrEmpty()) return Visible(count, weeks)
            return if (current is Visible) current else Hidden
        }
    }
}
