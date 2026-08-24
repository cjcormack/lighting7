package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.scripts.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/**
 * Compiles FX calculation scripts into directly-invocable lambdas.
 *
 * Performance strategy: compile once, evaluate once to extract a lambda, then invoke
 * the lambda directly on every tick. This avoids the per-tick overhead of constructing
 * a scripting host, building evaluation configs, and unwrapping results.
 *
 * The user's script body is transparently wrapped in a typed lambda declaration before
 * compilation — see [uk.me.cormack.lighting7.scripts.ScriptSourceWrapper], which owns that for
 * every script type and reports the line offset it introduces so diagnostics can be mapped back
 * to the user's own lines.
 *
 * After compilation + one-time evaluation, the lambda is extracted and cached.
 * Per-tick calls are direct JVM invocations — fully JIT-optimizable.
 */
class FxScriptCompiler(
    hostConfiguration: ScriptingHostConfiguration = defaultJvmScriptingHostConfiguration,
) {
    private val scriptingHost = BasicJvmScriptingHost(hostConfiguration)

    companion object {
        /**
         * Compiled scripts, cached for the **process**, not for one compiler instance.
         *
         * A [FxScriptCompiler] is built per [uk.me.cormack.lighting7.show.Show], and a `Show` is
         * built per project switch — and, in the test suite, per test. A per-instance cache meant
         * every one of those re-evaluated all 28 built-in `.fx.kts` effects: even with the
         * on-disk jar cache warm, that is 28 jar loads, 28 classloaders and 28 script evaluations
         * (~70 ms) to arrive at lambdas byte-identical to the ones the previous `Show` had.
         *
         * Sharing them is safe because [CompiledFxScript] is immutable and its lambdas are
         * stateless: a STATEFUL effect's mutable state is handed in per call as the `state`
         * parameter, never captured. The key is the script body and its mode, so two `Show`s
         * only share an entry when they compiled exactly the same source.
         *
         * Deliberately not keyed on the host configuration. That only selects *where* compiled
         * jars are cached on disk; it cannot change the bytecode, and the classpath a script
         * links against is fixed for the life of the JVM (see `ScriptCache.buildFingerprint`).
         */
        private val cache = ConcurrentHashMap<String, CompiledFxScript>()

        /** Drop every cached compilation. Test-only seam; nothing in the app needs it. */
        internal fun clearProcessCache() = cache.clear()
    }

    /**
     * Compile a script body and extract its lambda for the given effect mode.
     *
     * @param script The script body (calculate logic only, no metadata)
     * @param effectMode Determines which base class and lambda signature to use
     * @return The compiled script with extracted lambda, or an error result
     */
    /**
     * Compile and cache, keyed by effect mode + script content.
     *
     * Failures are cached too, deliberately: the common case for a failing compile is a user
     * editing a broken FX definition and hitting "Test" repeatedly, and each miss runs the
     * whole Kotlin compiler inside [kotlinx.coroutines.runBlocking] on a request thread. Callers
     * that want to force a genuine recompile — e.g. [FxFileLoader]'s retry pass, which is
     * distinguishing a transient failure from a real one — must call [invalidate] first.
     */
    fun compile(script: String, effectMode: EffectMode): CompiledFxScript {
        val cacheKey = "${effectMode.name}-${script.cacheKey()}"
        return cache.getOrPut(cacheKey) {
            doCompileAndExtract(script, effectMode)
        }
    }

    /**
     * Compile without caching or lambda extraction (for compile-check operations).
     */
    fun compileCheck(script: String, effectMode: EffectMode): CompileCheckResult {
        val wrapped = ScriptSourceWrapper.wrap(script, effectMode.asScriptType())
        val compilationConfiguration = compilationConfigFor(effectMode)

        val result = kotlinx.coroutines.runBlocking {
            scriptingHost.compiler(wrapped.text.toScriptSource(), compilationConfiguration)
        }

        val diagnostics = extractDiagnostics(result, wrapped.lineOffset)
        return CompileCheckResult(
            success = result is ResultWithDiagnostics.Success,
            messages = diagnostics,
        )
    }

    /**
     * Invalidate the cache entry for a given script.
     */
    fun invalidate(script: String, effectMode: EffectMode) {
        val cacheKey = "${effectMode.name}-${script.cacheKey()}"
        cache.remove(cacheKey)
    }

    private fun doCompileAndExtract(script: String, effectMode: EffectMode): CompiledFxScript {
        val wrapped = ScriptSourceWrapper.wrap(script, effectMode.asScriptType())
        val compilationConfiguration = compilationConfigFor(effectMode)

        // Step 1: Compile
        val compileResult = kotlinx.coroutines.runBlocking {
            scriptingHost.compiler(wrapped.text.toScriptSource(), compilationConfiguration)
        }

        val diagnostics = extractDiagnostics(compileResult, wrapped.lineOffset)

        if (compileResult !is ResultWithDiagnostics.Success) {
            return CompiledFxScript(
                isSuccess = false,
                diagnostics = diagnostics,
                effectMode = effectMode,
            )
        }

        // Step 2: Evaluate once to extract the lambda
        val compiledScript = compileResult.value
        val evalResult = kotlinx.coroutines.runBlocking {
            scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                // Provide dummy values — the lambda is just being defined, not invoked
                when (effectMode) {
                    EffectMode.STANDARD -> {
                        providedProperties(Pair("phase", 0.0))
                        providedProperties(Pair("context", EffectContext.SINGLE))
                        providedProperties(Pair("params", TypedParams(emptyMap(), emptyList())))
                    }
                    EffectMode.STATEFUL -> {
                        providedProperties(Pair("tick", MasterClock.ClockTick(0L, 0L, 0, 0.0, 0L)))
                        providedProperties(Pair("deltaMs", 0L))
                        providedProperties(Pair("context", EffectContext.SINGLE))
                        providedProperties(Pair("params", TypedParams(emptyMap(), emptyList())))
                        providedProperties(Pair("state", mutableMapOf<String, Any>()))
                    }
                    EffectMode.COMPOSITE -> {
                        providedProperties(Pair("phase", 0.0))
                        providedProperties(Pair("context", EffectContext.SINGLE))
                        providedProperties(Pair("params", TypedParams(emptyMap(), emptyList())))
                    }
                }
            })
        }

        if (evalResult !is ResultWithDiagnostics.Success) {
            val evalDiags = extractDiagnostics(evalResult, wrapped.lineOffset)
            return CompiledFxScript(
                isSuccess = false,
                diagnostics = diagnostics + evalDiags,
                effectMode = effectMode,
            )
        }

        // Step 3: Extract the lambda from the script's return value
        val returnValue = evalResult.value.returnValue
        val lambda = when (returnValue) {
            is ResultValue.Value -> returnValue.value
            else -> null
        }

        if (lambda == null) {
            return CompiledFxScript(
                isSuccess = false,
                diagnostics = diagnostics + FxCompileDiagnostic("ERROR", "Failed to extract calculate lambda from script"),
                effectMode = effectMode,
            )
        }

        return CompiledFxScript(
            isSuccess = true,
            diagnostics = diagnostics,
            effectMode = effectMode,
            standardFn = if (effectMode == EffectMode.STANDARD) {
                @Suppress("UNCHECKED_CAST")
                lambda as? (Double, EffectContext, TypedParams) -> FxOutput
            } else null,
            statefulFn = if (effectMode == EffectMode.STATEFUL) {
                @Suppress("UNCHECKED_CAST")
                lambda as? (MasterClock.ClockTick, Long, EffectContext, TypedParams, MutableMap<String, Any>) -> FxOutput
            } else null,
            compositeFn = if (effectMode == EffectMode.COMPOSITE) {
                @Suppress("UNCHECKED_CAST")
                lambda as? (Double, EffectContext, TypedParams) -> Map<FxOutputType, FxOutput>
            } else null,
        )
    }

    /**
     * FX-calc effect modes are the same three script types the rest of the app knows, under a
     * different name — mapping here keeps [ScriptSourceWrapper] the only place that knows how
     * either is wrapped or configured.
     */
    private fun EffectMode.asScriptType() = when (this) {
        EffectMode.STANDARD -> ScriptType.FX_CALC
        EffectMode.STATEFUL -> ScriptType.FX_CALC_STATEFUL
        EffectMode.COMPOSITE -> ScriptType.FX_CALC_COMPOSITE
    }

    private fun compilationConfigFor(effectMode: EffectMode) =
        ScriptSourceWrapper.compilationConfiguration(effectMode.asScriptType())

    /**
     * [lineOffset] shifts diagnostics back out of the lambda wrapper's coordinate space; every
     * FX-calc mode prepends exactly one line, so without it every error in the FX editor pointed
     * one line low.
     */
    private fun extractDiagnostics(result: ResultWithDiagnostics<*>, lineOffset: Int): List<FxCompileDiagnostic> {
        return result.reports
            .filter { it.severity != ScriptDiagnostic.Severity.DEBUG }
            .map { report ->
                FxCompileDiagnostic(
                    severity = report.severity.name,
                    message = report.message,
                    location = report.location?.let { loc ->
                        "${maxOf(1, loc.start.line - lineOffset)}:${loc.start.col}"
                    },
                )
            }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun String.cacheKey(): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(this.toByteArray()).toHexString()
    }
}

