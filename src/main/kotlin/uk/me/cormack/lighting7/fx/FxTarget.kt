package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color

/**
 * Reference to a fixture or group target.
 *
 * This sealed class enables FxTarget to reference either a single fixture
 * or an entire group, allowing effects to be applied uniformly to both.
 */
sealed class FxTargetRef {
    /** Key identifying the target (fixture key or group name) */
    abstract val targetKey: String

    /** Whether this references a group */
    abstract val isGroup: Boolean

    /** Reference to a single fixture by its key */
    data class FixtureRef(override val targetKey: String) : FxTargetRef() {
        override val isGroup: Boolean = false
    }

    /** Reference to a fixture group by its name */
    data class GroupRef(override val targetKey: String) : FxTargetRef() {
        override val isGroup: Boolean = true
    }

    companion object {
        /** Create a reference to a fixture */
        fun fixture(key: String) = FixtureRef(key)

        /** Create a reference to a group */
        fun group(name: String) = GroupRef(name)
    }
}

/**
 * Represents a property on a fixture or group that can be targeted by an effect.
 *
 * FxTarget abstracts how effect outputs are applied to fixture properties,
 * handling type conversion and blend mode application. Targets can reference
 * either a single fixture or a fixture group.
 */
sealed class FxTarget {
    /** Reference to the fixture or group this target applies to */
    abstract val targetRef: FxTargetRef

    /** Name of the property being targeted */
    abstract val propertyName: String

    /** Target key (fixture key or group name) */
    val targetKey: String get() = targetRef.targetKey

    /** Whether this target references a group */
    val isGroupTarget: Boolean get() = targetRef.isGroup

    /**
     * Apply an effect output value to a single fixture.
     *
     * @param fixture The fixture to apply to
     * @param output The effect output value to apply
     * @param blendMode How to blend with existing value
     */
    abstract fun applyValueToFixture(
        fixture: Fixture,
        output: FxOutput,
        blendMode: BlendMode
    )

    /**
     * Get the current value from a fixture (before FX).
     *
     * @param fixture The fixture to read from
     * @return The current value as an FxOutput
     */
    abstract fun getCurrentValueFromFixture(fixture: Fixture): FxOutput

    /**
     * Apply an effect output value to this target using the fixture registry.
     * For fixture targets, applies to the single fixture.
     * For group targets, this method should be called per-member by the engine.
     *
     * @param fixtures The fixture registry with transaction
     * @param fixtureKey The specific fixture key to apply to
     * @param output The effect output value to apply
     * @param blendMode How to blend with existing value
     */
    fun applyValue(
        fixtures: Fixtures.FixturesWithTransaction,
        fixtureKey: String,
        output: FxOutput,
        blendMode: BlendMode
    ) {
        val fixture = fixtures.untypedFixture(fixtureKey)
        applyValueToFixture(fixture, output, blendMode)
    }
}

/**
 * Target a slider property (dimmer, UV, etc.)
 */
data class SliderTarget(
    override val targetRef: FxTargetRef,
    override val propertyName: String
) : FxTarget() {

    /** Convenience constructor for targeting a single fixture */
    constructor(fixtureKey: String, propertyName: String) :
        this(FxTargetRef.fixture(fixtureKey), propertyName)

    override fun applyValueToFixture(
        fixture: Fixture,
        output: FxOutput,
        blendMode: BlendMode
    ) {
        if (output !is FxOutput.Slider) return

        val slider = getSlider(fixture) ?: return

        val baseValue = slider.value
        val newValue = applyBlendMode(baseValue, output.value, blendMode)
        slider.value = newValue
    }

    override fun getCurrentValueFromFixture(fixture: Fixture): FxOutput {
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

    companion object {
        /** Create a slider target for a group */
        fun forGroup(groupName: String, propertyName: String) =
            SliderTarget(FxTargetRef.group(groupName), propertyName)
    }
}

/**
 * Target RGB colour property.
 */
data class ColourTarget(
    override val targetRef: FxTargetRef,
    override val propertyName: String = "rgbColour"
) : FxTarget() {

    /** Convenience constructor for targeting a single fixture */
    constructor(fixtureKey: String) : this(FxTargetRef.fixture(fixtureKey))

    override fun applyValueToFixture(
        fixture: Fixture,
        output: FxOutput,
        blendMode: BlendMode
    ) {
        if (output !is FxOutput.Colour) return

        val colour = (fixture as? FixtureWithColour<*>)?.rgbColour ?: return

        val baseColour = colour.value
        val newColour = applyBlendMode(baseColour, output.color, blendMode)
        colour.value = newColour
    }

    override fun getCurrentValueFromFixture(fixture: Fixture): FxOutput {
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

    companion object {
        /** Create a colour target for a group */
        fun forGroup(groupName: String) = ColourTarget(FxTargetRef.group(groupName))
    }
}

/**
 * Target pan/tilt position properties.
 */
data class PositionTarget(
    override val targetRef: FxTargetRef,
    override val propertyName: String = "position"
) : FxTarget() {

    /** Convenience constructor for targeting a single fixture */
    constructor(fixtureKey: String) : this(FxTargetRef.fixture(fixtureKey))

    override fun applyValueToFixture(
        fixture: Fixture,
        output: FxOutput,
        blendMode: BlendMode
    ) {
        if (output !is FxOutput.Position) return

        val positionFixture = fixture as? FixtureWithPosition ?: return

        val basePan = positionFixture.pan.value
        val baseTilt = positionFixture.tilt.value

        val newPan = applyBlendMode(basePan, output.pan, blendMode)
        val newTilt = applyBlendMode(baseTilt, output.tilt, blendMode)

        positionFixture.pan.value = newPan
        positionFixture.tilt.value = newTilt
    }

    override fun getCurrentValueFromFixture(fixture: Fixture): FxOutput {
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

    companion object {
        /** Create a position target for a group */
        fun forGroup(groupName: String) = PositionTarget(FxTargetRef.group(groupName))
    }
}
