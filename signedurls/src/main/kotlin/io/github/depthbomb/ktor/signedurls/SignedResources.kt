package io.github.depthbomb.ktor.signedurls

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import kotlin.time.Duration

/**
 * Builds a signed relative link with the installed [Resources] serializer. For example,
 * `application.signedHref(Files.ById(id = 42), expiresIn = 4.days)`.
 * For an absolute URL, call [href] with your own [URLBuilder] and then call [sign] on it.
 */
public inline fun <reified T : Any> Application.signedHref(resource: T, expiresIn: Duration? = null): String {
    val builder = URLBuilder()
    href(resource, builder)
    require(!builder.encodedPath.startsWith("//")) { "A relative resource link cannot start with //" }

    return builder.sign(this, expiresIn).build().fullPath
}
