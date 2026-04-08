package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.fx.effects.*
import java.awt.Color

/**
 * Registers all built-in effects with the [FxRegistry].
 *
 * This is the single source of truth for:
 * - Effect type names and aliases (for API/WebSocket lookup)
 * - Parameter schemas (for the library endpoint and UI)
 * - Factory functions (for creating instances from string parameters)
 *
 * Built-in effects are registered at application startup via [Show.fxRegistry].
 */
fun FxRegistry.registerBuiltInEffects() {
    registerDimmerEffects()
    registerColourEffects()
    registerPositionEffects()
    registerCompositeEffects()
    registerControlsEffects()
}

// --- Compatible property lists ---

private val dimmerProperties = listOf("dimmer", "uv")
private val controlsSliderProperties = listOf("slider")
private val colourProperties = listOf("rgbColour")
private val positionProperties = listOf("position")
private val settingProperties = listOf("setting")

// --- Dimmer Effects ---

private fun FxRegistry.registerDimmerEffects() {
    register(EffectRegistration(
        id = "SineWave",
        aliases = setOf("sine_wave", "sine"),
        name = "Sine Wave",
        category = "dimmer",
        outputType = FxOutputType.SLIDER,
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value"),
        ),
        compatibleProperties = dimmerProperties,
        factory = { params, _, _ ->
            SineWave(
                min = params["min"]?.toUByteParam() ?: 0u,
                max = params["max"]?.toUByteParam() ?: 255u,
            )
        },
    ))

    register(EffectRegistration(
        id = "RampUp",
        aliases = setOf("ramp_up"),
        name = "Ramp Up",
        category = "dimmer",
        outputType = FxOutputType.SLIDER,
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value"),
            ParameterInfo("curve", "easingCurve", "LINEAR", "Easing curve"),
        ),
        compatibleProperties = dimmerProperties,
        factory = { params, _, _ ->
            RampUp(
                min = params["min"]?.toUByteParam() ?: 0u,
                max = params["max"]?.toUByteParam() ?: 255u,
                curve = params["curve"]?.toEasingCurveParam() ?: EasingCurve.LINEAR,
            )
        },
    ))

    register(EffectRegistration(
        id = "RampDown",
        aliases = setOf("ramp_down"),
        name = "Ramp Down",
        category = "dimmer",
        outputType = FxOutputType.SLIDER,
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value"),
            ParameterInfo("curve", "easingCurve", "LINEAR", "Easing curve"),
        ),
        compatibleProperties = dimmerProperties,
        factory = { params, _, _ ->
            RampDown(
                min = params["min"]?.toUByteParam() ?: 0u,
                max = params["max"]?.toUByteParam() ?: 255u,
                curve = params["curve"]?.toEasingCurveParam() ?: EasingCurve.LINEAR,
            )
        },
    ))

    register(EffectRegistration(
        id = "Triangle",
        name = "Triangle",
        category = "dimmer",
        outputType = FxOutputType.SLIDER,
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value"),
            ParameterInfo("curve", "easingCurve", "LINEAR", "Easing curve"),
        ),
        compatibleProperties = dimmerProperties,
        factory = { params, _, _ ->
            Triangle(
                min = params["min"]?.toUByteParam() ?: 0u,
                max = params["max"]?.toUByteParam() ?: 255u,
                curve = params["curve"]?.toEasingCurveParam() ?: EasingCurve.LINEAR,
            )
        },
    ))

    register(EffectRegistration(
        id = "Pulse",
        name = "Pulse",
        category = "dimmer",
        outputType = FxOutputType.SLIDER,
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value"),
            ParameterInfo("attackRatio", "double", "0.1", "Attack portion of cycle"),
            ParameterInfo("holdRatio", "double", "0.3", "Hold portion of cycle"),
            ParameterInfo("curve", "easingCurve", "QUAD_OUT", "Easing curve"),
        ),
        compatibleProperties = dimmerProperties,
        factory = { params, _, _ ->
            Pulse(
                min = params["min"]?.toUByteParam() ?: 0u,
                max = params["max"]?.toUByteParam() ?: 255u,
                attackRatio = params["attackRatio"]?.toDoubleOrNull() ?: 0.1,
                holdRatio = params["holdRatio"]?.toDoubleOrNull() ?: 0.3,
                curve = params["curve"]?.toEasingCurveParam() ?: EasingCurve.QUAD_OUT,
            )
        },
    ))

    register(EffectRegistration(
        id = "SquareWave",
        aliases = setOf("square_wave", "square"),
        name = "Square Wave",
        category = "dimmer",
        outputType = FxOutputType.SLIDER,
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value"),
            ParameterInfo("dutyCycle", "double", "0.5", "On time ratio"),
        ),
        compatibleProperties = dimmerProperties,
        factory = { params, _, _ ->
            SquareWave(
                min = params["min"]?.toUByteParam() ?: 0u,
                max = params["max"]?.toUByteParam() ?: 255u,
                dutyCycle = params["dutyCycle"]?.toDoubleOrNull() ?: 0.5,
            )
        },
    ))

    register(EffectRegistration(
        id = "Strobe",
        name = "Strobe",
        category = "dimmer",
        outputType = FxOutputType.SLIDER,
        parameters = listOf(
            ParameterInfo("offValue", "ubyte", "0", "Value when off"),
            ParameterInfo("onValue", "ubyte", "255", "Value when on"),
            ParameterInfo("onRatio", "double", "0.1", "On time ratio"),
        ),
        compatibleProperties = dimmerProperties,
        factory = { params, _, _ ->
            Strobe(
                offValue = params["offValue"]?.toUByteParam() ?: 0u,
                onValue = params["onValue"]?.toUByteParam() ?: 255u,
                onRatio = params["onRatio"]?.toDoubleOrNull() ?: 0.1,
            )
        },
    ))

    register(EffectRegistration(
        id = "Flicker",
        name = "Flicker",
        category = "dimmer",
        outputType = FxOutputType.SLIDER,
        parameters = listOf(
            ParameterInfo("min", "ubyte", "100", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value"),
        ),
        compatibleProperties = dimmerProperties,
        factory = { params, _, _ ->
            Flicker(
                min = params["min"]?.toUByteParam() ?: 100u,
                max = params["max"]?.toUByteParam() ?: 255u,
            )
        },
    ))

    register(EffectRegistration(
        id = "Breathe",
        name = "Breathe",
        category = "dimmer",
        outputType = FxOutputType.SLIDER,
        parameters = listOf(
            ParameterInfo("min", "ubyte", "0", "Minimum value"),
            ParameterInfo("max", "ubyte", "255", "Maximum value"),
        ),
        compatibleProperties = dimmerProperties,
        factory = { params, _, _ ->
            Breathe(
                min = params["min"]?.toUByteParam() ?: 0u,
                max = params["max"]?.toUByteParam() ?: 255u,
            )
        },
    ))

    register(EffectRegistration(
        id = "CandleFlicker",
        aliases = setOf("candle_flicker", "candle"),
        name = "Candle Flicker",
        category = "dimmer",
        outputType = FxOutputType.SLIDER,
        parameters = listOf(
            ParameterInfo("baseLevel", "ubyte", "180", "Average brightness level"),
            ParameterInfo("min", "ubyte", "100", "Minimum value floor"),
            ParameterInfo("max", "ubyte", "230", "Maximum value ceiling"),
            ParameterInfo("smoothing", "double", "0.85", "Smoothing factor (0-1, higher = smoother)"),
        ),
        compatibleProperties = dimmerProperties,
        factory = { params, _, _ ->
            CandleFlicker(
                baseLevel = params["baseLevel"]?.toUByteParam() ?: 180u,
                min = params["min"]?.toUByteParam() ?: 100u,
                max = params["max"]?.toUByteParam() ?: 230u,
                smoothing = params["smoothing"]?.toDoubleOrNull() ?: 0.85,
            )
        },
    ))

    register(EffectRegistration(
        id = "StaticValue",
        aliases = setOf("static_value"),
        name = "Static Value",
        category = "dimmer",
        outputType = FxOutputType.SLIDER,
        defaultStepTiming = true,
        parameters = listOf(
            ParameterInfo("value", "ubyte", "255", "Fixed value"),
        ),
        compatibleProperties = dimmerProperties,
        factory = { params, _, _ ->
            StaticValue(
                value = params["value"]?.toUByteParam() ?: 255u,
            )
        },
    ))
}

