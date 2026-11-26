package io.github.depthbomb.ktor.oidc

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.sessions.*
import io.ktor.util.*
import kotlinx.serialization.json.JsonObject
import java.net.URLDecoder

/**
 * Configures one confidential OpenID Connect client in `install(OidcLogin)`.
 * Set [issuer], [clientId], [clientSecret], and [redirectUri] during installation. The client uses the
 * authorization code flow with S256 PKCE and verifies RS256 ID tokens using the provider's published keys.
 *
 * Use absolute HTTPS URLs without user information or fragments. For local development, HTTP is supported
 * on `localhost`, `127.0.0.1`, and `[::1]`.
 *
 * @constructor Creates a configuration with the default [scopes] and [fetchUserInfo] settings.
 */
public class OidcLoginConfig {
    /**
     * Issuer URL used for discovery and ID token validation. Copy the provider's exact issuer identifier,
     * including its path and any trailing slash. The URL must have no query component.
     */
    public lateinit var issuer: String

    /** Registered, nonblank client identifier. It must be the ID token's sole audience. */
    public lateinit var clientId: String

    /**
     * Nonblank secret for authenticating the server to the token endpoint. The client uses
     * `client_secret_basic` by default and can use `client_secret_post` when advertised by the provider.
     */
    public lateinit var clientSecret: String

    /**
     * Exact callback URL registered with the provider. The plugin sends this value in both the authorization
     * request and the code exchange. Serve a GET route at this URL that calls [completeOidcLogin].
     */
    public lateinit var redirectUri: String

    /**
     * Scopes sent in the authorization request. Defaults to `openid`, `profile`, and `email`.
     * Include `openid` in any replacement set. The provider determines which requested claims it returns.
     */
    public var scopes: Set<String> = setOf("openid", "profile", "email")

    /**
     * Whether to fetch the provider's JSON UserInfo after verifying the ID token. Defaults to `true` and
     * requires a UserInfo endpoint in the discovery document. A successful response must identify the same
     * subject. Set to `false` to skip that request and receive `null` in [OidcIdentity.userInfo].
     */
    public var fetchUserInfo: Boolean = true
}

/**
 * Identity returned by [completeOidcLogin] after a successful callback. Use [issuer] and [subject] together
 * to look up the local account, then decide whether to establish an application session.
 */
public class OidcIdentity internal constructor(
    /** Exact issuer URL from the verified ID token. Matches [OidcLoginConfig.issuer], including any trailing slash. */
    public val issuer: String,

    /** Opaque, case-sensitive `sub` claim from the ID token. Preserve its exact value in account lookups. */
    public val subject: String,

    /**
     * JSON claims from the provider's UserInfo endpoint, including provider-specific fields.
     * Its `sub` matches [subject]. This value is `null` when [OidcLoginConfig.fetchUserInfo] is disabled.
     */
    public val userInfo: JsonObject?,
)

private val clientKey = AttributeKey<OidcClient>("io.github.depthbomb.ktor.oidc.client")

private fun decodeField(value: String): String? = try {
    URLDecoder.decode(value, Charsets.UTF_8)
} catch (_: IllegalArgumentException) {
    null
}

// OAuth parameter names are case-sensitive. Preserve the presence of duplicates and malformed values.
private fun callbackParameters(query: String): Map<String, String?> {
    val fields = mutableMapOf<String, String?>()
    for (pair in query.splitToSequence('&')) {
        when (val name = decodeField(pair.substringBefore('='))) {
            "state", "code", "iss", "error" -> {
                fields[name] = if (name in fields) null else decodeField(pair.substringAfter('=', ""))
            }
        }
    }

    return fields
}

/**
 * Installs OpenID Connect browser login for one provider. Register one [oidcLoginSession] in Ktor [Sessions]
 * and supply the provider and client registration through [OidcLoginConfig]. The client discovers the provider
 * on first use and caches successful discovery for the lifetime of the installation.
 *
 * Define a login route that redirects the browser to the URL from [beginOidcLogin] and a GET callback route
 * that calls [completeOidcLogin]. The callback owns account lookup, authorization, and the signed-in session.
 *
 * ```kotlin
 * install(Sessions) { oidcLoginSession(loginCookieTransformer) }
 * install(OidcLogin) {
 *     issuer = "https://id.example.com"
 *     clientId = "web-app"
 *     clientSecret = requireNotNull(System.getenv("OIDC_CLIENT_SECRET"))
 *     redirectUri = "https://app.example.com/auth/callback"
 * }
 * ```
 */
public val OidcLogin: ApplicationPlugin<OidcLoginConfig> =
    createApplicationPlugin("io.github.depthbomb.ktor.oidc", ::OidcLoginConfig) {
        application.attributes.put(clientKey, OidcClient(pluginConfig))
    }

/**
 * Starts a login for the current browser. Requires [OidcLogin] and a login session registered with [oidcLoginSession].
 * A fresh state, nonce, and PKCE verifier replace any previous attempt in that session. The attempt expires
 * after ten minutes. Send a redirect response so Ktor can commit the session cookie before the browser leaves.
 *
 * @return The provider's authorization URL, ready to pass to `call.respondRedirect`.
 * @throws java.io.IOException if discovery fails because of an HTTP or network error.
 * @throws IllegalArgumentException if the discovered issuer differs from the configured issuer or a required
 * provider endpoint is missing or uses an unsupported URL.
 */
public suspend fun ApplicationCall.beginOidcLogin(): String {
    val (pending, url) = application.attributes[clientKey].begin()
    sessions.set(pending)

    return url
}

/**
 * Completes the GET callback for a login started by [beginOidcLogin]. The callback must carry the matching
 * state and an authorization code in the query. If the provider advertises the `iss` response parameter,
 * it must be present. Any supplied `iss` must match the issuer saved for the login.
 *
 * The plugin exchanges the code using the saved PKCE verifier and checks the ID token's signature, issuer,
 * audience, validity dates, and nonce. When [OidcLoginConfig.fetchUserInfo] is enabled, it also checks the
 * UserInfo subject before returning an [OidcIdentity].
 *
 * An unrelated state leaves an unexpired attempt available. An expired attempt or a callback with matching
 * state marks the session for removal, including when the provider denies login. Ktor Sessions commits the
 * removal when the route or its error handler sends a response. Provider configuration, network, and session
 * storage failures propagate to the application.
 *
 * @return The verified identity, or `null` for a non-GET request, a missing or expired session, an invalid
 * callback, a denied or invalid grant, or identity claims that fail verification.
 * @throws java.io.IOException for transport failures, oversized provider responses, or malformed token response bodies.
 */
public suspend fun ApplicationCall.completeOidcLogin(): OidcIdentity? {
    if (request.httpMethod != HttpMethod.Get) {
        return null
    }

    val pending = sessions.get<PendingLogin>() ?: return null
    if (pending.expiresAt <= System.currentTimeMillis()) {
        sessions.clear<PendingLogin>()
        return null
    }

    val parameters = callbackParameters(request.queryString())
    if (parameters["state"] != pending.state) {
        return null
    }

    sessions.clear<PendingLogin>()
    val code = parameters["code"]
    if (code.isNullOrBlank() || "error" in parameters) {
        return null
    }

    val hasIssuer = "iss" in parameters
    if (hasIssuer && parameters["iss"] != pending.issuer) {
        return null
    }

    return application.attributes[clientKey].complete(pending, code, hasIssuer)
}
