package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [IngredientScaler] is best-effort and must never corrupt or drop a line.
 * These cases use the REAL ingredient lines from the Tuscan Peposo and
 * Japanese Milk Bread events (Step-0 capture) plus the fraction / mixed /
 * range / no-number edges the parser has to survive.
 */
class IngredientScalerTest {

    private fun x2(line: String) = IngredientScaler.scaleLine(line, 2.0)
    private fun half(line: String) = IngredientScaler.scaleLine(line, 0.5)

    @Test
    fun multiplierOne_isIdentity() {
        val line = "1 kg beef for stewing (chuck or similar)"
        assertEquals(line, IngredientScaler.scaleLine(line, 1.0))
    }

    // ---- Real Tuscan Peposo lines ----------------------------------------

    @Test
    fun realTuscanLines_scaleLeadingQuantityOnly() {
        assertEquals("2 kg beef for stewing (chuck or similar)", x2("1 kg beef for stewing (chuck or similar)"))
        assertEquals("1500 ml red wine", x2("750 ml red wine"))
        assertEquals("8 garlic cloves", x2("4 garlic cloves"))
        assertEquals("4 tbsp black pepper (coarsely ground)", x2("2 tbsp black pepper (coarsely ground)"))
        // No leading quantity -> verbatim.
        assertEquals("Salt", x2("Salt"))
        assertEquals("Extra virgin olive oil", x2("Extra virgin olive oil"))
    }

    @Test
    fun realTuscanLines_halve() {
        assertEquals("½ kg beef for stewing (chuck or similar)", half("1 kg beef for stewing (chuck or similar)"))
        assertEquals("375 ml red wine", half("750 ml red wine"))
        assertEquals("2 garlic cloves", half("4 garlic cloves"))
        assertEquals("1 tbsp black pepper (coarsely ground)", half("2 tbsp black pepper (coarsely ground)"))
    }

    // ---- Real Milk Bread lines: only the LEADING measure scales ----------

    @Test
    fun realMilkBreadLines_secondaryMeasuresUntouched() {
        // "60 mL water ¼ cup" -> the ¼ cup alt-measure must NOT scale.
        assertEquals("120 mL water ¼ cup", x2("60 mL water ¼ cup"))
        assertEquals("46 g bread flour 2 tbsp", x2("23 g bread flour 2 tbsp"))
        // "58 g unsalted butter softened, 4 tbsp / ½ stick" -> only the 58 g.
        assertEquals("116 g unsalted butter softened, 4 tbsp / ½ stick", x2("58 g unsalted butter softened, 4 tbsp / ½ stick"))
        assertEquals("2 tsp sea salt", x2("1 tsp sea salt"))
        // Header-ish lines with no number stay verbatim.
        assertEquals("For the Tangzhong", x2("For the Tangzhong"))
    }

    // ---- Fraction / mixed-number forms -----------------------------------

    @Test
    fun unicodeFraction_scales() {
        assertEquals("1 cup sugar", x2("½ cup sugar"))     // ½ * 2 = 1
        assertEquals("⅔ cup milk", x2("⅓ cup milk"))        // ⅓ * 2 = ⅔
        assertEquals("1 cup flour", IngredientScaler.scaleLine("⅓ cup flour", 3.0)) // ⅓ * 3 = 1
        assertEquals("¼ cup oil", half("½ cup oil"))         // ½ * 0.5 = ¼
    }

    @Test
    fun mixedNumbers_allForms_scale() {
        assertEquals("3 cups flour", x2("1½ cups flour"))        // adjacent unicode
        assertEquals("3 cups flour", x2("1 ½ cups flour"))       // spaced unicode
        assertEquals("3 cups flour", x2("1 1/2 cups flour"))     // spaced ascii
        assertEquals("1½ cups flour", IngredientScaler.scaleLine("¾ cups flour", 2.0)) // ¾*2 = 1½ -> glyph
    }

    @Test
    fun simpleAsciiFraction_scales() {
        assertEquals("1 tsp vanilla", x2("1/2 tsp vanilla"))
        assertEquals("¼ tsp salt", half("1/2 tsp salt"))  // ½ * 0.5 = ¼ glyph
    }

    @Test
    fun decimal_scales() {
        assertEquals("1 L stock", x2("0.5 L stock"))
        assertEquals("3 lb roast", x2("1.5 lb roast"))
    }

    @Test
    fun range_scalesBothEnds_keepsSeparator() {
        assertEquals("4-6 cloves garlic", x2("2-3 cloves garlic"))
        assertEquals("4 – 6 eggs", x2("2 – 3 eggs"))
    }

    // ---- Robustness: never crash, never drop ------------------------------

    @Test
    fun noLeadingNumber_returnedVerbatim() {
        assertEquals("Pinch of salt", x2("Pinch of salt"))
        assertEquals("A couple eggs", x2("A couple eggs"))
        assertEquals("", x2(""))
        assertEquals("Salt to taste (about 350°F oven)", x2("Salt to taste (about 350°F oven)"))
    }

    @Test
    fun mixedFractionAdjacent_oneAndAHalf_rendersWithGlyph() {
        // 0.75 (¾) -> scale x2 -> 1.5 -> "1½"
        assertEquals("1½", IngredientScaler.formatQuantity(1.5))
        assertEquals("2", IngredientScaler.formatQuantity(2.0))
        assertEquals("⅓", IngredientScaler.formatQuantity(1.0 / 3))
    }
}
