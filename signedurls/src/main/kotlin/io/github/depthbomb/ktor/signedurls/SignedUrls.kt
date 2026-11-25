package io.github.depthbomb.ktor.signedurls

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import kotlin.time.Duration

/** Installation settings for [SignedUrls]. */
public class SignedUrlsConfig {
    /** A 32-byte random AES key shared by application instances. Replacing it invalidates existing links. */
    public lateinit var key: ByteArray

    /** Query parameter reserved for the encrypted token. Defaults to `sig`. */
    public var parameterName: String = "sig"
}

private class Service(val parameterName: String, val tokens: SignedUrlTokens) {
    val encodedParameterName = parameterName.encodeURLParameter()

    init {
        require(encodedParameterName.decodeURLQueryComponent() == parameterName) {
            "The signed URL parameter name must be valid Unicode"
        }
    }
}

private val serviceKey = AttributeKey<Service>("io.github.depthbomb.ktor.signedurls.service")

/**
 * Installs signed link generation and opt-in request validation. The path and ordinary query values
 * remain visible. Anyone holding the link can reuse it across HTTP methods and hosts sharing the key.
 *
 * ```kotlin
 * install(SignedUrls) { key = signingKey }
 * val link = URLBuilder("https://example.com/files/42").sign(this, 30.seconds).buildString()
 *
 * routing {
 *     get("/files/{id}") {
 *         if (!call.validateSignedUrl()) {
 *             call.respond(HttpStatusCode.Forbidden)
 *             return@get
 *         }
 *         call.respondText("File " + call.pathParameters["id"])
 *     }
 * }
 * ```
 */
public val SignedUrls: ApplicationPlugin<SignedUrlsConfig> =
    createApplicationPlugin("io.github.depthbomb.ktor.signedurls", ::SignedUrlsConfig) {
        require(pluginConfig.parameterName.isNotBlank()) { "A signed URL parameter name is required" }
        application.attributes.put(serviceKey, Service(pluginConfig.parameterName, SignedUrlTokens(pluginConfig.key)))
    }

/**
 * Appends a signed token and returns this HTTP or HTTPS builder. Set the path and ordinary parameters before signing.
 * An existing reserved parameter or an [expiresIn] other than null or a positive finite duration
 * causes an [IllegalArgumentException]. Omit [expiresIn] for a reusable link without a deadline.
 */
public fun URLBuilder.sign(application: Application, expiresIn: Duration? = null): URLBuilder {
    val service = application.attributes[serviceKey]
    val target = targetForSigning(service.parameterName)

    encodedParameters.append(service.encodedParameterName, service.tokens.issue(target, expiresIn))

    return this
}

/**
 * Checks this request's path, query and optional expiration. Returns false for a missing, invalid,
 * or expired token. The route chooses how to respond. Install [SignedUrls] before calling this helper.
 */
public fun ApplicationCall.validateSignedUrl(): Boolean {
    val service = application.attributes[serviceKey]
    val target = parseTarget(request.uri, service.parameterName, requireToken = true) ?: return false
    val token = target.token ?: return false
    return service.tokens.validate(token, target.canonical)
}
