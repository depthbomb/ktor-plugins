# Signed URLs

Generate links such as `/files/42?format=pdf&sig=...` and check them in the routes that accept them.
The path and query stay visible. The token carries an encrypted copy of the target and its optional expiration.

## Installation

Add Maven Central to your repositories and add the plugin to your Ktor application.

```kotlin
dependencies {
    implementation("io.github.depthbomb.ktor:signedurls:0.1.0")
}
```

Generate a random 32-byte secret once and give every instance of your application the same key. For example,
store the Base64 output of `openssl rand -base64 32` in the application's secret store. Replacing the key invalidates
every link issued with the old key.

## Generate and validate a link

```kotlin
import io.github.depthbomb.ktor.signedurls.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

fun Application.fileRoutes(encodedKey: String) {
    install(SignedUrls) {
        key = Base64.getDecoder().decode(encodedKey)
    }
    routing {
        get("/files/{id}") {
            if (!call.validateSignedUrl()) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            call.respondText("File " + call.pathParameters["id"])
        }
    }
}

fun Application.downloadLink(id: Long): String {
    val builder = URLBuilder("https://downloads.example.com")
    builder.path("files", id.toString())
    builder.parameters.append("format", "pdf")
    return builder.sign(this, expiresIn = 30.seconds).buildString()
}
```

Call `sign` on an HTTP or HTTPS builder after setting the path and all query parameters. It updates the builder and
returns it. Omit `expiresIn` for a link without a deadline. When set, the duration must be positive and finite. The
route
gets `false` as soon as the deadline is reached, or if the token is missing, duplicated, or invalid.

The default token parameter is `sig`. Set `parameterName` in `install(SignedUrls)` if your application already uses that
name. Signing a builder that contains the reserved parameter fails before changing it.

## Ktor Resources

Applications using `ktor-server-resources` can generate a relative link directly from a resource. The application needs
the Kotlin serialization plugin and `install(Resources)`. The helper rejects a resource path starting with `//`, which a
browser would interpret as a hostname.

```kotlin
import io.github.depthbomb.ktor.signedurls.*
import io.ktor.http.*
import io.ktor.resources.Resource
import io.ktor.server.resources.href
import kotlin.time.Duration.Companion.days

@Resource("/files/{id}")
class FileDownload(val id: Int, val format: String)

val file = FileDownload(42, "pdf")
val relative = application.signedHref(file, expiresIn = 4.days)

val builder = URLBuilder("https://downloads.example.com")
application.href(file, builder)
val absolute = builder.sign(application, expiresIn = 4.days).buildString()
```

Use the same `call.validateSignedUrl()` check in a `get<FileDownload>` handler. Applications using only `URLBuilder` and
regular routes need just the signed URL plugin. [Ktor Resources](https://ktor.io/docs/server-resources.html)

## What a link covers

- The encoded path includes path parameter values, segment boundaries, and any trailing slash.
- Validation accepts both an ordinary request path and an absolute HTTP request target sent by a proxy.
- All ordinary query fields participate. Distinct names can change order, and equivalent query encodings match.
  Repeated values retain their order. Adding a parameter or changing `?flag` to `?flag=` invalidates the link. In query
  values, `+` and `%20` both mean a space. In names, keep a literal `+` as written because server engines
  differ in how they read it.
- Validation reads the complete request target. For very long queries, the server engine can expose fewer fields
  through `call.request.queryParameters` than are present in the signed URL.
- The scheme, host, port, HTTP method, and fragment are outside the signed target. Anyone holding a valid link
  can reuse it on routes sharing the key and the same path and query.
