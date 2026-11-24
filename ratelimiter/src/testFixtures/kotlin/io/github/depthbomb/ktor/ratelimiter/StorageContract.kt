package io.github.depthbomb.ktor.ratelimiter

import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/**
 * Exercises a [RateStorage] implementation with paced and burst policies, weighted requests,
 * independent keys, and concurrent attempts to spend the final token.
 *
 * @param storage Fresh backend dedicated to this test. The fixture writes buckets and leaves them in place.
 * @param advance Moves both the monotonic and wall clocks forward by the supplied duration.
 *   Keep both clocks frozen between calls, including during concurrent acquisitions.
 * @throws AssertionError if a decision, balance, or retry delay violates the tested contract.
 */
suspend fun verifyRateStorage(storage: RateStorage, advance: (Duration) -> Unit) {
    val key = BucketKey("contract", "alice")
    val rate = tokenBucket(5, 3.minutes)
    repeat(5) { assertIs<Granted>(storage.tryAcquire(key, rate, 1)) }
    val denied = assertIs<Limited>(storage.inspect(key, rate, 1))
    assertEquals(36.seconds, denied.retryAfter)
    val other = assertIs<Granted>(storage.inspect(key.copy(identifier = "bob"), rate, 1))
    assertEquals(other.balance.resetAt.plusSeconds(180), denied.balance.resetAt)
    assertIs<Granted>(storage.inspect(key.copy(policy = "other"), rate, 1))
    assertEquals(denied, storage.tryAcquire(key, rate, 1))

    advance(18.seconds)
    assertEquals(54.seconds, assertIs<Limited>(storage.tryAcquire(key, rate, 2)).retryAfter)
    advance(18.seconds)
    assertEquals(1, storage.inspect(key, rate, 1).balance.remaining)
    // Only one token has refilled. Exactly one of the competing requests should get it.
    coroutineScope {
        val results = List(32) { async(Dispatchers.Default) { storage.tryAcquire(key, rate, 1) } }.awaitAll()
        assertEquals(1, results.count { it is Granted })
    }

    val paced = paced(36.seconds)
    val pacedKey = key.copy(policy = "paced")
    assertIs<Granted>(storage.tryAcquire(pacedKey, paced, 1))
    assertEquals(36.seconds, assertIs<Limited>(storage.tryAcquire(pacedKey, paced, 1)).retryAfter)
    advance(10.minutes)
    assertEquals(1, storage.inspect(pacedKey, paced, 1).balance.remaining)
    assertIs<Granted>(storage.tryAcquire(key, rate, 5))
}
