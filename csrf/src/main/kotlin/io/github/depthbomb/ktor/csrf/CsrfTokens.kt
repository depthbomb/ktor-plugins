package io.github.depthbomb.ktor.csrf

import io.ktor.server.application.*
import io.ktor.util.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

/**
 * Connects [CsrfTokens] to the application's session storage. Bind the token to the current
 * session and discard it when the session expires or rotates. Propagate storage errors
 * so the application can distinguish an unavailable backend from an invalid token.
 */
public interface CsrfSessionAdapter {
    /**
     * Returns the token stored for the session associated with [call], or null if the session or token is missing.
     * Reading must leave session state untouched.
     */
    public suspend fun read(call: ApplicationCall): String?

    /**
     * Returns the token for the existing session associated with [call], using [create] to generate a token
     * if needed. Store and return one winner when several requests race to create it.
     * Fail if the session is missing or has expired.
     */
    public suspend fun getOrCreate(call: ApplicationCall, create: () -> String): String
}

/**
 * Defines token generation and validation as a pair. A custom strategy must accept the
 * format it generates and reject malformed input. [CsrfSessionAdapter] handles persistence.
 */
public interface CsrfTokenStrategy {
    /** Creates an unpredictable token for [call]. The session adapter stores the returned value. */
    public fun generate(call: ApplicationCall): String

    /**
     * Returns whether [presented] is valid for [call] given the session's stored token [expected].
     * Treat malformed or mismatched input as an ordinary validation failure.
     */
    public fun validate(call: ApplicationCall, expected: String, presented: String): Boolean
}

/**
 * Default strategy using 32 cryptographically random bytes encoded as 43 unpadded Base64url
 * characters. Validation requires canonical encoding and uses [MessageDigest.isEqual] for
 * the token comparison.
 */
public object SecureCsrfTokens : CsrfTokenStrategy {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val sextets = IntArray(256) { -1 }.apply {
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".forEachIndexed { index, symbol ->
            this[symbol.code] = index
        }
    }

    /** Generates a fresh Base64url token using [SecureRandom]. */
    override fun generate(call: ApplicationCall): String =
        encoder.encodeToString(ByteArray(32).also(random::nextBytes))

    /**
     * Returns true when [presented] is a canonical token that matches [expected]. Rejects incorrect
     * length, padding, characters outside the URL-safe alphabet, and nonzero unused bits.
     */
    override fun validate(call: ApplicationCall, expected: String, presented: String): Boolean {
        if (presented.length != 43 || expected.length != 43) {
            return false
        }

        val candidate = presented.toByteArray(Charsets.US_ASCII)
        // An ASCII encoding replacement is '?', which is outside the token alphabet.
        for (byte in candidate) {
            if (sextets[byte.toInt()] < 0) {
                return false
            }
        }

        // A 32-byte token leaves two unused bits in the final Base64 sextet.
        if (sextets[candidate.last().toInt()] and 3 != 0) {
            return false
        }

        // The candidate is canonical. If the stored bytes match, they must be canonical too.
        return MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), candidate)
    }
}

/** Installation settings for [CsrfTokens]. Supply the session adapter before installation completes. */
public class CsrfTokensConfig {
    /** Required adapter for reading and atomically initializing a token in the current session. */
    public lateinit var sessions: CsrfSessionAdapter

    /** Token generator and matching validator. Defaults to [SecureCsrfTokens]. */
    public var strategy: CsrfTokenStrategy = SecureCsrfTokens
}

private data class Service(val sessions: CsrfSessionAdapter, val strategy: CsrfTokenStrategy)

private val serviceKey = AttributeKey<Service>("io.github.depthbomb.ktor.csrf.service")

/**
 * Installs session-bound token services. Establish a session before calling [csrfToken] to
 * render a form. Pass the submitted value to [validateCsrfToken] before performing the protected
 * action. The application chooses how to transport the token and respond to a failed check.
 */
public val CsrfTokens: ApplicationPlugin<CsrfTokensConfig> =
    createApplicationPlugin("io.github.depthbomb.ktor.csrf", ::CsrfTokensConfig) {
        application.attributes.put(serviceKey, Service(pluginConfig.sessions, pluginConfig.strategy))
    }

/**
 * Returns this session's token, creating it atomically on the first request that needs one.
 * Install [CsrfTokens] and establish the session first. Session and generation failures
 * propagate to the caller.
 */
public suspend fun ApplicationCall.csrfToken(): String {
    val service = application.attributes[serviceKey]
    return service.sessions.getOrCreate(this) { service.strategy.generate(this) }
}

/**
 * Checks [candidate] against the current session using the configured strategy. Returns false
 * for a null candidate, a missing stored token, or a failed comparison. Validation only reads
 * session state. Storage and strategy exceptions propagate to the caller.
 */
public suspend fun ApplicationCall.validateCsrfToken(candidate: String?): Boolean {
    val service = application.attributes[serviceKey]
    if (candidate == null) {
        return false
    }

    val expected = service.sessions.read(this) ?: return false
    return service.strategy.validate(this, expected, candidate)
}
