package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire contract with the `kotlin-playground` widget, which owns the whole protocol client-side
 * and cannot be reconfigured beyond its base URL.
 *
 * The documents here are the ones the widget really posts, which is **not** the text the frontend
 * hands it. `lighting-react`'s `wrapForEditor` wraps the body in `//sampleStart` / `//sampleEnd`,
 * and the widget splits on those markers and then drops them: it keeps `prefix` as the text before
 * `//sampleStart` and `suffix` as the text after `//sampleEnd`, and every request body is
 * `prefix + editorContents + suffix`. So what arrives here is the script-type marker line followed
 * by the user's body, and `EditorDocument` takes its no-marker path — the whole document is the
 * user's script, at a one-line offset.
 *
 * Positions are the widget's: 0-based line and column, in the document as posted. The widget then
 * subtracts the newlines in its `prefix` — [MARKER_LINES] of them — before drawing anything, which
 * is why the round-trip is asserted rather than just the raw line.
 */
class ScriptEditorRouteTest : RouteIntegrationTest() {

    /**
     * What the widget sends. Mirrors `wrapForEditor` in lighting-react's `ScriptEditor.tsx` with
     * the fold markers removed, exactly as the widget removes them. If that function grows a
     * header again, this is the test that should notice.
     */
    private fun asSent(userScript: String, scriptType: String = "GENERAL") =
        "//@lighting7-script-type=$scriptType\n$userScript"

