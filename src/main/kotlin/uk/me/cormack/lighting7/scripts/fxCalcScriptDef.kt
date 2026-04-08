package uk.me.cormack.lighting7.scripts

import uk.me.cormack.lighting7.fx.*
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget

/**
 * Base class for STANDARD FX calculation scripts.
 *
 * Standard effects are pure functions of phase. The script body should end with
 * an [FxOutput] expression (e.g., `FxOutput.Slider(value)`).
 *
 * Example:
 * ```
 * val min = params.ubyte("min")
 * val max = params.ubyte("max")
 * val sine = (Math.sin(phase * 2 * Math.PI) + 1.0) / 2.0
 * val value = (min.toInt() + (max.toInt() - min.toInt()) * sine)
 *     .toInt().coerceIn(0, 255).toUByte()
 * FxOutput.Slider(value)
 * ```
 */
@KotlinScript(
    fileExtension = "fxcalc.kts",
    compilationConfiguration = FxCalcScriptConfiguration::class,
)
abstract class FxCalcScript(
    /** Position in the effect cycle, from 0.0 (start) to 1.0 (end) */
    val phase: Double,
    /** Distribution metadata (group size, member index, offsets) */
    val context: EffectContext,
    /** Typed parameter accessor with pre-parsed values */
    val params: TypedParams,
)

object FxCalcScriptConfiguration : ScriptCompilationConfiguration(
    {
        defaultImports(
            "uk.me.cormack.lighting7.fx.*",
            "uk.me.cormack.lighting7.fx.effects.*",
            "java.awt.Color",
            "kotlin.math.*",
        )

        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
            jvmTarget("17")
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }

        baseClass(FxCalcScript::class)
    },
)

/**
 * Base class for STATEFUL FX calculation scripts.
 *
 * Stateful effects receive tick-level timing and maintain state across calls.
 * The [state] map persists for the lifetime of the effect instance.
 * The script body should end with an [FxOutput] expression.
 *
 * Example:
 * ```
 * val baseLevel = params.ubyte("baseLevel").toDouble()
 * val currentLevel = state.getOrPut("level") { baseLevel } as Double
 * val newLevel = currentLevel + (Math.random() - 0.5) * 10
 * state["level"] = newLevel.coerceIn(0.0, 255.0)
 * FxOutput.Slider(newLevel.toInt().coerceIn(0, 255).toUByte())
 * ```
 */
@KotlinScript(
    fileExtension = "fxstateful.kts",
    compilationConfiguration = FxStatefulCalcScriptConfiguration::class,
)
abstract class FxStatefulCalcScript(
    /** Current clock tick with beat position and timestamp */
    val tick: MasterClock.ClockTick,
    /** Milliseconds since the last tick (0 on first tick) */
    val deltaMs: Long,
    /** Distribution metadata (group size, member index, offsets) */
    val context: EffectContext,
    /** Typed parameter accessor with pre-parsed values */
    val params: TypedParams,
    /** Persistent state map — survives across calculate calls for this effect instance */
    val state: MutableMap<String, Any>,
)

object FxStatefulCalcScriptConfiguration : ScriptCompilationConfiguration(
    {
        defaultImports(
            "uk.me.cormack.lighting7.fx.*",
            "uk.me.cormack.lighting7.fx.effects.*",
            "java.awt.Color",
            "kotlin.math.*",
        )

        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
            jvmTarget("17")
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }

        baseClass(FxStatefulCalcScript::class)
    },
)

/**
 * Base class for COMPOSITE FX calculation scripts.
 *
 * Composite effects produce outputs for multiple property types simultaneously.
 * The script body should end with a `Map<FxOutputType, FxOutput>` expression.
 *
 * Example:
 * ```
 * val intensity = if (phase < 0.1) 255 else ((1.0 - phase) * 255).toInt()
 * mapOf(
 *     FxOutputType.SLIDER to FxOutput.Slider(intensity.coerceIn(0, 255).toUByte()),
 *     FxOutputType.COLOUR to FxOutput.Colour(params.colour("flashColour")),
 * )
 * ```
 */
@KotlinScript(
    fileExtension = "fxcomposite.kts",
    compilationConfiguration = FxCompositeCalcScriptConfiguration::class,
)
abstract class FxCompositeCalcScript(
    /** Position in the effect cycle, from 0.0 (start) to 1.0 (end) */
    val phase: Double,
    /** Distribution metadata (group size, member index, offsets) */
    val context: EffectContext,
    /** Typed parameter accessor with pre-parsed values */
    val params: TypedParams,
)

object FxCompositeCalcScriptConfiguration : ScriptCompilationConfiguration(
    {
        defaultImports(
            "uk.me.cormack.lighting7.fx.*",
            "uk.me.cormack.lighting7.fx.effects.*",
            "java.awt.Color",
            "kotlin.math.*",
        )

        jvm {
            dependenciesFromCurrentContext(wholeClasspath = true)
            jvmTarget("17")
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }

        baseClass(FxCompositeCalcScript::class)
    },
)
