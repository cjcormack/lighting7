package uk.me.cormack.lighting7.fx.group

import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fx.*

/**
 * Extension functions for applying effects to fixture groups.
 *
 * These functions create single group-level FxInstances that automatically
 * handle phase distribution across group members at processing time.
 */

/**
 * Apply a dimmer effect to this group.
 *
 * Creates a single FxInstance that targets the entire group.
 * Distribution strategy is applied at processing time.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing values
 * @param distribution Strategy for distributing phases across members
 * @return The effect ID for the group effect
 */
fun <T> FixtureGroup<T>.applyDimmerFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName)
): Long where T : Fixture, T : FixtureWithDimmer {
    val target = SliderTarget.forGroup(name, "dimmer")
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        distributionStrategy = distribution
    }
    return engine.addEffect(instance)
}

/**
 * Apply a UV effect to this group.
 *
 * Creates a single FxInstance that targets the entire group.
 * Distribution strategy is applied at processing time.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing values
 * @param distribution Strategy for distributing phases across members
 * @return The effect ID for the group effect
 */
fun <T> FixtureGroup<T>.applyUvFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName)
): Long where T : Fixture, T : FixtureWithUv {
    val target = SliderTarget.forGroup(name, "uvColour")
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        distributionStrategy = distribution
    }
    return engine.addEffect(instance)
}

/**
 * Apply a colour effect to this group.
 *
 * Creates a single FxInstance that targets the entire group.
 * Distribution strategy is applied at processing time.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing values
 * @param distribution Strategy for distributing phases across members
 * @return The effect ID for the group effect
 */
fun <T> FixtureGroup<T>.applyColourFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName)
): Long where T : Fixture, T : FixtureWithColour<*> {
    val target = ColourTarget.forGroup(name)
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        distributionStrategy = distribution
    }
    return engine.addEffect(instance)
}

/**
 * Apply a position effect to this group.
 *
 * Creates a single FxInstance that targets the entire group.
 * Distribution strategy is applied at processing time.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing values
 * @param distribution Strategy for distributing phases across members
 * @return The effect ID for the group effect
 */
fun <T> FixtureGroup<T>.applyPositionFx(
    engine: FxEngine,
    effect: Effect,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    distribution: DistributionStrategy = DistributionStrategy.fromName(metadata.defaultDistributionName)
): Long where T : Fixture, T : FixtureWithPosition {
    val target = PositionTarget.forGroup(name)
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        distributionStrategy = distribution
    }
    return engine.addEffect(instance)
}

/**
 * Clear all effects for this group.
 *
 * This removes both:
 * - Group-level effects (effects targeting this group as a whole)
 * - Per-fixture effects (effects targeting individual fixtures in this group)
 *
 * @param engine The FX engine
 * @return Total number of effects removed
 */
fun FixtureGroup<*>.clearFx(engine: FxEngine): Int {
    // First remove group-level effects
    val groupEffectsRemoved = engine.removeEffectsForGroup(name)
    // Then remove any per-fixture effects
    val fixtureEffectsRemoved = sumOf { engine.removeEffectsForFixture(it.key) }
    return groupEffectsRemoved + fixtureEffectsRemoved
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
    ): Long where R : Fixture, R : FixtureWithDimmer {
        val typedGroup = group.requireCapable<R>()
        return typedGroup.applyDimmerFx(
            engine, effect, FxTiming(beatDivision), blendMode, distribution
        ).also { effectIds.add(it) }
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
    ): Long where R : Fixture, R : FixtureWithColour<*> {
        val typedGroup = group.requireCapable<R>()
        return typedGroup.applyColourFx(
            engine, effect, FxTiming(beatDivision), blendMode, distribution
        ).also { effectIds.add(it) }
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
    ): Long where R : Fixture, R : FixtureWithUv {
        val typedGroup = group.requireCapable<R>()
        return typedGroup.applyUvFx(
            engine, effect, FxTiming(beatDivision), blendMode, distribution
        ).also { effectIds.add(it) }
    }

    /**
     * Apply a position effect to the group.
     * Requires the group to contain fixtures with position capability.
     */
    inline fun <reified R> position(
        effect: Effect,
        beatDivision: Double = BeatDivision.QUARTER,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        distribution: DistributionStrategy = DistributionStrategy.fromName(group.metadata.defaultDistributionName)
    ): Long where R : Fixture, R : FixtureWithPosition {
        val typedGroup = group.requireCapable<R>()
        return typedGroup.applyPositionFx(
            engine, effect, FxTiming(beatDivision), blendMode, distribution
        ).also { effectIds.add(it) }
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
