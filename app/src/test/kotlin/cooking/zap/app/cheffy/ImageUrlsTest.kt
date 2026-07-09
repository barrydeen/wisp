package cooking.zap.app.cheffy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity suite for [ImageUrls] — mirrors the web `imageUrls.test.ts`
 * case-for-case (first half), then pins each divergence documented in
 * Phase 0 finding 0.5, proving the Android module now matches web where
 * [cooking.zap.app.ui.component.RichContent]'s renderer-oriented
 * detection did not. The server validates note-review image URLs with
 * the same web module, so these tests are the contract.
 */
class ImageUrlsTest {

    // --- isImageUrl: mirrors imageUrls.test.ts ---

    @Test
    fun acceptsExtensionBearingImageUrls() {
        assertTrue(ImageUrls.isImageUrl("https://example.com/dish.jpg"))
        assertTrue(ImageUrls.isImageUrl("https://example.com/dish.jpeg?w=800"))
        assertTrue(ImageUrls.isImageUrl("https://example.com/a/b/dish.WEBP"))
        assertTrue(ImageUrls.isImageUrl("https://example.com/dish.avif"))
    }

    @Test
    fun acceptsKnownImageHostsWithoutAnExtension() {
        assertTrue(ImageUrls.isImageUrl("https://image.nostr.build/abc123"))
        assertTrue(ImageUrls.isImageUrl("https://nostr.build/i/abc123"))
        assertTrue(ImageUrls.isImageUrl("https://i.ibb.co/xyz/photo"))
        assertTrue(ImageUrls.isImageUrl("https://primal.b-cdn.net/media-cache?u=foo"))
    }

    @Test
    fun rejectsNonImageUrls() {
        assertFalse(ImageUrls.isImageUrl("https://example.com/recipe.html"))
        assertFalse(ImageUrls.isImageUrl("https://example.com/video.mp4"))
        assertFalse(ImageUrls.isImageUrl("https://nostr.build/blog/post"))
        // extension in query only — pathname is what counts
        assertFalse(ImageUrls.isImageUrl("https://example.com/page?img=x.jpg"))
    }

    @Test
    fun rejectsInvalidUrls() {
        assertFalse(ImageUrls.isImageUrl("not a url"))
        assertFalse(ImageUrls.isImageUrl(""))
    }

    @Test
    fun rejectsLookalikeHostsThatOnlyContainATrustedDomainAsASubstring() {
        assertFalse(ImageUrls.isImageUrl("https://imgur.com.evil.example/abc"))
        assertFalse(ImageUrls.isImageUrl("https://image.nostr.build.evil.example/abc"))
        assertFalse(ImageUrls.isImageUrl("https://notimgur.com/abc"))
        assertFalse(ImageUrls.isImageUrl("https://evil.example/imgur.com/abc"))
        assertFalse(ImageUrls.isImageUrl("https://myimgproxyish.com/abc"))
    }

    @Test
    fun stillAcceptsRealSubdomainsAndImgproxyInstances() {
        assertTrue(ImageUrls.isImageUrl("https://i.imgur.com/abc"))
        assertTrue(ImageUrls.isImageUrl("https://imgproxy.iris.to/foo/bar"))
    }

    // --- extractImageUrls: mirrors imageUrls.test.ts ---

    @Test
    fun extractsImageUrlsFromRawNoteContentInOrder() {
        val content =
            "made this tonight https://image.nostr.build/aaa.jpg and plated it https://example.com/b.png"
        assertEquals(
            listOf("https://image.nostr.build/aaa.jpg", "https://example.com/b.png"),
            ImageUrls.extractImageUrls(content),
        )
    }

    @Test
    fun ignoresNonImageUrls() {
        assertEquals(
            emptyList<String>(),
            ImageUrls.extractImageUrls("see https://zap.cooking/recipe/123 for the recipe"),
        )
    }

    @Test
    fun deduplicatesRepeatedUrls() {
        val u = "https://example.com/x.jpg"
        assertEquals(listOf(u), ImageUrls.extractImageUrls("$u again $u"))
    }

    @Test
    fun stripsTrailingProsePunctuation() {
        assertEquals(
            listOf("https://example.com/x.jpg"),
            ImageUrls.extractImageUrls("look: https://example.com/x.jpg!"),
        )
    }

    @Test
    fun handlesEmptyAndImageFreeContent() {
        assertEquals(emptyList<String>(), ImageUrls.extractImageUrls(""))
        assertEquals(emptyList<String>(), ImageUrls.extractImageUrls("no links here"))
    }

    // --- filterImageUrls: mirrors imageUrls.test.ts ---

    @Test
    fun filtersDedupesAndPreservesFirstOccurrenceOrder() {
        val a = "https://example.com/a.jpg"
        val b = "https://example.com/b.png"
        assertEquals(
            listOf(a, b),
            ImageUrls.filterImageUrls(listOf(a, "https://example.com/page.html", b, a, "")),
        )
    }

    // --- Divergences from RichContent, per finding 0.5 — Android now matches web ---

    @Test
    fun divergence1_heicAndHeifAreExcludedByDesign() {
        // RichContent renders heic/heif; the note-review server (same web
        // module) rejects them — parity wins (Phase 0 decision 4).
        assertFalse(ImageUrls.isImageUrl("https://example.com/photo.heic"))
        assertFalse(ImageUrls.isImageUrl("https://example.com/photo.heif"))
    }

    @Test
    fun divergence1_webOnlyExtensionsAreAccepted() {
        // RichContent's set is missing svg/bmp/avif; the web module has them.
        assertTrue(ImageUrls.isImageUrl("https://example.com/logo.svg"))
        assertTrue(ImageUrls.isImageUrl("https://example.com/scan.bmp"))
        assertTrue(ImageUrls.isImageUrl("https://example.com/dish.avif"))
    }

    @Test
    fun divergence2_fragmentDoesNotDefeatTheExtensionCheck() {
        // RichContent derives the extension from the whole URL string, so
        // "jpg#gallery" fails its set lookup; web tests the parsed pathname,
        // which excludes the fragment.
        assertTrue(ImageUrls.isImageUrl("https://example.com/photo.jpg#gallery"))
    }

    @Test
    fun divergence3_blossomBareHashPathsAreNotImages() {
        // RichContent promotes 64-hex Blossom paths to media; the server
        // would 400 them (extensionless, host not on the rescue list).
        val hash = "a".repeat(64)
        assertFalse(ImageUrls.isImageUrl("https://blossom.example.com/$hash"))
        assertEquals(
            emptyList<String>(),
            ImageUrls.extractImageUrls("stored at https://blossom.example.com/$hash"),
        )
    }

    @Test
    fun divergence4_schemelessAndWebsocketTokensAreNotExtracted() {
        // RichContent's renderer regex matches bare domains and wss:// —
        // web's URL_REGEX is https?:// only.
        assertEquals(emptyList<String>(), ImageUrls.extractImageUrls("see example.com/x.jpg"))
        assertEquals(emptyList<String>(), ImageUrls.extractImageUrls("wss://relay.example.com/x.jpg"))
    }

    @Test
    fun divergence5_uppercaseHostsAreNormalizedLikeTheBrowser() {
        // JS `new URL(...)` lowercases the hostname before the host match;
        // java.net.URI would not — OkHttp's HttpUrl does.
        assertTrue(ImageUrls.isImageUrl("https://IMGUR.COM/abc"))
        assertTrue(ImageUrls.isImageUrl("https://Image.Nostr.Build/abc123"))
    }
}