/**
 * A compiled FX script with an extracted lambda for direct invocation.
 *
 * Exactly one of [standardFn], [statefulFn], or [compositeFn] will be non-null
 * for successful compilations, matching the [effectMode].
 */
data class CompiledFxScript(
    val isSuccess: Boolean,
    val diagnostics: List<FxCompileDiagnostic>,
    val effectMode: EffectMode,
    /** Direct lambda for STANDARD effects: (phase, context, params) -> FxOutput */
    val standardFn: ((Double, EffectContext, TypedParams) -> FxOutput)? = null,
    /** Direct lambda for STATEFUL effects: (tick, deltaMs, context, params, state) -> FxOutput */
    val statefulFn: ((MasterClock.ClockTick, Long, EffectContext, TypedParams, MutableMap<String, Any>) -> FxOutput)? = null,
    /** Direct lambda for COMPOSITE effects: (phase, context, params) -> Map<FxOutputType, FxOutput> */
    val compositeFn: ((Double, EffectContext, TypedParams) -> Map<FxOutputType, FxOutput>)? = null,
)

data class FxCompileDiagnostic(
    val severity: String,
    val message: String,
    val location: String? = null,
)

data class CompileCheckResult(
    val success: Boolean,
    val messages: List<FxCompileDiagnostic>,
)
