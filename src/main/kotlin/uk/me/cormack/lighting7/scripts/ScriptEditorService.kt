package uk.me.cormack.lighting7.scripts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.kotlin.scripting.ide_services.compiler.KJvmReplCompilerWithIdeServices
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.script.experimental.api.ReplAnalyzerResult
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.SourceCodeCompletionVariant
import kotlin.script.experimental.api.analysisDiagnostics
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration

/** A diagnostic in the coordinate space of the user's own script text (1-based lines and columns). */
data class EditorDiagnostic(
    val severity: ScriptDiagnostic.Severity,
    val message: String,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
)

/** A completion candidate, already in the shape the editor widget renders. */
data class EditorCompletion(
    val text: String,
    val displayText: String,
    val tail: String,
    val icon: String,
)

/**
 * Code analysis and completion for the script editor, served from this app's own embedded Kotlin
 * compiler.
 *
 * This replaces the bundled `kotlin-compiler-server` fork — a second 100 MB JVM whose completion
 * endpoint had in fact been a `return emptyList()` stub since upstream commit `85c80df1` and was
 * deleted outright shortly after, so the editor has had no working autocomplete at all. Serving it
 * here is both smaller and strictly more accurate: the templates in [ScriptSourceWrapper] are the
 * real ones, and `dependenciesFromCurrentContext(wholeClasspath = true)` resolves against the
 * running app rather than a staged jar that can be a build behind.
 *
 * ### Why a new compiler per request
 *
 * [KJvmReplCompilerWithIdeServices] is a *REPL* compiler and keeps snippet history. Reusing one
 * instance across unrelated snippets corrupts its analysis state: the first call succeeds and
 * every later one fails with `Failed to analyze declaration … NoDescriptorForDeclarationException`.
 * Each request therefore gets a fresh one. That costs roughly 0.2-0.6 s, dominated by the
 * classpath scan.
 *
 * ### Why it is serialised
 *
 * The desk drives DMX from this same JVM, and the editor asks for highlighting on every pause in
 * typing and completions on nearly every keystroke. All of that work runs on **one** dedicated
 * below-normal-priority daemon thread, so no amount of typing can spawn more than one concurrent
 * compiler. Requests that are superseded while queued are dropped rather than run — with a
 * fresh compiler per request there is no value in answering a keystroke the user has already
 * typed past.
 *
 * A wedged compile keeps its thread — a call with no suspension points cannot be cancelled. What
 * [TIMEOUT_MS] bounds is how long anything *else* waits for it; see [serialised]. Containment
 * rather than cancellation, and the thread it wedges is never the one driving the rig.
 */
