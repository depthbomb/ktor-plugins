package io.github.depthbomb.ktor.csrf

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*

/** The cookie carries an opaque session ID. Session records and their tokens live on the server. */
private class FormSessions : CsrfSessionAdapter {
    private data class Record(val token: String? = null)

    private val records = ConcurrentHashMap<String, Record>()

    fun start(call: ApplicationCall) {
        call.sessions.get<String>()?.let(records::remove)
        val id = UUID.randomUUID().toString()
        records[id] = Record()
        call.sessions.set(id)
    }

    override suspend fun read(call: ApplicationCall): String? =
        call.sessions.get<String>()?.let { records[it]?.token }

    override suspend fun getOrCreate(call: ApplicationCall, create: () -> String): String {
        val id = checkNotNull(call.sessions.get<String>()) { "Start a session before rendering a form" }
        val current = records.computeIfPresent(id) { _, record ->
            if (record.token == null) Record(create()) else record
        }
        return checkNotNull(current?.token) { "Session expired" }
    }
}

class SessionFormTest {
    @Test
    fun formLifecycle() = testApplication {
        val sessions = FormSessions()
        val changes = AtomicInteger()
        application {
            install(Sessions) {
                cookie<String>("form-id") {
                    cookie.httpOnly = true
                    cookie.extensions["SameSite"] = "Lax"
                }
            }
            install(CsrfTokens) { this.sessions = sessions }
            routing {
                // Establish an anonymous session. Repeat after a successful sign-in to rotate it.
                post("/session") {
                    sessions.start(call)
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/form") {
                    call.respondText(
                        """<form method="post" action="/save"><input type="hidden" name="csrf" value="${call.csrfToken()}"><button>Save</button></form>""",
                        ContentType.Text.Html
                    )
                }
                post("/save") {
                    if (!call.validateCsrfToken(call.receiveParameters()["csrf"])) {
                        call.respondText("Form expired", status = HttpStatusCode.Forbidden)
                        return@post
                    }
                    changes.incrementAndGet()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        suspend fun session(previous: String? = null): String = client.post("/session") {
            if (previous != null) header(HttpHeaders.Cookie, previous)
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')

        suspend fun render(cookie: String): String {
            val form = client.get("/form") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
            return Regex("""name="csrf" value="([A-Za-z0-9_-]{43})"""").find(form)!!.groupValues[1]
        }

        suspend fun submit(cookie: String?, token: String?): HttpResponse = client.submitForm(
            "/save", parameters { if (token != null) append("csrf", token) },
        ) { if (cookie != null) header(HttpHeaders.Cookie, cookie) }

        val alice = session()
        val bob = session()
        val tabs = coroutineScope { List(8) { async { render(alice) } }.awaitAll() }
        val token = tabs.distinct().single()
        assertNotEquals(token, render(bob))
        assertEquals(HttpStatusCode.NoContent, submit(alice, token).status)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val nonCanonical = token.dropLast(1) + alphabet[alphabet.indexOf(token.last()) + 1]
        for (invalid in listOf(null, "", "$token=", nonCanonical, "*".repeat(43), render(bob))) {
            val response = submit(alice, invalid)
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("Form expired", response.bodyAsText())
        }
        assertNull(submit(null, token).headers[HttpHeaders.SetCookie])
        val rotated = session(alice)
        assertEquals(HttpStatusCode.Forbidden, submit(alice, token).status)
        assertEquals(HttpStatusCode.Forbidden, submit(rotated, token).status)
        assertEquals(HttpStatusCode.NoContent, submit(rotated, render(rotated)).status)
        assertEquals(2, changes.get())
    }

    @Test
    fun defaultStrategyAcceptsOnlyCanonicalTokensEvenWhenStoredTextMatches() = testApplication {
        application {
            routing {
                get("/") {
                    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
                    for ((index, char) in alphabet.withIndex()) {
                        val token = "A".repeat(42) + char
                        assertEquals(index % 4 == 0, SecureCsrfTokens.validate(call, token, token))
                    }
                    for (invalid in listOf('?', '=', '+', '/', 'é', '\uD800')) {
                        val token = invalid + "A".repeat(42)
                        assertFalse(SecureCsrfTokens.validate(call, token, token))
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
        assertEquals(HttpStatusCode.NoContent, client.get("/").status)
    }

    @Test
    fun delegatesStrategyAndPropagatesStorageFailures() = testApplication {
        val failure = IllegalStateException("session storage offline")
        var stored: String? = null
        var creations = 0
        application {
            install(CsrfTokens) {
                sessions = object : CsrfSessionAdapter {
                    override suspend fun read(call: ApplicationCall): String? {
                        if (call.request.path() == "/offline") throw failure
                        return stored
                    }

                    override suspend fun getOrCreate(call: ApplicationCall, create: () -> String): String {
                        creations++
                        return create().also { stored = it }
                    }
                }
                strategy = object : CsrfTokenStrategy {
                    override fun generate(call: ApplicationCall) = "example"
                    override fun validate(call: ApplicationCall, expected: String, presented: String) =
                        presented == "custom-$expected"
                }
            }
            routing {
                get("/") {
                    assertFalse(call.validateCsrfToken("custom-example"))
                    assertEquals(0, creations)
                    assertEquals("example", call.csrfToken())
                    assertTrue(call.validateCsrfToken("custom-example"))
                    assertFalse(call.validateCsrfToken("example"))
                    call.respond(HttpStatusCode.OK)
                }
                get("/offline") {
                    assertSame(failure, assertFailsWith<IllegalStateException> { call.validateCsrfToken("value") })
                    call.respond(HttpStatusCode.ServiceUnavailable)
                }
            }
        }
        assertEquals(HttpStatusCode.OK, client.get("/").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/offline").status)
    }
}
