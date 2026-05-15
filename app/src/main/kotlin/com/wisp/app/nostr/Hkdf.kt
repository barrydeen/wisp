package com.wisp.app.nostr

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (RFC 5869) and HMAC-SHA256 primitives shared by NIP-44 encryption
 * and deterministic key derivation (Spark wallet entropy from nsec).
 */
object Hkdf {
    private val hmacLocal = ThreadLocal.withInitial { Mac.getInstance("HmacSHA256") }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = hmacLocal.get()!!
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * 32)
        val n = (length + 31) / 32
        var t = ByteArray(0)
        val okm = ByteArray(length)
        var offset = 0
        for (i in 1..n) {
            val input = t + info + byteArrayOf(i.toByte())
            t = hmacSha256(prk, input)
            val copyLen = minOf(32, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
        }
        return okm
    }
}
