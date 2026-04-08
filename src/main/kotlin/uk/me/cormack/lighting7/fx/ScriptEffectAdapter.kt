package uk.me.cormack.lighting7.fx

/**
 * Creates [Effect] / [StatefulEffect] / [CompositeEffect] instances from compiled FX scripts.
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
    ): EffectFactory {
        return { params, paletteSupplier, paletteVersionSupplier ->
            val typedParams = TypedParams(params, schema, paletteSupplier, paletteVersionSupplier)

            when (compiled.effectMode) {
                EffectMode.STANDARD -> {
                    val fn = compiled.standardFn
                        ?: throw IllegalStateException("No standard lambda in compiled script for '$effectName'")
                    StandardScriptEffect(fn, typedParams, effectName, outputType, params, defaultStepTiming)
                }
                EffectMode.STATEFUL -> {
                    val fn = compiled.statefulFn
                        ?: throw IllegalStateException("No stateful lambda in compiled script for '$effectName'")
                    StatefulScriptEffect(fn, typedParams, effectName, outputType, params, defaultStepTiming)
                }
                EffectMode.COMPOSITE -> {
                    val fn = compiled.compositeFn
                        ?: throw IllegalStateException("No composite lambda in compiled script for '$effectName'")
                    CompositeScriptEffect(fn, typedParams, effectName, outputType, params, defaultStepTiming)
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

/**
 * Composite script effect — invokes an extracted lambda returning multiple outputs.
 */
private class CompositeScriptEffect(
    private val fn: (Double, EffectContext, TypedParams) -> Map<FxOutputType, FxOutput>,
    private val typedParams: TypedParams,
    private val effectName: String,
    private val effectOutputType: FxOutputType,
    private val rawParams: Map<String, String>,
    private val effectDefaultStepTiming: Boolean,
) : CompositeEffect {
    override val name: String get() = effectName
    override val outputType: FxOutputType get() = effectOutputType
    override val parameters: Map<String, String> get() = rawParams
    override val defaultStepTiming: Boolean get() = effectDefaultStepTiming
    override val outputTypes: Set<FxOutputType> get() = setOf(effectOutputType)

    override fun calculateComposite(phase: Double, context: EffectContext): Map<FxOutputType, FxOutput> {
        return fn(phase, context, typedParams)
    }
}
