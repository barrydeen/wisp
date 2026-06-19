package cooking.zap.app.nostr

/**
 * The expanded food-hashtag set for the OnlyFood social feed (concern 1.6),
 * ported from the web client's `FoodstrFeedOptimized.svelte` `FOOD_HASHTAGS`.
 *
 * Breadth is deliberate: filtering on `foodstr` alone (as the old merged feed
 * did) makes the stream look empty. The trade-off is that ambiguous tags
 * (`#beef`, `#steak`, `#snack`) let some non-food noise through — NSpam +
 * mute lists catch the worst, and a hard/soft keyword relevance scorer is a
 * possible later precision refinement (not in v1).
 */
object FoodHashtags {

    /** Deduped, order-preserved. Used as the `#t` filter for kind-1 notes. */
    val ALL: List<String> = listOf(
        "foodstr", "cook", "cookstr", "zapcooking", "cooking", "drinkstr",
        "foodies", "carnivor", "carnivorediet", "soup", "soupstr", "drink",
        "eat", "burger", "steak", "steakstr", "dine", "dinner", "lunch",
        "breakfast", "supper", "yum", "snack", "snackstr", "dessert", "beef",
        "chicken", "bbq", "coffee", "mealprep", "meal", "recipe", "recipestr",
        "recipes", "food", "foodie", "foodporn", "instafood", "foodstagram",
        "foodblogger", "homecooking", "fromscratch", "baking", "baker",
        "pastry", "chef", "chefs", "cuisine", "gourmet", "restaurant",
        "restaurants", "pasta", "pizza", "sushi", "tacos", "taco", "burrito",
        "sandwich", "salad", "stew", "curry", "stirfry", "grill", "grilled",
        "roast", "roasted", "fried", "baked", "smoked", "fermented", "pickled",
        "preserved", "homemade", "vegan", "vegetarian", "keto", "paleo",
        "glutenfree", "dairyfree", "healthy", "nutrition", "nutritionist",
        "dietitian", "mealplan", "batchcooking",
    )
}
