package cooking.zap.app.mealplan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * Drift detection — local copies of the cross-platform fixtures must match the
 * sha256s recorded in frontend `docs/mealplan-contract.md`. Unilateral edits
 * on either side fail this test.
 */
class ContractFixtureChecksumTest {

    companion object {
        // From frontend docs/mealplan-contract.md (PR #550).
        val EXPECTED = mapOf(
            "ingredient-parser.vectors.json" to
                "cc7e0ecae3ee8fac51e3ce46502fc758a30c9022b6a3f3765d49c42914b3ab81",
            "week.vectors.json" to
                "de5c75e648548f0fe38ebe021a03da17a1f3a977ec7b103642bbf0fd066faf30",
            "mealplan-schema.vectors.json" to
                "d8a34927d30a8a636bbc84ed3a384ee5f975d65827ae3302aceffb0239373e18",
            "grocery-generation.vectors.json" to
                "090faff2b486886c0c14fe83e3a4fe48f2b3de8ddff375f18f1e5c1d125508d0",
            // From frontend docs/mealplan-contract.md (PR #649).
            "mealplan-eligibility.vectors.json" to
                "36e19caaa2ecc7df9005c1dc6f342a92a7cbe4b685faa3461c236fd3c39f531d",
        )
    }

    @Test
    fun fixturesMatchContractChecksums() {
        for ((name, expected) in EXPECTED) {
            val bytes = javaClass.getResource("/fixtures/$name")!!.readBytes()
            val actual = sha256Hex(bytes)
            assertEquals("checksum drift for $name", expected, actual)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
