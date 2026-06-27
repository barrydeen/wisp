package cooking.zap.app.nostr

import java.util.Locale

/**
 * The curated, sectioned topic taxonomy shown in onboarding (the "What do you
 * like to cook?" step). The food sections are based on the web client's
 * `CURATED_TAG_SECTIONS` (zapcooking/frontend, `src/lib/consts.ts`) — same
 * section names and display tags — so Android and web present the same food
 * categories, with a few Android-only food sections layered on top (e.g. "From
 * the foodstr feed").
 *
 * NOTE: this is no longer a strictly food-only taxonomy. The "Beyond food"
 * section intentionally includes broad Nostr interests (e.g. "Bitcoin",
 * "Photography") that do NOT normalize into [FoodHashtags.ALL]. Those publish as
 * ordinary `#t` interests via the existing path and do not affect the
 * food-filtered OnlyFood feed — only the food sections are guaranteed to map
 * into [FoodHashtags.ALL]. Don't assume every tag here is a food hashtag.
 *
 * This is the single source of truth for the onboarding topic picker; do not
 * hardcode a second flat list elsewhere. Tags are stored as display labels
 * (e.g. "Gluten Free", "Middle-Eastern"); [toHashtag] normalizes a label into a
 * nostr `#t` hashtag (lowercase, no spaces/hyphens) when the selection is
 * published as a kind-30015 interest set.
 */
object FoodTopics {

    data class Section(val emoji: String, val title: String, val tags: List<String>, val note: String? = null)

    val SECTIONS: List<Section> = listOf(
        Section(
            emoji = "🍽️",
            title = "Why are you cooking?",
            tags = listOf("Easy", "Quick", "Breakfast", "Lunch", "Supper", "Dessert", "Snack", "Drinks"),
        ),
        Section(
            emoji = "🌍",
            title = "Explore by culture",
            tags = listOf(
                "American", "Asian", "Chinese", "French", "German", "Greek", "Indian", "Italian",
                "Japanese", "Mexican", "Spanish", "Thai", "Turkish", "Vietnamese", "Mediterranean",
                "Middle-Eastern", "Brazilian", "Filipino", "Lebanese",
            ),
        ),
        Section(
            emoji = "🥩",
            title = "Proteins",
            tags = listOf("Beef", "Chicken", "Fish", "Lamb", "Pork", "Seafood", "Steak", "Turkey", "Duck", "Eggs", "Tofu"),
        ),
        Section(
            emoji = "🥕",
            title = "Ingredients",
            tags = listOf(
                "Apple", "Beans", "Bread", "Cheese", "Chocolate", "Coconut", "Corn", "Cream", "Fruit",
                "Garlic", "Mushrooms", "Noodles", "Pasta", "Peppers", "Potato", "Rice", "Spinach",
                "Tomato", "Vegetables",
            ),
        ),
        Section(
            emoji = "🍳",
            title = "Meals",
            tags = listOf("Pizza", "Pasta", "Soup", "Salad", "Sandwich", "Smoothie", "Breakfast", "Lunch", "Supper"),
        ),
        Section(
            emoji = "🔥",
            title = "Methods",
            tags = listOf("Baked", "Fry", "Oven", "Roast", "Slowcooked", "Grill", "Smoked", "Fermented", "Pickled", "Stir-fry"),
        ),
        Section(
            emoji = "🥗",
            title = "Lifestyle",
            tags = listOf("Vegan", "Keto", "Healthy", "Gluten Free", "Vegetarian", "Paleo", "Dairy-Free"),
        ),
        Section(
            emoji = "🌶️",
            title = "Flavor",
            tags = listOf("Spicy", "Sweet", "Curry"),
        ),
        Section(
            emoji = "🍴",
            title = "From the foodstr feed",
            tags = listOf(
                "Foodstr", "Foodie", "Homemade", "From Scratch", "Home Cooking", "Meal Prep", "BBQ",
                "Coffee", "Gourmet", "Chef", "Pastry", "Sushi", "Tacos", "Burrito",
            ),
        ),
        Section(
            emoji = "🌐",
            title = "Beyond food",
            note = "Zap Cooking is all about food — but Nostr is a wide-open network. Here are a few " +
                "other topics people post about, if you'd like them in your interests too.",
            tags = listOf(
                "Ask Nostr", "Homesteading", "Sports", "AI", "Bitcoin", "Nostr", "Photography", "Art",
                "Music", "Gardening", "Travel", "Health",
            ),
        ),
    )

    /**
     * Normalize a display tag to a nostr `#t` hashtag: lowercase with spaces and
     * hyphens stripped, e.g. "Gluten Free" -> "glutenfree", "Middle-Eastern" ->
     * "middleeastern". Matches the [FoodHashtags.ALL] convention.
     */
    fun toHashtag(tag: String): String =
        tag.lowercase(Locale.ROOT).replace(" ", "").replace("-", "")
}
