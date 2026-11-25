# CSRF tokens

Generate a token for an existing session and validate it before changing application state. Your application chooses
where the token goes in the frontend, where to check it, and what the user sees if the check fails.

## Installation

Add Maven Central to your repositories and add the plugin to your Ktor application.

```kotlin
dependencies {
    implementation("io.github.depthbomb.ktor:csrf:0.1.0")
}
```

## Render and validate a form

The example assumes that the application has already established a session. Pass in its session adapter and the
operation that an accepted submission should perform.

```kotlin
import io.github.depthbomb.ktor.csrf.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.preferencesForm(
    sessionAdapter: CsrfSessionAdapter,
    savePreferences: suspend (ApplicationCall) -> Unit,
) {
    install(CsrfTokens) { sessions = sessionAdapter }
    routing {
        get("/preferences") {
            val token = call.csrfToken()
            call.respondText(
                """<form method="post" action="/preferences">
                   <input type="hidden" name="csrf" value="$token">
                   <button>Save</button>
                   </form>""",
                ContentType.Text.Html,
            )
        }
        post("/preferences") {
            val candidate = call.receiveParameters()["csrf"]
            if (!call.validateCsrfToken(candidate)) {
                call.respondText("Form expired", status = HttpStatusCode.Forbidden)
                return@post
            }
            savePreferences(call)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
```

The default token uses 32 random bytes encoded as 43 unpadded Base64url characters. Tabs sharing a session can reuse it.
A JavaScript frontend can send the same value in a request header and the handler can pass that header to
`validateCsrfToken`.

## Connect your sessions

Implement `CsrfSessionAdapter` against the application's session store.

| Method                      | Contract                                                                                                                                                       |
|-----------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `read(call)`                | Return the token for the current session, or null if the session or token is missing. Reading must leave session state untouched.                              |
| `getOrCreate(call, create)` | Return the existing token or atomically store a new one from `create()`. Concurrent form renders must receive the same token. Fail if the session has expired. |

Establish an anonymous session before rendering a pre-login form. When login rotates the session, discard the previous
token with the old session. The integration test includes a working Ktor Sessions adapter, concurrent form renders, and
session rotation. [Session form example](src/test/kotlin/io/github/depthbomb/ktor/csrf/SessionFormTest.kt)

## Validation and customization

With the default strategy, missing tokens, malformed submissions, and tokens from another session return `false`.
Storage failures propagate so the application can handle an unavailable session backend separately from an expired form.

To use your own token format, set `strategy` in `install(CsrfTokens)` to a `CsrfTokenStrategy` implementing both
`generate` and `validate`. Both receive the current call. If the custom format contains characters with meaning in HTML,
escape the token when rendering it into a form.
