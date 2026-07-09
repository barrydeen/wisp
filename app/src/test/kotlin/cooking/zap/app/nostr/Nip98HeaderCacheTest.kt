package cooking.zap.app.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Nip98HeaderCache] behavior per Phase 0 decision 1
 * (CHEFFY_NOTE_REVIEW_PLAN.md): 30s TTL keyed on
 * (method, normalized URL, body hash); a 401 against a CACHED header is
 * invalidated, re-signed once, and retried once; a 401 against a fresh
 * header is returned as-is. Runs on the plain JVM with an injected clock.
 */
class Nip98HeaderCacheTest {

    private var now = 0L
    private val signer = FakeNip98Signer()

    private fun cache(ttlMillis: Long = Nip98HeaderCache.DEFAULT_TTL_MILLIS) =
        Nip98HeaderCache(ttlMillis = ttlMillis, clock = { now })

    private val url = "https://zap.cooking/api/zappy/note-review/credit-status?id=abc"

    // --- TTL / keying ---

    @Test
    fun withinTtl_reusesHeaderWithoutResigning() = runBlocking {
        val cache = cache()
        val first = cache.authHeader(signer, "GET", url)
        now += 29_999
        val second = cache.authHeader(signer, "GET", url)

        assertEquals(1, signer.signCount)
        assertEquals(first.header, second.header)
        assertFalse(first.fromCache)
        assertTrue(second.fromCache)
    }

    @Test
    fun afterTtl_resigns() = runBlocking {
        val cache = cache()
        val first = cache.authHeader(signer, "GET", url)
        now += 30_000
        val second = cache.authHeader(signer, "GET", url)

        assertEquals(2, signer.signCount)
        assertNotEquals(first.header, second.header)
        assertFalse(second.fromCache)
    }

    @Test
    fun differentQueryString_sharesTheKey() = runBlocking {
        // The u tag excludes the query (Nip98.normalizeUrl), so a header
        // signed for ?id=a is verifier-valid for ?id=b — the cache must
        // key the same way or the poll re-signs for no reason.
        val cache = cache()
        val a = cache.authHeader(signer, "GET", "https://zap.cooking/api/x?id=a")
        val b = cache.authHeader(signer, "GET", "https://zap.cooking/api/x?id=b")

        assertEquals(1, signer.signCount)
        assertEquals(a.header, b.header)
        assertTrue(b.fromCache)
    }

    @Test
    fun differentBody_getsItsOwnKey() = runBlocking {
        // A cached header binds its payload hash; different body bytes
        // must never reuse it.
        val cache = cache()
        cache.authHeader(signer, "POST", "https://zap.cooking/api/x", bodyString = """{"a":1}""")
        val other = cache.authHeader(signer, "POST", "https://zap.cooking/api/x", bodyString = """{"a":2}""")

        assertEquals(2, signer.signCount)
        assertFalse(other.fromCache)
    }

    @Test
    fun differentMethod_getsItsOwnKey() = runBlocking {
        val cache = cache()
        cache.authHeader(signer, "GET", "https://zap.cooking/api/x")
        val post = cache.authHeader(signer, "POST", "https://zap.cooking/api/x")

        assertEquals(2, signer.signCount)
        assertFalse(post.fromCache)
    }

    @Test
    fun invalidate_forcesResign() = runBlocking {
        val cache = cache()
        cache.authHeader(signer, "GET", url)
        cache.invalidate("GET", url)
        val second = cache.authHeader(signer, "GET", url)

        assertEquals(2, signer.signCount)
        assertFalse(second.fromCache)
    }

    // --- 401 retry rule ---

    @Test
    fun unauthorizedOnCachedHeader_resignsAndRetriesOnce() = runBlocking {
        val cache = cache()
        val warm = cache.authHeader(signer, "GET", url) // warm the cache
        val sentHeaders = mutableListOf<String>()

        val result = cache.withAuthHeader(
            signer, "GET", url,
            isUnauthorized = { it == 401 },
        ) { header ->
            sentHeaders += header
            if (sentHeaders.size == 1) 401 else 200
        }

        assertEquals(200, result)
        assertEquals(2, sentHeaders.size)
        assertEquals(warm.header, sentHeaders[0])       // first attempt used the cache
        assertNotEquals(sentHeaders[0], sentHeaders[1]) // retry used a fresh sign
        assertEquals(2, signer.signCount)               // warm + re-sign, nothing more
    }

    @Test
    fun unauthorizedOnFreshHeader_isNotRetried() = runBlocking {
        val cache = cache()
        var sends = 0

        val result = cache.withAuthHeader(
            signer, "GET", url,
            isUnauthorized = { it == 401 },
        ) { _ ->
            sends++
            401
        }

        assertEquals(401, result)
        assertEquals(1, sends)
        assertEquals(1, signer.signCount)
    }

    @Test
    fun persistentUnauthorizedOnCachedHeader_retriesExactlyOnce() = runBlocking {
        val cache = cache()
        cache.authHeader(signer, "GET", url) // warm
        var sends = 0

        val result = cache.withAuthHeader(
            signer, "GET", url,
            isUnauthorized = { it == 401 },
        ) { _ ->
            sends++
            401
        }

        assertEquals(401, result)
        assertEquals(2, sends)
        assertEquals(2, signer.signCount)
    }

    @Test
    fun successOnCachedHeader_doesNotResign() = runBlocking {
        val cache = cache()
        cache.authHeader(signer, "GET", url) // warm
        var sends = 0

        val result = cache.withAuthHeader(
            signer, "GET", url,
            isUnauthorized = { it == 401 },
        ) { _ ->
            sends++
            200
        }

        assertEquals(200, result)
        assertEquals(1, sends)
        assertEquals(1, signer.signCount)
    }
}
