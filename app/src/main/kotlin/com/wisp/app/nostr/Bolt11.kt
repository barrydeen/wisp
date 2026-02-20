package com.wisp.app.nostr

object Bolt11 {
    private val bech32Charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val hrpAmountRegex = Regex("""ln\w+?(\d+)([munp]?)$""")

    data class DecodedInvoice(
        val amountSats: Long?,
        val paymentHash: String?,
        val description: String?,
        val expiry: Long,
        val timestamp: Long
    ) {
        fun isExpired(): Boolean {
            val now = System.currentTimeMillis() / 1000
            return now > timestamp + expiry
        }
    }

    fun decode(invoice: String): DecodedInvoice? {
        val lower = invoice.lowercase().removePrefix("lightning:")
        val pos = lower.lastIndexOf('1')
        if (pos < 1) return null

        val hrp = lower.substring(0, pos)
        val dataStr = lower.substring(pos + 1)
        if (dataStr.length < 7 + 104) return null // timestamp(7) + signature(104) minimum

        // Decode bech32 data characters to 5-bit values
        val data5 = IntArray(dataStr.length)
        for (i in dataStr.indices) {
            val idx = bech32Charset.indexOf(dataStr[i])
            if (idx < 0) return null
            data5[i] = idx
        }

        // Remove 6-char bech32 checksum from end
        val dataLen = data5.size - 6
        if (dataLen < 7 + 104) return null

        // Parse amount from HRP
        val amountSats = parseHrpAmount(hrp)

        // Timestamp: first 7 x 5-bit values = 35-bit big-endian unix timestamp
        var timestamp = 0L
        for (i in 0 until 7) {
            timestamp = (timestamp shl 5) or data5[i].toLong()
        }

        // Parse tagged fields (between timestamp and signature)
        val sigStart = dataLen - 104
        var offset = 7
        var paymentHash: String? = null
        var description: String? = null
        var expiry = 3600L // default 1 hour

        while (offset < sigStart) {
            if (offset + 3 > sigStart) break
            val tag = data5[offset]
            val dataLength = (data5[offset + 1] shl 5) or data5[offset + 2]
            offset += 3

            if (offset + dataLength > sigStart) break

            when (tag) {
                1 -> { // payment hash: 52 x 5-bit = 260 bits = 32 bytes + 4 padding bits
                    if (dataLength == 52) {
                        paymentHash = convert5to8(data5, offset, dataLength)?.toHex()
                    }
                }
                13 -> { // description: variable length UTF-8
                    val bytes = convert5to8(data5, offset, dataLength)
                    if (bytes != null) {
                        description = String(bytes, Charsets.UTF_8)
                    }
                }
                6 -> { // expiry: variable length integer
                    var exp = 0L
                    for (i in 0 until dataLength) {
                        exp = (exp shl 5) or data5[offset + i].toLong()
                    }
                    expiry = exp
                }
            }
            offset += dataLength
        }

        return DecodedInvoice(
            amountSats = amountSats,
            paymentHash = paymentHash,
            description = description,
            expiry = expiry,
            timestamp = timestamp
        )
    }

    private fun parseHrpAmount(hrp: String): Long? {
        val match = hrpAmountRegex.find(hrp) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val multiplier = match.groupValues[2]
        return when (multiplier) {
            "m" -> amount * 100_000
            "u" -> amount * 100
            "n" -> amount / 10
            "p" -> amount / 10_000
            "" -> amount * 100_000_000
            else -> null
        }
    }

    /** Convert 5-bit values to 8-bit byte array (bech32 to bytes) */
    private fun convert5to8(data: IntArray, offset: Int, length: Int): ByteArray? {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Byte>()
        for (i in 0 until length) {
            acc = (acc shl 5) or data[offset + i]
            bits += 5
            while (bits >= 8) {
                bits -= 8
                result.add(((acc shr bits) and 0xFF).toByte())
            }
        }
        return result.toByteArray()
    }
}
