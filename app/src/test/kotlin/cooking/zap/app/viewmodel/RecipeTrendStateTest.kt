package cooking.zap.app.viewmodel

import cooking.zap.app.api.RecipeWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The trend reducer's state table. Pure — no ViewModel, no dispatcher.
 *
 * The rule worth defending here is that [RecipeTrendState.Hidden] is reachable
 * only from [RecipeTrendState.Loading]: once the pill has been shown, no later
 * result may take it away.
 */
class RecipeTrendStateTest {

    private fun weeks(vararg counts: Int): List<RecipeWeek> =
        counts.mapIndexed { i, c -> RecipeWeek("2026-01-%02d".format(i + 1), c) }

    private val good = weeks(1, 2, 4, 8, 8, 5)

    // --- From Loading ---

    @Test
    fun loading_nullResult_isHidden() {
        assertEquals(
            RecipeTrendState.Hidden,
            RecipeTrendState.reduce(null, RecipeTrendState.Loading),
        )
    }

    @Test
    fun loading_emptySeries_isHidden() {
        assertEquals(
            RecipeTrendState.Hidden,
            RecipeTrendState.reduce(emptyList(), RecipeTrendState.Loading),
        )
    }

    @Test
    fun loading_allZeroSeries_isHidden() {
        assertEquals(
            RecipeTrendState.Hidden,
            RecipeTrendState.reduce(weeks(0, 0, 0, 0), RecipeTrendState.Loading),
        )
    }

    @Test
    fun loading_zeroWindowButNonZeroHistory_isHidden() {
        // Recipes published, but none in the last three weeks: nothing to boast
        // about, so nothing renders.
        assertEquals(
            RecipeTrendState.Hidden,
            RecipeTrendState.reduce(weeks(9, 9, 9, 0, 0, 0), RecipeTrendState.Loading),
        )
    }

    @Test
    fun loading_positiveWindow_isVisibleWithRollingSum() {
        val state = RecipeTrendState.reduce(good, RecipeTrendState.Loading)

        assertEquals(RecipeTrendState.Visible(21, good), state)
    }

    // --- From Visible: never collapses ---

    @Test
    fun visible_thenFailureWithNothingCached_staysVisibleOnOldValue() {
        val visible = RecipeTrendState.Visible(21, good)

        val next = RecipeTrendState.reduce(null, visible)

        assertSame("a failed refresh must not collapse the header", visible, next)
    }

    @Test
    fun visible_thenEmptySeries_staysVisible() {
        val visible = RecipeTrendState.Visible(21, good)

        assertSame(visible, RecipeTrendState.reduce(emptyList(), visible))
    }

    @Test
    fun visible_thenAllZeroSeries_staysVisible() {
        // A genuine three-week lull is rare and still not worth reflowing a
        // scrolled feed for.
        val visible = RecipeTrendState.Visible(21, good)

        assertSame(visible, RecipeTrendState.reduce(weeks(0, 0, 0), visible))
    }

    @Test
    fun visible_thenNewerGoodSeries_updatesInPlace() {
        val visible = RecipeTrendState.Visible(21, good)
        val newer = weeks(8, 8, 5, 4)

        assertEquals(RecipeTrendState.Visible(17, newer), RecipeTrendState.reduce(newer, visible))
    }

    // --- From Hidden: recoverable ---

    @Test
    fun hidden_thenGoodSeries_becomesVisible() {
        // The collapse is one-way per process only in the Visible direction;
        // a later success may still reveal the pill.
        val state = RecipeTrendState.reduce(good, RecipeTrendState.Hidden)

        assertEquals(RecipeTrendState.Visible(21, good), state)
    }

    @Test
    fun hidden_thenFailure_staysHidden() {
        assertEquals(
            RecipeTrendState.Hidden,
            RecipeTrendState.reduce(null, RecipeTrendState.Hidden),
        )
    }
}
