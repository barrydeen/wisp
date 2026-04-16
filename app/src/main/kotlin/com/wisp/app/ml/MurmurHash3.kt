package com.wisp.app.ml

object MurmurHash3 {
    private const val C1 = 0xcc9e2d51.toInt()
    private const val C2 = 0x1b873593.toInt()
    private const val FMIX1 = 0x85ebca6b.toInt()
    private const val FMIX2 = 0xc2b2ae35.toInt()

    fun hash32(data: ByteArray, seed: Int = 0): Int {
        var h1 = seed
        val len = data.size
        val nblocks = len / 4

        for (i in 0 until nblocks) {
            val off = i * 4
            var k1 = (data[off].toInt() and 0xff) or
                ((data[off + 1].toInt() and 0xff) shl 8) or
                ((data[off + 2].toInt() and 0xff) shl 16) or
                ((data[off + 3].toInt() and 0xff) shl 24)
            k1 *= C1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= C2
            h1 = h1 xor k1
            h1 = Integer.rotateLeft(h1, 13)
            h1 = h1 * 5 + 0xe6546b64.toInt()
        }

        val tail = nblocks * 4
        var k1 = 0
        @Suppress("KotlinConstantConditions")
        when (len and 3) {
            3 -> {
                k1 = k1 xor ((data[tail + 2].toInt() and 0xff) shl 16)
                k1 = k1 xor ((data[tail + 1].toInt() and 0xff) shl 8)
                k1 = k1 xor (data[tail].toInt() and 0xff)
                k1 *= C1; k1 = Integer.rotateLeft(k1, 15); k1 *= C2; h1 = h1 xor k1
            }
            2 -> {
                k1 = k1 xor ((data[tail + 1].toInt() and 0xff) shl 8)
                k1 = k1 xor (data[tail].toInt() and 0xff)
                k1 *= C1; k1 = Integer.rotateLeft(k1, 15); k1 *= C2; h1 = h1 xor k1
            }
            1 -> {
                k1 = k1 xor (data[tail].toInt() and 0xff)
                k1 *= C1; k1 = Integer.rotateLeft(k1, 15); k1 *= C2; h1 = h1 xor k1
            }
        }

        h1 = h1 xor len
        h1 = h1 xor (h1 ushr 16)
        h1 *= FMIX1
        h1 = h1 xor (h1 ushr 13)
        h1 *= FMIX2
        h1 = h1 xor (h1 ushr 16)
        return h1
    }
}
