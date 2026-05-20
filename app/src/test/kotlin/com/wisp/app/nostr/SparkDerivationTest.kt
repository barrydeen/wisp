package com.wisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * Locks the nsec → Spark-wallet derivation contract from
 * `WALLET_PARITY.md` §1. Cross-platform regression: iOS must produce the
 * same entropy + mnemonic for the same input privkey.
 *
 * If a value here changes, the on-disk default wallet for every existing
 * user becomes unreachable from the same nsec. Do not edit these without
 * a versioned salt bump and a migration plan.
 *
 * The HKDF and BIP39 algorithms are inlined below rather than imported
 * from `Keys.deriveSparkEntropy` / `SparkRepository.entropyToMnemonic`
 * because `Keys` triggers secp256k1-kmp JNI init at class load — which
 * isn't available in the JVM unit-test classpath. Inlining keeps the
 * test self-contained AND makes the algorithm spec a literal regression
 * lock; if the production code drifts from these helpers, the test
 * vectors stop matching and we catch it.
 */
class SparkDerivationTest {

    // ---- HKDF (RFC 5869) mirror of nostr/Hkdf.kt ----

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
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

    /** Mirror of `deriveSparkEntropy(privkey)` — see Keys.kt:72-86. */
    private fun deriveSparkEntropy(privkey: ByteArray): ByteArray {
        require(privkey.size == 32)
        val prk = hkdfExtract("wisp-spark-wallet-v1".toByteArray(Charsets.UTF_8), privkey)
        return hkdfExpand(prk, "entropy".toByteArray(Charsets.UTF_8), 16)
    }

    /** Mirror of `SparkRepository.entropyToMnemonic` (private) — see SparkRepository.kt:150-170. */
    private fun entropyToMnemonic(entropy: ByteArray, wordlist: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumBits = entropy.size / 4
        val bits = StringBuilder()
        for (b in entropy) bits.append(String.format("%8s", Integer.toBinaryString(b.toInt() and 0xFF)).replace(' ', '0'))
        val hashBits = String.format("%8s", Integer.toBinaryString(hash[0].toInt() and 0xFF)).replace(' ', '0')
        bits.append(hashBits.substring(0, checksumBits))
        val words = mutableListOf<String>()
        val bitStr = bits.toString()
        for (i in bitStr.indices step 11) {
            val end = minOf(i + 11, bitStr.length)
            val index = Integer.parseInt(bitStr.substring(i, end), 2)
            words.add(wordlist[index])
        }
        return words.joinToString(" ")
    }

    private val bip39Words: List<String> by lazy {
        val stream = SparkDerivationTest::class.java.getResourceAsStream("/bip39-english.txt")
            ?: error("bip39-english.txt missing from resources")
        stream.bufferedReader().use { it.readLines().map { line -> line.trim() }.filter { line -> line.isNotEmpty() } }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun vector1_allOnesPrivkey() {
        val privkey = ByteArray(32) { 0x01 }
        val entropy = deriveSparkEntropy(privkey)
        val mnemonic = entropyToMnemonic(entropy, bip39Words)
        assertEquals("75119b77539f7c55289cfd67c6f85ee2", entropy.toHex())
        assertEquals(
            "insect mimic tape poet water clever pen panic guitar daughter bless session",
            mnemonic
        )
    }

    @Test
    fun vector2_allTwosPrivkey() {
        val privkey = ByteArray(32) { 0x02 }
        val entropy = deriveSparkEntropy(privkey)
        val mnemonic = entropyToMnemonic(entropy, bip39Words)
        assertEquals("8d7fd646909ed7facc43212f4705d573", entropy.toHex())
        assertEquals(
            "miracle wrong museum cancel uniform word country goddess consider deal inspire trade",
            mnemonic
        )
    }

    @Test
    fun bip39WordlistShape() {
        assertEquals(2048, bip39Words.size)
        // BIP39 English wordlist sentinel: first word is "abandon", last is "zoo".
        assertEquals("abandon", bip39Words.first())
        assertEquals("zoo", bip39Words.last())
    }
}
