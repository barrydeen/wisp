package cooking.zap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The recipe-detail route carries the recipe d-tag in its path, and real
 * d-tags contain characters that are illegal/ambiguous in a URL path —
 * notably `(` `)` and `/` (e.g. "mai-tai-barcadi-(german/-deutsch)"). If the
 * d-tag weren't URL-encoded, the NavController would mis-route or 404. These
 * pin the encode (and the round-trip the composable relies on).
 */
class RoutesRecipeTest {

    @Test
    fun recipe_encodesParensInDTag() {
        val route = Routes.recipe(
            author = "1852d83e2b9d12fa561071bfe159ff5ae510af1fc9b51b85539cb6a81486f207",
            dTag = "tuscan-peposo-(black-pepper-beef-stew)"
        )
        assertEquals(
            "recipe/1852d83e2b9d12fa561071bfe159ff5ae510af1fc9b51b85539cb6a81486f207/" +
                "tuscan-peposo-%28black-pepper-beef-stew%29",
            route
        )
        val encodedDTag = route.substringAfterLast('/')
        assertFalse("( must not survive un-encoded", encodedDTag.contains('('))
        assertFalse(") must not survive un-encoded", encodedDTag.contains(')'))
    }

    @Test
    fun recipe_encodesSlashSoItIsNotAPathSeparator() {
        val route = Routes.recipe(author = "abc", dTag = "mai-tai-barcadi-(german/-deutsch)")
        // The slash inside the d-tag is %2F, so the route still has exactly
        // two segments after "recipe/".
        assertEquals("recipe/abc/mai-tai-barcadi-%28german%2F-deutsch%29", route)
        assertEquals(3, route.split('/').size)
    }

    @Test
    fun recipe_roundTripsThroughUrlDecoder() {
        val dTag = "tuscan-peposo-(black-pepper-beef-stew)"
        val encoded = Routes.recipe("a", dTag).substringAfterLast('/')
        assertEquals(dTag, java.net.URLDecoder.decode(encoded, "UTF-8"))
    }
}
