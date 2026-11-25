package io.github.depthbomb.ktor.signedurls

import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SignedUrlTokensTest {
    @Test
    fun rejectsAtTheExactDeadline() {
        val start = Instant.parse("2026-07-01T12:00:00.123456789Z")
        var time = start
        val clock = object : Clock {
            override fun now() = time
        }
        val tokens = SignedUrlTokens(ByteArray(32), clock)
        val temporary = tokens.issue("/file", 30.seconds)
        val permanent = tokens.issue("/file", null)
        time = start + 30.seconds - 1.nanoseconds
        assertTrue(tokens.validate(temporary, "/file"))
        time += 1.nanoseconds
        assertFalse(tokens.validate(temporary, "/file"))
        time += 4.days
        assertFalse(tokens.validate(temporary, "/file"))
        assertTrue(tokens.validate(permanent, "/file"))
    }

    @Test
    fun tokensAuthenticateTheEnvelopeAndUseAFreshNonce() {
        val key = ByteArray(32)
        val tokens = SignedUrlTokens(key)
        val target = "/files/42?size=large"
        val token = tokens.issue(target, null)
        key.fill(1)
        assertTrue(tokens.validate(token, target))
        assertTrue(SignedUrlTokens(ByteArray(32)).validate(token, target))
        assertFalse(SignedUrlTokens(key).validate(token, target))
        assertFalse(tokens.validate(token, "/files/43?size=large"))
        assertNotEquals(token, tokens.issue(target, null))
        assertFalse(tokens.validate("$token=", target))

        val envelope = Base64.getUrlDecoder().decode(token)
        for (index in listOf(0, 1, 13, envelope.lastIndex)) {
            val changed = envelope.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            assertFalse(tokens.validate(Base64.getUrlEncoder().withoutPadding().encodeToString(changed), target))
        }
        assertTrue(tokens.validate(token, target))
    }

    @Test
    fun base64ValidationCoversEveryPossibleEnding() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val tokens = SignedUrlTokens(ByteArray(32))
        for (size in 1..3) {
            val target = "/" + "x".repeat(size)
            val token = tokens.issue(target, null)
            assertTrue(tokens.validate(token, target))
            assertFalse(tokens.validate(token + "=", target))
            if (token.length % 4 != 0) {
                val alternate = alphabet[alphabet.indexOf(token.last()) or 1]
                assertFalse(tokens.validate(token.dropLast(1) + alternate, target))
            }
        }
    }

    @Test
    fun overlappingCallsKeepTheirOwnCipherState() {
        val tokens = SignedUrlTokens(ByteArray(32))
        val start = CyclicBarrier(8)
        Executors.newFixedThreadPool(8).use { executor ->
            val jobs = List(8) { worker ->
                Callable {
                    start.await()
                    repeat(64) { iteration ->
                        val target = "/files/$worker?part=$iteration"
                        val token = tokens.issue(target, 30.seconds)
                        assertTrue(tokens.validate(token, target))
                        assertFalse(tokens.validate(token, "/"))
                    }
                }
            }
            executor.invokeAll(jobs).forEach { it.get() }
        }
    }
}
