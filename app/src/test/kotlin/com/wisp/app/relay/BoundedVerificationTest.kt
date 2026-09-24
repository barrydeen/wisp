package com.wisp.app.relay

import com.wisp.app.nostr.NostrEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class BoundedVerificationTest {
    @Test
    fun cacheIncludesPayloadAndSignatureNotJustClaimedId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val calls = AtomicInteger()
            val verifier = BoundedVerification<NostrEvent>(scope, workerCount = 1) {
                calls.incrementAndGet()
                it.content == "valid" && it.sig == "valid"
            }
            val valid = NostrEvent("claimed-id", "pubkey", 1, 1, emptyList(), "valid", "valid")
            assertTrue(verifier.verify(valid))
            assertFalse(verifier.verify(valid.copy(content = "tampered")))
            assertFalse(verifier.verify(valid.copy(sig = "tampered")))
            assertTrue(verifier.verify(valid))
            assertFalse(verifier.verify(valid.copy(sig = "tampered")))
            assertEquals(3, calls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun boundedQueueNeverSkipsVerificationAndDuplicatesShareWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CountDownLatch(1)
        try {
            val calls = AtomicInteger()
            val verifier = BoundedVerification<String>(scope, workerCount = 1, queueCapacity = 1) {
                calls.incrementAndGet()
                check(release.await(5, TimeUnit.SECONDS))
                it != "invalid"
            }
            val first = async { verifier.verify("same") }
            withTimeout(5_000) { while (calls.get() == 0) delay(1) }
            val duplicates = List(20) { async { verifier.verify("same") } }
            val invalid = async { verifier.verify("invalid") }
            delay(20)
            assertEquals(1, calls.get())
            assertFalse(first.isCompleted)
            assertFalse(invalid.isCompleted)
            release.countDown()
            withTimeout(5_000) {
                assertTrue(first.await())
                assertTrue(duplicates.awaitAll().all { it })
                assertFalse(invalid.await())
            }
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun cacheEvictionAndVerifierExceptionsFailClosed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val calls = AtomicInteger()
            val verifier = BoundedVerification<String>(scope, workerCount = 1, cacheSize = 1) {
                calls.incrementAndGet()
                check(it != "broken")
                true
            }
            assertTrue(verifier.verify("a"))
            assertFalse(verifier.verify("broken"))
            assertFalse(verifier.verify("broken"))
            assertTrue(verifier.verify("a"))
            assertEquals(3, calls.get())
        } finally {
            scope.cancel()
        }
    }
}
