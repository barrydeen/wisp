package cooking.zap.app.nostr

data class RecipeTag(
    val tag: String,
    val label: String,
    val emoji: String,
)

/**
 * Curated recipe categories shared by:
 * - Recipes tab chips
 * - Search "Tags" tab instant matches
 * - Recipe tag feed header metadata
 */
object RecipeTagCatalog {
    val recipeTags: List<RecipeTag> = listOf(
        RecipeTag("breakfast", "Breakfast", "🍳"),
        RecipeTag("lunch", "Lunch", "🥪"),
        RecipeTag("dinner", "Dinner", "🍽️"),
        RecipeTag("snack", "Snacks", "🍿"),
        RecipeTag("dessert", "Desserts", "🍰"),
        RecipeTag("baking", "Baking", "🧁"),
        RecipeTag("bread", "Bread", "🍞"),
        RecipeTag("soup", "Soups", "🥣"),
        RecipeTag("salad", "Salads", "🥗"),
        RecipeTag("pasta", "Pasta", "🍝"),
        RecipeTag("pizza", "Pizza", "🍕"),
        RecipeTag("grill", "Grill", "🔥"),
        RecipeTag("bbq", "BBQ", "🍖"),
        RecipeTag("vegan", "Vegan", "🥦"),
        RecipeTag("vegetarian", "Vegetarian", "🥕"),
        RecipeTag("chicken", "Chicken", "🍗"),
        RecipeTag("beef", "Beef", "🥩"),
        RecipeTag("seafood", "Seafood", "🦐"),
        RecipeTag("rice", "Rice", "🍚"),
        RecipeTag("noodles", "Noodles", "🍜"),
        RecipeTag("curry", "Curry", "🍛"),
        RecipeTag("tacos", "Tacos", "🌮"),
        RecipeTag("sandwich", "Sandwiches", "🥪"),
        RecipeTag("mealprep", "Meal Prep", "📦"),
        RecipeTag("onepot", "One Pot", "🍲"),
        RecipeTag("cocktail", "Cocktails", "🍸"),
        RecipeTag("coffee", "Coffee", "☕"),
        RecipeTag("italian", "Italian", "🇮🇹"),
        RecipeTag("mexican", "Mexican", "🇲🇽"),
        RecipeTag("indian", "Indian", "🇮🇳"),
    )

    private val byTag = recipeTags.associateBy { it.tag }

    fun byTag(tag: String): RecipeTag? = byTag[tag.trim().lowercase()]

    fun search(query: String): List<RecipeTag> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return recipeTags.filter { tag ->
            tag.tag.contains(needle) || tag.label.lowercase().contains(needle)
        }
    }
}
