package io.github.depthbomb.ktor.oidc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.http.*
import java.net.InetSocketAddress
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

internal class TestProvider : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val key = RSAKeyGenerator(2048).keyID("test").generate()
    val issuer = "http://127.0.0.1:${server.address.port}/provider"
    val callback = "http://localhost/auth/callback"

    @Volatile
    var nonce = ""

    @Volatile
    var profileSubject = "member-42"

    @Volatile
    var tokenSubject = "member-42"

    @Volatile
    var notBefore: Instant? = null

    @Volatile
    var tokenOverride: String? = null

    @Volatile
    var accessTokenHash: String? = null

    @Volatile
    var tokenErrorStatus = 400

    @Volatile
    var requireCallbackIssuer = false

    @Volatile
    var audience = listOf("test-client")

    @Volatile
    var signingKey = key

    @Volatile
    var tokenLifetimeSeconds = 120L

    @Volatile
    var tokenAuthorization: String? = null

    @Volatile
    var userInfoAuthorization: String? = null

    @Volatile
    var tokenForm: Parameters = Parameters.Empty
    val tokenCalls = AtomicInteger()
    val userInfoCalls = AtomicInteger()
    val discoveryCalls = AtomicInteger()

    init {
        server.createContext("/provider/.well-known/openid-configuration") { exchange ->
            discoveryCalls.incrementAndGet()
            exchange.json(
                """
                {
                    "issuer": "$issuer",
                    "authorization_endpoint": "$issuer/authorize?tenant=red%26blue%2Bgreen",
                    "token_endpoint": "$issuer/token",
                    "jwks_uri": "$issuer/keys",
                    "userinfo_endpoint": "$issuer/userinfo",
                    "response_types_supported": ["code"],
                    "authorization_response_iss_parameter_supported": $requireCallbackIssuer,
                    "subject_types_supported": ["public"],
                    "id_token_signing_alg_values_supported": ["RS256"]
                }
            """.trimIndent()
            )
        }
        server.createContext("/provider/keys") { it.json(JWKSet(key.toPublicJWK()).toString()) }
        server.createContext("/provider/token") { exchange ->
            tokenCalls.incrementAndGet()
            tokenAuthorization = exchange.requestHeaders.getFirst("Authorization")
            tokenForm = parseQueryString(exchange.requestBody.bufferedReader().use { it.readText() })
            if (tokenForm["code"] == "issued-code") {
                exchange.json("""{"access_token":"test-access","token_type":"Bearer","id_token":"${tokenOverride ?: idToken()}"}""")
            } else {
                exchange.json("""{"error":"invalid_grant"}""", tokenErrorStatus)
            }
        }
        server.createContext("/provider/userinfo") { exchange ->
            userInfoCalls.incrementAndGet()
            userInfoAuthorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.json("""{"sub":"$profileSubject","groups":["editors"]}""")
        }
        server.start()
    }

    private fun idToken(): String {
        val now = Instant.now()
        val claims =
            JWTClaimsSet.Builder().issuer(issuer).audience(audience).claim("azp", "test-client").subject(tokenSubject)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(tokenLifetimeSeconds)))
                .apply { notBefore?.let { notBeforeTime(Date.from(it)) } }
                .apply { accessTokenHash?.let { claim("at_hash", it) } }
                .claim("nonce", nonce).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims).apply {
            sign(RSASSASigner(signingKey))
        }.serialize()
    }

    private fun HttpExchange.json(value: String, status: Int = 200) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
    }
}
