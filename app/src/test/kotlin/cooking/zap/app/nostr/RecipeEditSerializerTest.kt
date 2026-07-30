package cooking.zap.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recipe **edit** encoding — [Nip23RecipeFormat.serializeEdit] and the
 * [RecipeSerializer.mergeForEdit]/[RecipeSerializer.toTags] pair underneath it.
 *
 * Every case here is a way an edit can quietly destroy part of the recipe it
 * was supposed to correct, so each asserts on **what survived**, not just on
 * what changed. The round-trip gates in [RecipeSerializerTest] cover the create
 * path and are deliberately untouched: create's output must not move.
 */
class RecipeEditSerializerTest {

    /**
     * A published recipe event. [extraTags] are appended verbatim, which is how
     * each test states the thing it expects to still be there afterwards.
     */
    private fun published(
        title: String,
        dTag: String,
        images: List<String>,
        categories: List<String> = listOf("italian"),
        extraTags: List<List<String>> = emptyList(),
        createdAt: Long = 1_700_000_000L,
        publishedAt: Long? = null,
        pubkey: String = "a".repeat(64),
        id: String = "1".repeat(64),
    ): NostrEvent {
        val tags = buildList {
            add(listOf("d", dTag))
            add(listOf("title", title))
            add(listOf("t", "zapcooking"))
            add(listOf("t", "zapcooking-$dTag"))
            publishedAt?.let { add(listOf("published_at", it.toString())) }
            images.forEach { add(listOf("image", it)) }
            categories.forEach { add(listOf("t", "zapcooking-$it")) }
            addAll(extraTags)
        }
        return NostrEvent(
            id = id,
            pubkey = pubkey,
            created_at = createdAt,
            kind = RecipeParser.RECIPE_KIND,
            tags = tags,
            content = "\n## Ingredients\n\n- 1 thing\n\n\n## Directions\n\n1. Do it.\n",
            sig = "0".repeat(128),
        )
    }

    /** Serialize an edit of [original], optionally under a [newTitle]. */
    private fun edit(
        original: NostrEvent,
        newTitle: String? = null,
        images: List<String>? = null,
        categories: List<String>? = null,
    ): UnsignedRecipeEvent {
        val recipe = RecipeParser.parse(original)
        return Nip23RecipeFormat.serializeEdit(
            recipe = recipe,
            title = newTitle ?: recipe.title!!,
            imageUrls = images ?: recipe.images,
            categories = categories ?: recipe.categories,
            original = original,
        )
    }

    private fun values(tags: List<List<String>>, name: String): List<String> =
        tags.filter { it.size >= 2 && it[0] == name }.map { it[1] }

    // --- D0: a re-serialize must not delete what the model is lossy about ----

    @Test
    fun edit_keepsEveryImage_notJustTheCover() {
        val cover = "https://example.com/cover.jpg"
        val second = "https://example.com/second.jpg"
        val original = published("Ragu", "ragu", images = listOf(cover, second))

        // The model's `image` is a single cover; `images` is the whole list.
        // Editing through the cover alone is exactly the deletion this guards.
        assertEquals(listOf(cover, second), RecipeParser.parse(original).images)

        val tags = edit(original, newTitle = "Ragù").tags
        assertEquals("both images survive, in order", listOf(cover, second), values(tags, "image"))
    }

    @Test
    fun edit_keepsPlainHashtags_whileReplacingRecipeConventionOnes() {
        val original = published(
            "Ragu", "ragu", images = listOf("i"), categories = listOf("italian"),
            extraTags = listOf(listOf("t", "vegan")),
        )
        // `hashtags` is every `t`; `categories` only the `zapcooking-` ones — so
        // a plain `t` is parsed and then not represented in the form.
        val parsed = RecipeParser.parse(original)
        assertTrue("plain t parsed", parsed.hashtags.contains("vegan"))
        assertFalse("plain t is not a category", parsed.categories.contains("vegan"))

        val tags = edit(original, categories = listOf("french")).tags
        val ts = values(tags, "t")
        assertTrue("plain t survives", ts.contains("vegan"))
        assertTrue("new category written", ts.contains("zapcooking-french"))
        assertFalse("old category replaced", ts.contains("zapcooking-italian"))
        assertTrue("root survives", ts.contains("zapcooking"))
        assertEquals("no duplicate root", 1, ts.count { it == "zapcooking" })
    }

