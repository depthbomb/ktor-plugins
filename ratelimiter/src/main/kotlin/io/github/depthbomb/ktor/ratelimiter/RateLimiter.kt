package io.github.depthbomb.ktor.ratelimiter

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * Configures how routes choose a bucket and charge a request. The defaults use the socket
 * peer's IP address and charge one token.
 */
public class RatePolicyConfig {
    internal var identifier: suspend ApplicationCall.() -> String = { request.local.remoteAddress }
    internal var weight: suspend ApplicationCall.() -> Int = { 1 }

    /**
     * Uses [block] to choose the identifier within this policy. It runs after Ktor checks
     * authentication, so it can read the call's principal, session, or attributes.
     * Return the same identifier for calls that should share quota.
     */
    public fun identifyBy(block: suspend ApplicationCall.() -> String) {
        identifier = block
    }

    /**
     * Uses [block] to calculate the request cost after authentication.
     * Return a whole number from one through the policy's capacity.
     */
    public fun costBy(block: suspend ApplicationCall.() -> Int) {
        weight = block
    }
}

/**
 * A named policy used by both route guards and manual quota operations.
 * The same identifier shares one bucket across those entry points.
 *
 * @property name Policy name used to look up the policy and namespace its storage keys.
 * @property rate Refill and burst settings for each identifier.
 */
public class RatePolicy internal constructor(
    public val name: String,
    public val rate: Rate,
    private val storage: RateStorage,
    internal val identifier: suspend ApplicationCall.() -> String,
    internal val weight: suspend ApplicationCall.() -> Int,
) {
    /**
     * Checks whether [identifier] can afford [cost] tokens at the time of the call.
     * The returned [RateDecision] describes the current balance. A later [tryAcquire]
     * may see a different balance. Storage errors propagate to the caller.
     *
     * @throws IllegalArgumentException if [cost] falls outside one through the policy's capacity.
     */
    public suspend fun inspect(identifier: String, cost: Int = 1): RateDecision {
        require(cost in 1..rate.capacity)
        return storage.inspect(BucketKey(name, identifier), rate, cost)
    }

    /**
     * Atomically charges [cost] tokens to [identifier] if enough are available. Returns [Granted]
     * with the post-charge balance, or [Limited] with the delay until this cost fits.
     * A rejection spends zero tokens. Storage errors propagate to the caller.
     *
     * @throws IllegalArgumentException if [cost] falls outside one through the policy's capacity.
     */
    public suspend fun tryAcquire(identifier: String, cost: Int = 1): RateDecision {
        require(cost in 1..rate.capacity)
        return storage.tryAcquire(BucketKey(name, identifier), rate, cost)
    }
}

/** Installation settings for [RateLimiter]. Register each named policy before using it in routing. */
public class RateLimiterConfig {
    /** Backend shared by all policies in this installation. Defaults to a process-local [MemoryRateStorage]. */
    public var storage: RateStorage = MemoryRateStorage()
    private val definitions = mutableMapOf<String, Pair<Rate, RatePolicyConfig>>()

    /**
     * Registers [name] with a plain-English [definition] accepted by [parseRate], then applies
     * [configure] to its route callbacks.
     *
     * @throws IllegalArgumentException if the name is blank or already registered, or the definition is invalid.
     */
    public fun policy(name: String, definition: String, configure: RatePolicyConfig.() -> Unit = {}) {
        policy(name, parseRate(definition), configure)
    }

    /**
     * Registers [name] with the supplied [rate]. Use [configure] to set a route identifier or cost.
     *
     * @throws IllegalArgumentException if the name is blank or already registered.
     */
    public fun policy(name: String, rate: Rate, configure: RatePolicyConfig.() -> Unit = {}) {
        require(name.isNotBlank() && name !in definitions) { "Blank or duplicate rate policy: $name" }
        definitions[name] = rate to RatePolicyConfig().apply(configure)
    }

    internal fun registry(): RatePolicies = RatePolicies(definitions.mapValues { (name, definition) ->
        val (rate, config) = definition
        RatePolicy(name, rate, storage, config.identifier, config.weight)
    })
}

/** Registry of the policies configured for one Ktor application. Access it through [rateLimiter]. */
public class RatePolicies internal constructor(private val policies: Map<String, RatePolicy>) {
    /**
     * Returns the registered policy named [name] for manual inspection or acquisition.
     *
     * @throws IllegalArgumentException if the application has no policy with that name.
     */
    public fun policy(name: String): RatePolicy = requireNotNull(policies[name]) { "Unknown rate policy: $name" }
}

