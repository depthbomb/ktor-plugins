package io.github.depthbomb.ktor.oidc

import com.nimbusds.common.contenttype.ContentType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.util.Resource
import com.nimbusds.jwt.JWTClaimNames
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.*
import com.nimbusds.oauth2.sdk.auth.*
import com.nimbusds.oauth2.sdk.http.HTTPRequest
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.oauth2.sdk.token.AccessToken
import com.nimbusds.oauth2.sdk.util.URLUtils
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.openid.connect.sdk.UserInfoRequest
import com.nimbusds.openid.connect.sdk.claims.AccessTokenHash
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URI

private fun URI.requireWebEndpoint(allowLocalHttp: Boolean): URI {
    val local = host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "[::1]"
    require(
        host != null && rawUserInfo == null && rawFragment == null &&
                (scheme.equals("https", true) || (allowLocalHttp && local && scheme.equals("http", true)))
    ) {
        "OIDC endpoints require an absolute HTTPS URL (HTTP is allowed on loopback for development)"
    }

    return this
}

private class Provider(
    val metadata: OIDCProviderMetadata,
    val authorizationUrl: String,
    val auth: ClientAuthentication,
    val validator: IDTokenValidator,
)

internal class OidcClient(config: OidcLoginConfig) {
    private val issuerUri = URI(config.issuer).requireWebEndpoint(allowLocalHttp = true)
    private val issuer = Issuer(issuerUri.toString())
    private val redirect = URI(config.redirectUri).requireWebEndpoint(allowLocalHttp = true)
    private val clientId = ClientID(config.clientId)
    private val secret = Secret(config.clientSecret)
    private val scopes = Scope(*config.scopes.toTypedArray())
    private val fetchUserInfo = config.fetchUserInfo
    private val discoveryMutex = Mutex()

    @Volatile
    private var cachedProvider: Provider? = null

    init {
        require(issuerUri.rawQuery == null) { "The OIDC issuer URL must have no query" }
        require(config.clientId.isNotBlank() && config.clientSecret.isNotBlank()) { "Client credentials are required" }
        require("openid" in config.scopes) { "OIDC login requires the openid scope" }
    }

    private suspend fun provider(): Provider = cachedProvider ?: discoveryMutex.withLock {
        cachedProvider ?: runInterruptible(Dispatchers.IO) { discover() }.also { cachedProvider = it }
    }

    private fun discover(): Provider {
        val response = HTTPRequest(HTTPRequest.Method.GET, OIDCProviderMetadata.resolveURL(issuer)).sendToProvider()
        if (response.statusCode != 200) {
            throw IOException("OIDC discovery failed (HTTP ${response.statusCode})")
        }

        val document = response.bodyAsJSONObject

        require(document["issuer"] == issuer.value) { "The discovered OIDC issuer must match the configured issuer" }

        val metadata = OIDCProviderMetadata.parse(document)

        val allowLocalHttp = issuerUri.scheme.equals("http", ignoreCase = true)

        val authorize = requireNotNull(metadata.authorizationEndpointURI).requireWebEndpoint(allowLocalHttp)
        requireNotNull(metadata.tokenEndpointURI).requireWebEndpoint(allowLocalHttp)

        val keys = requireNotNull(metadata.jwkSetURI).requireWebEndpoint(allowLocalHttp)
        if (fetchUserInfo) {
            requireNotNull(metadata.userInfoEndpointURI) { "The provider has no UserInfo endpoint" }.requireWebEndpoint(
                allowLocalHttp
            )
        }

        require(JWSAlgorithm.RS256 in metadata.idTokenJWSAlgs.orEmpty()) { "The provider must support RS256 ID tokens" }
        val methods = metadata.tokenEndpointAuthMethods
        val auth = when {
            methods == null || ClientAuthenticationMethod.CLIENT_SECRET_BASIC in methods -> ClientSecretBasic(
                clientId,
                secret
            )

            ClientAuthenticationMethod.CLIENT_SECRET_POST in methods -> ClientSecretPost(clientId, secret)
            else -> error("The provider does not support client secret authentication")
        }
        val validator = IDTokenValidator(issuer, clientId, JWSAlgorithm.RS256, keys.toURL()) { url ->
            val jwks = HTTPRequest(HTTPRequest.Method.GET, url).sendToProvider(maxBytes = 50 * 1024)
            if (jwks.statusCode != 200) {
                throw IOException("OIDC signing-key request failed (HTTP ${jwks.statusCode})")
            }

            Resource(jwks.body, jwks.getHeaderValue("Content-Type"))
        }

        return Provider(metadata, authorizationUrl(authorize), auth, validator)
    }

    private fun authorizationUrl(endpoint: URI): String {
        // Decode the raw query once so escaped delimiters stay inside their original values.
        val parameters = URLUtils.parseParameters(endpoint.rawQuery)
        parameters.keys.removeAll(setOf("state", "nonce", "code_challenge"))

        val request = AuthenticationRequest.Builder(ResponseType.CODE, scopes, clientId, redirect).build()
        parameters.putAll(request.toParameters())
        parameters["code_challenge_method"] = listOf(CodeChallengeMethod.S256.value)

        return endpoint.toASCIIString().substringBefore('?') + "?" + URLUtils.serializeParameters(parameters)

    }

