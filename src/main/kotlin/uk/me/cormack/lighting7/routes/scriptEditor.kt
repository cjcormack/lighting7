package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.scripts.EditorDiagnostic
import uk.me.cormack.lighting7.scripts.ScriptEditorService
import uk.me.cormack.lighting7.scripts.ScriptType
import uk.me.cormack.lighting7.state.State
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The script editor's language services, served from this app's own embedded Kotlin compiler.
 *
 * These routes replace a reverse proxy to a bundled `kotlin-compiler-server` fork — a second JVM
 * on port 8321 that cost ~122 MB of installer and, since upstream retired its own in-process
 * completion, only ever answered `/highlight`. See [ScriptEditorService] for why serving it here
 * is also *more* accurate.
 *
 * The URL shape below the mount point is dictated by the `kotlin-playground` widget, which owns
 * the whole protocol client-side and cannot be reconfigured beyond its base URL:
 *
 * - `GET  /versions`                       — **must** succeed. The widget fetches this once per
 *   page and, on any failure, silently drops *every* editor on the page to read-only with
 *   highlighting off. It is the single most load-bearing route here.
 * - `POST /api/{version}/compiler/highlight`
 * - `POST /api/{version}/compiler/complete?line=&ch=`
 * - `POST /api/{version}/compiler/run`     — the widget's own Run button, which the frontend hides.
 *
 * `{version}` is echoed back by the widget from `/versions` and is deliberately ignored: there is
 * exactly one compiler here, the one this app is running.
 *
 * Mounted at `/api/script-editor`, inside the gated `/api` subtree in [configureRouting] — so the
 * warm-up gate and the auth gate both cover it with no second install site. It compiles arbitrary
 * Kotlin on a LAN-reachable port, so an ungated editor would make the `/api/rest` gate decorative.
 * The widget's base URL is set in `lighting-react/src/kotlinScript/component.mjs`; the two must
 * agree, and a mismatch is silent — see `/versions` above.
 */
internal fun Route.routeScriptEditor(state: State) {
    val service = ScriptEditorService(state.scriptingHostConfiguration)

    route("/script-editor") {
        get("/versions") {
            call.respond(listOf(CompilerVersion(KotlinVersion.CURRENT.toString(), latestStable = true)))
        }

        post("/api/{version}/compiler/{action}") {
            val request = call.receive<PlaygroundProject>()
            val source = request.files.firstOrNull()?.text.orEmpty()
            val document = EditorDocument.of(source)

            when (call.parameters["action"]) {
                "highlight" -> {
                    val diagnostics = service.analyse(document.userScript, document.scriptType)
                    call.respond(mapOf(PLAYGROUND_FILE_NAME to diagnostics.map { document.toWire(it) }))
                }

                "complete" -> {
                    val line = call.request.queryParameters["line"]?.toIntOrNull()
                    val ch = call.request.queryParameters["ch"]?.toIntOrNull()
                    if (line == null || ch == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("line and ch are required"))
                        return@post
                    }
                    // The widget speaks CodeMirror: 0-based line, 0-based column, positioned in the
                    // full wrapped document. The compiler speaks 1-based line and column, in the
                    // user's own script.
                    val completions = service.complete(
                        userScript = document.userScript,
                        scriptType = document.scriptType,
                        line = document.toUserLine(line),
                        col = ch + 1,
                    )
                    call.respond(completions.map { CompletionItem(it.text, it.displayText, it.tail, it.icon) })
                }

                // The editor widget ships its own Run button which the frontend hides, because the
                // app's own Run (POST /{projectId}/scripts/run) is the path that actually runs a
                // script against the show. Answer in the widget's own result shape so that if the
                // button ever comes back it explains itself rather than hanging.
                "run" -> call.respond(
                    ExecutionResult(
                        text = "",
                        exception = ExceptionDescriptor(
                            message = "Run is not available from the editor — use the Run button beside it.",
                            fullName = "java.lang.UnsupportedOperationException",
                        ),
                    ),
                )

                else -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Unknown compiler action"))
            }
        }
    }
}

/**
 * The editor sends one synthetic file, and what the frontend writes is not what arrives here. It
 * puts a `//@lighting7-script-type=` line above the body and wraps the body itself in
 * `//sampleStart` / `//sampleEnd`. The widget uses the fold markers to decide what to show and then
 * **strips them**, posting `everythingBeforeSampleStart + editorContents + everythingAfterSampleEnd`.
 * So the live shape is the marker line followed by the user's body, and it lands on the no-marker
 * path below: the whole document is the user's script, one line of it a comment.
 *
 * The marker-bearing shape is still handled, for a client that hands its own text through
 * unaltered. There only the body between the markers is compiled — never a stand-in class the
 * frontend invented for the widget to display, which is how the two definitions of the DSL are
 * kept from drifting apart.
 *
 * [prefixLines] is how many lines precede the body, and is what converts between the widget's
 * coordinate space and the compiler's.
 */
