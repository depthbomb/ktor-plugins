package io.github.depthbomb.ktor.ratelimiter

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Continuous refill rule shared by all buckets of a policy. A new bucket starts full.
 * Create a rule with [tokenBucket], [paced], or [parseRate].
 *
 * @property tokens Number of tokens earned over one [period]. Fractional credit carries forward.
 * @property period Time it takes to earn [tokens] tokens.
 * @property capacity Maximum whole tokens a bucket can hold or spend in a single request.
 */
public class Rate internal constructor(
    public val tokens: Int,
    public val period: Duration,
    public val capacity: Int,
) {
    internal val tokenUnit: Long
    internal val refillSpeed: Int
    internal val maxDebt: Long

    init {
        require(tokens > 0 && capacity > 0) { "Rate and capacity must be positive" }
        require(period.isPositive() && period.isFinite() && period.inWholeNanoseconds.nanoseconds == period) {
            "Period must fit in nanoseconds"
        }
        // Reducing the fraction keeps the refill math exact and gives 1 per minute the same units as 2 per 2 minutes.
        val nanoseconds = period.inWholeNanoseconds
        val divisor = greatestCommonDivisor(nanoseconds, tokens.toLong())
        tokenUnit = nanoseconds / divisor
        refillSpeed = (tokens / divisor).toInt()
        require(tokenUnit <= Long.MAX_VALUE / capacity) { "Bucket duration overflow" }
        maxDebt = tokenUnit * capacity
    }

    private tailrec fun greatestCommonDivisor(a: Long, b: Long): Long =
        if (b == 0L) a else greatestCommonDivisor(b, a % b)
}

/**
 * Creates a bucket that earns [tokens] tokens evenly over [period] and holds up to [burst].
 * By default, [burst] equals [tokens]. For example, five tokens per three minutes earns
 * one token every 36 seconds, even when the burst capacity is two.
 *
 * @throws IllegalArgumentException if any argument is nonpositive or the interval or bucket arithmetic exceeds the supported range.
 */
public fun tokenBucket(tokens: Int, period: Duration, burst: Int = tokens): Rate = Rate(tokens, period, burst)

/**
 * Allows one request immediately and another after [interval] has elapsed.
 * Idle time can accumulate at most one token. Each admitted request costs one token.
 *
 * @throws IllegalArgumentException if [interval] is nonpositive or cannot be represented in nanoseconds.
 */
public fun paced(interval: Duration): Rate = Rate(1, interval, 1)

private val definition = Regex(
    """(\d+)\s+(per|every)\s+(?:(\d+)\s+)?(seconds?|minutes?|hours?|days?)(?:\s*,\s*burst\s+up\s+to\s+(\d+))?""",
    RegexOption.IGNORE_CASE,
)

/**
 * Parses a case-insensitive rate such as `5 per 3 minutes`, `5 per 3 minutes, burst up to 2`,
 * or `1 every 36 seconds`. The first two forms create a [tokenBucket]. The last creates a [paced] rule.
 *
 * Counts are positive whole numbers. Durations accept seconds, minutes, hours, and days in
 * singular or plural form. An omitted duration count means one, as in `1 per second`.
 *
 * @throws IllegalArgumentException if [text] has an unsupported form or contains a value outside the supported range.
 */
public fun parseRate(text: String): Rate {
    val match = requireNotNull(definition.matchEntire(text.trim())) { "Invalid rate definition: $text" }
    val (count, kind, length, unit, burst) = match.destructured
    val amount = length.ifEmpty { "1" }.toLong()
    val period = when (unit.lowercase().removeSuffix("s")) {
        "second" -> amount.seconds
        "minute" -> amount.minutes
        "hour" -> amount.hours
        else -> amount.days
    }
    if (kind.equals("every", ignoreCase = true)) {
        require(count.toInt() == 1 && burst.isEmpty()) { "Pacing requires 1 every <interval>" }
        return paced(period)
    }
    return tokenBucket(count.toInt(), period, burst.ifEmpty { count }.toInt())
}
