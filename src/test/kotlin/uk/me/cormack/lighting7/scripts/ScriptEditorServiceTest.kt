package uk.me.cormack.lighting7.scripts

import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the language services the script editor is served from, now that there is no second JVM
 * to ask. The completion cases are the ones that prove the *real* templates are in play: a
 * playground-style synthetic wrapper would resolve none of these.
 */
class ScriptEditorServiceTest {
    private val service = ScriptEditorService()

    @Test
    fun `general script diagnostics land on the user's own line`() = runBlocking {
        // Line 1 of what the user typed. GENERAL is wrapped in `runBlocking {`, so the compiler
        // sees this on line 2 — reporting that raw is the bug this offset exists to prevent.
        val diagnostics = service.analyse("val x: Int = \"not an int\"\n", ScriptType.GENERAL)

        val error = diagnostics.single { it.severity == ScriptDiagnostic.Severity.ERROR }
        assertEquals(1, error.startLine, "diagnostic should be on the user's line 1, not the wrapper's")
        assertTrue(error.message.contains("Int"), "unexpected message: ${error.message}")
    }

    @Test
    fun `fx calc diagnostics land on the user's own line`() = runBlocking {
        // FX_CALC is wrapped in a typed lambda declaration — also exactly one prepended line.
        val diagnostics = service.analyse("\nval bad: Int = \"nope\"\n", ScriptType.FX_CALC)

        val error = diagnostics.first { it.severity == ScriptDiagnostic.Severity.ERROR }
        assertEquals(2, error.startLine, "diagnostic should be on the user's line 2")
    }

    @Test
    fun `every reported diagnostic carries a usable interval`() = runBlocking {
        val diagnostics = service.analyse(
            "val a: Int = \"x\"\nval b: String = 3\nnosuchFunction()\n",
            ScriptType.GENERAL,
        )

        // One error per line. The unused-variable WARNINGs alongside them are real: GENERAL is
        // wrapped in `runBlocking`, which makes top-level vals local — the editor surfaces them
        // now, exactly as the Compile button always has.
        assertEquals(
            listOf(1, 2, 3),
            diagnostics.filter { it.severity == ScriptDiagnostic.Severity.ERROR }.map { it.startLine }.sorted(),
        )
        assertTrue(diagnostics.none { it.severity == ScriptDiagnostic.Severity.DEBUG })
        diagnostics.forEach {
            assertTrue(it.startLine >= 1 && it.endLine >= it.startLine, "bad interval: $it")
        }
    }

    @Test
    fun `a valid script reports no errors`() = runBlocking {
        val diagnostics = service.analyse("val brightness = 255\n", ScriptType.GENERAL)
        assertTrue(
            diagnostics.none { it.severity == ScriptDiagnostic.Severity.ERROR },
            "unexpected errors: $diagnostics",
        )
    }

    @Test
    fun `a diagnostic against the wrapper's footer is pulled onto the user's last line`() = runBlocking {
        // `val x = (` leaves the parenthesis unbalanced, so the compiler blames the wrapper's
        // closing brace — a line past anything the user wrote. Unclamped that maps onto the
        // editor's hidden suffix and no squiggle is drawn at all, which reads as "compiles fine".
        val diagnostics = service.analyse("val x = (", ScriptType.GENERAL)

        val error = diagnostics.first { it.severity == ScriptDiagnostic.Severity.ERROR }
        assertEquals(1, error.startLine, "should be clamped onto the user's only line")
        assertTrue(error.endLine <= 1, "end line escaped the user's script: ${error.endLine}")
    }

    @Test
    fun `completion resolves members of the show DSL`() = runBlocking {
        val completions = service.complete("show.", ScriptType.GENERAL, line = 1, col = 6)

        assertTrue(completions.isNotEmpty(), "no completions returned")
        assertTrue(
            completions.any { it.displayText == "fxEngine" && it.tail == "FxEngine" },
            "expected Lighting7 members, got ${completions.take(10).map { it.displayText }}",
        )
        assertTrue(
            completions.all { it.icon in setOf("class", "method", "property", "package", "genericValue") },
            "unstyled icon would render blank: ${completions.map { it.icon }.distinct()}",
        )
    }

    @Test
    fun `completion resolves top-level members of the script template`() = runBlocking {
        // Not a member of anything — this only resolves if `baseClass(LightingScript::class)`
        // reached the completer.
        val completions = service.complete("speedMas", ScriptType.GENERAL, line = 1, col = 9)

        assertTrue(
            completions.any { it.displayText == "speedMasters" },
            "expected LightingScript's own members, got ${completions.map { it.displayText }}",
        )
    }

    @Test
    fun `completion works for the fx application template too`() = runBlocking {
        val completions = service.complete("fxEngine.", ScriptType.FX_APPLICATION, line = 1, col = 10)
        assertTrue(completions.isNotEmpty(), "no completions for FX_APPLICATION")
    }

    @Test
    fun `repeated requests stay correct despite the repl compiler's snippet history`() = runBlocking {
        // A shared KJvmReplCompilerWithIdeServices poisons itself after the first snippet, so this
        // asserts the service really does build a fresh one per call.
        repeat(3) { attempt ->
            val completions = service.complete("show.", ScriptType.GENERAL, line = 1, col = 6)
            assertTrue(completions.isNotEmpty(), "completion $attempt returned nothing")
        }
    }
}
