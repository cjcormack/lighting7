package uk.me.cormack.lighting7.routes

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.mcp.publicModule
import uk.me.cormack.lighting7.moduleWithState
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val INDEX = "<!doctype html>\n<html lang=\"en\">\n  <head>\n    <meta charset=\"UTF-8\" />\n  </head>\n  <body><div id=\"root\"></div></body>\n</html>\n"
private const val SCRIPT = "console.log('</head>')\n"

private val bundle: File by lazy {
    Files.createTempDirectory("spa-page").toFile().also { dir ->
        dir.resolve("index.html").writeText(INDEX)
        dir.resolve("assets").mkdirs()
        dir.resolve("assets/app.js").writeText(SCRIPT)
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    }
}

private fun ApplicationTestBuilder.mountDesk(state: uk.me.cormack.lighting7.state.State) {
    environment { config = testAppConfig() }
    application { moduleWithState(state) }
}

private fun ApplicationTestBuilder.mountPublic(state: uk.me.cormack.lighting7.state.State) {
    environment { config = testAppConfig() }
    application { publicModule(state) }
}

private fun HttpResponse.framePolicy(): String? = headers["Content-Security-Policy"]

/** The bundle's listener-specific touches (cloud-p1 plan D9): the remote-parent meta and the frame policy. */
class SpaPageTest : RouteIntegrationTest() {
    override val extraConfig = listOf(
        "frontend.staticPath" to bundle.absolutePath,
        "cloud.baseUrl" to "https://cloud.example.test/base",
    )

    @Test
    fun `the public listener's index names the cloud's origin, on every path the index answers`() = testApplication {
        mountPublic(state)
        for (path in listOf("/", "/index.html", "/projects/1/busk")) {
            val page = client.get(path)
            assertEquals(HttpStatusCode.OK, page.status, path)
            val html = page.bodyAsText()
            assertTrue(
                html.contains("<head><meta name=\"lighting7-remote-parent\" content=\"https://cloud.example.test\" />"),
                "$path: $html",
            )
            assertEquals(1, Regex("lighting7-remote-parent").findAll(html).count(), path)
            assertNull(page.framePolicy(), path)
        }
    }

    @Test
    fun `the public listener leaves every other file alone`() = testApplication {
        mountPublic(state)
        val script = client.get("/assets/app.js")
        assertEquals(SCRIPT, script.bodyAsText())
        assertNull(script.framePolicy())
    }

    @Test
    fun `the desk's own API answers carry no frame policy`() = testApplication {
        mountDesk(state)
        assertNull(client.get("/api/rest/status").framePolicy())
    }

    @Test
    fun `the desk's own listener carries no meta, and no page but its own may frame it`() = testApplication {
        mountDesk(state)
        val page = client.get("/")
        assertEquals(INDEX, page.bodyAsText())
        assertEquals("frame-ancestors 'self'", page.framePolicy())
        assertEquals("frame-ancestors 'self'", client.get("/projects/1/busk").framePolicy())
        assertEquals("frame-ancestors 'self'", client.get("/assets/app.js").framePolicy())
    }
}

class SpaPageWithoutCloudTest : RouteIntegrationTest() {
    override val extraConfig = listOf("frontend.staticPath" to bundle.absolutePath)

    @Test
    fun `with no cloud configured the public listener's index is the bundle's`() = testApplication {
        mountPublic(state)
        assertEquals(INDEX, client.get("/").bodyAsText())
    }
}

class SpaPageCloudOffTest : RouteIntegrationTest() {
    override val extraConfig = listOf(
        "frontend.staticPath" to bundle.absolutePath,
        "cloud.baseUrl" to "https://cloud.example.test",
        "cloud.enabled" to "false",
    )

    @Test
    fun `with the cloud turned off the public listener's index is the bundle's`() = testApplication {
        mountPublic(state)
        assertEquals(INDEX, client.get("/").bodyAsText())
    }
}

class WithRemoteParentTest {
    @Test
    fun `the meta goes first in the head, whatever the head's attributes, and is escaped`() {
        assertEquals(
            "<html><head data-x=\"1\"><meta name=\"lighting7-remote-parent\" content=\"https://a.test\" /><title>t</title></head></html>",
            withRemoteParent("<html><head data-x=\"1\"><title>t</title></head></html>", "https://a.test"),
        )
        assertTrue(withRemoteParent("<HEAD>", "https://a\"b").contains("content=\"https://a&quot;b\""))
        assertFalse(withRemoteParent("<header></header>", "https://a.test").contains("lighting7-remote-parent"))
    }
}
