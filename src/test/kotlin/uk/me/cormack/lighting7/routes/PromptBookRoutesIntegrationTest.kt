package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.PromptBookRectDto
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-HTTP prompt-book lifecycle: the project's single book (create/replace/delete),
 * anchor upsert-by-cue, annotations, and the content-addressed script PDF store
 * (redirected to a temp dir).
 */
class PromptBookRoutesIntegrationTest {

    private lateinit var state: State
    private lateinit var scriptStoreRoot: Path
    private var projectId: Int = 0

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        scriptStoreRoot = Files.createTempDirectory("prompt-scripts-test-")
        state = State(testAppConfig("promptBooks.scriptStoreRoot" to scriptStoreRoot.toString()))
        projectId = seedMinimalProject(state)
        state.initializeShow()
        state.show.start()
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { scriptStoreRoot.toFile().deleteRecursively() }
    }

    private val hashA = "a".repeat(64)
    private fun rect(page: Int = 0, y: Double = 0.2) =
        PromptBookRectDto(page = page, x = 0.06, y = y, w = 0.88, h = 0.04)

    private fun newBook() =
        NewPromptBook(scriptHash = hashA, pageCount = 10, scriptFileName = "act-one.pdf")

    private fun bookUrl() = "/api/rest/project/$projectId/prompt-book"

    @Test
    fun `book create-replace-delete lifecycle`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // Create
        val createResp = client.put(bookUrl()) {
            contentType(ContentType.Application.Json)
            setBody(newBook())
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val created = createResp.body<PromptBookDetails>()
        assertEquals(hashA, created.scriptHash)
        assertTrue(created.canEdit)
        assertTrue(created.anchors.isEmpty())
        assertEquals(0, created.coverPages, "coverPages defaults to 0 when omitted")

        // GET returns the one book
        val fetched = client.get(bookUrl()).body<PromptBookDetails>()
        assertEquals(hashA, fetched.scriptHash)

        // A second PUT replaces the script in place — 200 (not 201), proving the
        // one-book-per-project invariant (no second row is created). Also sets a
        // cover-page count, which must persist through the round-trip.
        val newHash = "b".repeat(64)
        val putResp = client.put(bookUrl()) {
            contentType(ContentType.Application.Json)
            setBody(NewPromptBook(scriptHash = newHash, pageCount = 11, coverPages = 2))
        }
        assertEquals(HttpStatusCode.OK, putResp.status, putResp.bodyAsText())
        val updated = putResp.body<PromptBookDetails>()
        assertEquals(newHash, updated.scriptHash)
        assertEquals(11, updated.pageCount)
        assertEquals(2, updated.coverPages)
        assertEquals(2, client.get(bookUrl()).body<PromptBookDetails>().coverPages, "coverPages must survive GET")

        val deleteResp = client.delete(bookUrl())
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)
        assertEquals(HttpStatusCode.NotFound, client.get(bookUrl()).status)
    }

    @Test
    fun `create rejects malformed input`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val badHash = client.put(bookUrl()) {
            contentType(ContentType.Application.Json)
            setBody(NewPromptBook(scriptHash = "not-a-hash", pageCount = 5))
        }
        assertEquals(HttpStatusCode.BadRequest, badHash.status)

        val badPages = client.put(bookUrl()) {
            contentType(ContentType.Application.Json)
            setBody(NewPromptBook(scriptHash = hashA, pageCount = 0))
        }
        assertEquals(HttpStatusCode.BadRequest, badPages.status)

        // coverPages must leave at least one numbered page (0 <= coverPages < pageCount).
        val badCover = client.put(bookUrl()) {
            contentType(ContentType.Application.Json)
            setBody(NewPromptBook(scriptHash = hashA, pageCount = 5, coverPages = 5))
        }
        assertEquals(HttpStatusCode.BadRequest, badCover.status)
    }

    @Test
    fun `anchor upsert is keyed by cue and validates region`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        client.put(bookUrl()) {
            contentType(ContentType.Application.Json)
            setBody(newBook())
        }

        val stackId = client.post("/api/rest/project/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "stack-a"))
        }.body<CueStackDetails>().id
        val cueId = client.post("/api/rest/project/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "cue-1", cueStackId = stackId))
        }.body<CueDetails>().id

        val anchorUrl = "${bookUrl()}/anchors/$cueId"

        // Create
        val createResp = client.put(anchorUrl) {
            contentType(ContentType.Application.Json)
            setBody(UpsertAnchorRequest(region = listOf(rect()), label = "LX 1"))
        }
        assertEquals(HttpStatusCode.OK, createResp.status, createResp.bodyAsText())
        assertEquals("LX 1", createResp.body<PromptBookAnchorDto>().label)

        // Upsert same cue — still one anchor, region moved
        val moveResp = client.put(anchorUrl) {
            contentType(ContentType.Application.Json)
            setBody(UpsertAnchorRequest(region = listOf(rect(y = 0.6))))
        }
        assertEquals(HttpStatusCode.OK, moveResp.status)
        val book = client.get(bookUrl()).body<PromptBookDetails>()
        assertEquals(1, book.anchors.size, "upsert must not create a second anchor for the same cue")
        assertEquals(0.6, book.anchors[0].region[0].y)
        assertNull(book.anchors[0].label, "upsert without label must clear the cached label")

        // Invalid region → 400
        val badResp = client.put(anchorUrl) {
            contentType(ContentType.Application.Json)
            setBody(UpsertAnchorRequest(region = emptyList()))
        }
        assertEquals(HttpStatusCode.BadRequest, badResp.status)

        // Unknown cue → 404
        val unknownCue = client.put("${bookUrl()}/anchors/999999") {
            contentType(ContentType.Application.Json)
            setBody(UpsertAnchorRequest(region = listOf(rect())))
        }
        assertEquals(HttpStatusCode.NotFound, unknownCue.status)

        // Deleting the cue removes its anchor with it
        client.delete("/api/rest/project/$projectId/cues/$cueId")
        val afterCueDelete = client.get(bookUrl()).body<PromptBookDetails>()
        assertEquals(0, afterCueDelete.anchors.size, "cue deletion must take its anchor with it")
    }

    @Test
    fun `anchor upsert without a book is 404`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val stackId = client.post("/api/rest/project/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "stack-a"))
        }.body<CueStackDetails>().id
        val cueId = client.post("/api/rest/project/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "cue-1", cueStackId = stackId))
        }.body<CueDetails>().id

        val resp = client.put("${bookUrl()}/anchors/$cueId") {
            contentType(ContentType.Application.Json)
            setBody(UpsertAnchorRequest(region = listOf(rect())))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `annotation lifecycle and kind validation`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        client.put(bookUrl()) {
            contentType(ContentType.Application.Json)
            setBody(newBook())
        }

        val createResp = client.post("${bookUrl()}/annotations") {
            contentType(ContentType.Application.Json)
            setBody(AnnotationRequest(kind = "STRIKETHROUGH", region = listOf(rect(page = 1))))
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val annotation = createResp.body<PromptBookAnnotationDto>()

        val badKind = client.post("${bookUrl()}/annotations") {
            contentType(ContentType.Application.Json)
            setBody(AnnotationRequest(kind = "HIGHLIGHTER", region = listOf(rect())))
        }
        assertEquals(HttpStatusCode.BadRequest, badKind.status)

        val updateResp = client.put("${bookUrl()}/annotations/${annotation.id}") {
            contentType(ContentType.Application.Json)
            setBody(AnnotationRequest(kind = "NOTE", region = listOf(rect(page = 1)), text = "watch conductor"))
        }
        assertEquals(HttpStatusCode.OK, updateResp.status)
        assertEquals("watch conductor", updateResp.body<PromptBookAnnotationDto>().text)

        val deleteResp = client.delete("${bookUrl()}/annotations/${annotation.id}")
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)
        val book = client.get(bookUrl()).body<PromptBookDetails>()
        assertEquals(0, book.annotations.size)
    }

    @Test
    fun `script upload round-trips by content hash`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".toByteArray()
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(pdfBytes)
            .joinToString("") { "%02x".format(it) }

        val uploadResp = client.post("${bookUrl()}/scripts") {
            contentType(ContentType.Application.OctetStream)
            setBody(pdfBytes)
        }
        assertEquals(HttpStatusCode.Created, uploadResp.status, uploadResp.bodyAsText())
        val upload = uploadResp.body<ScriptUploadResponse>()
        assertEquals(expectedHash, upload.scriptHash)
        assertEquals(pdfBytes.size.toLong(), upload.sizeBytes)

        // Re-upload of identical bytes is idempotent
        val reupload = client.post("${bookUrl()}/scripts") {
            contentType(ContentType.Application.OctetStream)
            setBody(pdfBytes)
        }
        assertEquals(expectedHash, reupload.body<ScriptUploadResponse>().scriptHash)

        val downloadResp = client.get("${bookUrl()}/scripts/$expectedHash")
        assertEquals(HttpStatusCode.OK, downloadResp.status)
        assertContentEquals(pdfBytes, downloadResp.readRawBytes())
        assertTrue(
            downloadResp.headers["Cache-Control"]?.contains("immutable") == true,
            "content-addressed PDFs must be served immutable",
        )

        // Unknown hash → 404; malformed hash → 400
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("${bookUrl()}/scripts/${"c".repeat(64)}").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("${bookUrl()}/scripts/nope").status,
        )
    }

    @Test
    fun `script upload rejects non-PDF bytes`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val resp = client.post("${bookUrl()}/scripts") {
            contentType(ContentType.Application.OctetStream)
            setBody("just some text".toByteArray())
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `cue-locations reduces each anchor to its earliest rect`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        client.put(bookUrl()) {
            contentType(ContentType.Application.Json)
            setBody(newBook())
        }

        val stackId = client.post("/api/rest/project/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "stack-a"))
        }.body<CueStackDetails>().id
        val cue1 = client.post("/api/rest/project/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "cue-1", cueStackId = stackId))
        }.body<CueDetails>().id
        val cue2 = client.post("/api/rest/project/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "cue-2", cueStackId = stackId))
        }.body<CueDetails>().id

        // cue1: single rect near the top of page 0
        client.put("${bookUrl()}/anchors/$cue1") {
            contentType(ContentType.Application.Json)
            setBody(UpsertAnchorRequest(region = listOf(rect(page = 0, y = 0.1))))
        }
        // cue2: a multi-rect region (spans pages + lines). The earliest rect —
        // lowest page, then topmost y — must win, regardless of list order.
        client.put("${bookUrl()}/anchors/$cue2") {
            contentType(ContentType.Application.Json)
            setBody(
                UpsertAnchorRequest(
                    region = listOf(rect(page = 2, y = 0.5), rect(page = 1, y = 0.8), rect(page = 1, y = 0.3)),
                ),
            )
        }

        val locations = client.get("/api/rest/project/$projectId/cue-locations")
            .body<List<CueLocationDto>>()
        val byCue = locations.associateBy { it.cueId }
        assertEquals(2, locations.size)
        assertEquals(0, byCue.getValue(cue1).page)
        assertEquals(0.1, byCue.getValue(cue1).y)
        assertEquals(1, byCue.getValue(cue2).page, "earliest rect is on the lowest page")
        assertEquals(0.3, byCue.getValue(cue2).y, "and the topmost y on that page")
    }

    @Test
    fun `cue-locations is empty when the project has no book`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val locations = client.get("/api/rest/project/$projectId/cue-locations")
            .body<List<CueLocationDto>>()
        assertTrue(locations.isEmpty())
    }
}
