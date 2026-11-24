package io.github.depthbomb.ktor.ratelimiter

import java.time.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.runBlocking

class RateTest {
    @Test fun providerContract() = runBlocking {
        var nanos = 0L
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.EPOCH.plusNanos(nanos)
        }
        val storage = MemoryRateStorage({ nanos }, clock)
        verifyRateStorage(storage) { nanos += it.inWholeNanoseconds }
        nanos += 10.minutes.inWholeNanoseconds
        storage.prune()
        assertEquals(5, storage.inspect(BucketKey("contract", "alice"), tokenBucket(5, 3.minutes), 1).balance.remaining)
    }

    @Test fun failedClockReadDoesNotSpendQuota() = runBlocking {
        var available = false
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant {
                check(available) { "Clock unavailable" }
                return Instant.EPOCH
            }
        }
        val storage = MemoryRateStorage({ 0L }, clock)
        val rate = paced(1.minutes)
        val key = BucketKey("clock", "user")
        assertFailsWith<IllegalStateException> { storage.tryAcquire(key, rate, 1) }
        available = true
        assertIs<Granted>(storage.tryAcquire(key, rate, 1))
        Unit
    }

    @Test fun incompatibleRateCannotReinterpretOutstandingDebt() = runBlocking {
        var now = 0L
        val storage = MemoryRateStorage({ now }, Clock.systemUTC())
        val key = BucketKey("shared", "user")
        val old = tokenBucket(5, 3.minutes)
        val replacement = paced(1.minutes)
        storage.tryAcquire(key, old, 5)
        assertFailsWith<IllegalArgumentException> { storage.inspect(key, replacement, 1) }
        assertFailsWith<IllegalArgumentException> { storage.tryAcquire(key, replacement, 1) }
        val equivalent = tokenBucket(10, 6.minutes, burst = 5)
        assertEquals(0, storage.inspect(key, equivalent, 1).balance.remaining)
        assertEquals(old.period / 5, assertIs<Limited>(storage.tryAcquire(key, equivalent, 1)).retryAfter)
        now += 3.minutes.inWholeNanoseconds
        assertIs<Granted>(storage.tryAcquire(key, replacement, 1))
        Unit
    }

    @Test fun repeatedDebitsPreserveFractionalCreditAcrossPruning() = runBlocking {
        var now = 0L
        val storage = MemoryRateStorage({ now }, Clock.systemUTC())
        val key = BucketKey("fractional", "user")
        val rate = tokenBucket(3, 10.nanoseconds, burst = 2)
        assertIs<Granted>(storage.tryAcquire(key, rate, 2))
        now = 3
        assertEquals(1.nanoseconds, assertIs<Limited>(storage.tryAcquire(key, rate, 1)).retryAfter)
        now = 4
        assertIs<Granted>(storage.tryAcquire(key, rate, 1))
        now = 6
        storage.prune()
        assertEquals(1.nanoseconds, assertIs<Limited>(storage.inspect(key, rate, 1)).retryAfter)
        now = 7
        assertIs<Granted>(storage.tryAcquire(key, rate, 1))
        now = 14
        storage.prune()
        assertEquals(2, storage.inspect(key, rate, 1).balance.remaining)
    }

    @Test fun largeCapacityWithReducibleRefillFraction() = runBlocking {
        val rate = tokenBucket(1_000_000_000, 1.minutes)
        val key = BucketKey("large", "user")
        val storage = MemoryRateStorage({ 0L }, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        val empty = storage.tryAcquire(key, rate, rate.capacity)
        assertEquals(Instant.EPOCH.plusSeconds(60), empty.balance.resetAt)
        assertEquals(60.nanoseconds, assertIs<Limited>(storage.inspect(key, rate, 1)).retryAfter)
    }

    @Test fun definitions() {
        val rate = parseRate(" 5 PER 3 minutes, burst up to 2 ")
        assertEquals(2, rate.capacity)
        assertEquals(5, rate.tokens)
        assertEquals(3.minutes, rate.period)
        assertEquals(1, parseRate("1 per second").capacity)
        assertEquals(1, parseRate("1 every 36 seconds").capacity)
        for (bad in listOf("5 every 3 minutes", "0 per second", "1 per 0 days", "1 per second, burst up to 0",
            "1 every minute, burst up to 2", "1 per 9223372036854775807 days", "1000000000000 per second")) {
            assertFailsWith<IllegalArgumentException>(bad) { parseRate(bad) }
        }
    }
}