private val registryKey = AttributeKey<RatePolicies>("io.github.depthbomb.ktor.ratelimiter.registry")
private val scopeKey = AttributeKey<String>("io.github.depthbomb.ktor.ratelimiter.scope")

/** Returns the registry installed by [RateLimiter]. Install the plugin before accessing this property. */
public val Application.rateLimiter: RatePolicies get() = attributes[registryKey]

/**
 * Installs the policy registry and schedules cleanup for in-memory storage. Use [limitedBy]
 * to enforce a registered policy on routes or [rateLimiter] to access it directly.
 *
 * ```kotlin
 * install(RateLimiter) { policy("writes", "5 per 3 minutes") }
 * routing {
 *     limitedBy("writes") { post("/comments") { /* save the comment */ } }
 * }
 * ```
 */
public val RateLimiter: ApplicationPlugin<RateLimiterConfig> =
    createApplicationPlugin("io.github.depthbomb.ktor.ratelimiter", ::RateLimiterConfig) {
        application.attributes.put(registryKey, pluginConfig.registry())
        val memory = pluginConfig.storage as? MemoryRateStorage
        if (memory != null) {
            application.launch {
                while (isActive) {
                    delay(1.minutes)
                    memory.prune()
                }
            }
        }
    }

/**
 * Thrown by a route guard when its policy rejects a request. An application can handle it
 * in Ktor's StatusPages plugin and use [Limited.retryAfter] to set a retry delay.
 *
 * @property policy Policy that evaluated the request.
 * @property decision Rejection containing the balance and time until the attempted cost fits.
 * @property cost Number of tokens the request attempted to spend.
 */
public class RateLimitExceededException(
    public val policy: RatePolicy,
    public val decision: Limited,
    public val cost: Int,
) : RuntimeException("Rate limit exceeded for policy '${policy.name}'")

private class GuardConfig {
    lateinit var policy: RatePolicy
}

private val guard = createRouteScopedPlugin("io.github.depthbomb.ktor.ratelimiter.guard", ::GuardConfig) {
    val policy = pluginConfig.policy
    on(AuthenticationChecked) { call ->
        if (call.isHandled) {
            return@on
        }

        val cost = policy.weight(call)
        val decision = policy.tryAcquire(policy.identifier(call), cost)
        // Round up so a client waiting for a full refill never sees an earlier second.
        val reset = decision.balance.resetAt
        call.response.header("X-RateLimit-Limit", policy.rate.capacity.toString())
        call.response.header("X-RateLimit-Remaining", decision.balance.remaining.toString())
        call.response.header("X-RateLimit-Reset", (reset.epochSecond + if (reset.nano > 0) 1 else 0).toString())
        call.response.header("X-RateLimit-Cost", cost.toString())

        if (decision is Limited) {
            throw RateLimitExceededException(policy, decision, cost)
        }
    }
}

@OptIn(InternalAPI::class)
private class PolicySelector : TransparentRouteSelector()

/**
 * Applies the registered policy [name] to routes defined by [build]. Scopes with the same name
 * share buckets. Nesting the same policy charges a request once. The return value is the route
 * on which [build] ran.
 *
 * After authentication, the guard writes `X-RateLimit-Limit` (capacity), `X-RateLimit-Remaining`
 * (whole tokens), `X-RateLimit-Reset` (full-refill Unix time rounded up to seconds), and
 * `X-RateLimit-Cost` (attempted charge). On rejection it throws [RateLimitExceededException]
 * before the route handler runs, leaving the response status and body to the application.
 *
 * @throws IllegalArgumentException during route setup if [name] is unknown or conflicts with an enclosing policy.
 */
public fun Route.limitedBy(name: String, build: Route.() -> Unit): Route {
    val inherited = generateSequence(this) { it.parent }.firstNotNullOfOrNull { it.attributes.getOrNull(scopeKey) }
    if (inherited != null) {
        require(inherited == name) { "Conflicting nested rate policies: $inherited and $name" }
        // The parent guard already covers this subtree. Installing another would charge twice.
        return apply(build)
    }

    val selected = application.rateLimiter.policy(name)
    return createChild(PolicySelector()).apply {
        attributes.put(scopeKey, name)
        install(guard) { policy = selected }
        build()
    }
}
