package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
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
            val effects = state.show.fxEngine.getActiveEffects().map { it.toDto() }
            call.respond(effects)
        }

        post<AddEffect> {
            val request = call.receive<AddEffectRequest>()
            try {
                val effect = createEffectFromRequest(request)
                val target = createTargetFromRequest(request)
                val timing = FxTiming(
                    beatDivision = request.beatDivision,
                    startOnBeat = request.startOnBeat
                )
                val blendMode = BlendMode.valueOf(request.blendMode)

                val instance = FxInstance(effect, target, timing, blendMode)
                instance.phaseOffset = request.phaseOffset

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
    val parameters: Map<String, String> = emptyMap()
)

@Serializable
data class AddEffectResponse(val effectId: Long)

@Serializable
data class ClearEffectsResponse(val removedCount: Int)

@Serializable
data class ErrorResponse(val error: String)

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
    val currentPhase: Double
)

@Serializable
data class EffectTypeInfo(
    val name: String,
    val category: String,
    val outputType: String,
    val parameters: List<ParameterInfo>
)

@Serializable
data class ParameterInfo(
    val name: String,
    val type: String,
    val defaultValue: String,
    val description: String = ""
)

// Helper functions

private fun FxInstance.toDto() = EffectDto(
    id = id,
    effectType = effect.name,
    targetKey = target.targetKey,
    propertyName = target.propertyName,
    beatDivision = timing.beatDivision,
    blendMode = blendMode.name,
    isRunning = isRunning,
    phaseOffset = phaseOffset,
    currentPhase = lastPhase
)

private fun createTargetFromRequest(request: AddEffectRequest): FxTarget {
    return when (request.propertyName) {
        "dimmer" -> SliderTarget(request.fixtureKey, "dimmer")
        "uv" -> SliderTarget(request.fixtureKey, "uv")
        "rgbColour", "colour" -> ColourTarget(request.fixtureKey)
        "position" -> PositionTarget(request.fixtureKey)
        else -> throw IllegalArgumentException("Unknown property: ${request.propertyName}")
    }
}

