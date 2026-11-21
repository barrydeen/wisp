package com.wisp.app.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * BIP-39 mnemonic validation on the JVM classpath (companion-level — no
 * instance/Context dependencies). The canonical all-abandon vector is the
 * standard BIP-39 test vector for 128 bits of zero entropy.
 */
class SparkMnemonicValidationTest {
    private val canonical =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun `bundled wordlist is complete`() {
        val lines = SparkRepository::class.java
            .getResourceAsStream("/bip39-english.txt")
            ?.bufferedReader()?.readLines()?.filter { it.isNotBlank() }
        assertNotNull(lines)
        assertEquals(2048, lines!!.size)
    }

    @Test
    fun `canonical vector validates`() {
        assertNull(SparkRepository.validateMnemonic(canonical))
    }

    @Test
    fun `NBSP separators are normalized`() {
        val nbsp = '\u00A0'
        assertNull(SparkRepository.validateMnemonic(canonical.replace(' ', nbsp)))
    }

    @Test
    fun `leading and trailing NBSP are trimmed before splitting`() {
        assertNull(SparkRepository.validateMnemonic("\u00A0$canonical\u202F"))
    }

    @Test
    fun `figure and narrow NBSP separators are normalized`() {
        assertNull(SparkRepository.validateMnemonic(canonical.replace(' ', '\u2007')))
        assertNull(SparkRepository.validateMnemonic(canonical.replace(' ', '\u202F')))
    }

    @Test
    fun `multiple spaces and tabs are normalized`() {
        val phrase = "abandon  abandon\t abandon   abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertNull(SparkRepository.validateMnemonic(phrase))
    }

    @Test
    fun `normalizeMnemonic produces canonical single-ASCII-space lowercase phrase`() {
        val messy = "\u00A0ABANDON\u2007\tabandon  abandon\u202Fabandon abandon abandon abandon abandon abandon abandon abandon ABOUT\u00A0"
        assertEquals(canonical, SparkRepository.normalizeMnemonic(messy))
    }

    @Test
    fun `uppercase ASCII I lowercases cleanly under Turkish locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            // Valid 12-word BIP-39 vector containing words with 'I'
            val phrase = "ICE ICON IDEA IGNORE IMAGE IMPACT IMPOSE IMPROVE IMPULSE INCH INCLUDE INDOOR"
            // Verify lowercase normalization does not produce dotless 'ı'
            val normalized = SparkRepository.normalizeMnemonic(phrase)
            assertTrue(normalized.startsWith("ice icon idea ignore image"))
            // Checksum may or may not match; specifically verify no 'Invalid word' rejection
            val error = SparkRepository.validateMnemonic(phrase)
            assertTrue(error == null || error.contains("checksum", ignoreCase = true))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `empty wordlist fails closed`() {
        val error = SparkRepository.validateMnemonic(canonical, emptyMap())
        assertNotNull(error)
        assertTrue(error!!.contains("unavailable", ignoreCase = true))
    }

    @Test
    fun `truncated wordlist fails closed`() {
        val truncated = (0 until 2047).associate { "word$it" to it }
        val error = SparkRepository.validateMnemonic(canonical, truncated)
        assertNotNull(error)
        assertTrue(error!!.contains("unavailable", ignoreCase = true))
    }

    @Test
    fun `corrupted final word fails checksum`() {
        assertNotNull(SparkRepository.validateMnemonic(canonical.replace("about", "aboard")))
    }

    @Test
    fun `unknown word is rejected`() {
        assertNotNull(SparkRepository.validateMnemonic(canonical.replace("abandon", "notaword")))
    }

    @Test
    fun `wrong word count is rejected`() {
        assertNotNull(SparkRepository.validateMnemonic(canonical.dropLast(7)))
    }
}
