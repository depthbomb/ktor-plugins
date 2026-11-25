package io.github.depthbomb.ktor.signedurls

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class SignedUrlsTest {
    @Test
    fun builderSignsTheVisiblePathAndWholeQuery() = testApplication {
        lateinit var link: String
        engine {
            connector { host = "localhost"; port = 80 }
            connector { host = "second.example"; port = 80 }
        }
        application {
            install(SignedUrls) { key = ByteArray(32) }
            val builder = URLBuilder("https://first.example/files/42?format=large%20print&tag=one&tag=two&flag")
            assertSame(builder, builder.sign(this, 30.seconds))
            link = builder.build().fullPath
            routing {
                route("/files/{id}") {
                    handle {
                        if (!call.validateSignedUrl()) {
                            call.respond(HttpStatusCode.Forbidden)
                            return@handle
                        }
                        call.respondText(call.pathParameters["id"]!!)
                    }
                }
            }
        }
        startApplication()

        val token = link.substringAfter("sig=")
        assertEquals("42", client.get(link).bodyAsText())
        assertEquals("42",
            client.post("http://second.example/files/42?sig=$token&flag&tag=one&tag=two&format=large+print")
                .bodyAsText()
        )
        for (invalid in listOf(
            link.replace("/42", "/43"),
            link.replace("large%20print", "small"),
            link.replace("tag=one&tag=two", "tag=two&tag=one"),
            link.replace("&tag=two", ""),
            link.replace("&flag&", "&flag=&"),
            "$link&extra=1",
            "$link&SIG=$token",
            link.substringBefore("&sig="),
            link.replace(token, "invalid!"),
        )) {
            assertEquals(HttpStatusCode.Forbidden, client.get(invalid).status, invalid)
        }
    }

    @Test
    fun signingFailureLeavesBuilderUntouched() = testApplication {
        application {
            install(SignedUrls) {
                key = ByteArray(32)
                parameterName = "proof"
            }
            val builder = URLBuilder("/?sig=ordinary")
            for (duration in listOf(Duration.ZERO, (-1).seconds, Duration.INFINITE)) {
                assertFailsWith<IllegalArgumentException> { builder.sign(this, duration) }
                assertFalse(builder.parameters.contains("proof"))
            }
            builder.sign(this, 4.days)
            val signed = builder.build().fullPath
            assertTrue("sig=ordinary&proof=" in signed)
            assertFailsWith<IllegalArgumentException> { builder.sign(this) }
            assertEquals(signed, builder.build().fullPath)

            for (collision in listOf("PROOF=existing", "%70roof=existing", "proof", "proof=")) {
                val reserved = URLBuilder("/?$collision")
                val before = reserved.build().fullPath
                assertFailsWith<IllegalArgumentException> { reserved.sign(this) }
                assertEquals(before, reserved.build().fullPath)
            }
            val mail = URLBuilder("mailto:files@example.com")
            assertFailsWith<IllegalArgumentException> { mail.sign(this) }
            assertTrue(mail.encodedParameters.isEmpty())
            val malformed = URLBuilder().apply { encodedPath = "/files#fragment" }
            assertFailsWith<IllegalArgumentException> { malformed.sign(this) }
            assertTrue(malformed.encodedParameters.isEmpty())
            routing {
                get("/") { call.respondText(call.validateSignedUrl().toString()) }
                get("/link") { call.respondText(signed) }
            }
        }
        assertEquals("true", client.get(client.get("/link").bodyAsText()).bodyAsText())
    }

    @Test
    fun validatesAPreviouslyIssuedLinkWithAnAbsoluteRequestTarget() = testApplication {
        // Issued by the original v1 implementation with a zero-filled test key and no deadline.
        val token = "AcJz-CsUsZT7RSqoEAB4GlUnLc0iojrlNcCouh2P83yZvUpWqPh_ECdQ3UGtF52wj3C5b-xjXJ0VE7ZuHzOWUujY"
        application {
            install(SignedUrls) { key = ByteArray(32) }
            routing {
                get("/files/{id}") {
                    call.mutableOriginConnectionPoint.uri = "https://proxy.example${call.request.uri}"
                    call.respondText(call.validateSignedUrl().toString())
                }
            }
        }
        assertEquals("true", client.get("/files/42?tag=one&tag=two&sig=$token&format=pdf").bodyAsText())
    }

    @Test
    fun validatesTheEntireRequestPastTheQueryParserLimit() = testApplication {
        val builder = URLBuilder("/many")
        repeat(1100) { builder.parameters.append("n", it.toString()) }
        application {
            install(SignedUrls) { key = ByteArray(32) }
            builder.sign(this)
            routing {
                get("/many") { call.respondText(call.validateSignedUrl().toString()) }
            }
        }
        startApplication()

        assertEquals("true", client.get { url.takeFrom(builder) }.bodyAsText())
        builder.parameters.append("extra", "1")
        assertEquals("false", client.get { url.takeFrom(builder) }.bodyAsText())
    }

    @Test
    fun installRequiresAKeyAndParameterName() {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                application { install(SignedUrls) { key = ByteArray(31) } }
                startApplication()
            }
        }
        for (invalidName in listOf(" ", "\uD800")) {
            assertFailsWith<IllegalArgumentException> {
                testApplication {
                    application {
                        install(SignedUrls) {
                            key = ByteArray(32)
                            parameterName = invalidName
                        }
                    }
                    startApplication()
                }
            }
        }
    }
}
