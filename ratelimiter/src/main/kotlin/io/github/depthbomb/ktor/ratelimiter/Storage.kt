package io.github.depthbomb.ktor.ratelimiter

import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Identifies one quota bucket in a [RateStorage] backend.
 *
 * @property policy Registered policy name. Different policies have independent quotas.
 * @property identifier Request identity within that policy, such as a user ID or IP address.
 */
public data class BucketKey(public val policy: String, public val identifier: String)

/**
 * A snapshot taken when a storage provider evaluates a bucket.
 *
 * @property remaining Whole tokens currently available to spend. Fractional refill credit stays in the bucket.
 * @property resetAt Wall-clock instant when the bucket would be full if there were no further charges.
 */
public data class Balance(public val remaining: Int, public val resetAt: Instant)

/**
 * Result of checking a request cost against one bucket. [Granted] indicates it fits.
 * [Limited] includes the delay until enough tokens are available.
 */
public sealed interface RateDecision {
    /** Balance at inspection time, or immediately after the decision for an acquisition. */
    public val balance: Balance
}

/**
 * The bucket can cover the requested cost.
 *
 * @property balance Current balance for an inspection, or balance after a successful charge.
 */
public data class Granted(override val balance: Balance) : RateDecision

/**
 * The bucket has insufficient tokens. The attempted charge spends zero tokens.
 *
 * @property balance Available balance when the provider evaluated the request.
 * @property retryAfter Time until this particular cost fits, assuming no further consumption.
 */
public data class Limited(override val balance: Balance, public val retryAfter: Duration) : RateDecision

/**
 * Backend for named rate policies. Keep a separate bucket for each [BucketKey], start it full,
 * and preserve fractional credit as it refills up to [Rate.capacity]. The backend owns the
 * time source for its decisions. A shared backend must coordinate concurrent acquisitions
 * across all application instances that use it.
 *
 * Report insufficient quota with [Limited] and propagate backend failures to the caller.
 */
public interface RateStorage {
    /**
     * Returns what would happen if [key] requested [cost] whole tokens under [rate].
     * Report the current balance and leave the tokens available to other callers.
     * [RatePolicy] supplies a cost from one through the bucket capacity.
     */
    public suspend fun inspect(key: BucketKey, rate: Rate, cost: Int): RateDecision

    /**
     * Checks and charges [cost] tokens under [rate] in a single atomic operation for [key].
     * Return [Granted] with the balance after charging, or [Limited] without debiting.
     * [RatePolicy] supplies a cost from one through the bucket capacity.
     */
    public suspend fun tryAcquire(key: BucketKey, rate: Rate, cost: Int): RateDecision
}

/**
 * Thread-safe, process-local bucket storage. Each instance has independent balances.
 * An active key can use an equivalent refill rate and capacity. Once fully refilled,
 * it can be reused with different settings.
 *
 * @param nanoTime Monotonic nanosecond source for elapsed-time calculations. Defaults to [System.nanoTime].
 * @param clock Wall clock used to report [Balance.resetAt]. Defaults to UTC.
 */
public class MemoryRateStorage(
    private val nanoTime: () -> Long = System::nanoTime,
    private val clock: Clock = Clock.systemUTC(),
) : RateStorage {
    private class Entry(var debt: Long, var updated: Long, var fullAfter: Long, val rate: Rate) {
        fun debtAt(now: Long): Long {
            val elapsed = (now - updated).coerceAtLeast(0)
            // Once the bucket is full, we can skip a multiplication that might overflow after a long idle period.
            return if (elapsed >= fullAfter) 0L else debt - elapsed * rate.refillSpeed
        }
    }

    // Each lock owns its map, so unrelated identifiers can progress during acquisition and pruning.
    private val buckets = Array(32) { HashMap<BucketKey, Entry>() }

    /**
     * Reads the current in-memory balance and computes whether [cost] tokens are available.
     *
     * @throws IllegalArgumentException if [cost] is outside the capacity or [rate] conflicts with an active bucket for [key].
     */
    override suspend fun inspect(key: BucketKey, rate: Rate, cost: Int): RateDecision =
        evaluate(key, rate, cost, debit = false)

    /**
     * Applies [cost] to [key] atomically and returns its decision under [rate].
     *
     * @throws IllegalArgumentException if [cost] is outside the capacity or [rate] conflicts with an active bucket for [key].
     */
    override suspend fun tryAcquire(key: BucketKey, rate: Rate, cost: Int): RateDecision =
        evaluate(key, rate, cost, debit = true)

    /**
     * Removes fully replenished entries. Their next request starts with the same full balance.
     * [RateLimiter] calls this periodically. Standalone users can call it to reclaim idle entries.
     */
    public fun prune() {
        for (bucket in buckets) {
            synchronized(bucket) {
                val now = nanoTime()
                bucket.entries.removeIf { it.value.debtAt(now) == 0L }
            }
        }
    }

    private fun evaluate(key: BucketKey, rate: Rate, cost: Int, debit: Boolean): RateDecision {
        require(cost in 1..rate.capacity)

        val hash = key.hashCode()
        val bucket = buckets[(hash xor (hash ushr 16)) and (buckets.size - 1)]
        return synchronized(bucket) {
            val now = nanoTime()
            val entry = bucket[key]
            val prior = entry?.debtAt(now) ?: 0L
            if (prior > 0 && entry != null && entry.rate !== rate) {
                require(entry.rate.tokenUnit == rate.tokenUnit && entry.rate.refillSpeed == rate.refillSpeed && entry.rate.capacity == rate.capacity) {
                    "An active bucket for policy '${key.policy}' already uses a different rate"
                }
            }

            val available = rate.maxDebt - prior
            val charge = rate.tokenUnit * cost
            val allowed = charge <= available
            val debt = if (debit && allowed) prior + charge else prior
            val fullAfter = refillNanos(debt, rate.refillSpeed)
            // Read the wall clock before recording a charge. A clock failure must leave the tokens available.
            val reset = clock.instant().plusNanos(fullAfter)
            val balance = Balance(((rate.maxDebt - debt) / rate.tokenUnit).toInt(), reset)
            if (!allowed) {
                return@synchronized Limited(balance, refillNanos(charge - available, rate.refillSpeed).nanoseconds)
            }

            if (debit) {
                if (entry == null || entry.rate !== rate) {
                    bucket[key] = Entry(debt, now, fullAfter, rate)
                } else {
                    entry.debt = debt
                    entry.updated = now
                    entry.fullAfter = fullAfter
                }
            }

            Granted(balance)
        }
    }

    private fun refillNanos(debt: Long, speed: Int): Long {
        if (speed == 1) {
            return debt
        }

        return Math.ceilDiv(debt, speed.toLong())
    }
}