// --- Colour Effects ---

private fun FxRegistry.registerColourEffects() {
    register(EffectRegistration(
        id = "ColourCycle",
        aliases = setOf("colour_cycle", "colorcycle", "color_cycle"),
        name = "Colour Cycle",
        category = "colour",
        outputType = FxOutputType.COLOUR,
        parameters = listOf(
            ParameterInfo("colours", "colourList", "P1,P2,P3", "Comma-separated colours"),
            ParameterInfo("fadeRatio", "double", "0.5", "Crossfade ratio"),
        ),
        compatibleProperties = colourProperties,
        factory = { params, paletteSupplier, paletteVersionSupplier ->
            val colourStrings = params["colours"]?.split(",")?.map { it.trim() } ?: listOf("P1", "P2", "P3")
            val fadeRatio = params["fadeRatio"]?.toDoubleOrNull() ?: 0.5
            if (paletteSupplier != null && paletteVersionSupplier != null) {
                PaletteColourCycle(colourStrings, fadeRatio, paletteSupplier, paletteVersionSupplier)
            } else {
                ColourCycle(colours = colourStrings.map { parseExtendedColour(it) }, fadeRatio = fadeRatio)
            }
        },
    ))

    register(EffectRegistration(
        id = "RainbowCycle",
        aliases = setOf("rainbow_cycle", "rainbow"),
        name = "Rainbow Cycle",
        category = "colour",
        outputType = FxOutputType.COLOUR,
        parameters = listOf(
            ParameterInfo("saturation", "float", "1.0", "Colour saturation"),
            ParameterInfo("brightness", "float", "1.0", "Colour brightness"),
        ),
        compatibleProperties = colourProperties,
        factory = { params, _, _ ->
            RainbowCycle(
                saturation = params["saturation"]?.toFloatOrNull() ?: 1.0f,
                brightness = params["brightness"]?.toFloatOrNull() ?: 1.0f,
            )
        },
    ))

    register(EffectRegistration(
        id = "ColourStrobe",
        aliases = setOf("colour_strobe", "colorstrobe", "color_strobe"),
        name = "Colour Strobe",
        category = "colour",
        outputType = FxOutputType.COLOUR,
        parameters = listOf(
            ParameterInfo("onColour", "colour", "P1", "Flash colour"),
            ParameterInfo("offColour", "colour", "black", "Off colour"),
            ParameterInfo("onRatio", "double", "0.1", "On time ratio"),
        ),
        compatibleProperties = colourProperties,
        factory = { params, paletteSupplier, paletteVersionSupplier ->
            val onColourStr = params["onColour"] ?: "P1"
            val offColourStr = params["offColour"] ?: "black"
            val onRatio = params["onRatio"]?.toDoubleOrNull() ?: 0.1
            if (paletteSupplier != null && paletteVersionSupplier != null) {
                PaletteColourStrobe(onColourStr, offColourStr, onRatio, paletteSupplier, paletteVersionSupplier)
            } else {
                ColourStrobe(
                    onColour = parseExtendedColour(onColourStr),
                    offColour = parseExtendedColour(offColourStr),
                    onRatio = onRatio,
                )
            }
        },
    ))

    register(EffectRegistration(
        id = "ColourPulse",
        aliases = setOf("colour_pulse", "colorpulse", "color_pulse"),
        name = "Colour Pulse",
        category = "colour",
        outputType = FxOutputType.COLOUR,
        parameters = listOf(
            ParameterInfo("colourA", "colour", "black", "First colour"),
            ParameterInfo("colourB", "colour", "P1", "Second colour"),
        ),
        compatibleProperties = colourProperties,
        factory = { params, paletteSupplier, paletteVersionSupplier ->
            val colourAStr = params["colourA"] ?: "black"
            val colourBStr = params["colourB"] ?: "P1"
            if (paletteSupplier != null && paletteVersionSupplier != null) {
                PaletteColourPulse(colourAStr, colourBStr, paletteSupplier, paletteVersionSupplier)
            } else {
                ColourPulse(
                    colourA = parseExtendedColour(colourAStr),
                    colourB = parseExtendedColour(colourBStr),
                )
            }
        },
    ))

    register(EffectRegistration(
        id = "ColourFade",
        aliases = setOf("colour_fade", "colorfade", "color_fade"),
        name = "Colour Fade",
        category = "colour",
        outputType = FxOutputType.COLOUR,
        parameters = listOf(
            ParameterInfo("fromColour", "colour", "P1", "Starting colour"),
            ParameterInfo("toColour", "colour", "P2", "Ending colour"),
            ParameterInfo("pingPong", "boolean", "true", "Fade back to start"),
        ),
        compatibleProperties = colourProperties,
        factory = { params, paletteSupplier, paletteVersionSupplier ->
            val fromColourStr = params["fromColour"] ?: "P1"
            val toColourStr = params["toColour"] ?: "P2"
            val pingPong = params["pingPong"]?.toBooleanStrictOrNull() ?: true
            if (paletteSupplier != null && paletteVersionSupplier != null) {
                PaletteColourFade(fromColourStr, toColourStr, pingPong, paletteSupplier, paletteVersionSupplier)
            } else {
                ColourFade(
                    fromColour = parseExtendedColour(fromColourStr),
                    toColour = parseExtendedColour(toColourStr),
                    pingPong = pingPong,
                )
            }
        },
    ))

    register(EffectRegistration(
        id = "ColourFlicker",
        aliases = setOf("colour_flicker", "colorflicker", "color_flicker"),
        name = "Colour Flicker",
        category = "colour",
        outputType = FxOutputType.COLOUR,
        parameters = listOf(
            ParameterInfo("baseColour", "colour", "P1", "Base colour"),
            ParameterInfo("variation", "int", "50", "Maximum RGB variation"),
        ),
        compatibleProperties = colourProperties,
        factory = { params, paletteSupplier, paletteVersionSupplier ->
            val baseColourStr = params["baseColour"] ?: "P1"
            val variation = params["variation"]?.toIntOrNull() ?: 50
            if (paletteSupplier != null && paletteVersionSupplier != null) {
                PaletteColourFlicker(baseColourStr, variation, paletteSupplier, paletteVersionSupplier)
            } else {
                ColourFlicker(
                    baseColour = parseExtendedColour(baseColourStr),
                    variation = variation,
                )
            }
        },
    ))

    register(EffectRegistration(
        id = "StaticColour",
        aliases = setOf("static_colour", "staticcolor", "static_color"),
        name = "Static Colour",
        category = "colour",
        outputType = FxOutputType.COLOUR,
        defaultStepTiming = true,
        parameters = listOf(
            ParameterInfo("colour", "colour", "P1", "Fixed colour"),
        ),
        compatibleProperties = colourProperties,
        factory = { params, paletteSupplier, paletteVersionSupplier ->
            val colourStr = params["colour"] ?: "P1"
            if (paletteSupplier != null && paletteVersionSupplier != null) {
                PaletteStaticColour(colourStr, paletteSupplier, paletteVersionSupplier)
            } else {
                StaticColour(colour = parseExtendedColour(colourStr))
            }
        },
    ))
}

