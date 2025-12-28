package uk.me.cormack.lighting7.fx.group

import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fx.*

/**
 * Extension functions for applying effects to fixture groups.
 *
 * These functions automatically handle phase distribution across group members,
 * enabling sophisticated chase and synchronized effects with minimal code.
 */

/**
 * Apply a dimmer effect to all members of a group with distribution.
 *
 * @param engine The FX engine to add effects to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing values
 * @param distribution Strategy for distributing phases across members
 * @return List of effect IDs created (one per group member)
 */
fun <T> FixtureGroup<T>.applyDimmerFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName)
): List<Long> where T : Fixture, T : FixtureWithDimmer {
    return map { member ->
        val offset = distribution.calculateOffset(member, size)
        val target = SliderTarget(member.fixture.key, "dimmer")
        val instance = FxInstance(effect, target, timing, blendMode).apply {
            phaseOffset = offset
        }
        engine.addEffect(instance)
    }
}

/**
 * Apply a UV effect to all members of a group with distribution.
 *
 * @param engine The FX engine to add effects to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing values
 * @param distribution Strategy for distributing phases across members
 * @return List of effect IDs created (one per group member)
 */
fun <T> FixtureGroup<T>.applyUvFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName)
): List<Long> where T : Fixture, T : FixtureWithUv {
    return map { member ->
        val offset = distribution.calculateOffset(member, size)
        val target = SliderTarget(member.fixture.key, "uvColour")
        val instance = FxInstance(effect, target, timing, blendMode).apply {
            phaseOffset = offset
        }
        engine.addEffect(instance)
    }
}

/**
 * Apply a colour effect to all members of a group with distribution.
 *
 * @param engine The FX engine to add effects to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing values
 * @param distribution Strategy for distributing phases across members
 * @return List of effect IDs created (one per group member)
 */
fun <T> FixtureGroup<T>.applyColourFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName)
): List<Long> where T : Fixture, T : FixtureWithColour<*> {
    return map { member ->
        val offset = distribution.calculateOffset(member, size)
        val target = ColourTarget(member.fixture.key)
        val instance = FxInstance(effect, target, timing, blendMode).apply {
            phaseOffset = offset
        }
        engine.addEffect(instance)
    }
}

/**
 * Apply a position effect to all members of a group with distribution.
 *
 * Position effects can optionally apply member-specific pan/tilt offsets
 * from the group configuration.
 *
 * @param engine The FX engine to add effects to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing values
 * @param distribution Strategy for distributing phases across members
 * @param applyMemberOffsets Whether to apply member pan/tilt offsets
 * @return List of effect IDs created (one per group member)
 */
fun <T> FixtureGroup<T>.applyPositionFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName),
    applyMemberOffsets: Boolean = true
): List<Long> where T : Fixture, T : FixtureWithPosition {
    return map { member ->
        val offset = distribution.calculateOffset(member, size)

        // Wrap effect with member offsets if configured
        val adjustedEffect = if (applyMemberOffsets &&
            (member.metadata.panOffset != 0.0 || member.metadata.tiltOffset != 0.0)
        ) {
            OffsetPositionEffect(
                effect,
                member.metadata.panOffset,
                member.metadata.tiltOffset
            )
        } else effect

        val target = PositionTarget(member.fixture.key)
        val instance = FxInstance(adjustedEffect, target, timing, blendMode).apply {
            phaseOffset = offset
        }
        engine.addEffect(instance)
    }
}

/**
 * Clear all effects for fixtures in this group.
 *
 * @param engine The FX engine
 * @return Total number of effects removed
 */
fun FixtureGroup<*>.clearFx(engine: FxEngine): Int {
    return sumOf { engine.removeEffectsForFixture(it.fixture.key) }
}

/**
 * Helper effect that applies pan/tilt offsets to another position effect.
 *
 * This wraps a position effect and adds constant offsets to the output,
 * useful for spreading multiple fixtures across a stage while using
 * the same base movement pattern.
 */
