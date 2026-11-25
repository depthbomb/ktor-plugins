package io.github.depthbomb.ktor.signedurls

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.resources.*
import io.ktor.server.resources.Resources
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

@Resource("/folders/{folder}")
private class Folder(val folder: String) {
    @Resource("files/{id}")
    class File(val parent: Folder, val id: Int, val format: String)
}

@Resource("/{region}/{tenant}/files")
private class TenantFiles(val region: String, val tenant: String)

class SignedResourcesTest {
    @Test
    fun nestedResourceUsesTheSameValidatorAsABuilder() = testApplication {
        lateinit var relative: String
        lateinit var absolute: String
        engine {
            connector { host = "localhost"; port = 80 }
            connector { host = "downloads.example"; port = 443 }
        }
        application {
            install(Resources)
            install(SignedUrls) { key = ByteArray(32) }
            val file = Folder.File(Folder("shared docs"), 42, "full size")
            assertFailsWith<IllegalArgumentException> { signedHref(TenantFiles("", "")) }
            relative = signedHref(file, 4.days)
            val builder = URLBuilder("https://downloads.example")
            href(file, builder)
            absolute = builder.sign(this).buildString()
            routing {
                get<Folder.File> { resource ->
                    if (!call.validateSignedUrl()) {
                        call.respond(HttpStatusCode.Gone)
                        return@get
                    }
                    call.respondText("${resource.parent.folder}/${resource.id}/${resource.format}")
                }
            }
        }
        startApplication()

        assertTrue(relative.startsWith("/folders/shared%20docs/files/42?"))
        assertTrue(absolute.startsWith("https://downloads.example/"))
        assertEquals("shared docs/42/full size", client.get(relative).bodyAsText())
        assertEquals("shared docs/42/full size", client.get(absolute).bodyAsText())
        assertEquals(HttpStatusCode.Gone, client.get(relative.replace("/42", "/43")).status)
    }
}