    internal suspend fun begin(): Pair<PendingLogin, String> {
        val authorizationUrl = provider().authorizationUrl
        val state = State()
        val nonce = Nonce()
        val verifier = CodeVerifier()
        val challenge = CodeChallenge.compute(CodeChallengeMethod.S256, verifier)
        val pending = PendingLogin(
            state = state.value,
            nonce = nonce.value,
            verifier = verifier.value,
            expiresAt = System.currentTimeMillis() + LOGIN_SECONDS * 1000L,
            issuer = issuer.value,
            clientId = clientId.value,
            redirectUri = redirect.toString(),
        )
        // Nimbus generates all three values in the URL-safe Base64 alphabet.
        val url = "$authorizationUrl&state=${state.value}&nonce=${nonce.value}&code_challenge=${challenge.value}"

        return pending to url
    }

    internal suspend fun complete(pending: PendingLogin, code: String, hasIssuer: Boolean): OidcIdentity? {
        if (pending.issuer != issuer.value || pending.clientId != clientId.value || pending.redirectUri != redirect.toString()) {
            return null
        }

        val discovered = provider()
        if (!hasIssuer && discovered.metadata.supportsAuthorizationResponseIssuerParam()) {
            return null
        }

        return runInterruptible(Dispatchers.IO) {
            val response = exchangeCode(discovered, code, pending.verifier) ?: return@runInterruptible null
            val subject = verifiedSubject(discovered.validator, response, pending.nonce) ?: return@runInterruptible null
            val profile = if (fetchUserInfo) {
                readUserInfo(discovered.metadata.userInfoEndpointURI, response.tokens.accessToken, subject)
                    ?: return@runInterruptible null
            } else {
                null
            }

            OidcIdentity(issuer.value, subject, profile)
        }
    }

    private fun exchangeCode(provider: Provider, code: String, verifier: String): AccessTokenResponse? {
        val grant = AuthorizationCodeGrant(AuthorizationCode(code), redirect, CodeVerifier(verifier))
        val request =
            TokenRequest.Builder(provider.metadata.tokenEndpointURI, provider.auth, grant).build().toHTTPRequest()
        val httpResponse = request.sendToProvider()
        val response = try {
            TokenResponse.parse(httpResponse)
        } catch (_: ParseException) {
            // Parser exceptions can include pieces of the token response in the application's error log.
            throw IOException("Invalid OIDC token response (HTTP ${httpResponse.statusCode})")
        }
        if (!response.indicatesSuccess()) {
            if (httpResponse.statusCode == 400 && response.toErrorResponse().errorObject.code == "invalid_grant") {
                return null
            }

            throw IOException("OIDC token exchange failed (HTTP ${httpResponse.statusCode})")
        }

        return response.toSuccessResponse()
    }

    private fun verifiedSubject(validator: IDTokenValidator, response: AccessTokenResponse, nonce: String): String? {
        val compact = response.customParameters["id_token"] as? String ?: return null
        val claims = try {
            validator.validate(SignedJWT.parse(compact), Nonce(nonce))
        } catch (_: java.text.ParseException) {
            return null
        } catch (_: BadJOSEException) {
            return null
        }

        val notBefore = claims.getDateClaim(JWTClaimNames.NOT_BEFORE)
        if (notBefore != null && notBefore.time > System.currentTimeMillis() + validator.maxClockSkew * 1000L) {
            return null
        }

        val hash = claims.getClaim("at_hash")
        if (hash != null && hash != AccessTokenHash.compute(
                response.tokens.accessToken,
                JWSAlgorithm.RS256,
                null
            ).value
        ) {
            return null
        }

        val authorizedParty = claims.getStringClaim("azp")
        if (authorizedParty != null && authorizedParty != clientId.value) {
            return null
        }

        if (claims.getStringListClaim("aud")?.any { it != clientId.value } == true) {
            return null
        }

        return claims.getStringClaim("sub")?.takeUnless { it.isBlank() }
    }

    private fun readUserInfo(endpoint: URI, accessToken: AccessToken, subject: String): JsonObject? {
        val request = UserInfoRequest(endpoint, accessToken).toHTTPRequest()
        val response = request.sendToProvider()
        if (response.statusCode != 200) {
            throw IOException("OIDC UserInfo request failed (HTTP ${response.statusCode})")
        }

        response.ensureEntityContentType(ContentType.APPLICATION_JSON)
        val profile = try {
            Json.parseToJsonElement(response.body) as? JsonObject ?: throw IOException("Expected a UserInfo object")
        } catch (_: SerializationException) {
            throw IOException("Invalid OIDC UserInfo JSON")
        }

        if (profile["sub"] != JsonPrimitive(subject)) {
            return null
        }

        return profile
    }
}