private class OffsetPositionEffect(
    private val delegate: Effect,
    private val panOffsetDegrees: Double,
    private val tiltOffsetDegrees: Double
) : Effect {
    override val name get() = "${delegate.name}+Offset"
    override val outputType get() = FxOutputType.POSITION

    override fun calculate(phase: Double): FxOutput {
        val base = delegate.calculate(phase)
        if (base !is FxOutput.Position) return base

        // Convert offset degrees to DMX values
        // Typical moving heads: 540 degrees pan (0-255), 270 degrees tilt (0-255)
        val panOffsetDmx = (panOffsetDegrees / 540.0 * 255.0).toInt()
        val tiltOffsetDmx = (tiltOffsetDegrees / 270.0 * 255.0).toInt()

        return FxOutput.Position(
            (base.pan.toInt() + panOffsetDmx).coerceIn(0, 255).toUByte(),
            (base.tilt.toInt() + tiltOffsetDmx).coerceIn(0, 255).toUByte()
        )
    }
}

/**
 * DSL builder for applying effects to a group.
 *
 * Example:
 * ```kotlin
 * group.fx(fxEngine) {
 *     dimmer(Pulse(), BeatDivision.QUARTER, distribution = DistributionStrategy.LINEAR)
 *     colour(RainbowCycle(), BeatDivision.ONE_BAR, distribution = DistributionStrategy.UNIFIED)
 * }
 * ```
 */
class GroupFxBuilder<T : Fixture> @PublishedApi internal constructor(
    @PublishedApi internal val engine: FxEngine,
    @PublishedApi internal val group: FixtureGroup<T>
) {
    @PublishedApi
    internal val effectIds = mutableListOf<Long>()

    /**
     * Apply a dimmer effect to the group.
     * Requires the group to contain fixtures with dimmer capability.
     */
    inline fun <reified R> dimmer(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        distribution: DistributionStrategy = DistributionStrategy.fromName(group.metadata.defaultDistributionName)
    ): List<Long> where R : Fixture, R : FixtureWithDimmer {
        val typedGroup = group.requireCapable<R>()
        return typedGroup.applyDimmerFx(
            engine, effect, FxTiming(beatDivision), blendMode, distribution
        ).also { effectIds.addAll(it) }
    }

    /**
     * Apply a colour effect to the group.
     * Requires the group to contain fixtures with colour capability.
     */
    inline fun <reified R> colour(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        distribution: DistributionStrategy = DistributionStrategy.fromName(group.metadata.defaultDistributionName)
    ): List<Long> where R : Fixture, R : FixtureWithColour<*> {
        val typedGroup = group.requireCapable<R>()
        return typedGroup.applyColourFx(
            engine, effect, FxTiming(beatDivision), blendMode, distribution
        ).also { effectIds.addAll(it) }
    }

    /**
     * Apply a UV effect to the group.
     * Requires the group to contain fixtures with UV capability.
     */
    inline fun <reified R> uv(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        distribution: DistributionStrategy = DistributionStrategy.fromName(group.metadata.defaultDistributionName)
    ): List<Long> where R : Fixture, R : FixtureWithUv {
        val typedGroup = group.requireCapable<R>()
        return typedGroup.applyUvFx(
            engine, effect, FxTiming(beatDivision), blendMode, distribution
        ).also { effectIds.addAll(it) }
    }

    /**
     * Apply a position effect to the group.
     * Requires the group to contain fixtures with position capability.
     */
    inline fun <reified R> position(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        distribution: DistributionStrategy = DistributionStrategy.fromName(group.metadata.defaultDistributionName),
        applyMemberOffsets: Boolean = true
    ): List<Long> where R : Fixture, R : FixtureWithPosition {
        val typedGroup = group.requireCapable<R>()
        return typedGroup.applyPositionFx(
            engine, effect, FxTiming(beatDivision), blendMode, distribution, applyMemberOffsets
        ).also { effectIds.addAll(it) }
    }

    /** All effect IDs created by this builder */
    fun effectIds(): List<Long> = effectIds.toList()
}

/**
 * Apply multiple effects to a group using a DSL builder.
 *
 * @param engine The FX engine
 * @param block Configuration block for adding effects
 * @return List of all effect IDs created
 */
inline fun <reified T : Fixture> FixtureGroup<T>.fx(
    engine: FxEngine,
    block: GroupFxBuilder<T>.() -> Unit
): List<Long> {
    val builder = GroupFxBuilder(engine, this)
    builder.block()
    return builder.effectIds()
}
