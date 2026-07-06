package cooking.zap.app.repo

import cooking.zap.app.repo.LastDraftCache.Companion.logoutCacheAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral tests for [LastDraftCache] over an in-memory [LastDraftCache.Store] fake — the live
 * SharedPreferences path needs Android and is exercised on-device (no Robolectric in the hermetic
 * suite). These pin the per-account isolation that PR 4 depends on: clearing one account must never
 * touch another's cache, and only a full logout wipes everything.
 */
class LastDraftCacheStoreTest {

    private class FakeStore : LastDraftCache.Store {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun putAll(values: Map<String, String>) { map.putAll(values) }
        override fun remove(keys: List<String>) { keys.forEach { map.remove(it) } }
        override fun clearAll() { map.clear() }
    }

    private val a = "pubkeyA"
    private val b = "pubkeyB"

    @Test
    fun `clear(A) leaves B intact`() {
        val cache = LastDraftCache(FakeStore())
        cache.save(a, "content-A", "id-A")
        cache.save(b, "content-B", "id-B")

        cache.clear(a)

        assertNull("A content cleared", cache.getContent(a))
        assertNull("A id cleared", cache.getId(a))
        assertEquals("B content survives", "content-B", cache.getContent(b))
        assertEquals("B id survives", "id-B", cache.getId(b))
    }

    @Test
    fun `clearAll wipes every account`() {
        val cache = LastDraftCache(FakeStore())
        cache.save(a, "content-A", "id-A")
        cache.save(b, "content-B", "id-B")

        cache.clearAll()

        assertNull(cache.getContent(a))
        assertNull(cache.getId(a))
        assertNull(cache.getContent(b))
        assertNull(cache.getId(b))
    }

    @Test
    fun `clearIfId only clears the matching account and only on id match`() {
        val cache = LastDraftCache(FakeStore())
        cache.save(a, "content-A", "id-A")
        cache.save(b, "content-B", "id-B")

        assertFalse("no-op on id mismatch", cache.clearIfId(a, "id-OTHER"))
        assertEquals("content-A", cache.getContent(a))

        assertTrue("clears on id match", cache.clearIfId(a, "id-A"))
        assertNull(cache.getContent(a))
        assertEquals("B untouched throughout", "content-B", cache.getContent(b))
    }

    @Test
    fun `logout action switches on remaining accounts`() {
        // Account switch (an account remains) → clear only the removed account.
        assertEquals(LastDraftCache.LogoutCacheAction.CLEAR_REMOVED_ACCOUNT, logoutCacheAction(hasRemainingAccounts = true))
        // Full logout (nothing remains) → wipe all.
        assertEquals(LastDraftCache.LogoutCacheAction.CLEAR_ALL, logoutCacheAction(hasRemainingAccounts = false))
    }
}