private fun createEffectFromRequest(request: AddEffectRequest): Effect {
    val params = request.parameters

    return when (request.effectType.lowercase()) {
        // Dimmer effects
        "sinewave", "sine_wave" -> SineWave(
            min = params["min"]?.toUByteOrNull() ?: 0u,
            max = params["max"]?.toUByteOrNull() ?: 255u
        )
        "rampup", "ramp_up" -> RampUp(
            min = params["min"]?.toUByteOrNull() ?: 0u,
            max = params["max"]?.toUByteOrNull() ?: 255u
        )
        "rampdown", "ramp_down" -> RampDown(
            min = params["min"]?.toUByteOrNull() ?: 0u,
            max = params["max"]?.toUByteOrNull() ?: 255u
        )
        "triangle" -> Triangle(
            min = params["min"]?.toUByteOrNull() ?: 0u,
            max = params["max"]?.toUByteOrNull() ?: 255u
        )
        "pulse" -> Pulse(
            min = params["min"]?.toUByteOrNull() ?: 0u,
            max = params["max"]?.toUByteOrNull() ?: 255u,
            attackRatio = params["attackRatio"]?.toDoubleOrNull() ?: 0.1,
            holdRatio = params["holdRatio"]?.toDoubleOrNull() ?: 0.3
        )
        "squarewave", "square_wave" -> SquareWave(
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

        // Colour effects
        "colourcycle", "colour_cycle", "colorcycle", "color_cycle" -> {
            val colourStrings = params["colours"]?.split(",") ?: listOf("red", "green", "blue")
            val colours = colourStrings.map { parseColor(it.trim()) }
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
            onColor = params["onColor"]?.let { parseColor(it) } ?: Color.WHITE,
            offColor = params["offColor"]?.let { parseColor(it) } ?: Color.BLACK,
            onRatio = params["onRatio"]?.toDoubleOrNull() ?: 0.1
        )
        "colourpulse", "colour_pulse", "colorpulse", "color_pulse" -> ColourPulse(
            colorA = params["colorA"]?.let { parseColor(it) } ?: Color.BLACK,
            colorB = params["colorB"]?.let { parseColor(it) } ?: Color.WHITE
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
        "pansweep", "pan_sweep" -> PanSweep(
            startPan = params["startPan"]?.toUByteOrNull() ?: 0u,
            endPan = params["endPan"]?.toUByteOrNull() ?: 255u,
            tilt = params["tilt"]?.toUByteOrNull() ?: 128u
        )
        "tiltsweep", "tilt_sweep" -> TiltSweep(
            startTilt = params["startTilt"]?.toUByteOrNull() ?: 0u,
            endTilt = params["endTilt"]?.toUByteOrNull() ?: 255u,
            pan = params["pan"]?.toUByteOrNull() ?: 128u
        )

        else -> throw IllegalArgumentException("Unknown effect type: ${request.effectType}")
    }
}

private fun parseColor(colorString: String): Color {
    return when (colorString.lowercase()) {
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
            // Try parsing as hex (e.g., "#FF0000" or "FF0000")
            val hex = colorString.removePrefix("#")
            if (hex.length == 6) {
                Color(hex.toInt(16))
            } else {
                Color.WHITE
            }
        }
    }
}

private fun String.toUByteOrNull(): UByte? = toIntOrNull()?.coerceIn(0, 255)?.toUByte()

// Effect library - available effects and their parameters
private val effectLibrary = listOf(
    // Dimmer effects
    EffectTypeInfo(
        name = "SineWave",
        category = "dimmer",
        outputType = "SLIDER",
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value")
        )
    ),
    EffectTypeInfo(
        name = "RampUp",
        category = "dimmer",
        outputType = "SLIDER",
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value")
        )
    ),
    EffectTypeInfo(
        name = "RampDown",
        category = "dimmer",
        outputType = "SLIDER",
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value")
        )
    ),
    EffectTypeInfo(
        name = "Triangle",
        category = "dimmer",
        outputType = "SLIDER",
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value")
        )
    ),
    EffectTypeInfo(
        name = "Pulse",
        category = "dimmer",
        outputType = "SLIDER",
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value"),
            ParameterInfo("attackRatio", "double", "0.1", "Attack portion of cycle"),
            ParameterInfo("holdRatio", "double", "0.3", "Hold portion of cycle")
        )
    ),
    EffectTypeInfo(
        name = "SquareWave",
        category = "dimmer",
        outputType = "SLIDER",
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value"),
            ParameterInfo("dutyCycle", "double", "0.5", "On time ratio")
        )
    ),
    EffectTypeInfo(
        name = "Strobe",
        category = "dimmer",
        outputType = "SLIDER",
        parameters = listOf(
            ParameterInfo("offValue", "ubyte", "0", "Value when off"),
            ParameterInfo("onValue", "ubyte", "255", "Value when on"),
            ParameterInfo("onRatio", "double", "0.1", "On time ratio")
        )
    ),
    EffectTypeInfo(
        name = "Flicker",
        category = "dimmer",
        outputType = "SLIDER",
        parameters = listOf(
            ParameterInfo("min", "ubyte", "100", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value")
        )
    ),
    EffectTypeInfo(
        name = "Breathe",
        category = "dimmer",
        outputType = "SLIDER",
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value")
        )
    ),

    // Colour effects
    EffectTypeInfo(
        name = "ColourCycle",
        category = "colour",
        outputType = "COLOUR",
        parameters = listOf(
            ParameterInfo("colours", "string", "red,green,blue", "Comma-separated colours"),
            ParameterInfo("fadeRatio", "double", "0.5", "Crossfade ratio")
        )
    ),
    EffectTypeInfo(
        name = "RainbowCycle",
        category = "colour",
        outputType = "COLOUR",
        parameters = listOf(
            ParameterInfo("saturation", "float", "1.0", "Colour saturation"),
            ParameterInfo("brightness", "float", "1.0", "Colour brightness")
        )
    ),
    EffectTypeInfo(
        name = "ColourStrobe",
        category = "colour",
        outputType = "COLOUR",
        parameters = listOf(
            ParameterInfo("onColor", "string", "white", "Flash colour"),
            ParameterInfo("offColor", "string", "black", "Off colour"),
            ParameterInfo("onRatio", "double", "0.1", "On time ratio")
        )
    ),
    EffectTypeInfo(
        name = "ColourPulse",
        category = "colour",
        outputType = "COLOUR",
        parameters = listOf(
            ParameterInfo("colorA", "string", "black", "First colour"),
            ParameterInfo("colorB", "string", "white", "Second colour")
        )
    ),

    // Position effects
    EffectTypeInfo(
        name = "Circle",
        category = "position",
        outputType = "POSITION",
        parameters = listOf(
            ParameterInfo("panCenter", "ubyte", "128", "Pan center position"),
            ParameterInfo("tiltCenter", "ubyte", "128", "Tilt center position"),
            ParameterInfo("panRadius", "ubyte", "64", "Pan radius"),
            ParameterInfo("tiltRadius", "ubyte", "64", "Tilt radius")
        )
    ),
    EffectTypeInfo(
        name = "Figure8",
        category = "position",
        outputType = "POSITION",
        parameters = listOf(
            ParameterInfo("panCenter", "ubyte", "128", "Pan center position"),
            ParameterInfo("tiltCenter", "ubyte", "128", "Tilt center position"),
            ParameterInfo("panRadius", "ubyte", "64", "Pan radius"),
            ParameterInfo("tiltRadius", "ubyte", "32", "Tilt radius")
        )
    ),
    EffectTypeInfo(
        name = "PanSweep",
        category = "position",
        outputType = "POSITION",
        parameters = listOf(
            ParameterInfo("startPan", "ubyte", "0", "Start pan position"),
            ParameterInfo("endPan", "ubyte", "255", "End pan position"),
            ParameterInfo("tilt", "ubyte", "128", "Fixed tilt position")
        )
    ),
    EffectTypeInfo(
        name = "TiltSweep",
        category = "position",
        outputType = "POSITION",
        parameters = listOf(
            ParameterInfo("startTilt", "ubyte", "0", "Start tilt position"),
            ParameterInfo("endTilt", "ubyte", "255", "End tilt position"),
            ParameterInfo("pan", "ubyte", "128", "Fixed pan position")
        )
    )
)
