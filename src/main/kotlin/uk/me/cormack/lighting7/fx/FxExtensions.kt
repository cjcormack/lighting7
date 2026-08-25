package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.FixtureTarget
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithUv
import java.util.UUID

/**
 * Extension functions for applying FX to fixtures in a type-safe manner.
 *
 * Every entry point here takes `speedMasterUuid` and `rateSpeedMasterUuid`, so a
 * script-applied effect can address the same speed masters as one added through REST or
 * carried in a Look (sweep item **B2** — before it, every script effect pinned to master 1).
 * Both default to null, which is the persisted "no explicit master" value: null
 * `speedMasterUuid` means master 1, null `rateSpeedMasterUuid` means unscaled. The uuids are
 * bound to runtime bank slots by [FxEngine.addEffect], so an unknown uuid degrades to master 1
 * rather than failing the apply.
 */

/**
 * Apply a dimmer effect to a fixture with dimmer trait.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing value
 * @param speedMasterUuid Speed master driving the effect's phase (null → master 1)
 * @param rateSpeedMasterUuid Rate master scaling a WALL_CLOCK cycle (null → unscaled)
 * @return The effect ID
 */
fun <T> T.applyDimmerFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    speedMasterUuid: UUID? = null,
    rateSpeedMasterUuid: UUID? = null,
): Long where T : FixtureTarget, T : WithDimmer {
    val target = SliderTarget(this.targetKey, "dimmer")
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        this.speedMasterUuid = speedMasterUuid
        this.rateSpeedMasterUuid = rateSpeedMasterUuid
    }
    return engine.addEffect(instance)
}

/**
 * Apply a UV effect to a fixture with UV trait.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing value
 * @param speedMasterUuid Speed master driving the effect's phase (null → master 1)
 * @param rateSpeedMasterUuid Rate master scaling a WALL_CLOCK cycle (null → unscaled)
 * @return The effect ID
 */
fun <T> T.applyUvFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    speedMasterUuid: UUID? = null,
    rateSpeedMasterUuid: UUID? = null,
): Long where T : FixtureTarget, T : WithUv {
    val target = SliderTarget(this.targetKey, "uv")
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        this.speedMasterUuid = speedMasterUuid
        this.rateSpeedMasterUuid = rateSpeedMasterUuid
    }
    return engine.addEffect(instance)
}

/**
 * Apply a colour effect to a fixture with colour trait.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing value
 * @param speedMasterUuid Speed master driving the effect's phase (null → master 1)
 * @param rateSpeedMasterUuid Rate master scaling a WALL_CLOCK cycle (null → unscaled)
 * @return The effect ID
 */
fun <T> T.applyColourFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    speedMasterUuid: UUID? = null,
    rateSpeedMasterUuid: UUID? = null,
): Long where T : FixtureTarget, T : WithColour {
    val target = ColourTarget(this.targetKey)
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        this.speedMasterUuid = speedMasterUuid
        this.rateSpeedMasterUuid = rateSpeedMasterUuid
    }
    return engine.addEffect(instance)
}

/**
 * Apply a position effect to a fixture with position trait.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing value
 * @param speedMasterUuid Speed master driving the effect's phase (null → master 1)
 * @param rateSpeedMasterUuid Rate master scaling a WALL_CLOCK cycle (null → unscaled)
 * @return The effect ID
 */
