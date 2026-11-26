# RateLimiter

Use a named policy to share a quota across a single endpoint, a route subtree, or several unrelated routes. The same
policy is available from application code.

## Installation

Add Maven Central to your repositories and add the plugin to your Ktor application. The example uses Ktor authentication
to look up the user and StatusPages to turn a rejection into an HTTP response.

```kotlin
dependencies {
    implementation("io.github.depthbomb.ktor:ratelimiter:0.1.0")
    implementation("io.ktor:ktor-server-auth:3.6.2")
    implementation("io.ktor:ktor-server-status-pages:3.6.2")
}
```

## Apply a policy

This example assumes your application has an authentication provider named `account` that returns a stable user ID
as a `String` principal. Signed-in requests share a bucket by user ID. Anonymous requests share a bucket by peer IP.

```kotlin
import io.github.depthbomb.ktor.ratelimiter.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.seconds

private fun resolveWriteIdentifier(call: ApplicationCall): String {
    val userId = call.principal<String>("account")
    return if (userId != null) "user:$userId" else "ip:${call.request.local.remoteAddress}"
}

fun Application.configureWriteLimits() {
    install(RateLimiter) {
        policy("writes", "5 per 3 minutes") {
            identifyBy(::resolveWriteIdentifier)
        }
    }
    install(StatusPages) {
        exception<RateLimitExceededException> { call, failure ->
            val wait = failure.decision.retryAfter
            val seconds = wait.inWholeSeconds + if (wait > wait.inWholeSeconds.seconds) 1 else 0
            call.response.header(HttpHeaders.RetryAfter, seconds.toString())
            call.respondText("Try again later", status = HttpStatusCode.TooManyRequests)
        }
    }
    routing {
        authenticate("account", optional = true) {
            limitedBy("writes") {
                post("/comments") { call.respond(HttpStatusCode.Accepted) }
                post("/reactions") { call.respond(HttpStatusCode.Accepted) }
            }
        }
    }
}
```

| Definition | What one identifier can do |
| --- | --- |
| `5 per 3 minutes` | Spend five tokens immediately, then earn one every 36 seconds. |
| `5 per 3 minutes, burst up to 2` | Spend up to two immediately, with the same refill rate. |
| `1 every 36 seconds` | Start one request immediately. The next can start 36 seconds later. |

Definitions accept whole seconds, minutes, hours, and days. For computed values, use
`tokenBucket(5, 3.minutes, burst = 2)` or `paced(36.seconds)`. Rejections spend zero tokens and return immediately.

By default, a request costs one token and its identifier is the socket peer's IP address. Set `identifyBy { … }` and
`costBy { … }` inside `policy` to customize them. Both callbacks receive the Ktor call after authentication. A cost must
be between one and the bucket capacity. Behind a reverse proxy, use a forwarded client address only when the socket peer
is a trusted proxy. [Authentication and proxy examples](src/test/kotlin/io/github/depthbomb/ktor/ratelimiter/RateLimiterTest.kt)

## Response headers

The route guard sets these headers on admission and rejection, before the handler or exception response runs.

| Header | Value |
| --- | --- |
| `X-RateLimit-Limit` | Bucket capacity. |
| `X-RateLimit-Remaining` | Whole tokens available after the decision. |
| `X-RateLimit-Reset` | Unix time in seconds when the bucket will be full, rounded up. |
| `X-RateLimit-Cost` | Attempted cost of this request. |

For a retry delay, use `failure.decision.retryAfter`. An empty five-token bucket may afford the next request well before
its full reset.

## Manual use and storage

```kotlin
val policy = application.rateLimiter.policy("writes")
val decision = policy.tryAcquire(identifier = "user:42", cost = 2)
when (decision) {
    is Granted -> saveComment()
    is Limited -> retryLater(decision.retryAfter)
}
```

Here `saveComment` and `retryLater` are application functions. Manual acquisition shares the route quota when the policy
name and identifier match. Use `inspect` for a read-only snapshot. Use `tryAcquire` to make the atomic decision, and
charge a given operation in one place.

The default `MemoryRateStorage` keeps balances in one process. To share quotas between servers, implement `RateStorage`
and set `storage = yourBackend` inside `install(RateLimiter)`. Key buckets by both policy and identifier, preserve
fractional refill credit, and check and debit atomically. Return `Limited` for insufficient quota and propagate backend
errors. Gradle consumers can add `testImplementation(testFixtures("io.github.depthbomb.ktor:ratelimiter:0.1.0"))` to
exercise their provider with the [storage contract fixture](src/testFixtures/kotlin/io/github/depthbomb/ktor/ratelimiter/StorageContract.kt).
