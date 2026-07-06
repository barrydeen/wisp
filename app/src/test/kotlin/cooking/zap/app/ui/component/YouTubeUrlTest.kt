package cooking.zap.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [parseYouTube] — the pure YouTube URL id/start-offset parser backing inline
 * embeds in note content. Covers the URL shapes the detector claims to support plus the
 * HTML-escaped separator case (&amp;) called out in the PR review.
 */
class YouTubeUrlTest {

    private val id = "dQw4w9WgXcQ" // canonical 11-char id used across cases

    @Test
    fun `standard watch url`() {
        assertEquals(YouTubeRef(id, 0), parseYouTube("https://www.youtube.com/watch?v=$id"))
    }

    @Test
    fun `youtu_be short url`() {
        assertEquals(YouTubeRef(id, 0), parseYouTube("https://youtu.be/$id"))
    }

    @Test
    fun `shorts, embed, live, v, and mobile hosts`() {
        assertEquals(YouTubeRef(id, 0), parseYouTube("https://www.youtube.com/shorts/$id"))
        assertEquals(YouTubeRef(id, 0), parseYouTube("https://www.youtube.com/embed/$id"))
        assertEquals(YouTubeRef(id, 0), parseYouTube("https://www.youtube.com/live/$id"))
        assertEquals(YouTubeRef(id, 0), parseYouTube("https://www.youtube.com/v/$id"))
        assertEquals(YouTubeRef(id, 0), parseYouTube("https://m.youtube.com/watch?v=$id"))
    }

    @Test
    fun `v param after other query params`() {
        assertEquals(YouTubeRef(id, 0), parseYouTube("https://www.youtube.com/watch?feature=shared&v=$id"))
    }

    @Test
    fun `start offset in seconds via t and start`() {
        assertEquals(YouTubeRef(id, 90), parseYouTube("https://www.youtube.com/watch?v=$id&t=90"))
        assertEquals(YouTubeRef(id, 45), parseYouTube("https://youtu.be/$id?start=45"))
    }

    @Test
    fun `start offset in hms form`() {
        assertEquals(YouTubeRef(id, 3723), parseYouTube("https://youtu.be/$id?t=1h2m3s"))
        assertEquals(YouTubeRef(id, 125), parseYouTube("https://www.youtube.com/watch?v=$id&t=2m5s"))
    }

    @Test
    fun `html-escaped ampersand separator is normalized`() {
        // &amp;t= must still yield the start offset...
        assertEquals(YouTubeRef(id, 90), parseYouTube("https://www.youtube.com/watch?v=$id&amp;t=90"))
        // ...and &amp; before v= must still resolve the id.
        assertEquals(YouTubeRef(id, 0), parseYouTube("https://www.youtube.com/watch?feature=shared&amp;v=$id"))
    }

    @Test
    fun `non-youtube url returns null`() {
        assertNull(parseYouTube("https://example.com/watch?v=$id"))
        assertNull(parseYouTube("https://vimeo.com/123456789"))
        assertNull(parseYouTube("not a url at all"))
    }
}
