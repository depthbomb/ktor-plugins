package io.github.depthbomb.ktor.oidc

import com.nimbusds.oauth2.sdk.http.HTTPRequest
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OidcHttpTest {
    @Test
    fun `response limits apply to fixed and chunked bodies and redirects stop at the endpoint`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val body = "profile".encodeToByteArray()
        server.createContext("/fixed") { call ->
            call.sendResponseHeaders(200, body.size.toLong())
            call.responseBody.use { it.write(body) }
        }
        server.createContext("/chunked") { call ->
            call.sendResponseHeaders(200, 0)
            call.responseBody.use { it.write(body) }
        }
        server.createContext("/redirect") { call ->
            call.responseHeaders.set("Location", "/fixed")
            call.sendResponseHeaders(302, -1)
            call.close()
        }
        server.start()
        try {
            fun request(path: String) =
                HTTPRequest(HTTPRequest.Method.GET, URI("http://127.0.0.1:${server.address.port}/$path"))
            for (path in listOf("fixed", "chunked")) {
                assertEquals("profile", request(path).sendToProvider(body.size).body)
                assertFailsWith<IOException> { request(path).sendToProvider(body.size - 1) }
            }
            assertFailsWith<IOException> { request("redirect").sendToProvider() }
        } finally {
            server.stop(0)
        }
    }
}
