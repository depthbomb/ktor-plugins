package io.github.depthbomb.ktor.oidc

import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable

internal const val LOGIN_SECONDS = 600

@Serializable
internal class PendingLogin(
    val state: String,
    val nonce: String,
    val verifier: String,
    val expiresAt: Long,
    val issuer: String,
    val clientId: String,
    val redirectUri: String,
)

private fun CookieSessionBuilder<PendingLogin>.configureLoginCookie(configure: CookieConfiguration.() -> Unit) {
    sendOnlyIfModified = true
    cookie.path = "/"
    cookie.httpOnly = true
    cookie.secure = true
    cookie.maxAgeInSeconds = LOGIN_SECONDS.toLong()
    cookie.extensions["SameSite"] = "Lax"
    cookie.configure()
}

/**
 * Registers a browser cookie that holds the encrypted, authenticated state, nonce, and PKCE verifier for
 * [beginOidcLogin] and [completeOidcLogin]. Use this overload when the application also has an ID-backed
 * session. All instances that can receive the callback must share the same encryption and signing keys.
 *
 * @param transformer Ktor cookie transformer initialized with independent secret encryption and signing keys.
 * @param name Cookie and session provider name. Defaults to `oidc_login`. Choose a name distinct from the
 * application's signed-in session.
 * @param configureCookie Overrides applied after setting path `/`, Secure, HttpOnly, SameSite=Lax, and a
 * ten-minute maximum age. The cookie path must cover both login routes. The attempt itself expires after
 * ten minutes even if the browser keeps the cookie longer.
 * @throws IllegalArgumentException if [name] is already registered or a login session has already been registered.
 */
public fun SessionsConfig.oidcLoginSession(
    transformer: SessionTransportTransformerEncrypt,
    name: String = "oidc_login",
    configureCookie: CookieConfiguration.() -> Unit = {},
) {
    cookie<PendingLogin>(name) {
        transform(transformer)
        configureLoginCookie(configureCookie)
    }
}

/**
 * Registers a server-side login session for [beginOidcLogin] and [completeOidcLogin]. Ktor keeps the pending
 * transaction in [storage] and sends an opaque session ID in the browser cookie. Use this overload when the
 * login session is the application's only ID-backed Ktor session.
 *
 * @param storage Store accessible to every instance that can handle the callback. Configure expiry in the
 * store to reclaim abandoned transactions. The plugin checks each attempt's ten-minute deadline on callback.
 * @param name Cookie and session provider name. Defaults to `oidc_login`. Choose a name distinct from the
 * application's signed-in session.
 * @param configureCookie Overrides applied after setting path `/`, Secure, HttpOnly, SameSite=Lax, and a
 * ten-minute maximum age. The cookie path must cover both login routes.
 * @throws IllegalArgumentException if [name] is already registered or a login session has already been registered.
 */
public fun SessionsConfig.oidcLoginSession(
    storage: SessionStorage,
    name: String = "oidc_login",
    configureCookie: CookieConfiguration.() -> Unit = {},
) {
    cookie<PendingLogin>(name, storage) {
        configureLoginCookie(configureCookie)
    }
}