    @Test
    fun edit_carriesOverTagsTheModelDoesNotKnow() {
        // Nothing here knows what these mean. That is the point: preserving by
        // default is what makes the model's blind spots non-destructive.
        val unmodelled = listOf(
            listOf("gated", "gated_123", "100"),
            listOf("a", "30023:${"b".repeat(64)}:other"),
            listOf("some-future-tag", "value"),
        )
        val original = published("Ragu", "ragu", images = listOf("i"), extraTags = unmodelled)
        val tags = edit(original, newTitle = "Ragù").tags
        for (tag in unmodelled) assertTrue("preserved $tag", tags.contains(tag))
    }

    @Test
    fun edit_doesNotDuplicateOwnedTags() {
        val original = published("Ragu", "ragu", images = listOf("i"))
        val tags = edit(original, newTitle = "Ragù", images = listOf("j")).tags
        assertEquals("one d", 1, values(tags, "d").size)
        assertEquals("one title", 1, values(tags, "title").size)
        assertEquals("one image", listOf("j"), values(tags, "image"))
        assertEquals("retitled", listOf("Ragù"), values(tags, "title"))
    }

    @Test
    fun edit_dropsTheOriginalsClientTag() {
        // The publisher adds `client` per the member's current NIP-89 preference.
        // Carrying the original's forward would duplicate it and override a
        // preference the member has since turned off.
        val original = published(
            "Ragu", "ragu", images = listOf("i"),
            extraTags = listOf(listOf("client", "zap.cooking")),
        )
        assertTrue("no client tag", edit(original).tags.none { it.firstOrNull() == "client" })
    }

    /**
     * A legacy `#nostrcooking`-rooted recipe comes out of an edit rooted at
     * `#zapcooking`, and its category tags are re-prefixed to match.
     *
     * Pinned rather than assumed: `isGeneratedHashtag` treats **both** roots as
     * this serializer's own, so the legacy root is replaced rather than
     * preserved — the same thing the create path does
     * (`RecipeSerializer.ROOT`: "new recipes always carry the current root
     * tag"). The visible consequence is that editing a legacy recipe takes it
     * out of `#nostrcooking` feeds, so this is a product call, not just a tag
     * rule. Flagged for Prep; if he wants the legacy root preserved, this test
     * is the line that changes.
     */
    @Test
    fun edit_reRootsALegacyNostrcookingRecipe() {
        val original = NostrEvent(
            id = "1".repeat(64),
            pubkey = "a".repeat(64),
            created_at = 1_600_000_000L,
            kind = RecipeParser.RECIPE_KIND,
            tags = listOf(
                listOf("d", "ragu"),
                listOf("title", "Ragu"),
                listOf("t", "nostrcooking"),
                listOf("t", "nostrcooking-ragu"),
                listOf("t", "nostrcooking-italian"),
                listOf("image", "i"),
            ),
            content = "\n## Ingredients\n\n- 1 thing\n\n\n## Directions\n\n1. Do it.\n",
            sig = "0".repeat(128),
        )
        assertEquals(listOf("italian"), RecipeParser.parse(original).categories)

        val edited = edit(original, newTitle = "Ragù")
        val ts = values(edited.tags, "t")
        assertEquals("exactly one root", listOf("zapcooking"), ts.filter { it == "zapcooking" })
        assertEquals("legacy root gone", emptyList<String>(), ts.filter { it.startsWith("nostrcooking") })

        // The tag set stays internally coherent: the self-tag and the categories
        // share the new root, so no chip appears for the recipe itself.
        val reparsed = RecipeParser.parse(
            original.copy(id = "2".repeat(64), tags = edited.tags, content = edited.content)
        )
        assertEquals(listOf("italian"), reparsed.categories)
        assertEquals("ragu", reparsed.dTag)
    }

    // --- D1: the address is preserved, and the self-tag follows it -----------

    @Test
    fun edit_preservesIdentifier_acrossARename() {
        val original = published("Chocalate Cake", "chocalate-cake", images = listOf("i"))
        val tags = edit(original, newTitle = "Chocolate Cake").tags
        assertEquals("d frozen at the original", listOf("chocalate-cake"), values(tags, "d"))
        assertEquals("title updated", listOf("Chocolate Cake"), values(tags, "title"))
    }

