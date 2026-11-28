package uk.me.cormack.lighting7.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.promptbook.ScriptTextStore
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.HeldExecutor
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `GET /projects/{id}/prompt-book/text` in each of its states (prompt-book navigation plan §3). */
class PromptBookTextRouteTest {

    private lateinit var state: State
    private lateinit var scriptStoreRoot: Path
    private val builds = HeldExecutor()
    private var projectId: Int = 0

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        scriptStoreRoot = Files.createTempDirectory("prompt-scripts-test-")
        state = State(testAppConfig("promptBooks.scriptStoreRoot" to scriptStoreRoot.toString()))
        state.scriptText = ScriptTextStore(state::promptScriptPath, builds)
        projectId = seedMinimalProject(state)
        state.initializeShow()
        state.show.start()
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { scriptStoreRoot.toFile().deleteRecursively() }
    }

    private fun bookUrl() = "/api/rest/projects/$projectId/prompt-book"

    private val typedPdf: ByteArray by lazy {
        checkNotNull(javaClass.getResourceAsStream("/promptBook/typed-script.pdf")).readBytes()
    }

    @Test
    fun `a project with no prompt book has no text`() = testApplication {
        mountTestApp(state)
        assertEquals(HttpStatusCode.NotFound, jsonClient().get("${bookUrl()}/text").status)
    }

    @Test
    fun `an imported script is building until it is read, then ready and revalidated by its hash`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val upload = client.post("${bookUrl()}/scripts") {
            contentType(ContentType.Application.OctetStream)
            setBody(typedPdf)
        }
        assertEquals(HttpStatusCode.Created, upload.status)
        assertEquals(1, builds.queued.size, "the import starts the build")
        val hash = Json.parseToJsonElement(upload.bodyAsText()).jsonObject["scriptHash"]!!.jsonPrimitive.content
        client.put(bookUrl()) {
            contentType(ContentType.Application.Json)
            setBody(NewPromptBook(scriptHash = hash, pageCount = 3))
        }

        val building = client.get("${bookUrl()}/text")
        assertEquals(HttpStatusCode.Accepted, building.status)
        assertEquals("BUILDING", Json.parseToJsonElement(building.bodyAsText()).jsonObject["state"]!!.jsonPrimitive.content)
        assertNotNull(building.headers[HttpHeaders.RetryAfter])
        assertEquals(1, builds.queued.size, "asking again queues nothing more")

        builds.runAll()
        val ready = client.get("${bookUrl()}/text")
        assertEquals(HttpStatusCode.OK, ready.status)
        val body = Json.parseToJsonElement(ready.bodyAsText()).jsonObject
        assertEquals("READY", body["state"]!!.jsonPrimitive.content)
        val index = body["index"]!!.jsonObject
        assertEquals(3, index["pageCount"]!!.jsonPrimitive.int)
        assertEquals(hash, index["scriptHash"]!!.jsonPrimitive.content)
        assertTrue(index["pages"]!!.jsonArray[0].jsonObject["lines"]!!.jsonArray.isNotEmpty())

        val etag = assertNotNull(ready.headers[HttpHeaders.ETag])
        val again = client.get("${bookUrl()}/text") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, again.status)
        val listed = client.get("${bookUrl()}/text") { header(HttpHeaders.IfNoneMatch, "\"other\", W/$etag") }
        assertEquals(HttpStatusCode.NotModified, listed.status, "a list naming it weakly")
        val stale = client.get("${bookUrl()}/text") { header(HttpHeaders.IfNoneMatch, "\"other\"") }
        assertEquals(HttpStatusCode.OK, stale.status)
    }

    @Test
    fun `a book whose PDF is not on this desk says so`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.put(bookUrl()) {
            contentType(ContentType.Application.Json)
            setBody(NewPromptBook(scriptHash = "e".repeat(64), pageCount = 3))
        }
        val missing = client.get("${bookUrl()}/text")
        assertEquals(HttpStatusCode.OK, missing.status)
        val body = Json.parseToJsonElement(missing.bodyAsText()).jsonObject
        assertEquals("SCRIPT_MISSING", body["state"]!!.jsonPrimitive.content)
        assertTrue("index" !in body, "never an empty index")
        assertTrue(builds.queued.isEmpty())
    }

    @Test
    fun `an index whose PDF has left the store is removed when the project loads`() {
        val uuid = transaction(state.database) { DaoProject.findById(projectId)!!.uuid.toString() }
        val kept = state.promptScriptPath(uuid, "1".repeat(64))
        Files.createDirectories(kept.parent)
        Files.write(kept, typedPdf)
        val keptIndex = state.scriptText.indexPath(uuid, "1".repeat(64))
        val orphan = state.scriptText.indexPath(uuid, "2".repeat(64))
        Files.writeString(keptIndex, "{}")
        Files.writeString(orphan, "{}")

        runBlocking { state.projectManager.switchProject(projectId, reload = true) }

        assertTrue(keptIndex.exists())
        assertFalse(orphan.exists())
    }
}