private data class EditorDocument(
    val userScript: String,
    val scriptType: ScriptType,
    val prefixLines: Int,
) {
    /** Widget line (0-based, whole document) to user script line (1-based). */
    fun toUserLine(documentLine: Int): Int = maxOf(1, documentLine - prefixLines + 1)

    /** User script position (1-based) back to the widget's (0-based, whole document). */
    private fun toDocumentLine(userLine: Int): Int = userLine - 1 + prefixLines

    fun toWire(diagnostic: EditorDiagnostic) = HighlightDescriptor(
        interval = TextInterval(
            start = TextPosition(toDocumentLine(diagnostic.startLine), diagnostic.startCol - 1),
            end = TextPosition(toDocumentLine(diagnostic.endLine), diagnostic.endCol - 1),
        ),
        message = diagnostic.message,
        severity = diagnostic.severity.toWire(),
        className = diagnostic.severity.toClassName(),
    )

    companion object {
        private const val SCRIPT_TYPE_MARKER = "//@lighting7-script-type="
        private const val SAMPLE_START = "//sampleStart"
        private const val SAMPLE_END = "//sampleEnd"

        /**
         * The fold markers are absent from everything the widget posts, so the no-marker path is
         * the normal one rather than the fallback it looks like. A document with no script-type
         * marker either — a bare playground pointed at this desk, say — is taken at face value as a
         * GENERAL script, which degrades to slightly-wrong completions rather than to an error.
         */
        fun of(source: String): EditorDocument {
            val lines = source.lines()
            val start = lines.indexOfFirst { it.trim() == SAMPLE_START }

            // The search is restricted to the prefix when there is one, so a line the user typed
            // cannot name the template. In the shape the widget actually posts there is no prefix
            // to restrict it to, and what protects the choice is ordering instead: the frontend's
            // marker is line 1 and the *first* recognised one wins, so a
            // `//@lighting7-script-type=FX_DEFINITION` pasted in from documentation is ignored.
            // (An unrecognised name falls through rather than matching — a frontend ahead of this
            // backend on a new script type is the one case where a pasted line could still be
            // adopted.)
            val prefix = if (start >= 0) lines.subList(0, start) else lines
            val scriptType = prefix.firstNotNullOfOrNull { line ->
                line.trim().removePrefixOrNull(SCRIPT_TYPE_MARKER)?.let { name ->
                    ScriptType.entries.firstOrNull { it.name == name.trim() }
                }
            } ?: ScriptType.GENERAL

            if (start < 0) return EditorDocument(source, scriptType, prefixLines = 0)
            val end = lines.indexOfLast { it.trim() == SAMPLE_END }.takeIf { it > start } ?: lines.size

            return EditorDocument(
                userScript = lines.subList(start + 1, end).joinToString("\n"),
                scriptType = scriptType,
                prefixLines = start + 1,
            )
        }

        private fun String.removePrefixOrNull(prefix: String) =
            if (startsWith(prefix)) removePrefix(prefix) else null
    }
}

private fun ScriptDiagnostic.Severity.toWire() = when (this) {
    ScriptDiagnostic.Severity.ERROR, ScriptDiagnostic.Severity.FATAL -> "ERROR"
    ScriptDiagnostic.Severity.WARNING -> "WARNING"
    ScriptDiagnostic.Severity.INFO, ScriptDiagnostic.Severity.DEBUG -> "INFO"
}

/** The two underline styles the editor's stylesheet actually defines. */
private fun ScriptDiagnostic.Severity.toClassName() = when (this) {
    ScriptDiagnostic.Severity.ERROR, ScriptDiagnostic.Severity.FATAL -> "red_wavy_line"
    else -> "green_wavy_line"
}

private const val PLAYGROUND_FILE_NAME = "File.kt"

@Serializable
private data class PlaygroundProject(
    val args: String = "",
    val files: List<PlaygroundFile> = emptyList(),
    val confType: String = "java",
)

@Serializable
private data class PlaygroundFile(val name: String = "", val text: String = "", val publicId: String = "")

@Serializable
private data class CompilerVersion(val version: String, val latestStable: Boolean)

@Serializable
private data class TextPosition(val line: Int, val ch: Int)

@Serializable
private data class TextInterval(val start: TextPosition, val end: TextPosition)

@Serializable
private data class HighlightDescriptor(
    val interval: TextInterval,
    val message: String,
    val severity: String,
    val className: String,
)

@Serializable
private data class CompletionItem(
    val text: String,
    val displayText: String,
    val tail: String,
    val icon: String,
)

@Serializable
private data class ExceptionDescriptor(
    val message: String,
    val fullName: String,
    val stackTrace: List<String> = emptyList(),
    val cause: String? = null,
)

@Serializable
private data class ExecutionResult(
    val text: String,
    val errors: Map<String, List<HighlightDescriptor>> = emptyMap(),
    val exception: ExceptionDescriptor? = null,
)
