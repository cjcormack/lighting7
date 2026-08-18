package uk.me.cormack.lighting7.scripts

import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * A user script after wrapping, together with how far the wrapper pushed it down.
 *
 * [lineOffset] is the number of lines the wrapper prepended, so a diagnostic reported against
 * [text] at line *n* belongs to the user's line *n - lineOffset*. Nothing used to carry this,
 * which is why compile errors in GENERAL and FX_CALC scripts have always been reported one line
 * off — see [ScriptSourceWrapper].
 */
data class WrappedScriptSource(val text: String, val lineOffset: Int)

/**
 * The single owner of "how a user's script body becomes compilable Kotlin".
 *
 * Three places used to answer that question independently — `Show.Script.init`,
 * `FxScriptCompiler.wrapInLambda` and the script editor's own idea of it in the frontend — and
 * **none of them mapped line numbers back**, so every diagnostic for a wrapped script type was
 * reported against the wrapped text rather than the text the user is looking at.
 *
 * Centralising it here is what lets the editor's `/highlight` and the Compile button agree, and
 * is a precondition for serving the editor from this app's own compiler at all (there is no
 * second process to ask any more).
 *
 * The FX_CALC lambda signatures are written with **fully-qualified** type names even though each
 * template's `defaultImports` would cover the short forms. `FxScriptCompiler` used short names
 * and `Show` used long ones for the same three modes; unifying on the long form can only ever
 * resolve in more contexts, never fewer.
 */
object ScriptSourceWrapper {
    /**
     * Wrap [userScript] so it compiles against [scriptType]'s template.
     *
     * GENERAL is wrapped in `runBlocking` for coroutine support. The FX_CALC family is wrapped in
     * a typed lambda declaration whose signature matches the mode. FX_DEFINITION and
     * FX_APPLICATION configure effects rather than orchestrating anything, so they compile
     * verbatim and carry a zero offset.
     */
    fun wrap(userScript: String, scriptType: ScriptType): WrappedScriptSource = when (scriptType) {
        ScriptType.GENERAL -> lambda(
            "runBlocking {",
            userScript,
            "}",
        )

        ScriptType.FX_DEFINITION, ScriptType.FX_APPLICATION -> WrappedScriptSource(userScript, 0)

        ScriptType.FX_CALC -> calcLambda(
            "(Double, uk.me.cormack.lighting7.fx.EffectContext, uk.me.cormack.lighting7.fx.TypedParams) " +
                "-> uk.me.cormack.lighting7.fx.FxOutput",
            "phase, context, params",
            userScript,
        )

        ScriptType.FX_CALC_STATEFUL -> calcLambda(
            "(uk.me.cormack.lighting7.fx.MasterClock.ClockTick, Long, " +
                "uk.me.cormack.lighting7.fx.EffectContext, uk.me.cormack.lighting7.fx.TypedParams, " +
                "MutableMap<String, Any>) -> uk.me.cormack.lighting7.fx.FxOutput",
            "tick, deltaMs, context, params, state",
            userScript,
        )

        ScriptType.FX_CALC_COMPOSITE -> calcLambda(
            "(Double, uk.me.cormack.lighting7.fx.EffectContext, uk.me.cormack.lighting7.fx.TypedParams) " +
                "-> Map<uk.me.cormack.lighting7.fx.FxOutputType, uk.me.cormack.lighting7.fx.FxOutput>",
            "phase, context, params",
            userScript,
        )
    }

    /** The compilation configuration for [scriptType]'s template. */
    fun compilationConfiguration(scriptType: ScriptType): ScriptCompilationConfiguration =
        when (scriptType) {
            ScriptType.GENERAL -> createJvmCompilationConfigurationFromTemplate<LightingScript>()
            ScriptType.FX_DEFINITION -> createJvmCompilationConfigurationFromTemplate<FxDefinitionScript>()
            ScriptType.FX_APPLICATION -> createJvmCompilationConfigurationFromTemplate<FxApplicationScript>()
            ScriptType.FX_CALC -> createJvmCompilationConfigurationFromTemplate<FxCalcScript>()
            ScriptType.FX_CALC_STATEFUL -> createJvmCompilationConfigurationFromTemplate<FxStatefulCalcScript>()
            ScriptType.FX_CALC_COMPOSITE -> createJvmCompilationConfigurationFromTemplate<FxCompositeCalcScript>()
        }

    /**
     * The FX_CALC family: declare a typed lambda, then yield it as the script's result value so
     * the caller can extract and invoke it directly. Always exactly one prepended line.
     */
    private fun calcLambda(signature: String, parameters: String, userScript: String) = lambda(
        "val calculateFn: $signature = { $parameters ->",
        userScript,
        "}\ncalculateFn",
    )

    private fun lambda(header: String, userScript: String, footer: String) = WrappedScriptSource(
        text = "$header\n$userScript\n$footer",
        // The header is written as a single line on purpose: every caller depends on the offset
        // being the header's line count, so a two-line header would silently shift diagnostics.
        lineOffset = header.count { it == '\n' } + 1,
    )
}
