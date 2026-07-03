package cooking.zap.app.souschef

import cooking.zap.app.souschef.SousChefImagePrep.EncodeDecision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure decision logic for image preparation ([SousChefImagePrep]).
 * The Android decode/encode path is exercised on-device; these tests lock
 * the math it delegates to.
 */
class SousChefImagePrepTest {

    private companion object {
        const val MAX = SousChefImagePrep.MAX_EDGE_PX // 2048
        const val CEILING = SousChefImagePrep.MAX_DATA_URL_BYTES // 15MB
    }

    // computeInSampleSize — largest power of two keeping the decoded longest
    // edge >= 2048, so the exact-scale step only ever shrinks.

    @Test
    fun sampleSize_is_1_for_images_within_the_edge_limit() {
        assertEquals(1, SousChefImagePrep.computeInSampleSize(1024, 768))
        assertEquals(1, SousChefImagePrep.computeInSampleSize(MAX, MAX))
        assertEquals(1, SousChefImagePrep.computeInSampleSize(MAX, 100))
    }

    @Test
    fun sampleSize_is_1_just_under_double_the_edge_limit() {
        // 4095/2 = 2047 < 2048 — halving would undershoot, so stay at full size.
        assertEquals(1, SousChefImagePrep.computeInSampleSize(4095, 3000))
    }

    @Test
    fun sampleSize_is_2_at_exactly_double_the_edge_limit() {
        assertEquals(2, SousChefImagePrep.computeInSampleSize(4096, 3000))
    }

    @Test
    fun sampleSize_handles_odd_dimensions() {
        // 4097/2 = 2048 (integer division) >= 2048 → sample 2.
        assertEquals(2, SousChefImagePrep.computeInSampleSize(4097, 1233))
        // 8191/4 = 2047 < 2048 → stop at 2.
        assertEquals(2, SousChefImagePrep.computeInSampleSize(8191, 3000))
        assertEquals(4, SousChefImagePrep.computeInSampleSize(8192, 3000))
    }

    @Test
    fun sampleSize_uses_the_longest_edge_regardless_of_orientation() {
        assertEquals(
            SousChefImagePrep.computeInSampleSize(4096, 1000),
            SousChefImagePrep.computeInSampleSize(1000, 4096),
        )
    }

    // targetDimensions — exact scale after the sampled decode.

    @Test
    fun target_keeps_images_already_within_the_limit() {
        assertEquals(1920 to 1080, SousChefImagePrep.targetDimensions(1920, 1080))
        assertEquals(MAX to MAX, SousChefImagePrep.targetDimensions(MAX, MAX))
    }

    @Test
    fun target_scales_landscape_to_a_2048_longest_edge() {
        assertEquals(MAX to 1152, SousChefImagePrep.targetDimensions(4096, 2304))
    }

    @Test
    fun target_scales_portrait_to_a_2048_longest_edge() {
        assertEquals(1152 to MAX, SousChefImagePrep.targetDimensions(2304, 4096))
    }

    @Test
    fun target_handles_odd_dimensions_and_extreme_ratios() {
        val (w, h) = SousChefImagePrep.targetDimensions(4097, 33)
        assertEquals(MAX, w)
        assertEquals(16, h) // 33 * (2048/4097) = 16.49… → 16, floored at 1 minimum
        // A pathological sliver never collapses to 0.
        assertEquals(MAX to 1, SousChefImagePrep.targetDimensions(100_000, 2))
    }

    // decideEncode — the 85 → 70 → fail ladder against the 15MB data-URL
    // ceiling. At or under the ceiling accepts; strictly over steps down.

    @Test
    fun encode_accepts_at_or_under_the_ceiling_at_initial_quality() {
        assertEquals(
            EncodeDecision.Accept,
            SousChefImagePrep.decideEncode(CEILING, SousChefImagePrep.INITIAL_JPEG_QUALITY),
        )
        assertEquals(
            EncodeDecision.Accept,
            SousChefImagePrep.decideEncode(1_000L, SousChefImagePrep.INITIAL_JPEG_QUALITY),
        )
    }

    @Test
    fun encode_over_ceiling_at_initial_quality_retries_at_fallback() {
        assertEquals(
            EncodeDecision.Retry(SousChefImagePrep.FALLBACK_JPEG_QUALITY),
            SousChefImagePrep.decideEncode(CEILING + 1, SousChefImagePrep.INITIAL_JPEG_QUALITY),
        )
    }

    @Test
    fun encode_accepts_at_fallback_quality_when_under_the_ceiling() {
        assertEquals(
            EncodeDecision.Accept,
            SousChefImagePrep.decideEncode(CEILING, SousChefImagePrep.FALLBACK_JPEG_QUALITY),
        )
    }

    @Test
    fun encode_over_ceiling_at_fallback_quality_fails() {
        assertEquals(
            EncodeDecision.Fail,
            SousChefImagePrep.decideEncode(CEILING + 1, SousChefImagePrep.FALLBACK_JPEG_QUALITY),
        )
    }

    // data-URL assembly — the server consumes the data: prefix verbatim.

    @Test
    fun dataUrl_prefixes_jpeg_base64() {
        assertEquals("data:image/jpeg;base64,AAAA", SousChefImagePrep.toJpegDataUrl("AAAA"))
    }
}
