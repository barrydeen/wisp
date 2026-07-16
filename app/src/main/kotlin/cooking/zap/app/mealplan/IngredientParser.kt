package cooking.zap.app.mealplan

import cooking.zap.app.nostr.GroceryEvents

/**
 * Port of frontend `src/lib/utils/ingredientParser.ts` — **web wins**. Quirks
 * (plural singularization `"2 cups"` → `"2 cup"`, comma-suffix retention
 * `"eggs, beaten"`) are the cross-platform contract pinned by
 * `ingredient-parser.vectors.json`. Do not "improve" unilaterally; change the
 * fixtures upstream and move both platforms together.
 *
 * Category inference mirrors `groceryService.inferCategory` (keyword lists +
 * check order). Category display order for grocery UI is
 * [GroceryEvents.CATEGORIES] (produce → protein → dairy → pantry → frozen → other).
 */
object IngredientParser {

    data class ParsedIngredient(
        val name: String,
        val quantity: String,
        val category: String,
        val originalText: String,
    )

    // Web UNITS list — order matters: singular forms precede plurals so the
    // trailing `s?` on QUANTITY_PATTERN can eat the plural (the filed quirk).
    private val UNITS = listOf(
        "cup", "cups", "c",
        "tablespoon", "tablespoons", "tbsp", "tbs", "tb",
        "teaspoon", "teaspoons", "tsp", "ts",
        "ounce", "ounces", "oz",
        "pound", "pounds", "lb", "lbs",
        "gram", "grams", "g",
        "kilogram", "kilograms", "kg",
        "milliliter", "milliliters", "ml",
        "liter", "liters", "l",
        "quart", "quarts", "qt",
        "pint", "pints", "pt",
        "gallon", "gallons", "gal",
        "piece", "pieces", "pc", "pcs",
        "slice", "slices",
        "can", "cans",
        "jar", "jars",
        "package", "packages", "pkg",
        "bunch", "bunches",
        "head", "heads",
        "clove", "cloves",
        "stalk", "stalks",
        "sprig", "sprigs",
        "pinch", "pinches",
        "dash", "dashes",
        "stick", "sticks",
        "large", "medium", "small",
    )

    private val UNIT_PATTERN = UNITS.joinToString("|") { Regex.escape(it) }
    private const val FRACTION_CHARS = "½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"
    private val NUMBER_PATTERN =
        "(?:\\d+[\\s-])?(?:\\d+/\\d+|[$FRACTION_CHARS]|\\d+(?:\\.\\d+)?)"

    private val QUANTITY_PATTERN = Regex(
        "^\\s*($NUMBER_PATTERN)\\s*(?:($UNIT_PATTERN)s?(?:\\.)?)?\\s+",
        RegexOption.IGNORE_CASE,
    )
    private val ALT_QUANTITY_PATTERN = Regex(
        "^\\s*($NUMBER_PATTERN)\\s+($UNIT_PATTERN)\\s+",
        RegexOption.IGNORE_CASE,
    )

    private val PREP_WORDS = listOf(
        "diced", "chopped", "minced", "sliced", "cubed", "grated", "shredded",
        "melted", "softened", "room temperature", "divided", "optional",
        "to taste", "or more", "or less", "packed", "sifted", "peeled",
    )

    private val PRODUCE_KEYWORDS = listOf(
        "apple", "banana", "orange", "lemon", "lime", "grape", "berry", "strawberry",
        "blueberry", "raspberry", "mango", "pineapple", "watermelon", "melon",
        "lettuce", "spinach", "kale", "cabbage", "broccoli", "cauliflower",
        "carrot", "celery", "cucumber", "tomato", "pepper", "onion", "garlic",
        "potato", "sweet potato", "squash", "zucchini", "mushroom", "avocado",
        "herb", "basil", "cilantro", "parsley", "mint", "fruit", "vegetable",
    )
    private val PROTEIN_KEYWORDS = listOf(
        "chicken", "beef", "pork", "lamb", "turkey", "duck", "meat",
        "fish", "salmon", "tuna", "shrimp", "crab", "lobster", "seafood",
        "egg", "tofu", "tempeh", "seitan", "bacon", "sausage", "ham",
    )
    private val DAIRY_KEYWORDS = listOf(
        "milk", "cream", "butter", "cheese", "yogurt", "sour cream",
        "cottage cheese", "cream cheese", "whipped cream", "half and half",
    )
    private val FROZEN_KEYWORDS = listOf(
        "frozen", "ice cream", "popsicle", "sorbet", "gelato",
    )
    private val PANTRY_KEYWORDS = listOf(
        "flour", "sugar", "salt", "pepper", "oil", "vinegar", "spice",
        "rice", "pasta", "noodle", "bread", "cereal", "oat", "bean", "lentil",
        "can", "canned", "sauce", "broth", "stock", "honey", "syrup",
        "nut", "almond", "peanut", "walnut", "seed", "chocolate", "cocoa",
    )