    @Test
    fun `highlight reports errors where the widget will draw them`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // Two user lines; the error is on the second.
        val response = client.post("/script-editor/api/2.4.10/compiler/highlight") {
            contentType(ContentType.Application.Json)
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", asSent("val ok = 1\nval bad: Int = \"nope\"")))))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val diagnostics = response.body<Map<String, List<HighlightDto>>>()
        val errors = diagnostics.getValue("File.kt").filter { it.severity == "ERROR" }
        assertEquals(1, errors.size, "expected exactly one error, got $errors")

        val error = errors.single()
        // The marker occupies document line 0, so the user's second line is document line 2 …
        assertEquals(2, error.interval.start.line)
        // … and the widget subtracts its prefix to land on the second line of the editor.
        assertEquals(1, error.interval.start.line - MARKER_LINES, "would be drawn on the wrong line")
        assertEquals("red_wavy_line", error.className)
        assertTrue(error.interval.end.line >= error.interval.start.line)
    }

    @Test
    fun `highlight on valid code returns no errors`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/script-editor/api/2.4.10/compiler/highlight") {
            contentType(ContentType.Application.Json)
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", asSent("val level = 255")))))
        }

        val diagnostics = response.body<Map<String, List<HighlightDto>>>().getValue("File.kt")
        assertTrue(diagnostics.none { it.severity == "ERROR" }, "unexpected errors: $diagnostics")
    }

    @Test
    fun `complete resolves the show DSL at the widget's cursor position`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val userScript = "show."
        // Cursor on the first line of the editor: the widget adds its prefix back before asking.
        val line = 0 + MARKER_LINES
        val ch = userScript.length

        val response = client.post("/script-editor/api/2.4.10/compiler/complete?line=$line&ch=$ch") {
            contentType(ContentType.Application.Json)
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", asSent(userScript)))))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val completions = response.body<List<CompletionDto>>()
        assertTrue(
            completions.any { it.displayText == "fxEngine" },
            "expected Lighting7 members, got ${completions.take(10).map { it.displayText }}",
        )
    }

    @Test
    fun `complete without a cursor is a bad request rather than an empty list`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/script-editor/api/2.4.10/compiler/complete") {
            contentType(ContentType.Application.Json)
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", asSent("show.")))))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    /**
     * The marker is what tells the backend which of the six templates to compile against. A
     * document without one falls back to GENERAL rather than failing — but an FX template's
     * symbols only resolve when it is present, which is what this asserts.
     */
    @Test
    fun `the script-type marker selects the template`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // `params` is an FxCalcScript constructor property; it does not exist on LightingScript.
        val response = client.post("/script-editor/api/2.4.10/compiler/highlight") {
            contentType(ContentType.Application.Json)
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", asSent("params", "FX_CALC")))))
        }

        val diagnostics = response.body<Map<String, List<HighlightDto>>>().getValue("File.kt")
        assertTrue(
            diagnostics.none { it.severity == "ERROR" && it.message.contains("params") },
            "`params` should resolve under FX_CALC — got $diagnostics",
        )
    }

    /**
     * A line in the user's own body that looks like a marker — pasted from documentation, say —
     * must not redirect the compile at a different base class. In the shape the widget sends there
     * is no prefix to restrict the search to, so what protects this is ordering: the frontend's
     * marker is the document's first line and the first recognised one wins.
     */
    @Test
    fun `a marker inside the user's script cannot override the declared template`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // Declared GENERAL on line 1; the body claims FX_CALC. `fixtures` is public on
        // LightingScript and absent from FxCalcScript, so it resolving proves the first marker won.
        val response = client.post("/script-editor/api/2.4.10/compiler/highlight") {
            contentType(ContentType.Application.Json)
            setBody(
                PlaygroundProjectDto(
                    files = listOf(
                        PlaygroundFileDto("File.kt", asSent("//@lighting7-script-type=FX_CALC\nval f = fixtures")),
                    ),
                ),
            )
        }

        val diagnostics = response.body<Map<String, List<HighlightDto>>>().getValue("File.kt")
        assertTrue(
            diagnostics.none { it.severity == "ERROR" },
            "`fixtures` should resolve under the leading GENERAL marker — got $diagnostics",
        )
    }

    /**
     * The fallback shape: a document that still carries the fold markers. The widget never sends
     * one — it strips them — but the route accepts it, and a bare playground pointed at this desk
     * or a frontend that hands its own text straight through would produce it. Here the body is
     * everything between the markers, and positions are relative to the whole document, so the
     * offset is the number of lines up to and including `//sampleStart`.
     */
    @Test
    fun `a document that still carries the fold markers is read as body only`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val document = "//@lighting7-script-type=GENERAL\nfun x() {\n//sampleStart\nval ok = 1\nval bad: Int = \"nope\"\n//sampleEnd\n}\n"
        val response = client.post("/script-editor/api/2.4.10/compiler/highlight") {
            contentType(ContentType.Application.Json)
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", document))))
        }

        val errors = response.body<Map<String, List<HighlightDto>>>().getValue("File.kt")
            .filter { it.severity == "ERROR" }
        assertEquals(1, errors.size, "expected exactly one error, got $errors")
        // Three lines precede the body, so its second line is document line 4.
        assertEquals(4, errors.single().interval.start.line)
    }

    @Test
    fun `run explains itself rather than executing anything`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/script-editor/api/2.4.10/compiler/run") {
            contentType(ContentType.Application.Json)
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", asSent("val x = 1")))))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body<ExecutionResultDto>().exception!!.message.contains("Run button"))
    }
}

/** The frontend's marker line, which the widget keeps as its `prefix` and subtracts client-side. */
private const val MARKER_LINES = 1


@Serializable
private data class PlaygroundProjectDto(
    val args: String = "",
    val files: List<PlaygroundFileDto> = emptyList(),
    val confType: String = "java",
)

@Serializable
private data class PlaygroundFileDto(val name: String, val text: String, val publicId: String = "")

@Serializable
private data class PositionDto(val line: Int, val ch: Int)

@Serializable
private data class IntervalDto(val start: PositionDto, val end: PositionDto)

@Serializable
private data class HighlightDto(
    val interval: IntervalDto,
    val message: String,
    val severity: String,
    val className: String,
)

@Serializable
private data class CompletionDto(
    val text: String,
    val displayText: String,
    val tail: String,
    val icon: String,
)

@Serializable
private data class ExceptionDto(val message: String, val fullName: String)

@Serializable
private data class ExecutionResultDto(val text: String, val exception: ExceptionDto? = null)
