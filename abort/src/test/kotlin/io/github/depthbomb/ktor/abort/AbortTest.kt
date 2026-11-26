package io.github.depthbomb.ktor.abort

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AbortTest {
    @Test
    fun abortPreservesStatusAndMessageForStatusPages() = testApplication {
        val customStatus = HttpStatusCode(499, "Client Closed Request")
        application {
            install(StatusPages) {
                exception<HttpException> { call, error ->
                    call.respondText(error.message ?: error.status.description, status = error.status)
                }
            }
            routing {
                get("/gone") { call.abort(410, "Item removed") }
                get("/custom") { call.abort(customStatus, null) }
                get("/empty") { call.abort(499, "") }
            }
        }

        val gone = client.get("/gone")
        assertEquals(HttpStatusCode.Gone, gone.status)
        assertEquals("Item removed", gone.bodyAsText())

        val custom = client.get("/custom")
        assertEquals(customStatus.value, custom.status.value)
        assertEquals(customStatus.description, custom.bodyAsText())

        val empty = client.get("/empty")
        assertEquals(499, empty.status.value)
        assertEquals("", empty.bodyAsText())
    }

    @Test
    fun conditionalOverloadsAbortOnlyWhenTheirGuardMatches() = testApplication {
        routing {
            get("/") {
                val forbidden = HttpStatusCode.Forbidden
                call.abortIf(false, 403)
                call.abortIf(false, forbidden)
                call.abortUnless(true, 403)
                call.abortUnless(true, forbidden)

                val ifInt = assertFailsWith<HttpException> { call.abortIf(true, 403, "denied") }
                assertEquals(forbidden, ifInt.status)
                assertEquals("denied", ifInt.message)

                val ifStatus = assertFailsWith<HttpException> { call.abortIf(true, forbidden) }
                assertEquals(forbidden, ifStatus.status)
                assertNull(ifStatus.message)

                val unlessInt = assertFailsWith<HttpException> { call.abortUnless(false, 499, null) }
                assertEquals(499, unlessInt.status.value)
                assertNull(unlessInt.message)

                val unlessStatus = assertFailsWith<HttpException> { call.abortUnless(false, forbidden, "denied") }
                assertEquals(forbidden, unlessStatus.status)
                assertEquals("denied", unlessStatus.message)

                call.respond(HttpStatusCode.NoContent)
            }
        }
        assertEquals(HttpStatusCode.NoContent, client.get("/").status)
    }

    @Test
    fun returningGuardsAllowSmartCastsInRoutes() = testApplication {
        routing {
            get("/{guard}") {
                val id = call.request.queryParameters["id"]?.toIntOrNull()
                when (call.parameters["guard"]) {
                    "if-int" -> call.abortIf(id == null, 400)
                    "if-status" -> call.abortIf(id == null, HttpStatusCode.BadRequest)
                    "unless-int" -> call.abortUnless(id != null, 400)
                    "unless-status" -> call.abortUnless(id != null, HttpStatusCode.BadRequest)
                    else -> call.abort(404)
                }
                call.respondText((id + 1).toString())
            }
        }

        for (guard in listOf("if-int", "if-status", "unless-int", "unless-status")) {
            assertEquals("42", client.get("/$guard?id=41").bodyAsText())
        }
    }
}
