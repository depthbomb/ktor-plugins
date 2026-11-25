package io.github.depthbomb.ktor.signedurls

import java.security.SecureRandom
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

private const val FORMAT_VERSION: Byte = 1
private const val NONCE_BYTES = 12
private const val TAG_BYTES = 16
private const val HEADER_BYTES = 1 + NONCE_BYTES
private val context = "io.github.depthbomb.ktor.signedurls/v1".encodeToByteArray()
private val random = SecureRandom()
private val encoder = Base64.getUrlEncoder().withoutPadding()
private val decoder = Base64.getUrlDecoder()
private const val BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

internal class SignedUrlTokens(key: ByteArray, private val clock: Clock = Clock.System) {
    init {
        require(key.size == 32) { "Signed URLs require a 32-byte AES key" }
    }

    private val secret = SecretKeySpec(key, "AES")
    private val encryptor = AtomicReference<Cipher?>()
    private val decryptor = AtomicReference<Cipher?>()

    fun issue(target: String, expiresIn: Duration?): String {
        require(expiresIn == null || (expiresIn.isFinite() && expiresIn.isPositive())) {
            "expiresIn must be a positive finite duration"
        }
        val deadline = expiresIn?.let { clock.now() + it }
        val plaintext = "${deadline ?: ""}\n$target".encodeToByteArray()
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val envelope = withCipher(Cipher.ENCRYPT_MODE, GCMParameterSpec(TAG_BYTES * 8, nonce)) { cipher ->
            val output = ByteArray(HEADER_BYTES + cipher.getOutputSize(plaintext.size))
            output[0] = FORMAT_VERSION
            nonce.copyInto(output, 1)
            val size = HEADER_BYTES + cipher.doFinal(plaintext, 0, plaintext.size, output, HEADER_BYTES)
            if (size == output.size) output else output.copyOf(size)
        }
        return encoder.encodeToString(envelope)
    }

    fun validate(token: String, target: String): Boolean {
        if (token.isEmpty() || !hasCanonicalEnding(token)) {
            return false
        }

        val envelope = try {
            decoder.decode(token)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (envelope.size <= HEADER_BYTES + TAG_BYTES || envelope[0] != FORMAT_VERSION) {
            return false
        }

        val plaintext = try {
            withCipher(Cipher.DECRYPT_MODE, GCMParameterSpec(TAG_BYTES * 8, envelope, 1, NONCE_BYTES)) { cipher ->
                cipher.doFinal(envelope, HEADER_BYTES, envelope.size - HEADER_BYTES)
            }
        } catch (_: AEADBadTagException) {
            return false
        }

        val payload = plaintext.decodeToString()
        val separator = payload.indexOf('\n')
        if (separator < 0 || payload.length - separator - 1 != target.length ||
            !payload.regionMatches(separator + 1, target, 0, target.length)
        ) {
            return false
        }

        if (separator == 0) {
            return true
        }

        val deadline = Instant.parseOrNull(payload.substring(0, separator)) ?: return false
        return clock.now() < deadline
    }

    // Keep one idle cipher per direction and application. Overlapping calls get separate instances.
    private inline fun <T> withCipher(mode: Int, parameters: GCMParameterSpec, operation: (Cipher) -> T): T {
        val idle = if (mode == Cipher.ENCRYPT_MODE) encryptor else decryptor
        val cipher = idle.getAndSet(null) ?: Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, secret, parameters)
        cipher.updateAAD(context)

        val result = operation(cipher)

        idle.compareAndSet(null, cipher)

        return result
    }
}

// The JDK decoder validates the alphabet, but accepts padding and nonzero unused bits in the last digit.
private fun hasCanonicalEnding(token: String): Boolean {
    val digit = BASE64URL.indexOf(token.last())
    if (digit < 0) {
        return false
    }

    return when (token.length % 4) {
        0 -> true
        2 -> digit and 15 == 0
        3 -> digit and 3 == 0
        else -> false
    }
}
