package cooking.zap.app.souschef

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SousChefImageValidationTest {

    private companion object {
        const val TWENTY_MB: Long = 20L * 1024 * 1024
        const val TOO_LARGE_ERROR = "Image file is too large. Please use an image under 20MB."
        const val NOT_AN_IMAGE_ERROR = "Please choose an image file."
    }

    // MIME type

    @Test
    fun accepts_common_image_mime_types() {
        assertNull(validateStagedImage("image/jpeg", 1_000L))
        assertNull(validateStagedImage("image/png", 1_000L))
        assertNull(validateStagedImage("image/webp", 1_000L))
    }

    @Test
    fun rejects_non_image_mime_types() {
        assertEquals(NOT_AN_IMAGE_ERROR, validateStagedImage("video/mp4", 1_000L))
        assertEquals(NOT_AN_IMAGE_ERROR, validateStagedImage("application/pdf", 1_000L))
        assertEquals(NOT_AN_IMAGE_ERROR, validateStagedImage("text/plain", 1_000L))
    }

    @Test
    fun rejects_an_unknown_mime_type() {
        assertEquals(NOT_AN_IMAGE_ERROR, validateStagedImage(null, 1_000L))
    }

    @Test
    fun mime_rejection_wins_over_size_rejection() {
        assertEquals(NOT_AN_IMAGE_ERROR, validateStagedImage("video/mp4", TWENTY_MB + 1))
    }

    // size limit — web parity is `file.size > 20 * 1024 * 1024`, so exactly
    // 20MB passes and one byte over fails.

    @Test
    fun accepts_a_file_of_exactly_20mb() {
        assertNull(validateStagedImage("image/jpeg", TWENTY_MB))
    }

    @Test
    fun rejects_a_file_one_byte_over_20mb() {
        assertEquals(TOO_LARGE_ERROR, validateStagedImage("image/jpeg", TWENTY_MB + 1))
    }

    @Test
    fun rejects_a_file_far_over_20mb() {
        assertEquals(TOO_LARGE_ERROR, validateStagedImage("image/jpeg", 100L * 1024 * 1024))
    }

    @Test
    fun accepts_an_image_whose_size_the_provider_does_not_report() {
        assertNull(validateStagedImage("image/jpeg", null))
    }
}
