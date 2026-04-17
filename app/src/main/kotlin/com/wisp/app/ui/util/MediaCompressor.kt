package com.wisp.app.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

object MediaCompressor {

    data class Result(val bytes: ByteArray, val mimeType: String, val ext: String) {
        fun asTriple(): Triple<ByteArray, String, String> = Triple(bytes, mimeType, ext)
    }

    private const val PROFILE_MAX_DIM = 400
    private const val PROFILE_QUALITY = 75
    private const val CONTENT_MAX_DIM = 1920
    private const val CONTENT_QUALITY = 82

    suspend fun compressForProfile(bytes: ByteArray, sourceMime: String): Result =
        compressImage(bytes, sourceMime, PROFILE_MAX_DIM, PROFILE_QUALITY)

    suspend fun compressForContent(bytes: ByteArray, sourceMime: String): Result =
        compressImage(bytes, sourceMime, CONTENT_MAX_DIM, CONTENT_QUALITY)

    private suspend fun compressImage(
        bytes: ByteArray,
        sourceMime: String,
        maxDim: Int,
        quality: Int,
    ): Result = withContext(Dispatchers.Default) {
        if (!sourceMime.startsWith("image/")) {
            return@withContext Result(bytes, sourceMime, extensionFor(sourceMime))
        }
        // GIFs are handled separately by GifToMp4Converter; if one slips through, pass through raw
        // so animation isn't silently dropped.
        if (sourceMime == "image/gif") {
            return@withContext Result(bytes, sourceMime, "gif")
        }

        val decoded = decode(bytes, maxDim) ?: return@withContext Result(bytes, sourceMime, extensionFor(sourceMime))

        // ImageDecoder (API 28+) already applies EXIF orientation. BitmapFactory does not — rotate manually.
        val oriented = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val rotation = readExifRotation(bytes)
            if (rotation != 0) decoded.rotated(rotation).also { if (it !== decoded) decoded.recycle() } else decoded
        } else decoded

        val scaled = oriented.scaledDown(maxDim)
        if (scaled !== oriented) oriented.recycle()

        val out = ByteArrayOutputStream(scaled.byteCount / 4)
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        scaled.recycle()
        Result(out.toByteArray(), "image/jpeg", "jpg")
    }

    private fun decode(bytes: ByteArray, maxDim: Int): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(bytes, maxDim)
        } else {
            decodeWithBitmapFactory(bytes, maxDim)
        }
    }

    private fun decodeWithImageDecoder(bytes: ByteArray, maxDim: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > maxDim) {
                val scale = maxDim.toFloat() / longest.toFloat()
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun decodeWithBitmapFactory(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun Bitmap.scaledDown(maxDim: Int): Bitmap {
        val longest = max(width, height)
        if (longest <= maxDim) return this
        val scale = maxDim.toFloat() / longest.toFloat()
        val newW = (width * scale).roundToInt().coerceAtLeast(1)
        val newH = (height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, newW, newH, true)
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
    }

    private fun readExifRotation(bytes: ByteArray): Int = try {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        else -> "bin"
    }
}
