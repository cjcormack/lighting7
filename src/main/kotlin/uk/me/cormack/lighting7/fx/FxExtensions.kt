package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.*

/**
 * Extension functions for applying FX to fixtures in a type-safe manner.
 */

/**
 * Apply a dimmer effect to a fixture with dimmer trait.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing value
 * @return The effect ID
 */
fun FixtureWithDimmer.applyDimmerFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE
): Long {
    val fixture = this as Fixture
    val target = SliderTarget(fixture.key, "dimmer")
    val instance = FxInstance(effect, target, timing, blendMode)
    return engine.addEffect(instance)
}

/**
 * Apply a UV effect to a fixture with UV trait.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing value
 * @return The effect ID
 */
fun FixtureWithUv.applyUvFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE
): Long {
    val fixture = this as Fixture
    val target = SliderTarget(fixture.key, "uvColour")
    val instance = FxInstance(effect, target, timing, blendMode)
    return engine.addEffect(instance)
}

/**
 * Apply a colour effect to a fixture with colour trait.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing value
 * @return The effect ID
 */
fun <T : FixtureColour<*>> FixtureWithColour<T>.applyColourFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE
): Long {
    val fixture = this as Fixture
    val target = ColourTarget(fixture.key)
    val instance = FxInstance(effect, target, timing, blendMode)
    return engine.addEffect(instance)
}

/**
 * Apply a position effect to a fixture with position trait.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing value
 * @return The effect ID
 */
fun FixtureWithPosition.applyPositionFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE
): Long {
    val fixture = this as Fixture
    val target = PositionTarget(fixture.key)
    val instance = FxInstance(effect, target, timing, blendMode)
    return engine.addEffect(instance)
}

/**
 * DSL-style builder for FX configuration.
 *
 * Provides a convenient way to add multiple effects to a fixture.
 *
 * Example:
 * ```
 * fixture.fx(fxEngine) {
 *     dimmer(SineWave(), BeatDivision.HALF)
 *     colour(RainbowCycle(), BeatDivision.ONE_BAR)
 * }
 * ```
 */
class FxBuilder(
    private val engine: FxEngine,
    private val fixtureKey: String
) {
    /**
     * Add a dimmer effect.
     *
     * @param effect The effect to apply
     * @param beatDivision Duration of one effect cycle in beats
     * @param blendMode How to blend with existing value
     * @param phaseOffset Phase offset for chase effects (0.0-1.0)
     * @return The effect ID
     */
    fun dimmer(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        phaseOffset: Double = 0.0
    ): Long {
        val instance = FxInstance(
            effect = effect,
            target = SliderTarget(fixtureKey, "dimmer"),
            timing = FxTiming(beatDivision),
            blendMode = blendMode
        )
        instance.phaseOffset = phaseOffset
        return engine.addEffect(instance)
    }

    /**
     * Add a colour effect.
     *
     * @param effect The effect to apply
     * @param beatDivision Duration of one effect cycle in beats
     * @param blendMode How to blend with existing value
     * @param phaseOffset Phase offset for chase effects (0.0-1.0)
     * @return The effect ID
     */
    fun colour(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        phaseOffset: Double = 0.0
    ): Long {
        val instance = FxInstance(
            effect = effect,
            target = ColourTarget(fixtureKey),
            timing = FxTiming(beatDivision),
            blendMode = blendMode
        )
        instance.phaseOffset = phaseOffset
        return engine.addEffect(instance)
    }

    /**
     * Add a UV effect.
     *
     * @param effect The effect to apply
     * @param beatDivision Duration of one effect cycle in beats
     * @param blendMode How to blend with existing value
     * @param phaseOffset Phase offset for chase effects (0.0-1.0)
     * @return The effect ID
     */
    fun uv(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        phaseOffset: Double = 0.0
    ): Long {
        val instance = FxInstance(
            effect = effect,
            target = SliderTarget(fixtureKey, "uvColour"),
            timing = FxTiming(beatDivision),
            blendMode = blendMode
        )
        instance.phaseOffset = phaseOffset
        return engine.addEffect(instance)
    }

    /**
     * Add a position effect.
     *
     * @param effect The effect to apply
     * @param beatDivision Duration of one effect cycle in beats
     * @param blendMode How to blend with existing value
     * @param phaseOffset Phase offset for chase effects (0.0-1.0)
     * @return The effect ID
     */
    fun position(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        phaseOffset: Double = 0.0
    ): Long {
        val instance = FxInstance(
            effect = effect,
            target = PositionTarget(fixtureKey),
            timing = FxTiming(beatDivision),
            blendMode = blendMode
        )
        instance.phaseOffset = phaseOffset
        return engine.addEffect(instance)
    }
}

/**
 * Apply multiple effects to a fixture using a DSL builder.
 *
 * @param engine The FX engine
 * @param block Configuration block for adding effects
 */
fun Fixture.fx(engine: FxEngine, block: FxBuilder.() -> Unit) {
    FxBuilder(engine, this.key).block()
}

/**
 * Remove all effects targeting this fixture.
 *
 * @param engine The FX engine
 * @return Number of effects removed
 */
fun Fixture.clearFx(engine: FxEngine): Int {
    return engine.removeEffectsForFixture(this.key)
}
