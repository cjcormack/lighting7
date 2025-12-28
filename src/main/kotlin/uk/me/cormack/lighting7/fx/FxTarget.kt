package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color

/**
 * Represents a property on a fixture that can be targeted by an effect.
 *
 * FxTarget abstracts how effect outputs are applied to fixture properties,
 * handling type conversion and blend mode application.
 */
sealed class FxTarget {
    /** Key of the fixture this target applies to */
    abstract val fixtureKey: String

    /** Name of the property being targeted */
    abstract val propertyName: String

    /**
     * Apply an effect output value to this target.
     *
     * @param fixtures The fixture registry with transaction
     * @param output The effect output value to apply
     * @param blendMode How to blend with existing value
     */
    abstract fun applyValue(
        fixtures: Fixtures.FixturesWithTransaction,
        output: FxOutput,
        blendMode: BlendMode
    )

    /**
     * Get the current value from this target (before FX).
     *
     * @param fixtures The fixture registry with transaction
     * @return The current value as an FxOutput
     */
    abstract fun getCurrentValue(fixtures: Fixtures.FixturesWithTransaction): FxOutput
}

/**
 * Target a slider property (dimmer, UV, etc.)
 */
data class SliderTarget(
    override val fixtureKey: String,
    override val propertyName: String
) : FxTarget() {
    override fun applyValue(
        fixtures: Fixtures.FixturesWithTransaction,
        output: FxOutput,
        blendMode: BlendMode
    ) {
        if (output !is FxOutput.Slider) return

        val fixture = fixtures.untypedFixture(fixtureKey)
        val slider = getSlider(fixture) ?: return

        val baseValue = slider.value
        val newValue = applyBlendMode(baseValue, output.value, blendMode)
        slider.value = newValue
    }

    override fun getCurrentValue(fixtures: Fixtures.FixturesWithTransaction): FxOutput {
        val fixture = fixtures.untypedFixture(fixtureKey)
        val slider = getSlider(fixture)
        return FxOutput.Slider(slider?.value ?: 0u)
    }

    private fun getSlider(fixture: Fixture): FixtureSlider? {
        return when (propertyName) {
            "dimmer" -> (fixture as? FixtureWithDimmer)?.dimmer
            "uv", "uvColour" -> (fixture as? FixtureWithUv)?.uvColour
            else -> null
        }
    }

    private fun applyBlendMode(base: UByte, effect: UByte, mode: BlendMode): UByte {
        return when (mode) {
            BlendMode.OVERRIDE -> effect
            BlendMode.ADDITIVE -> (base.toInt() + effect.toInt()).coerceIn(0, 255).toUByte()
            BlendMode.MULTIPLY -> ((base.toInt() * effect.toInt()) / 255).toUByte()
            BlendMode.MAX -> maxOf(base, effect)
            BlendMode.MIN -> minOf(base, effect)
        }
    }
}

/**
 * Target RGB colour property.
 */
data class ColourTarget(
    override val fixtureKey: String,
    override val propertyName: String = "rgbColour"
) : FxTarget() {
    override fun applyValue(
        fixtures: Fixtures.FixturesWithTransaction,
        output: FxOutput,
        blendMode: BlendMode
    ) {
        if (output !is FxOutput.Colour) return

        val fixture = fixtures.untypedFixture(fixtureKey)
        val colour = (fixture as? FixtureWithColour<*>)?.rgbColour ?: return

        val baseColour = colour.value
        val newColour = applyBlendMode(baseColour, output.color, blendMode)
        colour.value = newColour
    }

    override fun getCurrentValue(fixtures: Fixtures.FixturesWithTransaction): FxOutput {
        val fixture = fixtures.untypedFixture(fixtureKey)
        val colour = (fixture as? FixtureWithColour<*>)?.rgbColour
        return FxOutput.Colour(colour?.value ?: Color.BLACK)
    }

    private fun applyBlendMode(base: Color, effect: Color, mode: BlendMode): Color {
        return when (mode) {
            BlendMode.OVERRIDE -> effect
            BlendMode.ADDITIVE -> Color(
                (base.red + effect.red).coerceIn(0, 255),
                (base.green + effect.green).coerceIn(0, 255),
                (base.blue + effect.blue).coerceIn(0, 255)
            )
            BlendMode.MULTIPLY -> Color(
                (base.red * effect.red) / 255,
                (base.green * effect.green) / 255,
                (base.blue * effect.blue) / 255
            )
            BlendMode.MAX -> Color(
                maxOf(base.red, effect.red),
                maxOf(base.green, effect.green),
                maxOf(base.blue, effect.blue)
            )
            BlendMode.MIN -> Color(
                minOf(base.red, effect.red),
                minOf(base.green, effect.green),
                minOf(base.blue, effect.blue)
            )
        }
    }
}

/**
 * Target pan/tilt position properties.
 */
data class PositionTarget(
    override val fixtureKey: String,
    override val propertyName: String = "position"
) : FxTarget() {
    override fun applyValue(
        fixtures: Fixtures.FixturesWithTransaction,
        output: FxOutput,
        blendMode: BlendMode
    ) {
        if (output !is FxOutput.Position) return

        val fixture = fixtures.untypedFixture(fixtureKey)
        val positionFixture = fixture as? FixtureWithPosition ?: return

        val basePan = positionFixture.pan.value
        val baseTilt = positionFixture.tilt.value

        val newPan = applyBlendMode(basePan, output.pan, blendMode)
        val newTilt = applyBlendMode(baseTilt, output.tilt, blendMode)

        positionFixture.pan.value = newPan
        positionFixture.tilt.value = newTilt
    }

    override fun getCurrentValue(fixtures: Fixtures.FixturesWithTransaction): FxOutput {
        val fixture = fixtures.untypedFixture(fixtureKey)
        val positionFixture = fixture as? FixtureWithPosition
        return FxOutput.Position(
            positionFixture?.pan?.value ?: 128u,
            positionFixture?.tilt?.value ?: 128u
        )
    }

    private fun applyBlendMode(base: UByte, effect: UByte, mode: BlendMode): UByte {
        return when (mode) {
            BlendMode.OVERRIDE -> effect
            BlendMode.ADDITIVE -> (base.toInt() + effect.toInt() - 128).coerceIn(0, 255).toUByte()
            BlendMode.MULTIPLY -> ((base.toInt() * effect.toInt()) / 255).toUByte()
            BlendMode.MAX -> maxOf(base, effect)
            BlendMode.MIN -> minOf(base, effect)
        }
    }
}