// --- Position Effects ---

private fun FxRegistry.registerPositionEffects() {
    register(EffectRegistration(
        id = "Circle",
        name = "Circle",
        category = "position",
        outputType = FxOutputType.POSITION,
        parameters = listOf(
            ParameterInfo("panCenter", "ubyte", "128", "Pan center position"),
            ParameterInfo("tiltCenter", "ubyte", "128", "Tilt center position"),
            ParameterInfo("panRadius", "ubyte", "64", "Pan radius"),
            ParameterInfo("tiltRadius", "ubyte", "64", "Tilt radius"),
        ),
        compatibleProperties = positionProperties,
        factory = { params, _, _ ->
            Circle(
                panCenter = params["panCenter"]?.toUByteParam() ?: 128u,
                tiltCenter = params["tiltCenter"]?.toUByteParam() ?: 128u,
                panRadius = params["panRadius"]?.toUByteParam() ?: 64u,
                tiltRadius = params["tiltRadius"]?.toUByteParam() ?: 64u,
            )
        },
    ))

    register(EffectRegistration(
        id = "Figure8",
        aliases = setOf("figure_8"),
        name = "Figure 8",
        category = "position",
        outputType = FxOutputType.POSITION,
        parameters = listOf(
            ParameterInfo("panCenter", "ubyte", "128", "Pan center position"),
            ParameterInfo("tiltCenter", "ubyte", "128", "Tilt center position"),
            ParameterInfo("panRadius", "ubyte", "64", "Pan radius"),
            ParameterInfo("tiltRadius", "ubyte", "32", "Tilt radius"),
        ),
        compatibleProperties = positionProperties,
        factory = { params, _, _ ->
            Figure8(
                panCenter = params["panCenter"]?.toUByteParam() ?: 128u,
                tiltCenter = params["tiltCenter"]?.toUByteParam() ?: 128u,
                panRadius = params["panRadius"]?.toUByteParam() ?: 64u,
                tiltRadius = params["tiltRadius"]?.toUByteParam() ?: 32u,
            )
        },
    ))

    register(EffectRegistration(
        id = "Sweep",
        name = "Sweep",
        category = "position",
        outputType = FxOutputType.POSITION,
        parameters = listOf(
            ParameterInfo("startPan", "ubyte", "64", "Start pan position"),
            ParameterInfo("startTilt", "ubyte", "128", "Start tilt position"),
            ParameterInfo("endPan", "ubyte", "192", "End pan position"),
            ParameterInfo("endTilt", "ubyte", "128", "End tilt position"),
            ParameterInfo("curve", "easingCurve", "SINE_IN_OUT", "Easing curve"),
            ParameterInfo("pingPong", "boolean", "true", "Return to start"),
        ),
        compatibleProperties = positionProperties,
        factory = { params, _, _ ->
            Sweep(
                startPan = params["startPan"]?.toUByteParam() ?: 64u,
                startTilt = params["startTilt"]?.toUByteParam() ?: 128u,
                endPan = params["endPan"]?.toUByteParam() ?: 192u,
                endTilt = params["endTilt"]?.toUByteParam() ?: 128u,
                curve = params["curve"]?.toEasingCurveParam() ?: EasingCurve.SINE_IN_OUT,
                pingPong = params["pingPong"]?.toBooleanStrictOrNull() ?: true,
            )
        },
    ))

    register(EffectRegistration(
        id = "PanSweep",
        aliases = setOf("pan_sweep"),
        name = "Pan Sweep",
        category = "position",
        outputType = FxOutputType.POSITION,
        parameters = listOf(
            ParameterInfo("startPan", "ubyte", "64", "Start pan position"),
            ParameterInfo("endPan", "ubyte", "192", "End pan position"),
            ParameterInfo("tilt", "ubyte", "128", "Fixed tilt position"),
            ParameterInfo("curve", "easingCurve", "SINE_IN_OUT", "Easing curve"),
            ParameterInfo("pingPong", "boolean", "true", "Return to start"),
        ),
        compatibleProperties = positionProperties,
        factory = { params, _, _ ->
            PanSweep(
                startPan = params["startPan"]?.toUByteParam() ?: 64u,
                endPan = params["endPan"]?.toUByteParam() ?: 192u,
                tilt = params["tilt"]?.toUByteParam() ?: 128u,
                curve = params["curve"]?.toEasingCurveParam() ?: EasingCurve.SINE_IN_OUT,
                pingPong = params["pingPong"]?.toBooleanStrictOrNull() ?: true,
            )
        },
    ))

    register(EffectRegistration(
        id = "TiltSweep",
        aliases = setOf("tilt_sweep"),
        name = "Tilt Sweep",
        category = "position",
        outputType = FxOutputType.POSITION,
        parameters = listOf(
            ParameterInfo("startTilt", "ubyte", "64", "Start tilt position"),
            ParameterInfo("endTilt", "ubyte", "192", "End tilt position"),
            ParameterInfo("pan", "ubyte", "128", "Fixed pan position"),
            ParameterInfo("curve", "easingCurve", "SINE_IN_OUT", "Easing curve"),
            ParameterInfo("pingPong", "boolean", "true", "Return to start"),
        ),
        compatibleProperties = positionProperties,
        factory = { params, _, _ ->
            TiltSweep(
                startTilt = params["startTilt"]?.toUByteParam() ?: 64u,
                endTilt = params["endTilt"]?.toUByteParam() ?: 192u,
                pan = params["pan"]?.toUByteParam() ?: 128u,
                curve = params["curve"]?.toEasingCurveParam() ?: EasingCurve.SINE_IN_OUT,
                pingPong = params["pingPong"]?.toBooleanStrictOrNull() ?: true,
            )
        },
    ))

    register(EffectRegistration(
        id = "RandomPosition",
        aliases = setOf("random_position"),
        name = "Random Position",
        category = "position",
        outputType = FxOutputType.POSITION,
        parameters = listOf(
            ParameterInfo("panCenter", "ubyte", "128", "Pan center position"),
            ParameterInfo("tiltCenter", "ubyte", "128", "Tilt center position"),
            ParameterInfo("panRange", "ubyte", "64", "Pan range"),
            ParameterInfo("tiltRange", "ubyte", "64", "Tilt range"),
        ),
        compatibleProperties = positionProperties,
        factory = { params, _, _ ->
            RandomPosition(
                panCenter = params["panCenter"]?.toUByteParam() ?: 128u,
                tiltCenter = params["tiltCenter"]?.toUByteParam() ?: 128u,
                panRange = params["panRange"]?.toUByteParam() ?: 64u,
                tiltRange = params["tiltRange"]?.toUByteParam() ?: 64u,
            )
        },
    ))

    register(EffectRegistration(
        id = "StaticPosition",
        aliases = setOf("static_position"),
        name = "Static Position",
        category = "position",
        outputType = FxOutputType.POSITION,
        defaultStepTiming = true,
        parameters = listOf(
            ParameterInfo("pan", "ubyte", "128", "Fixed pan position"),
            ParameterInfo("tilt", "ubyte", "128", "Fixed tilt position"),
        ),
        compatibleProperties = positionProperties,
        factory = { params, _, _ ->
            StaticPosition(
                pan = params["pan"]?.toUByteParam() ?: 128u,
                tilt = params["tilt"]?.toUByteParam() ?: 128u,
            )
        },
    ))
}

