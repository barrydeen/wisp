package com.wisp.app.nostr

sealed class NostrUriData {
    data class NoteRef(val eventId: String, val relays: List<String> = emptyList(), val author: String? = null) : NostrUriData()
    data class ProfileRef(val pubkey: String, val relays: List<String> = emptyList()) : NostrUriData()
}

object Nip19 {
    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV = IntArray(128) { -1 }.also {
        BECH32_CHARSET.forEachIndexed { i, c -> it[c.code] = i }
    }

    fun npubEncode(pubkey: ByteArray): String = bech32Encode("npub", pubkey)
    fun nsecEncode(privkey: ByteArray): String = bech32Encode("nsec", privkey)
    fun noteEncode(eventId: ByteArray): String = bech32Encode("note", eventId)

    fun nprofileEncode(pubkeyHex: String, relays: List<String> = emptyList()): String {
        val tlv = buildTlv {
            addTlv(0x00, pubkeyHex.hexToByteArray())
            for (relay in relays) addTlv(0x01, relay.toByteArray(Charsets.UTF_8))
        }
        return bech32Encode("nprofile", tlv)
    }

    fun neventEncode(eventId: ByteArray, relays: List<String> = emptyList(), author: ByteArray? = null): String {
        val tlv = buildTlv {
            addTlv(0x00, eventId)
            for (relay in relays) addTlv(0x01, relay.toByteArray(Charsets.UTF_8))
            if (author != null) addTlv(0x02, author)
        }
        return bech32Encode("nevent", tlv)
    }

    private class TlvBuilder {
        private val bytes = mutableListOf<Byte>()
        fun addTlv(type: Int, value: ByteArray) {
            bytes.add(type.toByte())
            bytes.add(value.size.toByte())
            for (b in value) bytes.add(b)
        }
        fun build(): ByteArray = bytes.toByteArray()
    }

    private inline fun buildTlv(block: TlvBuilder.() -> Unit): ByteArray =
        TlvBuilder().apply(block).build()

    fun nsecDecode(bech32: String): ByteArray {
        val (hrp, data) = bech32Decode(bech32)
        require(hrp == "nsec") { "Expected nsec, got $hrp" }
        require(data.size == 32) { "Invalid nsec length" }
        return data
    }

    fun npubDecode(bech32: String): ByteArray {
        val (hrp, data) = bech32Decode(bech32)
        require(hrp == "npub") { "Expected npub, got $hrp" }
        require(data.size == 32) { "Invalid npub length" }
        return data
    }

    fun noteDecode(bech32: String): String {
        val (hrp, data) = bech32Decode(bech32)
        require(hrp == "note") { "Expected note, got $hrp" }
        require(data.size == 32) { "Invalid note length" }
        return data.toHex()
    }

    fun neventDecode(bech32: String): NostrUriData.NoteRef {
        val (hrp, data) = bech32Decode(bech32)
        require(hrp == "nevent") { "Expected nevent, got $hrp" }
        return parseTlvNote(data)
    }

    fun nprofileDecode(bech32: String): NostrUriData.ProfileRef {
        val (hrp, data) = bech32Decode(bech32)
        require(hrp == "nprofile") { "Expected nprofile, got $hrp" }
        return parseTlvProfile(data)
    }

