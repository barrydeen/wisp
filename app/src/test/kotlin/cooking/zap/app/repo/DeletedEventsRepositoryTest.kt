package cooking.zap.app.repo

import cooking.zap.app.repo.DeletedEventsRepository.Companion.decodeAddressTimes
import cooking.zap.app.repo.DeletedEventsRepository.Companion.encodeAddressTimes
import cooking.zap.app.repo.DeletedEventsRepository.Companion.migrateLegacyAddresses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function gate for the coord→deletion-time persistence format used by
 * [DeletedEventsRepository] (the live store needs an Android Context for
 * SharedPreferences and is exercised on-device). The tricky part is that a
 * coordinate is itself colon-delimited ("kind:pubkey:dTag"), yet the encoded
 * entry prefixes a colon-joined timestamp — so decode must split on the *first*
 * colon only, and survive malformed input from older/corrupt prefs.
 */
class DeletedEventsRepositoryTest {

    private val coord = DeletedEventsRepository.addressCoord(30001, "pubkeyhex", "my-collection")

    // ---- round-trip ------------------------------------------------------

    @Test
    fun `encode then decode preserves entries`() {
        val map = mapOf(
            coord to 1_700_000_000L,
            DeletedEventsRepository.addressCoord(30023, "abc", "soup") to 42L,
        )
        assertEquals(map, decodeAddressTimes(encodeAddressTimes(map)))
    }

    @Test
    fun `coord with colons round-trips (split on first colon only)`() {
        // A coord has two colons; a d-tag could even add more. The timestamp is
        // digits-only, so the first colon is always the real separator.
        val weird = DeletedEventsRepository.addressCoord(30001, "pub", "a:b:c")
        val decoded = decodeAddressTimes(encodeAddressTimes(mapOf(weird to 99L)))
        assertEquals(99L, decoded[weird])
        assertEquals(1, decoded.size)
    }

    @Test
    fun `permanent tombstone (MAX) survives round-trip`() {
        val decoded = decodeAddressTimes(encodeAddressTimes(mapOf(coord to Long.MAX_VALUE)))
        assertEquals(Long.MAX_VALUE, decoded[coord])
    }

    @Test
    fun `empty round-trips to empty`() {
        assertTrue(encodeAddressTimes(emptyMap()).isEmpty())
        assertTrue(decodeAddressTimes(emptySet()).isEmpty())
    }

    // ---- malformed input is skipped, not crashed -------------------------

    @Test
    fun `decode skips malformed entries`() {
        val entries = setOf(
            "1700:$coord",      // valid
            "",                  // empty
            "nocolon",           // no separator
            ":$coord",           // empty timestamp (sep at 0)
            "notanumber:$coord", // non-numeric timestamp
        )
        val decoded = decodeAddressTimes(entries)
        assertEquals(1, decoded.size)
        assertEquals(1700L, decoded[coord])
    }

    // ---- legacy migration ------------------------------------------------

    @Test
    fun `legacy bare coords migrate to permanent tombstones`() {
        val legacy = setOf(coord, DeletedEventsRepository.addressCoord(30023, "x", "y"))
        val migrated = migrateLegacyAddresses(legacy)
        assertEquals(legacy.size, migrated.size)
        assertTrue(migrated.values.all { it == Long.MAX_VALUE })
        assertTrue(migrated.keys.containsAll(legacy))
    }

    @Test
    fun `legacy null migrates to empty`() {
        assertTrue(migrateLegacyAddresses(null).isEmpty())
    }
}
