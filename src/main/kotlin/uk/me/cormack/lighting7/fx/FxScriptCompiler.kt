package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.scripts.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Compiles FX calculation scripts into directly-invocable lambdas.
 *
 * Performance strategy: compile once, evaluate once to extract a lambda, then invoke
 * the lambda directly on every tick. This avoids the per-tick overhead of constructing
 * a scripting host, building evaluation configs, and unwrapping results.
 *
 * The user's script body is transparently wrapped in a lambda declaration before
 * compilation. For a STANDARD effect, the script:
 * ```
 * val min = params.ubyte("min")
 * FxOutput.Slider(min)
 * ```
 * becomes:
 * ```
 * val calculateFn: (Double, EffectContext, TypedParams) -> FxOutput = { phase, context, params ->
 *     val min = params.ubyte("min")
 *     FxOutput.Slider(min)
 * }
 * ```
 *
 * After compilation + one-time evaluation, the lambda is extracted and cached.
 * Per-tick calls are direct JVM invocations — fully JIT-optimizable.
 */
class FxScriptCompiler {
    private val cache = ConcurrentHashMap<String, CompiledFxScript>()
    private val scriptingHost = BasicJvmScriptingHost()

    /**
     * Compile a script body and extract its lambda for the given effect mode.
     *
     * @param script The script body (calculate logic only, no metadata)
     * @param effectMode Determines which base class and lambda signature to use
     * @return The compiled script with extracted lambda, or an error result
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
        val wrapped = wrapInLambda(script, effectMode)
        val compilationConfiguration = compilationConfigFor(effectMode)

        val result = kotlinx.coroutines.runBlocking {
            scriptingHost.compiler(wrapped.toScriptSource(), compilationConfiguration)
        }

        val diagnostics = extractDiagnostics(result)
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
        val wrapped = wrapInLambda(script, effectMode)
        val compilationConfiguration = compilationConfigFor(effectMode)

        // Step 1: Compile
        val compileResult = kotlinx.coroutines.runBlocking {
            scriptingHost.compiler(wrapped.toScriptSource(), compilationConfiguration)
        }

        val diagnostics = extractDiagnostics(compileResult)

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
            val evalDiags = extractDiagnostics(evalResult)
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
     * Wrap the user's script body in a lambda declaration.
     * The lambda signature matches the effect mode.
     */
    private fun wrapInLambda(script: String, effectMode: EffectMode): String {
        return when (effectMode) {
            EffectMode.STANDARD -> """
                |val calculateFn: (Double, EffectContext, TypedParams) -> FxOutput = { phase, context, params ->
                |$script
                |}
                |calculateFn
            """.trimMargin()

            EffectMode.STATEFUL -> """
                |val calculateFn: (MasterClock.ClockTick, Long, EffectContext, TypedParams, MutableMap<String, Any>) -> FxOutput = { tick, deltaMs, context, params, state ->
                |$script
                |}
                |calculateFn
            """.trimMargin()

            EffectMode.COMPOSITE -> """
                |val calculateFn: (Double, EffectContext, TypedParams) -> Map<FxOutputType, FxOutput> = { phase, context, params ->
                |$script
                |}
                |calculateFn
            """.trimMargin()
        }
    }

    private fun compilationConfigFor(effectMode: EffectMode) = when (effectMode) {
        EffectMode.STANDARD -> createJvmCompilationConfigurationFromTemplate<FxCalcScript>()
        EffectMode.STATEFUL -> createJvmCompilationConfigurationFromTemplate<FxStatefulCalcScript>()
        EffectMode.COMPOSITE -> createJvmCompilationConfigurationFromTemplate<FxCompositeCalcScript>()
    }

    private fun extractDiagnostics(result: ResultWithDiagnostics<*>): List<FxCompileDiagnostic> {
        return result.reports
            .filter { it.severity != ScriptDiagnostic.Severity.DEBUG }
            .map { report ->
                FxCompileDiagnostic(
                    severity = report.severity.name,
                    message = report.message,
                    location = report.location?.let { loc -> "${loc.start.line}:${loc.start.col}" },
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
