package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.effects.*
import uk.me.cormack.lighting7.state.State
import java.awt.Color

/**
 * REST API routes for FX (effects) control.
 */
internal fun Route.routeApiRestFx(state: State) {
    route("/fx") {
        // Master Clock endpoints
        route("/clock") {
            get<ClockStatus> {
                val clock = state.show.fxEngine.masterClock
                call.respond(ClockStatusResponse(
                    bpm = clock.bpm.value,
                    isRunning = clock.isRunning.value
                ))
            }

            post<ClockBpm> {
                val request = call.receive<SetBpmRequest>()
                state.show.fxEngine.masterClock.setBpm(request.bpm)
                call.respond(ClockStatusResponse(
                    bpm = state.show.fxEngine.masterClock.bpm.value,
                    isRunning = state.show.fxEngine.masterClock.isRunning.value
                ))
            }

            post<ClockTap> {
                state.show.fxEngine.masterClock.tap()
                call.respond(ClockStatusResponse(
                    bpm = state.show.fxEngine.masterClock.bpm.value,
                    isRunning = state.show.fxEngine.masterClock.isRunning.value
                ))
            }
        }

        // Active effects endpoints
        get<ActiveEffects> {
            val engine = state.show.fxEngine
            val effects = engine.getActiveEffects().map { it.toDto(engine.isMultiElementExpanded(it)) }
            call.respond(effects)
        }

        post<AddEffect> {
            val request = call.receive<AddEffectRequest>()
            try {
                val effect = createEffectFromRequest(request)
                val target = createTargetFromRequest(request, state)
                val timing = FxTiming(
                    beatDivision = request.beatDivision,
                    startOnBeat = request.startOnBeat
                )
                val blendMode = BlendMode.valueOf(request.blendMode)

                val instance = FxInstance(effect, target, timing, blendMode)
                instance.phaseOffset = request.phaseOffset
                request.distributionStrategy?.let {
                    instance.distributionStrategy = DistributionStrategy.fromName(it)
                }
                request.elementFilter?.let {
                    instance.elementFilter = ElementFilter.fromName(it)
                }

                val effectId = state.show.fxEngine.addEffect(instance)
                call.respond(AddEffectResponse(effectId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Unknown error"))
            }
        }

        delete<EffectId> {
            val removed = state.show.fxEngine.removeEffect(it.id)
            if (removed) {
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        post<EffectId.Pause> {
            state.show.fxEngine.pauseEffect(it.parent.id)
            call.respond(HttpStatusCode.OK)
        }

        post<EffectId.Resume> {
            state.show.fxEngine.resumeEffect(it.parent.id)
            call.respond(HttpStatusCode.OK)
        }

        // Update a running effect
        put<EffectId> { resource ->
            val request = call.receive<UpdateEffectRequest>()
            try {
                val engine = state.show.fxEngine
                val existing = engine.getEffect(resource.id)
                if (existing == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Effect not found"))
                    return@put
                }

                // Resolve new effect if type or parameters changed
                val newEffect = if (request.effectType != null || request.parameters != null) {
                    val effectType = request.effectType ?: existing.effect.name
                    val params = request.parameters ?: existing.effect.parameters
                    createEffectFromTypeAndParams(effectType, params)
                } else null

                val newTiming = request.beatDivision?.let { FxTiming(it, existing.timing.startOnBeat) }
                val newBlendMode = request.blendMode?.let { BlendMode.valueOf(it) }
                val newDistribution = request.distributionStrategy?.let { DistributionStrategy.fromName(it) }
                val newElementMode = request.elementMode?.let { ElementMode.valueOf(it) }
                val newElementFilter = request.elementFilter?.let { ElementFilter.fromName(it) }

                val updated = engine.updateEffect(
                    effectId = resource.id,
                    newEffect = newEffect,
                    newTiming = newTiming,
                    newBlendMode = newBlendMode,
                    newPhaseOffset = request.phaseOffset,
                    newDistributionStrategy = newDistribution,
                    newElementMode = newElementMode,
                    newElementFilter = newElementFilter
                )

                if (updated != null) {
                    call.respond(updated.toDto(engine.isMultiElementExpanded(updated)))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Effect not found"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update effect"))
            }
        }

        // Get active effects for a specific fixture (direct + indirect via groups)
        get<FixtureEffects> { resource ->
            val fixtureKey = resource.fixtureKey
            val engine = state.show.fxEngine
            val direct = engine.getEffectsForFixture(fixtureKey).map { it.toDto(engine.isMultiElementExpanded(it)) }
            val indirect = engine.getIndirectEffectsForFixture(fixtureKey).map { it.toIndirectDto() }
            call.respond(FixtureEffectsResponse(direct, indirect))
        }

        // Clear all effects for a fixture
        delete<FixtureEffects> {
            val count = state.show.fxEngine.removeEffectsForFixture(it.fixtureKey)
            call.respond(ClearEffectsResponse(count))
        }

        // Clear all effects
        post<ClearAll> {
            state.show.fxEngine.clearAllEffects()
            call.respond(HttpStatusCode.OK)
        }

        // Effect library - list available effect types
        get<EffectLibrary> {
            call.respond(effectLibrary)
        }
    }
}

// Resource classes for type-safe routing

@Resource("/clock/status")
data object ClockStatus

@Resource("/clock/bpm")
data object ClockBpm

@Resource("/clock/tap")
data object ClockTap

@Resource("/active")
data object ActiveEffects

@Resource("/add")
data object AddEffect

@Resource("/{id}")
data class EffectId(val id: Long) {
    @Resource("/pause")
    data class Pause(val parent: EffectId)

    @Resource("/resume")
    data class Resume(val parent: EffectId)
}

@Resource("/fixture/{fixtureKey}")
data class FixtureEffects(val fixtureKey: String)

@Resource("/clear")
data object ClearAll

@Resource("/library")
data object EffectLibrary

// Request/Response DTOs

@Serializable
data class ClockStatusResponse(
    val bpm: Double,
    val isRunning: Boolean
)

@Serializable
data class SetBpmRequest(val bpm: Double)

@Serializable
data class AddEffectRequest(
    val effectType: String,
    val fixtureKey: String,
    val propertyName: String,
    val beatDivision: Double = BeatDivision.QUARTER,
    val blendMode: String = "OVERRIDE",
    val startOnBeat: Boolean = true,
    val phaseOffset: Double = 0.0,
    val parameters: Map<String, String> = emptyMap(),
    val distributionStrategy: String? = null,
    val elementFilter: String? = null
)

@Serializable
data class AddEffectResponse(val effectId: Long)

@Serializable
data class ClearEffectsResponse(val removedCount: Int)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class FixtureEffectsResponse(
    val direct: List<EffectDto>,
    val indirect: List<IndirectEffectDto>
)

@Serializable
data class IndirectEffectDto(
    val id: Long,
    val effectType: String,
    val groupName: String,
    val propertyName: String,
    val beatDivision: Double,
    val blendMode: String,
    val isRunning: Boolean,
    val phaseOffset: Double,
    val currentPhase: Double,
    val parameters: Map<String, String>,
    val distributionStrategy: String
)

@Serializable
data class UpdateEffectRequest(
    val effectType: String? = null,
    val parameters: Map<String, String>? = null,
    val beatDivision: Double? = null,
    val blendMode: String? = null,
    val phaseOffset: Double? = null,
    val distributionStrategy: String? = null,
    val elementMode: String? = null,
    val elementFilter: String? = null
)

@Serializable
data class EffectDto(
    val id: Long,
    val effectType: String,
    val targetKey: String,
    val propertyName: String,
    val beatDivision: Double,
    val blendMode: String,
    val isRunning: Boolean,
    val phaseOffset: Double,
    val currentPhase: Double,
    val parameters: Map<String, String>,
    val isGroupTarget: Boolean,
    val distributionStrategy: String? = null,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val presetId: Int? = null,
)

@Serializable
data class EffectTypeInfo(
    val name: String,
    val category: String,
    val outputType: String,
    val parameters: List<ParameterInfo>,
    val compatibleProperties: List<String>
)

@Serializable
data class ParameterInfo(
    val name: String,
    val type: String,
    val defaultValue: String,
    val description: String = ""
)

// Helper functions

private fun FxInstance.toDto(isMultiElementExpanded: Boolean = false) = EffectDto(
    id = id,
    effectType = effect.name,
    targetKey = target.targetKey,
    propertyName = target.propertyName,
    beatDivision = timing.beatDivision,
    blendMode = blendMode.name,
    isRunning = isRunning,
    phaseOffset = phaseOffset,
    currentPhase = lastPhase,
    parameters = effect.parameters,
    isGroupTarget = isGroupEffect,
    distributionStrategy = if (isGroupEffect || isMultiElementExpanded)
        distributionStrategy.javaClass.simpleName else null,
    elementMode = if (isGroupEffect && isMultiElementExpanded)
        elementMode.name else null,
    elementFilter = if ((isGroupEffect || isMultiElementExpanded) && elementFilter != ElementFilter.ALL)
        elementFilter.name else null,
    presetId = presetId,
)

private fun FxInstance.toIndirectDto() = IndirectEffectDto(
    id = id,
    effectType = effect.name,
    groupName = target.targetKey,
    propertyName = target.propertyName,
    beatDivision = timing.beatDivision,
    blendMode = blendMode.name,
    isRunning = isRunning,
    phaseOffset = phaseOffset,
    currentPhase = lastPhase,
    parameters = effect.parameters,
    distributionStrategy = distributionStrategy.javaClass.simpleName
)

private fun createTargetFromRequest(request: AddEffectRequest, state: State): FxTarget {
    return when (request.propertyName) {
        "dimmer" -> SliderTarget(request.fixtureKey, "dimmer")
        "uv" -> SliderTarget(request.fixtureKey, "uv")
        "rgbColour", "colour" -> ColourTarget(request.fixtureKey)
        "position" -> PositionTarget(request.fixtureKey)
        else -> {
            // Check if the property is a slider or a setting on the fixture
            val fixture = try {
                state.show.fixtures.untypedFixture(request.fixtureKey) as? Fixture
            } catch (_: Exception) { null }
            val prop = fixture?.fixtureProperties?.find { it.name == request.propertyName }
            val propValue = prop?.classProperty?.call(fixture)
            if (propValue is Slider) {
                SliderTarget(request.fixtureKey, request.propertyName)
            } else {
                SettingTarget(request.fixtureKey, request.propertyName)
            }
        }
    }
}

private fun createEffectFromRequest(request: AddEffectRequest): Effect {
    return createEffectFromTypeAndParams(request.effectType, request.parameters)
}

private fun String.toUByteOrNull(): UByte? = toIntOrNull()?.coerceIn(0, 255)?.toUByte()

internal fun parseColor(colorString: String): Color {
    return parseExtendedColour(colorString).color
}

/**
 * Parse a colour string into an [ExtendedColour].
 *
 * Supported formats:
 * - Named colours: "red", "green", "blue", "yellow", "cyan", "magenta", "orange", "pink", "white", "black"
 * - Hex: "#FF0000", "FF0000", "#F00"
 * - Extended: "#ff0000;w128;a64;uv200" (semicolons separate optional W/A/UV channels)
 */
internal fun parseExtendedColour(colorString: String): ExtendedColour {
    // Split on semicolons to separate RGB from extended channels
    val parts = colorString.split(";")
    val rgbPart = parts[0].trim()

    val baseColor = when (rgbPart.lowercase()) {
        "red" -> Color.RED
        "green" -> Color.GREEN
        "blue" -> Color.BLUE
        "yellow" -> Color.YELLOW
        "cyan" -> Color.CYAN
        "magenta" -> Color.MAGENTA
        "orange" -> Color.ORANGE
        "pink" -> Color.PINK
        "white" -> Color.WHITE
        "black" -> Color.BLACK
        else -> {
            // Try parsing as hex (e.g., "#FF0000", "FF0000", "#F00")
            val hex = rgbPart.removePrefix("#")
            when (hex.length) {
                6 -> Color(hex.toInt(16))
                3 -> {
                    val r = hex.substring(0, 1).toInt(16)
                    val g = hex.substring(1, 2).toInt(16)
                    val b = hex.substring(2, 3).toInt(16)
                    Color(r * 17, g * 17, b * 17)
                }
                else -> Color.WHITE
            }
        }
    }

    // Parse extended channels from remaining parts (e.g., "w128", "a64", "uv200")
    var white: UByte = 0u
    var amber: UByte = 0u
    var uv: UByte = 0u

    for (i in 1 until parts.size) {
        val part = parts[i].trim().lowercase()
        when {
            part.startsWith("uv") -> uv = part.removePrefix("uv").toIntOrNull()?.coerceIn(0, 255)?.toUByte() ?: 0u
            part.startsWith("w") -> white = part.removePrefix("w").toIntOrNull()?.coerceIn(0, 255)?.toUByte() ?: 0u
            part.startsWith("a") -> amber = part.removePrefix("a").toIntOrNull()?.coerceIn(0, 255)?.toUByte() ?: 0u
        }
    }

    return ExtendedColour(baseColor, white, amber, uv)
}

private fun String.toEasingCurveOrNull(): EasingCurve? = try {
    EasingCurve.valueOf(this.uppercase())
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Shared effect creation function used by both fixture and group FX routes.
 * Supports the union of all effect types and aliases.
 */
internal fun createEffectFromTypeAndParams(effectType: String, params: Map<String, String>): Effect {
    return when (effectType.lowercase()) {
        // Dimmer effects
        "sinewave", "sine_wave", "sine" -> SineWave(
            min = params["min"]?.toUByteOrNull() ?: 0u,
            max = params["max"]?.toUByteOrNull() ?: 255u
        )
        "rampup", "ramp_up" -> RampUp(
            min = params["min"]?.toUByteOrNull() ?: 0u,
            max = params["max"]?.toUByteOrNull() ?: 255u,
            curve = params["curve"]?.toEasingCurveOrNull() ?: EasingCurve.LINEAR
        )
        "rampdown", "ramp_down" -> RampDown(
            min = params["min"]?.toUByteOrNull() ?: 0u,
            max = params["max"]?.toUByteOrNull() ?: 255u,
            curve = params["curve"]?.toEasingCurveOrNull() ?: EasingCurve.LINEAR
        )
        "triangle" -> Triangle(
            min = params["min"]?.toUByteOrNull() ?: 0u,
            max = params["max"]?.toUByteOrNull() ?: 255u,
            curve = params["curve"]?.toEasingCurveOrNull() ?: EasingCurve.LINEAR
        )
        "pulse" -> Pulse(
            min = params["min"]?.toUByteOrNull() ?: 0u,
            max = params["max"]?.toUByteOrNull() ?: 255u,
            attackRatio = params["attackRatio"]?.toDoubleOrNull() ?: 0.1,
            holdRatio = params["holdRatio"]?.toDoubleOrNull() ?: 0.3,
            curve = params["curve"]?.toEasingCurveOrNull() ?: EasingCurve.QUAD_OUT
        )
        "squarewave", "square_wave", "square" -> SquareWave(
            min = params["min"]?.toUByteOrNull() ?: 0u,
            max = params["max"]?.toUByteOrNull() ?: 255u,
            dutyCycle = params["dutyCycle"]?.toDoubleOrNull() ?: 0.5
        )
        "strobe" -> Strobe(
            offValue = params["offValue"]?.toUByteOrNull() ?: 0u,
            onValue = params["onValue"]?.toUByteOrNull() ?: 255u,
            onRatio = params["onRatio"]?.toDoubleOrNull() ?: 0.1
        )
        "flicker" -> Flicker(
            min = params["min"]?.toUByteOrNull() ?: 100u,
            max = params["max"]?.toUByteOrNull() ?: 255u
        )
        "breathe" -> Breathe(
            min = params["min"]?.toUByteOrNull() ?: 0u,
            max = params["max"]?.toUByteOrNull() ?: 255u
        )
        "staticvalue", "static_value" -> StaticValue(
            value = params["value"]?.toUByteOrNull() ?: 255u
        )

        // Colour effects
        "colourcycle", "colour_cycle", "colorcycle", "color_cycle" -> {
            val colourStrings = params["colours"]?.split(",") ?: listOf("red", "green", "blue")
            val colours = colourStrings.map { parseExtendedColour(it.trim()) }
            ColourCycle(
                colours = colours,
                fadeRatio = params["fadeRatio"]?.toDoubleOrNull() ?: 0.5
            )
        }
        "rainbowcycle", "rainbow_cycle", "rainbow" -> RainbowCycle(
            saturation = params["saturation"]?.toFloatOrNull() ?: 1.0f,
            brightness = params["brightness"]?.toFloatOrNull() ?: 1.0f
        )
        "colourstrobe", "colour_strobe", "colorstrobe", "color_strobe" -> ColourStrobe(
            onColor = params["onColor"]?.let { parseExtendedColour(it) } ?: ExtendedColour.fromColor(Color.WHITE),
            offColor = params["offColor"]?.let { parseExtendedColour(it) } ?: ExtendedColour.BLACK,
            onRatio = params["onRatio"]?.toDoubleOrNull() ?: 0.1
        )
        "colourpulse", "colour_pulse", "colorpulse", "color_pulse" -> ColourPulse(
            colorA = params["colorA"]?.let { parseExtendedColour(it) } ?: ExtendedColour.BLACK,
            colorB = params["colorB"]?.let { parseExtendedColour(it) } ?: ExtendedColour.fromColor(Color.WHITE)
        )
        "colourfade", "colour_fade", "colorfade", "color_fade" -> ColourFade(
            fromColor = params["fromColor"]?.let { parseExtendedColour(it) } ?: ExtendedColour.fromColor(Color.RED),
            toColor = params["toColor"]?.let { parseExtendedColour(it) } ?: ExtendedColour.fromColor(Color.BLUE),
            pingPong = params["pingPong"]?.toBooleanStrictOrNull() ?: true
        )
        "colourflicker", "colour_flicker", "colorflicker", "color_flicker" -> ColourFlicker(
            baseColor = params["baseColor"]?.let { parseExtendedColour(it) } ?: ExtendedColour.fromColor(Color.ORANGE),
            variation = params["variation"]?.toIntOrNull() ?: 50
        )
        "staticcolour", "static_colour", "staticcolor", "static_color" -> StaticColour(
            color = params["color"]?.let { parseExtendedColour(it) } ?: ExtendedColour.fromColor(Color.WHITE)
        )

        // Position effects
        "circle" -> Circle(
            panCenter = params["panCenter"]?.toUByteOrNull() ?: 128u,
            tiltCenter = params["tiltCenter"]?.toUByteOrNull() ?: 128u,
            panRadius = params["panRadius"]?.toUByteOrNull() ?: 64u,
            tiltRadius = params["tiltRadius"]?.toUByteOrNull() ?: 64u
        )
        "figure8", "figure_8" -> Figure8(
            panCenter = params["panCenter"]?.toUByteOrNull() ?: 128u,
            tiltCenter = params["tiltCenter"]?.toUByteOrNull() ?: 128u,
            panRadius = params["panRadius"]?.toUByteOrNull() ?: 64u,
            tiltRadius = params["tiltRadius"]?.toUByteOrNull() ?: 32u
        )
        "sweep" -> Sweep(
            startPan = params["startPan"]?.toUByteOrNull() ?: 64u,
            startTilt = params["startTilt"]?.toUByteOrNull() ?: 128u,
            endPan = params["endPan"]?.toUByteOrNull() ?: 192u,
            endTilt = params["endTilt"]?.toUByteOrNull() ?: 128u,
            curve = params["curve"]?.toEasingCurveOrNull() ?: EasingCurve.SINE_IN_OUT,
            pingPong = params["pingPong"]?.toBooleanStrictOrNull() ?: true
        )
        "pansweep", "pan_sweep" -> PanSweep(
            startPan = params["startPan"]?.toUByteOrNull() ?: 64u,
            endPan = params["endPan"]?.toUByteOrNull() ?: 192u,
            tilt = params["tilt"]?.toUByteOrNull() ?: 128u,
            curve = params["curve"]?.toEasingCurveOrNull() ?: EasingCurve.SINE_IN_OUT,
            pingPong = params["pingPong"]?.toBooleanStrictOrNull() ?: true
        )
        "tiltsweep", "tilt_sweep" -> TiltSweep(
            startTilt = params["startTilt"]?.toUByteOrNull() ?: 64u,
            endTilt = params["endTilt"]?.toUByteOrNull() ?: 192u,
            pan = params["pan"]?.toUByteOrNull() ?: 128u,
            curve = params["curve"]?.toEasingCurveOrNull() ?: EasingCurve.SINE_IN_OUT,
            pingPong = params["pingPong"]?.toBooleanStrictOrNull() ?: true
        )
        "randomposition", "random_position" -> RandomPosition(
            panCenter = params["panCenter"]?.toUByteOrNull() ?: 128u,
            tiltCenter = params["tiltCenter"]?.toUByteOrNull() ?: 128u,
            panRange = params["panRange"]?.toUByteOrNull() ?: 64u,
            tiltRange = params["tiltRange"]?.toUByteOrNull() ?: 64u
        )
        "staticposition", "static_position" -> StaticPosition(
            pan = params["pan"]?.toUByteOrNull() ?: 128u,
            tilt = params["tilt"]?.toUByteOrNull() ?: 128u
        )

        // Setting effects
        "staticsetting", "static_setting" -> StaticSetting(
            level = params["level"]?.toUByteOrNull() ?: 0u
        )

        else -> throw IllegalArgumentException("Unknown effect type: $effectType")
    }
}

// Effect library - available effects and their parameters
private val dimmerProperties = listOf("dimmer", "uv")
private val controlsSliderProperties = listOf("slider")
private val colourProperties = listOf("rgbColour")
private val positionProperties = listOf("position")
private val settingProperties = listOf("setting")

internal val effectLibrary = listOf(
    // Dimmer effects
    EffectTypeInfo("SineWave", "dimmer", "SLIDER", listOf(
        ParameterInfo("min", "ubyte", "0", "Minimum value"),
        ParameterInfo("max", "ubyte", "255", "Maximum value")
    ), dimmerProperties),
    EffectTypeInfo("RampUp", "dimmer", "SLIDER", listOf(
        ParameterInfo("min", "ubyte", "0", "Minimum value"),
        ParameterInfo("max", "ubyte", "255", "Maximum value"),
        ParameterInfo("curve", "easingCurve", "LINEAR", "Easing curve")
    ), dimmerProperties),
    EffectTypeInfo("RampDown", "dimmer", "SLIDER", listOf(
        ParameterInfo("min", "ubyte", "0", "Minimum value"),
        ParameterInfo("max", "ubyte", "255", "Maximum value"),
        ParameterInfo("curve", "easingCurve", "LINEAR", "Easing curve")
    ), dimmerProperties),
    EffectTypeInfo("Triangle", "dimmer", "SLIDER", listOf(
        ParameterInfo("min", "ubyte", "0", "Minimum value"),
        ParameterInfo("max", "ubyte", "255", "Maximum value"),
        ParameterInfo("curve", "easingCurve", "LINEAR", "Easing curve")
    ), dimmerProperties),
    EffectTypeInfo("Pulse", "dimmer", "SLIDER", listOf(
        ParameterInfo("min", "ubyte", "0", "Minimum value"),
        ParameterInfo("max", "ubyte", "255", "Maximum value"),
        ParameterInfo("attackRatio", "double", "0.1", "Attack portion of cycle"),
        ParameterInfo("holdRatio", "double", "0.3", "Hold portion of cycle"),
        ParameterInfo("curve", "easingCurve", "QUAD_OUT", "Easing curve")
    ), dimmerProperties),
    EffectTypeInfo("SquareWave", "dimmer", "SLIDER", listOf(
        ParameterInfo("min", "ubyte", "0", "Minimum value"),
        ParameterInfo("max", "ubyte", "255", "Maximum value"),
        ParameterInfo("dutyCycle", "double", "0.5", "On time ratio")
    ), dimmerProperties),
    EffectTypeInfo("Strobe", "dimmer", "SLIDER", listOf(
        ParameterInfo("offValue", "ubyte", "0", "Value when off"),
        ParameterInfo("onValue", "ubyte", "255", "Value when on"),
        ParameterInfo("onRatio", "double", "0.1", "On time ratio")
    ), dimmerProperties),
    EffectTypeInfo("Flicker", "dimmer", "SLIDER", listOf(
        ParameterInfo("min", "ubyte", "100", "Minimum value"),
        ParameterInfo("max", "ubyte", "255", "Maximum value")
    ), dimmerProperties),
    EffectTypeInfo("Breathe", "dimmer", "SLIDER", listOf(
        ParameterInfo("min", "ubyte", "0", "Minimum value"),
        ParameterInfo("max", "ubyte", "255", "Maximum value")
    ), dimmerProperties),
    EffectTypeInfo("StaticValue", "dimmer", "SLIDER", listOf(
        ParameterInfo("value", "ubyte", "255", "Fixed value")
    ), dimmerProperties),

    // Colour effects
    EffectTypeInfo("ColourCycle", "colour", "COLOUR", listOf(
        ParameterInfo("colours", "colourList", "red,green,blue", "Comma-separated colours"),
        ParameterInfo("fadeRatio", "double", "0.5", "Crossfade ratio")
    ), colourProperties),
    EffectTypeInfo("RainbowCycle", "colour", "COLOUR", listOf(
        ParameterInfo("saturation", "float", "1.0", "Colour saturation"),
        ParameterInfo("brightness", "float", "1.0", "Colour brightness")
    ), colourProperties),
    EffectTypeInfo("ColourStrobe", "colour", "COLOUR", listOf(
        ParameterInfo("onColor", "colour", "white", "Flash colour"),
        ParameterInfo("offColor", "colour", "black", "Off colour"),
        ParameterInfo("onRatio", "double", "0.1", "On time ratio")
    ), colourProperties),
    EffectTypeInfo("ColourPulse", "colour", "COLOUR", listOf(
        ParameterInfo("colorA", "colour", "black", "First colour"),
        ParameterInfo("colorB", "colour", "white", "Second colour")
    ), colourProperties),
    EffectTypeInfo("ColourFade", "colour", "COLOUR", listOf(
        ParameterInfo("fromColor", "colour", "red", "Starting colour"),
        ParameterInfo("toColor", "colour", "blue", "Ending colour"),
        ParameterInfo("pingPong", "boolean", "true", "Fade back to start")
    ), colourProperties),
    EffectTypeInfo("ColourFlicker", "colour", "COLOUR", listOf(
        ParameterInfo("baseColor", "colour", "orange", "Base colour"),
        ParameterInfo("variation", "int", "50", "Maximum RGB variation")
    ), colourProperties),
    EffectTypeInfo("StaticColour", "colour", "COLOUR", listOf(
        ParameterInfo("color", "colour", "white", "Fixed colour")
    ), colourProperties),

    // Position effects
    EffectTypeInfo("Circle", "position", "POSITION", listOf(
        ParameterInfo("panCenter", "ubyte", "128", "Pan center position"),
        ParameterInfo("tiltCenter", "ubyte", "128", "Tilt center position"),
        ParameterInfo("panRadius", "ubyte", "64", "Pan radius"),
        ParameterInfo("tiltRadius", "ubyte", "64", "Tilt radius")
    ), positionProperties),
    EffectTypeInfo("Figure8", "position", "POSITION", listOf(
        ParameterInfo("panCenter", "ubyte", "128", "Pan center position"),
        ParameterInfo("tiltCenter", "ubyte", "128", "Tilt center position"),
        ParameterInfo("panRadius", "ubyte", "64", "Pan radius"),
        ParameterInfo("tiltRadius", "ubyte", "32", "Tilt radius")
    ), positionProperties),
    EffectTypeInfo("Sweep", "position", "POSITION", listOf(
        ParameterInfo("startPan", "ubyte", "64", "Start pan position"),
        ParameterInfo("startTilt", "ubyte", "128", "Start tilt position"),
        ParameterInfo("endPan", "ubyte", "192", "End pan position"),
        ParameterInfo("endTilt", "ubyte", "128", "End tilt position"),
        ParameterInfo("curve", "easingCurve", "SINE_IN_OUT", "Easing curve"),
        ParameterInfo("pingPong", "boolean", "true", "Return to start")
    ), positionProperties),
    EffectTypeInfo("PanSweep", "position", "POSITION", listOf(
        ParameterInfo("startPan", "ubyte", "64", "Start pan position"),
        ParameterInfo("endPan", "ubyte", "192", "End pan position"),
        ParameterInfo("tilt", "ubyte", "128", "Fixed tilt position"),
        ParameterInfo("curve", "easingCurve", "SINE_IN_OUT", "Easing curve"),
        ParameterInfo("pingPong", "boolean", "true", "Return to start")
    ), positionProperties),
    EffectTypeInfo("TiltSweep", "position", "POSITION", listOf(
        ParameterInfo("startTilt", "ubyte", "64", "Start tilt position"),
        ParameterInfo("endTilt", "ubyte", "192", "End tilt position"),
        ParameterInfo("pan", "ubyte", "128", "Fixed pan position"),
        ParameterInfo("curve", "easingCurve", "SINE_IN_OUT", "Easing curve"),
        ParameterInfo("pingPong", "boolean", "true", "Return to start")
    ), positionProperties),
    EffectTypeInfo("RandomPosition", "position", "POSITION", listOf(
        ParameterInfo("panCenter", "ubyte", "128", "Pan center position"),
        ParameterInfo("tiltCenter", "ubyte", "128", "Tilt center position"),
        ParameterInfo("panRange", "ubyte", "64", "Pan range"),
        ParameterInfo("tiltRange", "ubyte", "64", "Tilt range")
    ), positionProperties),
    EffectTypeInfo("StaticPosition", "position", "POSITION", listOf(
        ParameterInfo("pan", "ubyte", "128", "Fixed pan position"),
        ParameterInfo("tilt", "ubyte", "128", "Fixed tilt position")
    ), positionProperties),

    // Controls effects (settings and non-dimmer sliders)
    EffectTypeInfo("StaticValue", "controls", "SLIDER", listOf(
        ParameterInfo("value", "ubyte", "255", "Fixed value")
    ), controlsSliderProperties),
    EffectTypeInfo("StaticSetting", "controls", "SLIDER", listOf(
        ParameterInfo("level", "ubyte", "0", "DMX level for the setting")
    ), settingProperties)
)
