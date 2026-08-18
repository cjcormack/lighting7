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
 * The wire contract with the `kotlin-playground` widget, which owns the whole protocol
 * client-side and cannot be reconfigured beyond its base URL. These use the **real** wrapper text
 * the frontend sends, markers and all, because the parsing of that wrapper is the seam where the
 * two repos meet.
 *
 * Positions here are the widget's: 0-based line and column, in the whole wrapped document.
 */
class ScriptEditorRouteTest : RouteIntegrationTest() {

    // Verbatim from SCRIPT_WRAPPERS.GENERAL in lighting-react's ScriptEditor.tsx. If that file
    // changes shape, this test should be the thing that notices.
    private val generalPrefix = """
        //@lighting7-script-type=GENERAL
        import uk.me.cormack.lighting7.fixture.*
        import uk.me.cormack.lighting7.show.*
        import uk.me.cormack.lighting7.scripts.*
        import kotlinx.coroutines.*

        class TestScript(
            show: Show,
            fixtures: Fixtures.FixturesWithTransaction,
            fxEngine: FxEngine,
            scriptName: String,
            step: Int,
            coroutineScope: CoroutineScope,
        ): LightingScript(show, fixtures, fxEngine, scriptName, step, coroutineScope) {}

        fun TestScript.test() {
        //sampleStart
    """.trimIndent() + "\n"

    private val generalSuffix = "\n//sampleEnd\n}\n"

    /** Lines of prefix before the user's first line — what the widget subtracts client-side. */
    private val prefixLines = generalPrefix.count { it == '\n' }

    private fun wrap(userScript: String) = generalPrefix + userScript + generalSuffix

    @Test
    fun `highlight reports errors against the wrapped document the widget sent`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // Two user lines; the error is on the second.
        val response = client.post("/script-editor/api/2.4.10/compiler/highlight") {
            contentType(ContentType.Application.Json)
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", wrap("val ok = 1\nval bad: Int = \"nope\"")))))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val diagnostics = response.body<Map<String, List<HighlightDto>>>()
        val errors = diagnostics.getValue("File.kt").filter { it.severity == "ERROR" }
        assertEquals(1, errors.size, "expected exactly one error, got $errors")

        val error = errors.single()
        // User line 2 (1-based) → document line prefixLines + 1 (0-based).
        assertEquals(prefixLines + 1, error.interval.start.line)
        assertEquals("red_wavy_line", error.className)
        assertTrue(error.interval.end.line >= error.interval.start.line)
    }

    @Test
    fun `highlight on valid code returns no errors`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/script-editor/api/2.4.10/compiler/highlight") {
            contentType(ContentType.Application.Json)
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", wrap("val level = 255")))))
        }

        val diagnostics = response.body<Map<String, List<HighlightDto>>>().getValue("File.kt")
        assertTrue(diagnostics.none { it.severity == "ERROR" }, "unexpected errors: $diagnostics")
    }

    @Test
    fun `complete resolves the show DSL at the widget's cursor position`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val userScript = "show."
        // The widget sends the cursor in document coordinates: 0-based line, 0-based column.
        val line = prefixLines
        val ch = userScript.length

        val response = client.post("/script-editor/api/2.4.10/compiler/complete?line=$line&ch=$ch") {
            contentType(ContentType.Application.Json)
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", wrap(userScript)))))
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
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", wrap("show.")))))
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
        val fxCalcDocument = "//@lighting7-script-type=FX_CALC\nfun x() {\n//sampleStart\nparams\n//sampleEnd\n}\n"
        val response = client.post("/script-editor/api/2.4.10/compiler/highlight") {
            contentType(ContentType.Application.Json)
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", fxCalcDocument))))
        }

        val diagnostics = response.body<Map<String, List<HighlightDto>>>().getValue("File.kt")
        assertTrue(
            diagnostics.none { it.severity == "ERROR" && it.message.contains("params") },
            "`params` should resolve under FX_CALC — got $diagnostics",
        )
    }

    /**
     * The marker is read from the prefix only. A line in the user's own body that looks like one —
     * pasted from documentation, say — must not be able to redirect the compile at a different
     * base class.
     */
    @Test
    fun `a marker inside the user's script cannot override the declared template`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // Declared GENERAL in the prefix; the body claims FX_CALC. `fixtures` is public on
        // LightingScript and absent from FxCalcScript, so it resolving proves the prefix won.
        // (`show` would not do — it is deliberately `private val` on the base class.)
        val response = client.post("/script-editor/api/2.4.10/compiler/highlight") {
            contentType(ContentType.Application.Json)
            setBody(
                PlaygroundProjectDto(
                    files = listOf(
                        PlaygroundFileDto("File.kt", wrap("//@lighting7-script-type=FX_CALC\nval f = fixtures")),
                    ),
                ),
            )
        }

        val diagnostics = response.body<Map<String, List<HighlightDto>>>().getValue("File.kt")
        assertTrue(
            diagnostics.none { it.severity == "ERROR" },
            "`show` should resolve under the prefix's GENERAL template — got $diagnostics",
        )
    }

    @Test
    fun `run explains itself rather than executing anything`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/script-editor/api/2.4.10/compiler/run") {
            contentType(ContentType.Application.Json)
            setBody(PlaygroundProjectDto(files = listOf(PlaygroundFileDto("File.kt", wrap("val x = 1")))))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body<ExecutionResultDto>().exception!!.message.contains("Run button"))
    }
}

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
