package com.wisp.app.ml

import android.content.Context
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class NSpamWeights private constructor(
    val coef: FloatArray,
    val intercept: Float,
    val calibX: FloatArray,
    val calibY: FloatArray
) {
    companion object {
        fun loadFromAssets(context: Context, path: String = "nspam/weights.npz"): NSpamWeights {
            val arrays = mutableMapOf<String, FloatArray>()
            val scalars = mutableMapOf<String, Float>()
            context.assets.open(path).use { stream ->
                parseNpz(stream, arrays, scalars)
            }
            return NSpamWeights(
                coef = arrays["effective_coef"] ?: error("missing effective_coef"),
                intercept = scalars["intercept"] ?: error("missing intercept"),
                calibX = arrays["calib_x"] ?: error("missing calib_x"),
                calibY = arrays["calib_y"] ?: error("missing calib_y")
            )
        }

        private fun parseNpz(
            input: InputStream,
            arrays: MutableMap<String, FloatArray>,
            scalars: MutableMap<String, Float>
        ) {
            val zip = ZipInputStream(input)
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.removeSuffix(".npy")
                val data = zip.readBytes()
                val npy = parseNpy(data)
                if (npy.size == 1) {
                    scalars[name] = npy[0]
                } else {
                    arrays[name] = npy
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        private fun parseNpy(data: ByteArray): FloatArray {
            val major = data[6].toInt() and 0xFF
            val headerLen: Int
            val headerStart: Int
            if (major <= 1) {
                headerLen = ((data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8))
                headerStart = 10
            } else {
                headerLen = ByteBuffer.wrap(data, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                headerStart = 12
            }
            val header = String(data, headerStart, headerLen, Charsets.US_ASCII).trim()
            val dataStart = headerStart + headerLen

            val shapeMatch = Regex("""'shape'\s*:\s*\(([^)]*)\)""").find(header)
            val shapeStr = shapeMatch?.groupValues?.get(1)?.trim() ?: ""
            val count = if (shapeStr.isEmpty()) {
                1
            } else {
                shapeStr.split(",").filter { it.isNotBlank() }
                    .fold(1) { acc, s -> acc * s.trim().toInt() }
            }

            val buf = ByteBuffer.wrap(data, dataStart, count * 4).order(ByteOrder.LITTLE_ENDIAN)
            val result = FloatArray(count)
            buf.asFloatBuffer().get(result)
            return result
        }
    }
}
