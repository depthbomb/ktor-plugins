# Abort

Use `call.abort` to stop immediately, `call.abortIf` when a condition is true, or `call.abortUnless` when it is false.
Each accepts an `Int` or an `HttpStatusCode`. When a call aborts, it throws `HttpException` with the supplied status
and optional message.

## Installation

Add the Abort dependency and import `io.github.depthbomb.ktor.abort.*` to use the extensions directly on `call`.
Add Ktor's `ktor-server-status-pages` dependency if you use the response handler below.

```kotlin
dependencies {
    implementation("io.github.depthbomb.ktor:abort:0.1.0")
    implementation("io.ktor:ktor-server-status-pages:3.6.2")
}
```

## Handle an abort

Handle `HttpException` in Ktor's `StatusPages` to choose the response body. This example uses the status description
when the caller omits the message.

```kotlin
install(StatusPages) {
    exception<HttpException> { call, error ->
        call.respondText(error.message ?: error.status.description, status = error.status)
    }
}

routing {
    get("/items/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: call.abort(400, "Expected a numeric item ID")
        call.abortUnless(id > 0, HttpStatusCode.BadRequest, "Expected a positive item ID")
        call.respondText("Item $id")
    }
}
```