    @Test
    fun edit_selfTagFollowsIdentifier_soNoCategoryChipAppears() {
        val original = published(
            "Chocalate Cake", "chocalate-cake", images = listOf("i"),
            categories = listOf("dessert"),
        )
        val edited = edit(original, newTitle = "Chocolate Cake")
        val ts = values(edited.tags, "t")
        assertTrue("self-tag from d", ts.contains("zapcooking-chocalate-cake"))
        assertFalse("no self-tag from the new title", ts.contains("zapcooking-chocolate-cake"))

        // The consequence, not just the tag: deriveCategories excludes exactly
        // "<root>-<dTag>", so a title-derived self-tag would survive that filter
        // and render as a category chip named after the recipe itself.
        val reparsed = RecipeParser.parse(
            original.copy(id = "2".repeat(64), tags = edited.tags, content = edited.content)
        )
        assertEquals(listOf("dessert"), reparsed.categories)
    }

    // --- D2: an edit must not re-date the recipe ----------------------------

    @Test
    fun edit_pinsPublicationMoment_fromTheOriginalsCreatedAt() {
        // The original carries no published_at, so the parser falls back to its
        // created_at — and that fallback is what the edit has to write down
        // before republishing under a fresh created_at.
        val original = published("Ragu", "ragu", images = listOf("i"), createdAt = 1_600_000_000L)
        val tags = edit(original, newTitle = "Ragù").tags
        assertEquals(listOf("1600000000"), values(tags, "published_at"))
    }

    @Test
    fun edit_keepsAnExistingPublishedAt() {
        val original = published(
            "Ragu", "ragu", images = listOf("i"),
            createdAt = 1_700_000_000L, publishedAt = 1_500_000_000L,
        )
        val tags = edit(original).tags
        assertEquals(listOf("1500000000"), values(tags, "published_at"))
        assertEquals("still only one", 1, values(tags, "published_at").size)
    }

    @Test
    fun edit_survivesASecondEdit_withoutDrift() {
        // Edit twice off the previous result: the address, the publication
        // moment and an unmodelled tag must all be the same as after edit one.
        val original = published(
            "Ragu", "ragu", images = listOf("a.jpg", "b.jpg"), createdAt = 1_600_000_000L,
            extraTags = listOf(listOf("gated", "gated_123", "100")),
        )
        val once = edit(original, newTitle = "Ragù")
        val onceEvent = original.copy(
            id = "2".repeat(64), created_at = 1_800_000_000L,
            tags = once.tags, content = once.content,
        )
        val twice = edit(onceEvent, newTitle = "Ragù alla Bolognese").tags

        assertEquals(listOf("ragu"), values(twice, "d"))
        assertEquals(listOf("1600000000"), values(twice, "published_at"))
        assertEquals(listOf("a.jpg", "b.jpg"), values(twice, "image"))
        assertTrue("unmodelled tag still there", twice.contains(listOf("gated", "gated_123", "100")))
        assertEquals("one published_at", 1, values(twice, "published_at").size)
    }

    // --- D2 regression guard: the create path does not move ------------------

    @Test
    fun create_tagsAreUnchanged_noIdentifierNoPublishedAt() {
        val tags = RecipeSerializer.toTags("X Y", "s", listOf("u"), listOf("Italian"))
        assertTrue("no published_at on create", tags.none { it.firstOrNull() == "published_at" })
        assertEquals("d is slug(title)", listOf("x-y"), values(tags, "d"))
        assertTrue("self-tag from the slug", tags.contains(listOf("t", "zapcooking-x-y")))
    }

    @Test
    fun create_serializeIsByteIdenticalToTheDefaultedToTags() {
        val event = NostrEvent.fromJson(
            javaClass.getResource("/recipes/tuscan_peposo.json")!!.readText()
        )
        val recipe = RecipeParser.parse(event)
        val produced = Nip23RecipeFormat.serialize(
            recipe, recipe.title!!, recipe.images, recipe.categories,
        )
        val expected = RecipeSerializer.toTags(
            recipe.title!!, recipe.summary, recipe.images, recipe.categories,
        )
        assertEquals(expected, produced.tags)
        assertEquals(event.content, produced.content)
    }
}
