package io.github.depthbomb.ktor.ratelimiter

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class RateLimiterTest {
    @Test
    fun wrappedAndManualRoutesShareOneBucket() = testApplication {
        application {
            install(Authentication) {
                bearer("account") {
                    authenticate { credential -> credential.token.takeIf { it == "alice" } }
                }
            }
            install(RateLimiter) {
                storage = MemoryRateStorage({ 0L }, Clock.fixed(Instant.ofEpochSecond(100, 1), ZoneOffset.UTC))
                policy("writes", "5 per 3 minutes") {
                    identifyBy { principal<String>("account") ?: "anonymous" }
                    costBy { if (request.path() == "/batch") 3 else 1 }
                }
            }
            install(StatusPages) {
                exception<RateLimitExceededException> { call, failure ->
                    val wait = failure.decision.retryAfter
                    val seconds = wait.inWholeSeconds + if (wait > wait.inWholeSeconds.seconds) 1 else 0
                    call.response.header(HttpHeaders.RetryAfter, seconds.toString())
                    call.respondText(failure.policy.name, status = HttpStatusCode.TooManyRequests)
                }
            }
            routing {
                authenticate("account", optional = true) {
                    limitedBy("writes") {
                        limitedBy("writes") {
                            post("/batch") { call.respondText("saved") }
                        }
                    }
                    limitedBy("writes") {
                        post("/single") { call.respondText("saved") }
                    }
                }
                get("/manual") {
                    val policy = application.rateLimiter.policy("writes")
                    assertFailsWith<IllegalArgumentException> { policy.tryAcquire("alice", cost = 6) }
                    assertEquals(2, policy.inspect("alice").balance.remaining)
                    assertIs<Granted>(policy.tryAcquire("alice"))
                    call.respondText("saved")
                }
            }
        }
        val batch = client.post("/batch") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.OK, batch.status)
        assertHeaders(batch, "2", "209", "3")
        assertNull(client.get("/manual").headers.getAll("X-RateLimit-Limit"))
        val single = client.post("/single") { bearerAuth("alice") }
        assertHeaders(single, "0", "281", "1")
        val denied = client.post("/batch") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.TooManyRequests, denied.status)
        assertEquals("writes", denied.bodyAsText())
        assertEquals("108", denied.headers[HttpHeaders.RetryAfter])
        assertHeaders(denied, "0", "281", "3")
        assertHeaders(client.post("/single"), "4", "137", "1")
    }

    private fun assertHeaders(response: HttpResponse, remaining: String, reset: String, cost: String) {
        assertEquals(listOf("5"), response.headers.getAll("X-RateLimit-Limit"))
        assertEquals(listOf(remaining), response.headers.getAll("X-RateLimit-Remaining"))
        assertEquals(listOf(reset), response.headers.getAll("X-RateLimit-Reset"))
        assertEquals(listOf(cost), response.headers.getAll("X-RateLimit-Cost"))
    }

    @Test
    fun conflictingNestedPoliciesFailAtSetup() = testApplication {
        application {
            install(RateLimiter) {
                policy("one", "1 per second")
                policy("two", "1 per second")
            }
            routing { limitedBy("one") { route("/nested") { limitedBy("two") {} } } }
        }
        assertFailsWith<IllegalArgumentException> { startApplication() }
    }

    @Test
    fun useForwardedIdentityOnlyForATrustedPeer() = testApplication {
        // The trusted edge appends the real client address as the last X-Forwarded-For value.
        val trustedPeers = mutableSetOf<String>()
        application {
            install(XForwardedHeaders) { useLastProxy() }
            install(RateLimiter) {
                policy("clients", "1 per day") {
                    identifyBy {
                        val peer = request.local.remoteAddress
                        if (peer in trustedPeers) request.origin.remoteAddress else peer
                    }
                }
            }
            install(StatusPages) {
                exception<RateLimitExceededException> { call, _ -> call.respond(HttpStatusCode.TooManyRequests) }
            }
            routing {
                get("/peer") { call.respondText(call.request.local.remoteAddress) }
                limitedBy("clients") { get("/") { call.respond(HttpStatusCode.OK) } }
            }
        }
        suspend fun request(chain: String) = client.get("/") { header(HttpHeaders.XForwardedFor, chain) }.status
        assertEquals(HttpStatusCode.OK, request("192.0.2.11"))
        assertEquals(HttpStatusCode.TooManyRequests, request("192.0.2.12"))
        trustedPeers.add(client.get("/peer").bodyAsText())
        assertEquals(HttpStatusCode.OK, request("192.0.2.111, 192.0.2.11"))
        assertEquals(HttpStatusCode.TooManyRequests, request("192.0.2.112, 192.0.2.11"))
        assertEquals(HttpStatusCode.OK, request("192.0.2.12"))
    }

    @Test
    fun storageFailurePropagatesFromTheRoute() = testApplication {
        val failure = IllegalStateException("unavailable")
        application {
            install(StatusPages) {
                exception<IllegalStateException> { call, error ->
                    call.respondText(error.message.orEmpty(), status = HttpStatusCode.ServiceUnavailable)
                }
            }
            install(RateLimiter) {
                storage = object : RateStorage {
                    override suspend fun inspect(key: BucketKey, rate: Rate, cost: Int): RateDecision = throw failure
                    override suspend fun tryAcquire(key: BucketKey, rate: Rate, cost: Int): RateDecision = throw failure
                }
                policy("read", "1 every second")
            }
            routing { limitedBy("read") { get("/") { error("unexpected admission") } } }
        }
        val response = client.get("/")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
        assertEquals("unavailable", response.bodyAsText())
        assertNull(response.headers.getAll("X-RateLimit-Remaining"))
    }
}
