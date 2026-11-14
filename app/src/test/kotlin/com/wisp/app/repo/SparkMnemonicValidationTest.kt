package com.wisp.app.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val phrase = canonical.replace(' ', nbsp)
        assertNull(SparkRepository.validateMnemonic(phrase))
    }

    @Test
    fun `figure and narrow NBSP separators are normalized`() {
        val figure = '\u2007'
        val narrow = '\u202F'
        val phrase = canonical.replace(' ', figure).replace("abandon$figure", "abandon$narrow", true)
        assertNull(SparkRepository.validateMnemonic(phrase))
    }

    @Test
    fun `multiple spaces and tabs are normalized`() {
        val phrase = "abandon  abandon\t abandon   abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertNull(SparkRepository.validateMnemonic(phrase))
    }

    @Test
    fun `corrupted final word fails checksum`() {
        val corrupted = canonical.replace("about", "aboard")
        assertNotNull(SparkRepository.validateMnemonic(corrupted))
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