    private val FRACTION_MAP = mapOf(
        '½' to "1/2", '⅓' to "1/3", '⅔' to "2/3", '¼' to "1/4", '¾' to "3/4",
        '⅕' to "1/5", '⅖' to "2/5", '⅗' to "3/5", '⅘' to "4/5",
        '⅙' to "1/6", '⅚' to "5/6",
        '⅛' to "1/8", '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8",
    )

    /** Fixed grocery grouping order — same as web `groceryStore` / [GroceryEvents.CATEGORIES]. */
    val CATEGORY_DISPLAY_ORDER: List<String> = GroceryEvents.CATEGORIES

    fun parseIngredient(text: String): ParsedIngredient {
        val originalText = text.trim()
        var workingText = originalText

        workingText = workingText.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()

        val commaIndex = workingText.indexOf(',')
        if (commaIndex > 0) {
            val afterComma = workingText.substring(commaIndex + 1).trim().lowercase()
            if (PREP_WORDS.any { afterComma.startsWith(it) }) {
                workingText = workingText.substring(0, commaIndex).trim()
            }
        }

        var quantity = ""
        var name = workingText

        val match = QUANTITY_PATTERN.find(workingText)
        if (match != null) {
            val numberPart = match.groupValues[1].trim()
            val unitPart = match.groupValues[2].trim()
            quantity = if (unitPart.isNotEmpty()) "$numberPart $unitPart" else numberPart
            name = workingText.substring(match.value.length).trim()
        } else {
            val altMatch = ALT_QUANTITY_PATTERN.find(workingText)
            if (altMatch != null) {
                quantity = "${altMatch.groupValues[1].trim()} ${altMatch.groupValues[2].trim()}"
                name = workingText.substring(altMatch.value.length).trim()
            }
        }

        name = name.replace(Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE), "").trim()

        if (name.isEmpty()) {
            name = originalText
            quantity = ""
        }

        return ParsedIngredient(
            name = name,
            quantity = quantity,
            category = inferCategory(name),
            originalText = originalText,
        )
    }

    fun extractIngredientsFromRecipe(content: String): List<String> {
        val ingredients = mutableListOf<String>()
        val sectionMatch = Regex(
            "## Ingredients\\s*\\n([\\s\\S]*?)(?=\\n## |$)",
            RegexOption.IGNORE_CASE,
        ).find(content) ?: return emptyList()

        for (line in sectionMatch.groupValues[1].split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) continue
            if (trimmed.startsWith("**") && trimmed.endsWith("**")) continue

            when {
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val ingredient = trimmed.substring(2).trim()
                    if (ingredient.isNotEmpty()) ingredients.add(ingredient)
                }
                Regex("^\\d+\\.\\s+").containsMatchIn(trimmed) -> {
                    val ingredient = trimmed.replace(Regex("^\\d+\\.\\s+"), "").trim()
                    if (ingredient.isNotEmpty()) ingredients.add(ingredient)
                }
                trimmed.isNotEmpty() && !Regex("^[A-Z][a-z]+:$").matches(trimmed) -> {
                    ingredients.add(trimmed)
                }
            }
        }
        return ingredients
    }

    fun parseIngredientsFromRecipe(content: String): List<ParsedIngredient> =
        extractIngredientsFromRecipe(content).map { parseIngredient(it) }

    fun normalizeFraction(text: String): String {
        var result = text
        for ((fraction, replacement) in FRACTION_MAP) {
            result = result.replace(fraction.toString(), replacement)
        }
        return result
    }

    /** Web `inferCategory` — keyword check order is produce → protein → dairy → frozen → pantry. */
    fun inferCategory(name: String): String {
        val lowercaseName = name.lowercase()
        if (PRODUCE_KEYWORDS.any { lowercaseName.contains(it) }) return "produce"
        if (PROTEIN_KEYWORDS.any { lowercaseName.contains(it) }) return "protein"
        if (DAIRY_KEYWORDS.any { lowercaseName.contains(it) }) return "dairy"
        if (FROZEN_KEYWORDS.any { lowercaseName.contains(it) }) return "frozen"
        if (PANTRY_KEYWORDS.any { lowercaseName.contains(it) }) return "pantry"
        return GroceryEvents.CATEGORY_OTHER
    }
}
