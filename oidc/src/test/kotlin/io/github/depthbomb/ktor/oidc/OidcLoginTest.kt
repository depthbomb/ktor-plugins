package io.github.depthbomb.ktor.oidc

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.ktor.client.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.*
import kotlin.test.*

class OidcLoginTest {
    private fun withLogin(
        fetchProfile: Boolean = true,
        storedLogin: Boolean = false,
        block: suspend TestProvider.(HttpClient) -> Unit,
    ) {
        TestProvider().use { provider ->
            testApplication {
                application {
                    install(Sessions) {
                        val random = SecureRandom()
                        val cipher =
                            SessionTransportTransformerEncrypt(random.generateSeed(32), random.generateSeed(32))
                        if (storedLogin) {
                            oidcLoginSession(SessionStorageMemory()) { secure = false }
                            cookie<String>("app_user") { transform(cipher) }
                        } else {
                            oidcLoginSession(cipher) { secure = false }
                            cookie<String>("app_user", SessionStorageMemory())
                        }
                    }
                    install(OidcLogin) {
                        issuer = provider.issuer
                        clientId = "test-client"
                        clientSecret = "test-secret"
                        redirectUri = provider.callback
                        fetchUserInfo = fetchProfile
                    }
                    routing {
                        get("/auth/login") { call.respondRedirect(call.beginOidcLogin()) }
                        get("/auth/callback") {
                            val identity = try {
                                call.completeOidcLogin()
                            } catch (_: IOException) {
                                return@get call.respond(HttpStatusCode.BadGateway)
                            } ?: return@get call.respond(HttpStatusCode.Unauthorized)
                            call.sessions.set(identity.subject)
                            call.respondText(buildJsonObject {
                                put("issuer", identity.issuer)
                                put("subject", identity.subject)
                                put("profile", identity.userInfo ?: JsonNull)
                            }.toString())
                        }
                        get("/me") { call.respondText(call.sessions.get<String>().orEmpty()) }
                        get("/expire") {
                            val pending = call.sessions.get<PendingLogin>()!!
                            call.sessions.set(
                                PendingLogin(
                                    pending.state, pending.nonce, pending.verifier, 0,
                                    pending.issuer, pending.clientId, pending.redirectUri
                                )
                            )
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                }
                provider.block(createClient {
                    followRedirects = false
                    install(HttpCookies)
                })
            }
        }
    }

    private suspend fun TestProvider.begin(browser: HttpClient): Parameters {
        val response = browser.get("/auth/login")
        assertEquals(HttpStatusCode.Found, response.status)
        val url = Url(assertNotNull(response.headers[HttpHeaders.Location]))
        assertEquals("$issuer/authorize", url.toString().substringBefore('?'))
        nonce = assertNotNull(url.parameters["nonce"])
        return url.parameters
    }

    @Test
    fun `route can read verified UserInfo and establish an application session`() = withLogin { browser ->
        val params = begin(browser)
        assertEquals("red&blue+green", params["tenant"])
        assertEquals("code", params["response_type"])
        assertEquals("test-client", params["client_id"])
        assertEquals(callback, params["redirect_uri"])
        assertEquals(setOf("openid", "profile", "email"), params["scope"]?.split(" ")?.toSet())
        assertEquals("S256", params["code_challenge_method"])
        assertNull(params["code_verifier"])

        val callbackPath = "/auth/callback?code=issued-code&state=${params["state"]}"
        val result = browser.get(callbackPath)
        assertEquals(HttpStatusCode.OK, result.status)
        val identity = Json.parseToJsonElement(result.bodyAsText()).jsonObject
        assertEquals(issuer, identity["issuer"]?.jsonPrimitive?.content)
        assertEquals("member-42", identity["subject"]?.jsonPrimitive?.content)
        val groups = identity["profile"]?.jsonObject?.get("groups")?.jsonArray
        assertEquals("editors", groups?.single()?.jsonPrimitive?.content)

        val verifier = assertNotNull(tokenForm["code_verifier"])
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        assertEquals(params["code_challenge"], Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
        assertEquals(callback, tokenForm["redirect_uri"])
        assertEquals("authorization_code", tokenForm["grant_type"])
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("test-client:test-secret".toByteArray()),
            tokenAuthorization
        )
        assertEquals("Bearer test-access", userInfoAuthorization)

        assertEquals(HttpStatusCode.Unauthorized, browser.get(callbackPath).status)
        assertEquals(1, tokenCalls.get())
        assertEquals("member-42", browser.get("/me").bodyAsText())
    }

    @Test
    fun `concurrent login starts share discovery and generate distinct transactions`() = withLogin { browser ->
        val urls = coroutineScope {
            List(12) { async { Url(assertNotNull(browser.get("/auth/login").headers[HttpHeaders.Location])) } }.awaitAll()
        }
        assertEquals(1, discoveryCalls.get())
        for (name in listOf("state", "nonce", "code_challenge")) {
            assertEquals(urls.size, urls.map { assertNotNull(it.parameters[name]) }.toSet().size)
        }
    }

    @Test
    fun `callback preserves the exact provider subject`() = withLogin { browser ->
        tokenSubject = " member-42 "
        profileSubject = tokenSubject
        val state = begin(browser)["state"]
        val result = browser.get("/auth/callback?state=$state&code=issued-code")
        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals(
            tokenSubject,
            Json.parseToJsonElement(result.bodyAsText()).jsonObject["subject"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `callback honors the advertised issuer parameter and case-sensitive names`() = withLogin { browser ->
        requireCallbackIssuer = true
        val first = begin(browser)["state"]
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?state=$first&code=issued-code").status)

        val state = begin(browser)["state"]
        val result = browser.get("/auth/callback") {
            parameter("state", state)
            parameter("code", "issued-code")
            parameter("Error", "extension")
            parameter("iss", issuer)
        }
        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals(1, tokenCalls.get())
    }

    @Test
    fun `unreadable ID tokens and tokens outside their validity window yield no identity`() = withLogin { browser ->
        tokenOverride = "unavailable"
        val first = begin(browser)["state"]
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?state=$first&code=issued-code").status)

        tokenOverride = null
        tokenLifetimeSeconds = 7200
        notBefore = Instant.now().plusSeconds(3600)
        val second = begin(browser)["state"]
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?state=$second&code=issued-code").status)
        assertEquals(0, userInfoCalls.get())

        notBefore = Instant.now().minusSeconds(1)
        val third = begin(browser)["state"]
        assertEquals(HttpStatusCode.OK, browser.get("/auth/callback?state=$third&code=issued-code").status)
    }

    @Test
    fun `pending login is independent of an existing server-side application session`() = withLogin { browser ->
        repeat(2) {
            val state = begin(browser)["state"]
            if (it > 0) {
                assertEquals("member-42", browser.get("/me").bodyAsText())
            }
            assertEquals(HttpStatusCode.OK, browser.get("/auth/callback?state=$state&code=issued-code").status)
            assertEquals("member-42", browser.get("/me").bodyAsText())
        }
    }

    @Test
    fun `login can keep the transaction in server storage`() = withLogin(storedLogin = true) { browser ->
        val state = begin(browser)["state"]
        val path = "/auth/callback?state=$state&code=issued-code"
        assertEquals(HttpStatusCode.OK, browser.get(path).status)
        assertEquals(HttpStatusCode.Unauthorized, browser.get(path).status)
        assertEquals("member-42", browser.get("/me").bodyAsText())
        assertEquals(1, tokenCalls.get())
    }

    @Test
    fun `unrelated callbacks leave the pending login intact and a matching denial clears it`() = withLogin { browser ->
        val callbackPath = "/auth/callback?code=issued-code&state="
        assertEquals(HttpStatusCode.Unauthorized, browser.get(callbackPath + "missing").status)
        val oldState = begin(browser)["state"]
        val state = begin(browser)["state"]
        assertNotEquals(oldState, state)
        assertEquals(HttpStatusCode.Unauthorized, browser.get(callbackPath + oldState).status)
        assertEquals(HttpStatusCode.Unauthorized, browser.get(callbackPath + state + "&state=extra").status)
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?error=access_denied&state=$state").status)
        assertEquals(HttpStatusCode.Unauthorized, browser.get(callbackPath + state).status)
        assertEquals(0, tokenCalls.get())
        assertEquals("", browser.get("/me").bodyAsText())
    }

    @Test
    fun `expired state and a callback for another issuer stop before token exchange`() = withLogin { browser ->
        val expired = begin(browser)["state"]
        assertEquals(HttpStatusCode.NoContent, browser.get("/expire").status)
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?state=$expired&code=issued-code").status)
        val state = begin(browser)["state"]
        assertEquals(
            HttpStatusCode.Unauthorized,
            browser.get("/auth/callback?state=$state&code=issued-code&iss=other").status
        )
        assertEquals(0, tokenCalls.get())
    }

    @Test
    fun `nonce and audience must match before fetching UserInfo`() = withLogin { browser ->
        val first = begin(browser)["state"]
        nonce = "different"
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?state=$first&code=issued-code").status)
        val second = begin(browser)["state"]
        audience = listOf("another-client")
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?state=$second&code=issued-code").status)
        val third = begin(browser)["state"]
        audience = listOf("test-client", "another-client")
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?state=$third&code=issued-code").status)
        assertEquals(3, tokenCalls.get())
        assertEquals(0, userInfoCalls.get())
        assertEquals("", browser.get("/me").bodyAsText())
    }

    @Test
    fun `ID token must be current and signed by the discovered key`() = withLogin { browser ->
        tokenLifetimeSeconds = -3600
        val expired = begin(browser)["state"]
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?state=$expired&code=issued-code").status)
        tokenLifetimeSeconds = 120
        signingKey = RSAKeyGenerator(2048).generate()
        val state = begin(browser)["state"]
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?state=$state&code=issued-code").status)
        assertEquals(0, userInfoCalls.get())
        assertEquals("", browser.get("/me").bodyAsText())
    }

    @Test
    fun `UserInfo belongs to the subject of the ID token`() = withLogin { browser ->
        val state = begin(browser)["state"]
        profileSubject = "another-subject"
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?state=$state&code=issued-code").status)
        assertEquals(1, userInfoCalls.get())
        assertEquals("", browser.get("/me").bodyAsText())
    }

    @Test
    fun `optional access token hash is checked before UserInfo`() = withLogin { browser ->
        val digest = MessageDigest.getInstance("SHA-256").digest("test-access".toByteArray(Charsets.US_ASCII))
        accessTokenHash = Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(16))
        val first = begin(browser)["state"]
        assertEquals(HttpStatusCode.OK, browser.get("/auth/callback?state=$first&code=issued-code").status)

        accessTokenHash = "different"
        val second = begin(browser)["state"]
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?state=$second&code=issued-code").status)
        assertEquals(1, userInfoCalls.get())
    }

    @Test
    fun `provider failures propagate even when the response mentions an invalid grant`() = withLogin { browser ->
        tokenErrorStatus = 503
        val state = begin(browser)["state"]
        val path = "/auth/callback?state=$state&code=unavailable"
        assertEquals(HttpStatusCode.BadGateway, browser.get(path).status)
        assertEquals(HttpStatusCode.Unauthorized, browser.get(path).status)
        assertEquals(1, tokenCalls.get())
        assertEquals(0, userInfoCalls.get())
    }

    @Test
    fun `UserInfo can be disabled without skipping the code exchange`() = withLogin(fetchProfile = false) { browser ->
        val first = begin(browser)["state"]
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/auth/callback?state=$first&code=expired-code").status)
        val state = begin(browser)["state"]
        val result = browser.get("/auth/callback?state=$state&code=issued-code")
        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals(JsonNull, Json.parseToJsonElement(result.bodyAsText()).jsonObject["profile"])
        assertEquals(0, userInfoCalls.get())
        assertEquals("member-42", browser.get("/me").bodyAsText())
    }
}
