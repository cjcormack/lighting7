package uk.me.cormack.lighting7.fx

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the process-wide compilation cache in [FxScriptCompiler].
 *
 * A compiler is built per [uk.me.cormack.lighting7.show.Show] — per project switch in the app,
 * and per test in the suite. When the cache lived on the instance, each new `Show` re-evaluated
 * all 28 built-in `.fx.kts` effects to arrive at identical lambdas; sharing it took ~70 ms off
 * every `Show` construction and ~15 s off the suite.
 *
 * The property that makes it safe — and that this test exists to keep — is that a
 * [CompiledFxScript] is immutable and its lambdas are stateless, so handing the *same instance*
 * to two different `Show`s is sound. A future change that gave a compiled script per-`Show`
 * identity would have to break the `assertSame` below.
 */
class FxScriptCompilerCacheTest {

    private val script = "FxOutput.Slider((phase * 255).toInt().toUByte())"

    @Test
    fun `two compilers share one compiled script for the same source`() {
        FxScriptCompiler.clearProcessCache()

        val first = FxScriptCompiler()
        val second = FxScriptCompiler()

        val a = first.compile(script, EffectMode.STANDARD)
        assertTrue(a.isSuccess, "fixture script must compile: ${a.diagnostics}")

        val b = second.compile(script, EffectMode.STANDARD)

        assertSame(
            a,
            b,
            "a second FxScriptCompiler recompiled the same source — the process-wide cache in " +
                "FxScriptCompiler is no longer shared, which puts ~70 ms back on every Show",
        )
        assertNotNull(a.standardFn)
    }

    @Test
    fun `effect mode is part of the cache key`() {
        FxScriptCompiler.clearProcessCache()
        val compiler = FxScriptCompiler()

        val standard = compiler.compile(script, EffectMode.STANDARD)
        val composite = compiler.compile(script, EffectMode.COMPOSITE)

        // Same source text, different wrapper and lambda signature: these must not collide.
        assertTrue(standard.isSuccess, "STANDARD should compile: ${standard.diagnostics}")
        assertSame(EffectMode.STANDARD, standard.effectMode)
        assertSame(EffectMode.COMPOSITE, composite.effectMode)
    }

    @Test
    fun `invalidate forces a genuine recompile`() {
        FxScriptCompiler.clearProcessCache()
        val compiler = FxScriptCompiler()

        val before = compiler.compile(script, EffectMode.STANDARD)
        compiler.invalidate(script, EffectMode.STANDARD)
        val after = compiler.compile(script, EffectMode.STANDARD)

        // FxFileLoader's retry pass depends on this: it distinguishes a transient failure (e.g. a
        // torn cached jar) from a real one by invalidating and compiling again.
        assertTrue(after.isSuccess, "recompile should succeed: ${after.diagnostics}")
        assertTrue(before !== after, "invalidate() did not evict the shared cache entry")
    }
}
