package uk.me.cormack.lighting7.fx

/**
 * Creates [Effect] / [StatefulEffect] instances from compiled FX scripts.
 *
 * The adapter wraps extracted lambdas (from [FxScriptCompiler]) in thin Effect implementations.
 * Per-tick calls are direct lambda invocations — no scripting host, no coroutine bridge,
 * no config allocation. Same performance profile as hardcoded effect classes.
 */
object ScriptEffectAdapter {

    /**
     * Create an [EffectFactory] from a [CompiledFxScript].
     *
     * The factory creates lightweight Effect wrappers that invoke the script's
     * extracted lambda on each calculate() call.
     */
    fun createFactory(
        compiled: CompiledFxScript,
        schema: List<ParameterInfo>,
        effectName: String,
        outputType: FxOutputType,
        defaultStepTiming: Boolean = false,
        registrationId: String? = null,
        defaultDirection: EffectDirection = EffectDirection.FORWARD,
    ): EffectFactory {
        return { params, resolveColourSource, colourSourceVersion ->
            val typedParams = TypedParams(params, schema, resolveColourSource, colourSourceVersion)

            when (compiled.effectMode) {
                EffectMode.STANDARD -> {
                    val fn = compiled.standardFn
                        ?: throw IllegalStateException("No standard lambda in compiled script for '$effectName'")
                    StandardScriptEffect(fn, typedParams, effectName, outputType, params, defaultStepTiming, defaultDirection, registrationId)
                }
                EffectMode.STATEFUL -> {
                    val fn = compiled.statefulFn
                        ?: throw IllegalStateException("No stateful lambda in compiled script for '$effectName'")
                    StatefulScriptEffect(fn, typedParams, effectName, outputType, params, defaultStepTiming, defaultDirection, registrationId)
                }
            }
        }
    }
}

/**
 * Standard script effect — invokes an extracted lambda directly.
 */
private class StandardScriptEffect(
    private val fn: (Double, EffectContext, TypedParams) -> FxOutput,
    private val typedParams: TypedParams,
    private val effectName: String,
    private val effectOutputType: FxOutputType,
    private val rawParams: Map<String, String>,
    private val effectDefaultStepTiming: Boolean,
    override val defaultDirection: EffectDirection,
    override val registrationId: String?,
) : Effect {
    override val name: String get() = effectName
    override val outputType: FxOutputType get() = effectOutputType
    override val parameters: Map<String, String> get() = rawParams
    override val defaultStepTiming: Boolean get() = effectDefaultStepTiming

    override fun calculate(phase: Double, context: EffectContext): FxOutput {
        return fn(phase, context, typedParams)
    }
}

/**
 * Stateful script effect — invokes an extracted lambda with persistent state.
 */
private class StatefulScriptEffect(
    private val fn: (MasterClock.ClockTick, Long, EffectContext, TypedParams, MutableMap<String, Any>) -> FxOutput,
    private val typedParams: TypedParams,
    private val effectName: String,
    private val effectOutputType: FxOutputType,
    private val rawParams: Map<String, String>,
    private val effectDefaultStepTiming: Boolean,
    override val defaultDirection: EffectDirection,
    override val registrationId: String?,
) : StatefulEffect {
    override val name: String get() = effectName
    override val outputType: FxOutputType get() = effectOutputType
    override val parameters: Map<String, String> get() = rawParams
    override val defaultStepTiming: Boolean get() = effectDefaultStepTiming

    private val state = mutableMapOf<String, Any>()

    override fun initialize() {
        state.clear()
    }

    override fun calculateStateful(
        tick: MasterClock.ClockTick,
        deltaMs: Long,
        context: EffectContext,
    ): FxOutput {
        return fn(tick, deltaMs, context, typedParams, state)
    }
}
