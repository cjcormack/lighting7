package uk.me.cormack.lighting7.fx.effects

import uk.me.cormack.lighting7.fx.*

/**
 * Palette-aware colour effect wrappers.
 *
 * These wrappers store raw colour parameter strings instead of parsed colours.
 * On each [calculate] call, colour strings are resolved against the current palette
 * via [resolveColour], enabling instant colour scheme changes across all running effects.
 *
 * A palette version cache avoids re-parsing colours on every tick when the palette
 * hasn't changed.
 */

/**
 * Palette-aware wrapper for [ColourCycle].
 *
 * Resolves colour strings (which may include palette refs like "P1", "P2")
 * against the live palette on each calculation.
 */
class PaletteColourCycle(
    private val colourStrings: List<String>,
    private val fadeRatio: Double = 0.5,
    private val paletteSupplier: () -> List<ExtendedColour>,
    private val versionSupplier: () -> Long,
) : Effect {
    override val name = "Colour Cycle"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf(
        "colours" to colourStrings.joinToString(","),
        "fadeRatio" to fadeRatio.toString()
    )

    private var cachedVersion = -1L
    private var cachedDelegate: ColourCycle? = null

    private fun delegate(): ColourCycle {
        val version = versionSupplier()
        if (version != cachedVersion || cachedDelegate == null) {
            val palette = paletteSupplier()
            val colours = colourStrings.flatMap { str ->
                if (isAllPaletteRef(str) && palette.isNotEmpty()) palette
                else listOf(resolveColour(str, palette))
            }
            cachedDelegate = ColourCycle(colours, fadeRatio)
            cachedVersion = version
        }
        return cachedDelegate!!
    }

    override fun calculate(phase: Double, context: EffectContext): FxOutput = delegate().calculate(phase, context)
}

/**
 * Palette-aware wrapper for [ColourStrobe].
 */
class PaletteColourStrobe(
    private val onColorStr: String,
    private val offColorStr: String,
    private val onRatio: Double = 0.1,
    private val paletteSupplier: () -> List<ExtendedColour>,
    private val versionSupplier: () -> Long,
) : Effect {
    override val name = "Colour Strobe"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf(
        "onColor" to onColorStr,
        "offColor" to offColorStr,
        "onRatio" to onRatio.toString()
    )

    private var cachedVersion = -1L
    private var cachedDelegate: ColourStrobe? = null

    private fun delegate(): ColourStrobe {
        val version = versionSupplier()
        if (version != cachedVersion || cachedDelegate == null) {
            val palette = paletteSupplier()
            cachedDelegate = ColourStrobe(
                onColor = resolveColour(onColorStr, palette),
                offColor = resolveColour(offColorStr, palette),
                onRatio = onRatio
            )
            cachedVersion = version
        }
        return cachedDelegate!!
    }

    override fun calculate(phase: Double, context: EffectContext): FxOutput = delegate().calculate(phase, context)
}

/**
 * Palette-aware wrapper for [ColourPulse].
 */
class PaletteColourPulse(
    private val colorAStr: String,
    private val colorBStr: String,
    private val paletteSupplier: () -> List<ExtendedColour>,
    private val versionSupplier: () -> Long,
) : Effect {
    override val name = "Colour Pulse"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf(
        "colorA" to colorAStr,
        "colorB" to colorBStr
    )

    private var cachedVersion = -1L
    private var cachedDelegate: ColourPulse? = null

    private fun delegate(): ColourPulse {
        val version = versionSupplier()
        if (version != cachedVersion || cachedDelegate == null) {
            val palette = paletteSupplier()
            cachedDelegate = ColourPulse(
                colorA = resolveColour(colorAStr, palette),
                colorB = resolveColour(colorBStr, palette)
            )
            cachedVersion = version
        }
        return cachedDelegate!!
    }

    override fun calculate(phase: Double, context: EffectContext): FxOutput = delegate().calculate(phase, context)
}

/**
 * Palette-aware wrapper for [ColourFade].
 */
class PaletteColourFade(
    private val fromColorStr: String,
    private val toColorStr: String,
    private val pingPong: Boolean = true,
    private val paletteSupplier: () -> List<ExtendedColour>,
    private val versionSupplier: () -> Long,
) : Effect {
    override val name = "Colour Fade"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf(
        "fromColor" to fromColorStr,
        "toColor" to toColorStr,
        "pingPong" to pingPong.toString()
    )

    private var cachedVersion = -1L
    private var cachedDelegate: ColourFade? = null

    private fun delegate(): ColourFade {
        val version = versionSupplier()
        if (version != cachedVersion || cachedDelegate == null) {
            val palette = paletteSupplier()
            cachedDelegate = ColourFade(
                fromColor = resolveColour(fromColorStr, palette),
                toColor = resolveColour(toColorStr, palette),
                pingPong = pingPong
            )
            cachedVersion = version
        }
        return cachedDelegate!!
    }

    override fun calculate(phase: Double, context: EffectContext): FxOutput = delegate().calculate(phase, context)
}

/**
 * Palette-aware wrapper for [ColourFlicker].
 */
class PaletteColourFlicker(
    private val baseColorStr: String,
    private val variation: Int = 50,
    private val paletteSupplier: () -> List<ExtendedColour>,
    private val versionSupplier: () -> Long,
) : Effect {
    override val name = "Colour Flicker"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf(
        "baseColor" to baseColorStr,
        "variation" to variation.toString()
    )

    private var cachedVersion = -1L
    private var cachedDelegate: ColourFlicker? = null

    private fun delegate(): ColourFlicker {
        val version = versionSupplier()
        if (version != cachedVersion || cachedDelegate == null) {
            val palette = paletteSupplier()
            cachedDelegate = ColourFlicker(
                baseColor = resolveColour(baseColorStr, palette),
                variation = variation
            )
            cachedVersion = version
        }
        return cachedDelegate!!
    }

    override fun calculate(phase: Double, context: EffectContext): FxOutput = delegate().calculate(phase, context)
}

/**
 * Palette-aware wrapper for [StaticColour].
 */
class PaletteStaticColour(
    private val colorStr: String,
    private val paletteSupplier: () -> List<ExtendedColour>,
    private val versionSupplier: () -> Long,
) : Effect {
    override val name = "Static Colour"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf("color" to colorStr)

    private var cachedVersion = -1L
    private var cachedDelegate: StaticColour? = null

    private fun delegate(): StaticColour {
        val version = versionSupplier()
        if (version != cachedVersion || cachedDelegate == null) {
            val palette = paletteSupplier()
            cachedDelegate = StaticColour(color = resolveColour(colorStr, palette))
            cachedVersion = version
        }
        return cachedDelegate!!
    }

    override fun calculate(phase: Double, context: EffectContext): FxOutput = delegate().calculate(phase, context)
}
