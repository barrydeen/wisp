package cooking.zap.app.nostr

/**
 * Dev / e2e test recipes that must not surface on the Recipes feed.
 * Coordinates are NIP-01 addressable keys `kind:pubkey:d-tag` (not event ids),
 * so later replaceable revisions stay hidden too.
 *
 * Kept in sync with web `HIDDEN_RECIPE_COORDINATES` in `src/lib/consts.ts`.
 */
object HiddenRecipes {
    val COORDINATES: Set<String> = setOf(
        "30023:8b739c62ed2a9b76c2836a18a6bc9a480b6f8d902b8f702083dfae20bf6b15b9:zc-pr11-test-bravo",
        "30023:8b739c62ed2a9b76c2836a18a6bc9a480b6f8d902b8f702083dfae20bf6b15b9:zc-pr11-test-alpha",
        "30023:8b739c62ed2a9b76c2836a18a6bc9a480b6f8d902b8f702083dfae20bf6b15b9:pr10-pancakes",
        "30023:a22a71c97b536902adb2b15f3e56014d2a2a2adc0c2d99f3996081455cc4ea92:pr11-ghost-recipe",
        "30023:a22a71c97b536902adb2b15f3e56014d2a2a2adc0c2d99f3996081455cc4ea92:pr10-zero-parse2",
        "30023:a22a71c97b536902adb2b15f3e56014d2a2a2adc0c2d99f3996081455cc4ea92:pr10-zero-parse",
        "30023:772e4f7ffd63a09748eb231e40e4dbd772fe997b8748c194f6204cfd8e4c933f:e2e-salad",
        "30023:772e4f7ffd63a09748eb231e40e4dbd772fe997b8748c194f6204cfd8e4c933f:e2e-toast",
        "30023:772e4f7ffd63a09748eb231e40e4dbd772fe997b8748c194f6204cfd8e4c933f:e2e-curry",
        "30023:783f7c04246e161314bd33853b20aecd3b027e3ef9c9783ec3d973365a2f269c:e2e-salad",
        "30023:bcaa6d25a3cac844c4631e082f62917fb6c8e3b80a3a05c87a65444310f04921:e2e-salad",
        "30023:434b9310a45d05d97c1d45354fb4a9857bf181db3194e107402c6cb002164c9a:e2e-salad",
        "30023:434b9310a45d05d97c1d45354fb4a9857bf181db3194e107402c6cb002164c9a:e2e-curry",
        "30023:f74290982a8cce6b8f869f3c33f5f9844bcbaf9ad22909904aae6f04efce69f4:e2e-curry",
        "30023:5a866ed1f65d68aec7c1879f810eff389ec15bfccf685b303df040d072f50864:e2e-curry",
        "30023:f62cdccd2c958cdd9726351cbc0e804ab9e32c47f6c62d649b0aaa23f9651f0d:e2e-salad",
        "30023:f62cdccd2c958cdd9726351cbc0e804ab9e32c47f6c62d649b0aaa23f9651f0d:e2e-curry",
        "30023:dd7e9c53ae4509aba878370c7285395e5d61b98e8eabdb33afa4deb6b6f68c13:e2e-salad",
        "30023:dd7e9c53ae4509aba878370c7285395e5d61b98e8eabdb33afa4deb6b6f68c13:e2e-curry",
    )

    fun isHidden(coordinate: String): Boolean = coordinate in COORDINATES

    fun isHidden(kind: Int, pubkey: String, dTag: String): Boolean =
        isHidden("$kind:$pubkey:$dTag")
}
