package uk.me.cormack.lighting7.scripts

import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.show.Show
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget

/**
 * Base class for FX definition scripts.
 *
 * These scripts define custom effect types by registering [EffectRegistration]s
 * with the [FxRegistry]. They have a minimal API surface — no access to fixtures,
 * groups, the FX engine, tempo, or DMX.
 *
 * The factory returns an [Effect] the script implements itself — there are no compiled-in effect
 * classes to instantiate. To start from a built-in, look its registration up through [fxRegistry]
 * and call its own factory rather than re-implementing its maths.
 *
 * Example:
 * ```
 * registerEffect(EffectRegistration(
 *     id = "half-dimmer",
 *     name = "Half Dimmer",
 *     category = "dimmer",
 *     outputType = FxOutputType.SLIDER,
 *     parameters = listOf(ParameterInfo("max", "ubyte", "255", "Peak level")),
 *     compatibleProperties = listOf("dimmer"),
 *     factory = { params, _, _ ->
 *         val max = params["max"]?.toUByteParam() ?: 255u
 *         object : Effect {
 *             override val name = "Half Dimmer"
 *             override val outputType = FxOutputType.SLIDER
 *             override val parameters get() = mapOf("max" to max.toString())
 *             override fun calculate(phase: Double, context: EffectContext): FxOutput =
 *                 FxOutput.Slider(if (phase < 0.5) max else 0u)
 *         }
 *     },
 * ))
 * ```
 */
@KotlinScript(
    fileExtension = "fxdef.kts",
    compilationConfiguration = FxDefinitionScriptConfiguration::class,
)
abstract class FxDefinitionScript(
    private val show: Show,
    val scriptName: String,
    /** Database ID of this script, used to tag registered effects for the library API. */
    val scriptId: Int? = null,
) {
    /** Access to the effect registry for browsing existing effects. */
    val fxRegistry: FxRegistry get() = show.fxRegistry

    /** Track IDs of effects registered by this script (for cleanup). */
    internal val registeredEffectIds = mutableListOf<String>()

    /**
     * Register a custom effect type that becomes available in the library API.
     *
     * The effect is automatically tagged with [EffectSource.USER] and will be
     * cleaned up when this script is unloaded.
     */
    fun registerEffect(registration: EffectRegistration) {
        val scriptReg = registration.copy(source = EffectSource.USER, sourceDefinitionId = scriptId)
        show.fxRegistry.register(scriptReg)
        registeredEffectIds.add(scriptReg.id)
    }
}

object FxDefinitionScriptConfiguration : ScriptCompilationConfiguration(
    {
        defaultImports(
            "uk.me.cormack.lighting7.fx.*",
            "java.awt.Color",
        )

        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
            jvmTarget("17")
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }

        baseClass(FxDefinitionScript::class)
    },
)
