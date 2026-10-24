package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.Timing

/**
 * Creates [Fx] / [StatefulFx] instances from compiled FX scripts.
 *
 * The adapter wraps extracted lambdas (from [FxScriptCompiler]) in thin Effect implementations.
 * Per-tick calls are direct lambda invocations — no scripting host, no coroutine bridge,
 * no config allocation. Same performance profile as hardcoded effect classes.
 */
object ScriptFxAdapter {

    /**
     * Create an [FxFactory] from a [CompiledFxScript].
     *
     * The factory creates lightweight Effect wrappers that invoke the script's
     * extracted lambda on each calculate() call.
     */
    fun createFactory(
        compiled: CompiledFxScript,
        schema: List<ParameterInfo>,
        fxName: String,
        outputType: FxOutputType,
        defaultTiming: Timing = Timing.CYCLE,
        registrationId: String? = null,
        defaultDirection: FxDirection = FxDirection.FORWARD,
    ): FxFactory {
        return { params, resolveColourSource, colourSourceVersion ->
            val typedParams = TypedParams(params, schema, resolveColourSource, colourSourceVersion)

            when (compiled.fxMode) {
                FxMode.STANDARD -> {
                    val fn = compiled.standardFn
                        ?: throw IllegalStateException("No standard lambda in compiled script for '$fxName'")
                    StandardScriptFx(fn, typedParams, fxName, outputType, params, defaultTiming, defaultDirection, registrationId)
                }
                FxMode.STATEFUL -> {
                    val fn = compiled.statefulFn
                        ?: throw IllegalStateException("No stateful lambda in compiled script for '$fxName'")
                    StatefulScriptFx(fn, typedParams, fxName, outputType, params, defaultTiming, defaultDirection, registrationId)
                }
            }
        }
    }
}

/**
 * Standard script effect — invokes an extracted lambda directly.
 */
private class StandardScriptFx(
    private val fn: (Double, FxContext, TypedParams) -> FxOutput,
    private val typedParams: TypedParams,
    private val fxName: String,
    private val fxOutputType: FxOutputType,
    private val rawParams: Map<String, String>,
    private val fxDefaultTiming: Timing,
    override val defaultDirection: FxDirection,
    override val registrationId: String?,
) : Fx {
    override val name: String get() = fxName
    override val outputType: FxOutputType get() = fxOutputType
    override val parameters: Map<String, String> get() = rawParams
    override val defaultTiming: Timing get() = fxDefaultTiming

    override fun calculate(phase: Double, context: FxContext): FxOutput {
        return fn(phase, context, typedParams)
    }
}

/**
 * Stateful script effect — invokes an extracted lambda with persistent state.
 */
private class StatefulScriptFx(
    private val fn: (MasterClock.ClockTick, Long, FxContext, TypedParams, MutableMap<String, Any>) -> FxOutput,
    private val typedParams: TypedParams,
    private val fxName: String,
    private val fxOutputType: FxOutputType,
    private val rawParams: Map<String, String>,
    private val fxDefaultTiming: Timing,
    override val defaultDirection: FxDirection,
    override val registrationId: String?,
) : StatefulFx {
    override val name: String get() = fxName
    override val outputType: FxOutputType get() = fxOutputType
    override val parameters: Map<String, String> get() = rawParams
    override val defaultTiming: Timing get() = fxDefaultTiming

    private val state = mutableMapOf<String, Any>()

    override fun initialize() {
        state.clear()
    }

    override fun calculateStateful(
        tick: MasterClock.ClockTick,
        deltaMs: Long,
        context: FxContext,
    ): FxOutput {
        return fn(tick, deltaMs, context, typedParams, state)
    }
}
