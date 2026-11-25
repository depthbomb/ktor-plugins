package io.github.depthbomb.ktor.signedurls

import io.ktor.http.*
import kotlin.test.*

class SignedTargetTest {
    private fun target(uri: String) = parseTarget(uri, "sig")

    @Test
    fun queryEquivalencePreservesTheValuesSeenByTheRoute() {
        val signed = target("/a%2Fb/?q=caf%C3%A9+menu&x=1&x=2&flag&sig=abc")!!
        assertEquals("abc", signed.token)
        assertEquals(signed, target("/a%2Fb/?%73ig=abc&x=1&flag&q=café%20menu&x=%32"))
        for (change in listOf(
            "/a/b/?q=caf%C3%A9+menu&x=1&x=2&flag",
            "/a%2Fb?q=caf%C3%A9+menu&x=1&x=2&flag",
            "/a%2Fb/?q=caf%C3%A9%2Bmenu&x=1&x=2&flag",
            "/a%2Fb/?q=caf%C3%A9+menu&x=2&x=1&flag",
            "/a%2Fb/?q=caf%C3%A9+menu&x=1&x=2&flag=",
            "/a%2Fb/?q=caf%C3%A9+menu&x=1&x=2&flag&flag",
        )) {
            assertNotEquals(signed.canonical, target(change)!!.canonical)
        }
        assertNotEquals(target("/?a=1&A=2"), target("/?A=2&a=1"))
        assertNotEquals(target("/?a+b=1"), target("/?a%20b=1"))
        assertNotEquals(target("/?a+b=1"), target("/?a%2Bb=1"))
        assertEquals(target("/?a+b=1&x=2"), target("/?x=2&%61+b=1"))
        assertNotEquals(target("/?a+b=1&a%20b=2"), target("/?a%20b=2&a+b=1"))
        assertNull(parseTarget("/?a+b=one&a%20b=two", "a b"))
        assertNotEquals(target("/?a=x%26b%3Dy"), target("/?a=x&b=y"))
        assertEquals(target("/?a=x&%61=y"), target("/?a=x&a=y"))
    }

    @Test
    fun readsEveryFieldAndRejectsMalformedQueries() {
        val many = (1..1100).joinToString("&") { "n$it=$it" }
        val signed = target("/?$many&sig=ok")!!
        assertEquals("ok", signed.token)
        assertNotEquals(signed.canonical, target("/?$many&extra=1")!!.canonical)
        assertNull(target("/?sig=ok&$many&%73ig=ok"))
        for (malformed in listOf("sig", "sig=", "sig=ok&sig", "x=%", "x=%Q0", "x=%FF", "x=%C3")) {
            assertNull(target("/?$malformed"))
        }
        assertNotNull(target("/?x=%EF%BF%BD"))
    }

    @Test
    fun absoluteHttpTargetsUseTheEncodedPathAndQuery() {
        val relative = target("/files/a%2Fb?part=1&sig=ok")
        assertEquals(relative, target("https://files.example:8443/files/a%2Fb?sig=ok&part=1"))
        assertEquals(target("/?sig=ok"), target("http://files.example?sig=ok"))
        assertEquals(target("/?sig=ok"), parseTarget("/?%73%69%67=ok", "sig", requireToken = true))
        assertNull(target("https://files.example/path?sig=ok#section"))
        assertNull(target("http://"))
    }

    @Test
    fun builderAndWireAgreeOnEveryEncodedField() {
        val characters = (0..127).map(Int::toChar).joinToString("") + "\u00e9\uD83D\uDE80"
        val encoded = characters.encodeURLParameter()
        assertEquals("/?x=$encoded", target("/?x=$encoded")!!.canonical)

        val builders = listOf(
            URLBuilder().apply { parameters.appendAll("", emptyList()) },
            URLBuilder().apply { parameters.append(characters, characters) },
            URLBuilder("/path/").apply {
                encodedParameters.append("bare&name", "one&extra=two")
                encodedParameters.append("key=part", "x=y")
                encodedParameters.append("space+name", "%2B")
                encodedParameters.appendAll("flag", emptyList())
            },
        )
        for (builder in builders) {
            val canonical = builder.targetForSigning("sig")
            builder.encodedParameters.append("sig", "ok")
            val wire = buildString { appendUrlFullPath(builder.encodedPath, builder.encodedParameters, false) }
            val parsed = assertNotNull(parseTarget(wire, "sig", requireToken = true))
            assertEquals(canonical, parsed.canonical)
            assertEquals("ok", parsed.token)
        }
    }
}
