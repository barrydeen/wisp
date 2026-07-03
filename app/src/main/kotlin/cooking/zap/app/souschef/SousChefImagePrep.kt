package cooking.zap.app.souschef

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Staged-image → data-URL preparation for Sous Chef image extraction
 * (Phase 3). Mirrors the web's pipeline: downsample so the longest edge is
 * ≤ [MAX_EDGE_PX], JPEG-encode at [INITIAL_JPEG_QUALITY], and enforce a
 * post-encode ceiling of [MAX_DATA_URL_BYTES] with one quality fallback to
 * [FALLBACK_JPEG_QUALITY] before giving up.
 *
 * The decision logic ([computeInSampleSize], [targetDimensions],
 * [decideEncode], [toJpegDataUrl]) is pure and unit-tested; only
 * [prepareImageDataUrl] touches Android I/O. Input Uris are NOT assumed
 * pre-validated — Phase 2's pick validation passes unknown-size files
 * through, so the post-encode ceiling here is the real backstop and
 * unreadable/corrupt streams must fail soft, never crash.
 */
object SousChefImagePrep {

    /** Longest edge after downsampling — matches the web's resize target. */
    const val MAX_EDGE_PX = 2048

    const val INITIAL_JPEG_QUALITY = 85
    const val FALLBACK_JPEG_QUALITY = 70

    /** Post-encode ceiling on the full data-URL string, matching the web. */
    const val MAX_DATA_URL_BYTES = 15L * 1024 * 1024

    /** Same copy as [validateStagedImage]'s oversize rejection. */
    const val OVERSIZE_ERROR = "Image file is too large. Please use an image under 20MB."
    const val READ_FAILURE_ERROR = "Couldn't read that image. Please try another photo."

    /**
     * BitmapFactory power-of-two sample size: the largest power of two that
     * still leaves the decoded longest edge ≥ [maxEdge], so the exact-scale
     * step in [targetDimensions] only ever shrinks. Images already within
     * [maxEdge] decode at full size (1).
     */
    fun computeInSampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PX): Int {
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /**
     * Exact post-decode dimensions: unchanged when the longest edge is
     * already ≤ [maxEdge], else scaled proportionally so the longest edge is
     * exactly [maxEdge] (short edge rounded, floored at 1px).
     */
    fun targetDimensions(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PX): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longest
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        return if (width >= height) maxEdge to h else w to maxEdge
    }

    /** Verdict on one encode attempt: ship it, retry lower, or give up. */
    sealed interface EncodeDecision {
        data object Accept : EncodeDecision
        data class Retry(val quality: Int) : EncodeDecision
        data object Fail : EncodeDecision
    }

    /**
     * The 85 → 70 → fail ladder. A data URL at or under the ceiling is
     * accepted; over the ceiling at a quality above the fallback retries at
     * [FALLBACK_JPEG_QUALITY]; over the ceiling at (or below) the fallback
     * fails with [OVERSIZE_ERROR].
     */
    fun decideEncode(dataUrlBytes: Long, quality: Int): EncodeDecision = when {
        dataUrlBytes <= MAX_DATA_URL_BYTES -> EncodeDecision.Accept
        quality > FALLBACK_JPEG_QUALITY -> EncodeDecision.Retry(FALLBACK_JPEG_QUALITY)
        else -> EncodeDecision.Fail
    }

    /**
     * Assemble the data URL the server consumes verbatim
     * (`parseRecipe.server.ts` passes `data:`-prefixed strings straight to
     * the vision model).
     */
    fun toJpegDataUrl(base64: String): String = "data:image/jpeg;base64,$base64"

    /** Outcome of [prepareImageDataUrl]. */
    sealed interface Result {
        data class Ready(val dataUrl: String) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * Decode, downsample, JPEG-encode, and base64 the staged image into a
     * data URL. CPU-bound, runs on `Dispatchers.Default`; never call on Main.
     * All failure modes (missing/corrupt/unreadable stream, oversize after
     * the quality fallback) return [Result.Failed] — this never throws for
     * bad input.
     */
    suspend fun prepareImageDataUrl(resolver: ContentResolver, uri: Uri): Result =
        withContext(Dispatchers.Default) {
            try {
                // Pass 1: bounds only — cheap, and rejects non-images/corrupt
                // streams before any allocation.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                } ?: return@withContext Result.Failed(READ_FAILURE_ERROR)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@withContext Result.Failed(READ_FAILURE_ERROR)
                }

                // Pass 2: sampled decode, then exact scale to the target edge.
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight)
                }
                val decoded = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@withContext Result.Failed(READ_FAILURE_ERROR)

                val (targetW, targetH) = targetDimensions(decoded.width, decoded.height)
                val bitmap = if (targetW == decoded.width && targetH == decoded.height) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                        if (it !== decoded) decoded.recycle()
                    }
                }

                try {
                    var quality = INITIAL_JPEG_QUALITY
                    var result: Result? = null
                    // Terminates: decideEncode can only Retry while quality is
                    // above the fallback, and Retry lowers it to the fallback.
                    while (result == null) {
                        val bytes = ByteArrayOutputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                            out.toByteArray()
                        }
                        val dataUrl = toJpegDataUrl(Base64.getEncoder().encodeToString(bytes))
                        when (val decision = decideEncode(dataUrl.length.toLong(), quality)) {
                            is EncodeDecision.Accept -> result = Result.Ready(dataUrl)
                            is EncodeDecision.Retry -> quality = decision.quality
                            is EncodeDecision.Fail -> result = Result.Failed(OVERSIZE_ERROR)
                        }
                    }
                    result
                } finally {
                    bitmap.recycle()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // SecurityException (revoked grant), OOM on absurd inputs,
                // provider hiccups — all fail soft into the error state.
                Result.Failed(READ_FAILURE_ERROR)
            }
        }
}
