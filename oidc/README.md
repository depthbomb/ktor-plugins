# OpenID Connect

Start login in one route and complete it in another. The login route redirects to the provider. The callback gets a
verified identity and the provider's JSON UserInfo before your application decides whether to sign the user in.

## Installation

Add Maven Central to your repositories and add the plugin to your Ktor application.

```kotlin
dependencies {
    implementation("io.github.depthbomb.ktor:oidc:0.1.0")
}
```

## Login and callback routes

Apply the Kotlin serialization plugin (`kotlin("plugin.serialization")`) at your Kotlin version so Ktor can serialize
the example's `UserSession` cookie.

```kotlin
import io.github.depthbomb.ktor.oidc.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import java.io.IOException

@Serializable
data class UserSession(val accountId: String)

fun Application.configureLogin(
    loginCookie: SessionTransportTransformerEncrypt,
    userStorage: SessionStorage,
    findAccount: suspend (OidcIdentity) -> String?,
) {
    install(Sessions) {
        oidcLoginSession(loginCookie)
        cookie<UserSession>("app_user", userStorage) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = true
            cookie.extensions["SameSite"] = "Lax"
        }
    }
    install(OidcLogin) {
        issuer = System.getenv("OIDC_ISSUER")
        clientId = System.getenv("OIDC_CLIENT_ID")
        clientSecret = System.getenv("OIDC_CLIENT_SECRET")
        redirectUri = "https://app.example.com/auth/callback"
    }
    routing {
        get("/auth/login") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondRedirect(call.beginOidcLogin())
        }
        get("/auth/callback") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("Referrer-Policy", "no-referrer")
            val identity = try {
                call.completeOidcLogin()
            } catch (_: IOException) {
                return@get call.respond(HttpStatusCode.BadGateway)
            } ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val accountId = findAccount(identity) ?: return@get call.respond(HttpStatusCode.Forbidden)
            val existing = call.sessions.get<UserSession>()
            if (existing != null && existing.accountId != accountId) {
                return@get call.respond(HttpStatusCode.Conflict, "Sign out before switching accounts")
            }
            call.sessions.set(UserSession(accountId))
            call.respondRedirect("/")
        }
    }
}
```

Use `identity.issuer` and `identity.subject` together to look up an account. In the callback or in `findAccount`,
`identity.userInfo` contains the provider's JSON claims, including custom fields such as
`identity.userInfo?.get("groups")`.
The plugin checks that UserInfo and the signed ID token refer to the same subject. To skip the UserInfo request, set
`fetchUserInfo = false`. The default scopes are `openid`, `profile`, and `email`.

Register the exact callback URL with the provider and copy the issuer from its discovery document, including any
trailing slash. This version supports confidential clients, GET callbacks, S256 PKCE, RS256 ID tokens, and JSON
UserInfo. The configured client must be the ID token's sole audience. Configure the provider's final endpoint URLs
directly. Discovery, token, UserInfo, and signing-key requests reject HTTP redirects. Signing-key responses can be up
to 50 KiB, and the other responses can be up to 1 MiB.

## Login sessions

Ktor Sessions keeps the state, nonce, and PKCE verifier in a ten-minute login cookie. Starting another login replaces
it.
Construct `SessionTransportTransformerEncrypt(encryptionKey, signingKey)` with two independent secret keys (32 random
bytes each) and share them across app instances. The default cookie is Secure, HttpOnly, and SameSite=Lax.
For loopback HTTP development, use `oidcLoginSession(loginCookie) { secure = false }` and adjust the app session cookie
too.

If login is the application's only ID-backed session, you can use `oidcLoginSession(storage)` instead. Ktor then stores
the transaction server-side and puts an opaque ID in the cookie. Use shared storage with expiry for abandoned logins.

The example keeps an existing application session bound to its account. If your application supports switching
accounts or elevating privileges during login, rotate the session ID when making that change.

A denied, expired, or invalid login returns `null`. Provider, network, and storage failures propagate to the
application. Send an error response from the route or a Ktor error handler such as StatusPages so Sessions can
commit the pending cookie deletion. An engine-generated error response can bypass that step.