fun <T> T.applyPositionFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    speedMasterUuid: UUID? = null,
    rateSpeedMasterUuid: UUID? = null,
): Long where T : FixtureTarget, T : WithPosition {
    val target = PositionTarget(this.targetKey)
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        this.speedMasterUuid = speedMasterUuid
        this.rateSpeedMasterUuid = rateSpeedMasterUuid
    }
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
 *     dimmer(effect("SineWave"), BeatDivision.HALF)
 *     colour(effect("RainbowCycle"), BeatDivision.ONE_BAR)
 * }
 * ```
 *
 * Effects are named, not constructed: `effect(id, params)` on the script base class looks the
 * type up in the [FxRegistry], which is the same vocabulary the UI and cues use.
 */
class FxBuilder(
    private val engine: FxEngine,
    private val targetKey: String
) {
    /**
     * Add a dimmer effect.
     *
     * @param effect The effect to apply
     * @param beatDivision Duration of one effect cycle in beats
     * @param blendMode How to blend with existing value
     * @param phaseOffset Phase offset for chase effects (0.0-1.0)
     * @param speedMasterUuid Speed master driving the effect's phase (null → master 1)
     * @param rateSpeedMasterUuid Rate master scaling a WALL_CLOCK cycle (null → unscaled)
     * @return The effect ID
     */
    fun dimmer(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        phaseOffset: Double = 0.0,
        speedMasterUuid: UUID? = null,
        rateSpeedMasterUuid: UUID? = null,
    ): Long {
        val instance = FxInstance(
            effect = effect,
            target = SliderTarget(targetKey, "dimmer"),
            timing = FxTiming(beatDivision),
            blendMode = blendMode
        )
        instance.phaseOffset = phaseOffset
        instance.speedMasterUuid = speedMasterUuid
        instance.rateSpeedMasterUuid = rateSpeedMasterUuid
        return engine.addEffect(instance)
    }

    /**
     * Add a colour effect.
     *
     * @param effect The effect to apply
     * @param beatDivision Duration of one effect cycle in beats
     * @param blendMode How to blend with existing value
     * @param phaseOffset Phase offset for chase effects (0.0-1.0)
     * @param speedMasterUuid Speed master driving the effect's phase (null → master 1)
     * @param rateSpeedMasterUuid Rate master scaling a WALL_CLOCK cycle (null → unscaled)
     * @return The effect ID
     */
    fun colour(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        phaseOffset: Double = 0.0,
        speedMasterUuid: UUID? = null,
        rateSpeedMasterUuid: UUID? = null,
    ): Long {
        val instance = FxInstance(
            effect = effect,
            target = ColourTarget(targetKey),
            timing = FxTiming(beatDivision),
            blendMode = blendMode
        )
        instance.phaseOffset = phaseOffset
        instance.speedMasterUuid = speedMasterUuid
        instance.rateSpeedMasterUuid = rateSpeedMasterUuid
        return engine.addEffect(instance)
    }

    /**
     * Add a UV effect.
     *
     * @param effect The effect to apply
     * @param beatDivision Duration of one effect cycle in beats
     * @param blendMode How to blend with existing value
     * @param phaseOffset Phase offset for chase effects (0.0-1.0)
     * @param speedMasterUuid Speed master driving the effect's phase (null → master 1)
     * @param rateSpeedMasterUuid Rate master scaling a WALL_CLOCK cycle (null → unscaled)
     * @return The effect ID
     */
    fun uv(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        phaseOffset: Double = 0.0,
        speedMasterUuid: UUID? = null,
        rateSpeedMasterUuid: UUID? = null,
    ): Long {
        val instance = FxInstance(
            effect = effect,
            target = SliderTarget(targetKey, "uv"),
            timing = FxTiming(beatDivision),
            blendMode = blendMode
        )
        instance.phaseOffset = phaseOffset
        instance.speedMasterUuid = speedMasterUuid
        instance.rateSpeedMasterUuid = rateSpeedMasterUuid
        return engine.addEffect(instance)
    }

    /**
     * Add a position effect.
     *
     * @param effect The effect to apply
     * @param beatDivision Duration of one effect cycle in beats
     * @param blendMode How to blend with existing value
     * @param phaseOffset Phase offset for chase effects (0.0-1.0)
     * @param speedMasterUuid Speed master driving the effect's phase (null → master 1)
     * @param rateSpeedMasterUuid Rate master scaling a WALL_CLOCK cycle (null → unscaled)
     * @return The effect ID
     */
    fun position(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        phaseOffset: Double = 0.0,
        speedMasterUuid: UUID? = null,
        rateSpeedMasterUuid: UUID? = null,
    ): Long {
        val instance = FxInstance(
            effect = effect,
            target = PositionTarget(targetKey),
            timing = FxTiming(beatDivision),
            blendMode = blendMode
        )
        instance.phaseOffset = phaseOffset
        instance.speedMasterUuid = speedMasterUuid
        instance.rateSpeedMasterUuid = rateSpeedMasterUuid
        return engine.addEffect(instance)
    }
}

/**
 * Apply multiple effects to a target using a DSL builder.
 *
 * @param engine The FX engine
 * @param block Configuration block for adding effects
 */
fun FixtureTarget.fx(engine: FxEngine, block: FxBuilder.() -> Unit) {
    FxBuilder(engine, this.targetKey).block()
}

/**
 * Remove all effects targeting this fixture.
 *
 * @param engine The FX engine
 * @return Number of effects removed
 */
fun FixtureTarget.clearFx(engine: FxEngine): Int {
    return engine.removeEffectsForFixture(this.targetKey)
}