// --- Composite Effects ---

private fun FxRegistry.registerCompositeEffects() {
    register(EffectRegistration(
        id = "LightningStrike",
        aliases = setOf("lightning_strike", "lightning"),
        name = "Lightning Strike",
        category = "composite",
        outputType = FxOutputType.SLIDER,
        parameters = listOf(
            ParameterInfo("maxBrightness", "ubyte", "255", "Peak brightness during flash"),
            ParameterInfo("minBrightness", "ubyte", "0", "Base brightness between strikes"),
            ParameterInfo("flashColour", "colour", "white", "Colour during flash"),
            ParameterInfo("decayColour", "colour", "#5064ff", "Colour during decay"),
            ParameterInfo("ambientColour", "colour", "black", "Colour between strikes"),
        ),
        compatibleProperties = dimmerProperties,
        factory = { params, _, _ ->
            LightningStrike(
                maxBrightness = params["maxBrightness"]?.toUByteParam() ?: 255u,
                minBrightness = params["minBrightness"]?.toUByteParam() ?: 0u,
                flashColour = params["flashColour"]?.let { parseExtendedColour(it) }
                    ?: ExtendedColour.fromColor(Color.WHITE),
                decayColour = params["decayColour"]?.let { parseExtendedColour(it) }
                    ?: ExtendedColour.fromColor(Color(80, 100, 255)),
                ambientColour = params["ambientColour"]?.let { parseExtendedColour(it) }
                    ?: ExtendedColour.BLACK,
            )
        },
    ))
}

// --- Controls Effects (settings and non-dimmer sliders) ---

private fun FxRegistry.registerControlsEffects() {
    // StaticValue also serves as a controls effect for non-dimmer sliders
    register(EffectRegistration(
        id = "ControlsStaticValue",
        aliases = setOf("controls_static_value"),
        name = "Static Value",
        category = "controls",
        outputType = FxOutputType.SLIDER,
        defaultStepTiming = true,
        parameters = listOf(
            ParameterInfo("value", "ubyte", "255", "Fixed value"),
        ),
        compatibleProperties = controlsSliderProperties,
        factory = { params, _, _ ->
            StaticValue(
                value = params["value"]?.toUByteParam() ?: 255u,
            )
        },
    ))

    register(EffectRegistration(
        id = "StaticSetting",
        aliases = setOf("static_setting"),
        name = "Static Setting",
        category = "controls",
        outputType = FxOutputType.SLIDER,
        parameters = listOf(
            ParameterInfo("level", "ubyte", "0", "DMX level for the setting"),
        ),
        compatibleProperties = settingProperties,
        factory = { params, _, _ ->
            StaticSetting(
                level = params["level"]?.toUByteParam() ?: 0u,
            )
        },
    ))
}