class ScriptEditorService(
    private val hostConfiguration: ScriptingHostConfiguration = defaultJvmScriptingHostConfiguration,
) {
    private val logger = LoggerFactory.getLogger(ScriptEditorService::class.java)

    private val mutex = Mutex()

    /**
     * One counter per script type rather than one for the whole process. With a single counter,
     * a keystroke in *any* editor superseded a queued request from *every* other one — two people
     * (or two tabs) typing at once could starve each other of highlighting indefinitely, each
     * request cancelled by the other's.
     *
     * Two editors of the *same* type in different tabs still share a slot: nothing in the widget's
     * protocol identifies which editor a request came from, so there is no key that separates
     * them. That case degrades to "one of them gets its answer on the next keystroke", which is
     * what the debounce would do anyway.
     */
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    /** Diagnostics for [userScript], compiled against [scriptType]'s real template. */
    suspend fun analyse(userScript: String, scriptType: ScriptType): List<EditorDiagnostic> =
        serialised(scriptType, emptyList()) {
            val wrapped = ScriptSourceWrapper.wrap(userScript, scriptType)
            val result = KJvmReplCompilerWithIdeServices(hostConfiguration).analyze(
                wrapped.text.toScriptSource(sourceName(scriptType)),
                SourceCode.Position(1, 1),
                ScriptSourceWrapper.compilationConfiguration(scriptType),
            )
            val diagnostics = result.valueOrNull()?.get(ReplAnalyzerResult.analysisDiagnostics)
                ?: return@serialised emptyList()

            // The wrapper's footer is real compilable text, so the compiler can and does report
            // against it — an unbalanced `(` is blamed on the closing `}` a line past the user's
            // last. Left unclamped that maps onto the editor's hidden suffix and the squiggle
            // simply never appears, which reads as "no error" for a script that does not compile.
            val lastUserLine = maxOf(1, userScript.lines().size)
            diagnostics.mapNotNull { it.toEditorDiagnostic(wrapped.lineOffset, lastUserLine) }.toList()
        }

    /**
     * Completions at [line]/[col] (both 1-based, in the user's own coordinate space) for
     * [userScript] compiled against [scriptType]'s real template.
     */
    suspend fun complete(
        userScript: String,
        scriptType: ScriptType,
        line: Int,
        col: Int,
    ): List<EditorCompletion> = serialised(scriptType, emptyList()) {
        val wrapped = ScriptSourceWrapper.wrap(userScript, scriptType)
        val result = KJvmReplCompilerWithIdeServices(hostConfiguration).complete(
            wrapped.text.toScriptSource(sourceName(scriptType)),
            SourceCode.Position(line + wrapped.lineOffset, col),
            ScriptSourceWrapper.compilationConfiguration(scriptType),
        )
        result.valueOrNull()?.map { it.toEditorCompletion() }?.toList().orEmpty()
    }

    private fun ScriptDiagnostic.toEditorDiagnostic(lineOffset: Int, lastUserLine: Int): EditorDiagnostic? {
        if (severity == ScriptDiagnostic.Severity.DEBUG) return null
        val location = location ?: return null
        val end = location.end ?: location.start
        return EditorDiagnostic(
            severity = severity,
            message = message,
            // Clamped at both ends: below, a diagnostic against the wrapper's header lands on
            // line 0 or lower; above, one against its footer lands past the user's last line. The
            // editor silently fails to mark either, so both are pulled onto the nearest real line.
            startLine = (location.start.line - lineOffset).coerceIn(1, lastUserLine),
            startCol = location.start.col,
            endLine = (end.line - lineOffset).coerceIn(1, lastUserLine),
            endCol = end.col,
        )
    }

    private fun SourceCodeCompletionVariant.toEditorCompletion() = EditorCompletion(
        text = text,
        displayText = displayText,
        tail = tail,
        // The completer's vocabulary and the editor's stylesheet agree on these four; anything
        // else would render as an unstyled blank square rather than an icon.
        icon = if (icon in STYLED_ICONS) icon else FALLBACK_ICON,
    )

    /**
     * Run [block] on the analysis thread, one at a time, skipping requests that a newer one has
     * already superseded while they waited.
     *
     * [TIMEOUT_MS] wraps **acquiring the lock**, not the compile. That placement is the whole
     * point: the compiler call has no suspension points, so a coroutine timeout cannot interrupt
     * one that has already started — `withTimeoutOrNull` around it would wait for it to finish
     * anyway and bound nothing. Bounding the *wait* is what can actually be enforced, and it is
     * the case that matters: if one compile wedges, every later keystroke fails fast with an empty
     * result instead of hanging its request until the wedge clears.
     */
    private suspend fun <T> serialised(scriptType: ScriptType, ifSkipped: T, block: suspend () -> T): T {
        val generation = generations.computeIfAbsent(scriptType.name) { AtomicLong() }
        val mine = generation.incrementAndGet()
        if (generation.get() != mine) return ifSkipped
        return try {
            withTimeoutOrNull(TIMEOUT_MS) {
                mutex.withLock {
                    if (generation.get() != mine) return@withLock ifSkipped
                    withContext(dispatcher) { block() }
                }
            } ?: run {
                logger.warn("Script editor analysis waited longer than {} ms — returning empty", TIMEOUT_MS)
                ifSkipped
            }
        } catch (e: CancellationException) {
            // Rethrow, never convert to an empty result: on the JVM CancellationException *is* an
            // Exception, so the generic catch below would otherwise swallow the client having
            // disconnected mid-keystroke and leave us responding into a dead call.
            throw e
        } catch (e: Exception) {
            // The editor asks about half-typed code constantly; a compiler frontend blowing up
            // on it is expected, and must never surface as a 500 that the widget turns into a
            // dead editor.
            logger.debug("Script editor analysis failed", e)
            ifSkipped
        }
    }

    /**
     * The file extension decides which script definition the compiler applies, so it has to match
     * the template — a `.kts` that does not end in the template's own extension is compiled as a
     * plain script, with none of the base class's members in scope.
     */
    private fun sourceName(scriptType: ScriptType) = "Editor." + when (scriptType) {
        ScriptType.GENERAL -> "lightng.kts"
        ScriptType.FX_DEFINITION -> "fxdef.kts"
        ScriptType.FX_APPLICATION -> "fxapp.kts"
        ScriptType.FX_CALC -> "fxcalc.kts"
        ScriptType.FX_CALC_STATEFUL -> "fxstateful.kts"
        ScriptType.FX_CALC_COMPOSITE -> "fxcomposite.kts"
    }

    companion object {
        /**
         * Shared, not per-instance: there is one desk and one editor, so serialising globally is
         * what the throttling is *for*. It also stops the route tests — which mount a fresh
         * application per test — from leaving a thread behind each time.
         */
        private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "script-editor-analysis").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 2
            }
        }.asCoroutineDispatcher()

        private const val TIMEOUT_MS = 10_000L
        private val STYLED_ICONS = setOf("class", "method", "property", "package")
        private const val FALLBACK_ICON = "genericValue"
    }
}
