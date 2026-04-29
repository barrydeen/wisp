package com.wisp.app.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.madebyevan.thumbhash.ThumbHash

object MediaHashDecoder {
    fun decode(thumbHash: String?, blurHash: String?, width: Int, height: Int): Bitmap? {
        decodeThumbHash(thumbHash, width, height)?.let { return it }
        return BlurHashDecoder.decode(blurHash, width, height)
    }

    private fun decodeThumbHash(thumbHash: String?, width: Int, height: Int): Bitmap? {
        if (thumbHash.isNullOrBlank()) return null

        return try {
            val decoded = decodeThumbHashBytes(thumbHash) ?: return null
            val image = ThumbHash.thumbHashToRGBA(decoded)
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(image.width * image.height)
            for (i in pixels.indices) {
                val px = i * 4
                pixels[i] = Color.argb(
                    image.rgba[px + 3].toInt() and 0xFF,
                    image.rgba[px].toInt() and 0xFF,
                    image.rgba[px + 1].toInt() and 0xFF,
                    image.rgba[px + 2].toInt() and 0xFF
                )
            }
            bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            if (image.width == width && image.height == height) bitmap
            else Bitmap.createScaledBitmap(bitmap, width, height, true)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeThumbHashBytes(thumbHash: String): ByteArray? {
        val normalized = thumbHash.trim()
        return runCatching { Base64.decode(normalized, Base64.DEFAULT) }.getOrNull()
    }
}