    private fun parseTlvNote(data: ByteArray): NostrUriData.NoteRef {
        var eventId: String? = null
        val relays = mutableListOf<String>()
        var author: String? = null
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xFF
            val length = data[i + 1].toInt() and 0xFF
            i += 2
            if (i + length > data.size) break
            val value = data.copyOfRange(i, i + length)
            when (type) {
                0x00 -> if (value.size == 32) eventId = value.toHex()
                0x01 -> relays.add(value.toString(Charsets.UTF_8))
                0x02 -> if (value.size == 32) author = value.toHex()
            }
            i += length
        }
        requireNotNull(eventId) { "nevent missing event ID" }
        return NostrUriData.NoteRef(eventId, relays, author)
    }

    private fun parseTlvProfile(data: ByteArray): NostrUriData.ProfileRef {
        var pubkey: String? = null
        val relays = mutableListOf<String>()
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xFF
            val length = data[i + 1].toInt() and 0xFF
            i += 2
            if (i + length > data.size) break
            val value = data.copyOfRange(i, i + length)
            when (type) {
                0x00 -> if (value.size == 32) pubkey = value.toHex()
                0x01 -> relays.add(value.toString(Charsets.UTF_8))
            }
            i += length
        }
        requireNotNull(pubkey) { "nprofile missing pubkey" }
        return NostrUriData.ProfileRef(pubkey, relays)
    }

    fun decodeNostrUri(uri: String): NostrUriData? {
        val bech32 = uri.removePrefix("nostr:")
        return try {
            when {
                bech32.startsWith("note1") -> NostrUriData.NoteRef(noteDecode(bech32))
                bech32.startsWith("nevent1") -> neventDecode(bech32)
                bech32.startsWith("npub1") -> NostrUriData.ProfileRef(npubDecode(bech32).toHex())
                bech32.startsWith("nprofile1") -> nprofileDecode(bech32)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun bech32Encode(hrp: String, data: ByteArray): String {
        val values = convertBits(data, 8, 5, true)
        val checksum = bech32Checksum(hrp, values)
        return buildString(hrp.length + 1 + values.size + 6) {
            append(hrp)
            append('1')
            for (v in values) append(BECH32_CHARSET[v])
            for (v in checksum) append(BECH32_CHARSET[v])
        }
    }

    private fun bech32Decode(str: String): Pair<String, ByteArray> {
        val lower = str.lowercase()
        val pos = lower.lastIndexOf('1')
        require(pos >= 1) { "Invalid bech32 string" }
        val hrp = lower.substring(0, pos)
        val dataStr = lower.substring(pos + 1)
        require(dataStr.length >= 6) { "Bech32 data too short" }
        val values = IntArray(dataStr.length) { i ->
            val v = CHARSET_REV[dataStr[i].code]
            require(v != -1) { "Invalid bech32 character" }
            v
        }
        require(verifyChecksum(hrp, values)) { "Invalid bech32 checksum" }
        val data = values.copyOfRange(0, values.size - 6)
        return hrp to convertBits(data, 5, 8, false)
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        var acc = 0; var bits = 0
        val ret = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1
        for (b in data) {
            acc = (acc shl fromBits) or (b.toInt() and 0xFF)
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add((acc shr bits) and maxv)
            }
        }
        if (pad && bits > 0) ret.add((acc shl (toBits - bits)) and maxv)
        return ret.toIntArray()
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0; var bits = 0
        val ret = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        for (v in data) {
            acc = (acc shl fromBits) or v
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add(((acc shr bits) and maxv).toByte())
            }
        }
        if (pad && bits > 0) ret.add(((acc shl (toBits - bits)) and maxv).toByte())
        return ret.toByteArray()
    }

    private fun bech32Polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) if ((b shr i) and 1 == 1) chk = chk xor gen[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val ret = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            ret[i] = hrp[i].code shr 5
            ret[i + hrp.length + 1] = hrp[i].code and 31
        }
        return ret
    }

    private fun verifyChecksum(hrp: String, values: IntArray): Boolean {
        val exp = hrpExpand(hrp)
        val combined = IntArray(exp.size + values.size)
        exp.copyInto(combined)
        values.copyInto(combined, exp.size)
        return bech32Polymod(combined) == 1
    }

    private fun bech32Checksum(hrp: String, values: IntArray): IntArray {
        val exp = hrpExpand(hrp)
        val combined = IntArray(exp.size + values.size + 6)
        exp.copyInto(combined)
        values.copyInto(combined, exp.size)
        val polymod = bech32Polymod(combined) xor 1
        return IntArray(6) { i -> (polymod shr (5 * (5 - i))) and 31 }
    }
}
