@file:OptIn(ExperimentalContracts::class)

package io.github.depthbomb.ktor.abort

import io.ktor.http.*
import io.ktor.server.application.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Signals an HTTP outcome from a call handler. Throwing this exception leaves the call's response untouched.
 * An application can catch it in Ktor's `StatusPages` plugin and use [status] and [message] to build the response.
 *
 * @constructor Creates an exception carrying [status] and the optional [message].
 * @param status HTTP status for the application's exception handler.
 * @param message The detail exposed through [Throwable.message]. Omitting it leaves the message null.
 */
public class HttpException public constructor(
    /** The supplied HTTP status, including its original description. */
    public val status: HttpStatusCode,
    message: String? = null,
) : RuntimeException(message)

/**
 * Throws an [HttpException] immediately. Its [Nothing] return type allows use in expressions such as
 * `val id = call.parameters["id"] ?: call.abort(400, "Missing id")`.
 *
 * @param status Numeric HTTP status. Ktor supplies the description for known codes and its default
 * description for unknown codes. The numeric value is preserved.
 * @param message Optional exception detail, passed through unchanged. Defaults to null.
 * @throws HttpException On every call. The application's exception handler chooses the response.
 */
public fun ApplicationCall.abort(status: Int, message: String? = null): Nothing =
    throw HttpException(HttpStatusCode.fromValue(status), message)

/**
 * Throws an [HttpException] immediately. Its [Nothing] return type allows use wherever a value is expected.
 *
 * @param status HTTP status to carry on the exception. A custom description is preserved.
 * @param message Optional exception detail, passed through unchanged. Defaults to null.
 * @throws HttpException On every call. The application's exception handler chooses the response.
 */
public fun ApplicationCall.abort(status: HttpStatusCode, message: String? = null): Nothing =
    throw HttpException(status, message)

/**
 * Aborts the current call when [condition] is true. On normal return, Kotlin can infer that [condition] is false.
 *
 * @param condition Whether to throw. A false value lets the route continue.
 * @param status Numeric HTTP status to resolve through Ktor when aborting. Unknown codes retain their value.
 * @param message Optional detail for the exception. Defaults to null.
 * @throws HttpException When [condition] is true, carrying the resolved status and [message].
 */
public fun ApplicationCall.abortIf(condition: Boolean, status: Int, message: String? = null) {
    contract { returns() implies (!condition) }
    if (condition) {
        throw HttpException(HttpStatusCode.fromValue(status), message)
    }
}

/**
 * Aborts the current call when [condition] is true. On normal return, Kotlin can infer that [condition] is false.
 *
 * @param condition Whether to throw. A false value lets the route continue.
 * @param status HTTP status to carry on the exception, including any custom description.
 * @param message Optional detail for the exception. Defaults to null.
 * @throws HttpException When [condition] is true, carrying [status] and [message].
 */
public fun ApplicationCall.abortIf(condition: Boolean, status: HttpStatusCode, message: String? = null) {
    contract { returns() implies (!condition) }
    if (condition) {
        throw HttpException(status, message)
    }
}

/**
 * Aborts the current call when [condition] is false. On normal return, Kotlin can infer that [condition] is true.
 *
 * @param condition Requirement for continuing the route.
 * @param status Numeric HTTP status to resolve through Ktor when aborting. Unknown codes retain their value.
 * @param message Optional detail for the exception. Defaults to null.
 * @throws HttpException When [condition] is false, carrying the resolved status and [message].
 */
public fun ApplicationCall.abortUnless(condition: Boolean, status: Int, message: String? = null) {
    contract { returns() implies condition }
    if (!condition) {
        throw HttpException(HttpStatusCode.fromValue(status), message)
    }
}

/**
 * Aborts the current call when [condition] is false. On normal return, Kotlin can infer that [condition] is true.
 *
 * @param condition Requirement for continuing the route.
 * @param status HTTP status to carry on the exception, including any custom description.
 * @param message Optional detail for the exception. Defaults to null.
 * @throws HttpException When [condition] is false, carrying [status] and [message].
 */
public fun ApplicationCall.abortUnless(condition: Boolean, status: HttpStatusCode, message: String? = null) {
    contract { returns() implies condition }
    if (!condition) {
        throw HttpException(status, message)
    }
}
