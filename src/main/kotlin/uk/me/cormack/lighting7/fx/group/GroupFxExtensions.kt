package uk.me.cormack.lighting7.fx.group

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithUv
import uk.me.cormack.lighting7.fx.*
import java.util.UUID

/**
 * Extension functions for applying effects to fixture groups.
 *
 * These functions create single group-level FxInstances that automatically
 * handle phase distribution across group members at processing time.
 *
 * As on the fixture side, every entry point takes `speedMasterUuid` and
 * `rateSpeedMasterUuid` (sweep item **B2**); null means master 1 and unscaled respectively.
 */

/**
 * Apply a dimmer effect to this group.
 *
 * Creates a single FxInstance that targets the entire group.
 * Order is applied at processing time.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing values
 * @param order The order the effect runs across the members
 * @param speedMasterUuid Speed master driving the effect's phase (null → master 1)
 * @param rateSpeedMasterUuid Rate master scaling a WALL_CLOCK cycle (null → unscaled)
 * @return The effect ID for the group effect
 */
fun <T> FixtureGroup<T>.applyDimmerFx(
    engine: FxEngine,
    effect: Fx,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    order: SpreadOrder = SpreadOrder.fromName(metadata.defaultOrderName),
    speedMasterUuid: UUID? = null,
    rateSpeedMasterUuid: UUID? = null,
): Long where T : GroupableFixture, T : WithDimmer {
    val target = SliderTarget.forGroup(name, "dimmer")
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        this.order = order
        this.speedMasterUuid = speedMasterUuid
        this.rateSpeedMasterUuid = rateSpeedMasterUuid
    }
    return engine.addFx(instance)
}

/**
 * Apply a UV effect to this group.
 *
 * Creates a single FxInstance that targets the entire group.
 * Order is applied at processing time.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing values
 * @param order The order the effect runs across the members
 * @param speedMasterUuid Speed master driving the effect's phase (null → master 1)
 * @param rateSpeedMasterUuid Rate master scaling a WALL_CLOCK cycle (null → unscaled)
 * @return The effect ID for the group effect
 */
fun <T> FixtureGroup<T>.applyUvFx(
    engine: FxEngine,
    effect: Fx,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    order: SpreadOrder = SpreadOrder.fromName(metadata.defaultOrderName),
    speedMasterUuid: UUID? = null,
    rateSpeedMasterUuid: UUID? = null,
): Long where T : GroupableFixture, T : WithUv {
    val target = SliderTarget.forGroup(name, "uv")
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        this.order = order
        this.speedMasterUuid = speedMasterUuid
        this.rateSpeedMasterUuid = rateSpeedMasterUuid
    }
    return engine.addFx(instance)
}

/**
 * Apply a colour effect to this group.
 *
 * Creates a single FxInstance that targets the entire group.
 * Order is applied at processing time.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing values
 * @param order The order the effect runs across the members
 * @param speedMasterUuid Speed master driving the effect's phase (null → master 1)
 * @param rateSpeedMasterUuid Rate master scaling a WALL_CLOCK cycle (null → unscaled)
 * @return The effect ID for the group effect
 */
fun <T> FixtureGroup<T>.applyColourFx(
    engine: FxEngine,
    effect: Fx,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    order: SpreadOrder = SpreadOrder.fromName(metadata.defaultOrderName),
    speedMasterUuid: UUID? = null,
    rateSpeedMasterUuid: UUID? = null,
): Long where T : GroupableFixture, T : WithColour {
    val target = ColourTarget.forGroup(name)
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        this.order = order
        this.speedMasterUuid = speedMasterUuid
        this.rateSpeedMasterUuid = rateSpeedMasterUuid
    }
    return engine.addFx(instance)
}

/**
 * Apply a position effect to this group.
 *
 * Creates a single FxInstance that targets the entire group.
 * Order is applied at processing time.
 *
 * @param engine The FX engine to add the effect to
 * @param effect The effect to apply
 * @param timing Effect timing configuration
 * @param blendMode How to blend with existing values
 * @param order The order the effect runs across the members
 * @param speedMasterUuid Speed master driving the effect's phase (null → master 1)
 * @param rateSpeedMasterUuid Rate master scaling a WALL_CLOCK cycle (null → unscaled)
 * @return The effect ID for the group effect
 */
fun <T> FixtureGroup<T>.applyPositionFx(
    engine: FxEngine,
    effect: Fx,
    timing: FxTiming = FxTiming(),
    blendMode: BlendMode = BlendMode.OVERRIDE,
    order: SpreadOrder = SpreadOrder.fromName(metadata.defaultOrderName),
    speedMasterUuid: UUID? = null,
    rateSpeedMasterUuid: UUID? = null,
): Long where T : GroupableFixture, T : WithPosition {
    val target = PositionTarget.forGroup(name)
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        this.order = order
        this.speedMasterUuid = speedMasterUuid
        this.rateSpeedMasterUuid = rateSpeedMasterUuid
    }
    return engine.addFx(instance)
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
    val groupFxRemoved = engine.removeFxForGroup(name)
    // Then remove any per-fixture effects (including subgroup members)
    val fixtureFxRemoved = allMembers.sumOf { engine.removeFxForFixture(it.key) }
    return groupFxRemoved + fixtureFxRemoved
}

/**
 * DSL builder for applying effects to a group.
 *
 * Example:
 * ```kotlin
 * group.fx(fxEngine) {
 *     dimmer(effect("Wave", "shape" to "PULSE"), CycleLength.ONE_BEAT, order = SpreadOrder.NORMAL)
 *     colour(effect("RainbowCycle"), CycleLength.ONE_BAR, order = SpreadOrder.UNIFIED)
 * }
 * ```
 */
class GroupFxBuilder<T : GroupableFixture> @PublishedApi internal constructor(
    @PublishedApi internal val engine: FxEngine,
    @PublishedApi internal val group: FixtureGroup<T>
) {
    @PublishedApi
    internal val fxIds = mutableListOf<Long>()

    /**
     * Apply a dimmer effect to the group.
     * Requires the group to contain fixtures with dimmer capability.
     */
    inline fun <reified R> dimmer(
        effect: Fx,
        cycle: Double = CycleLength.ONE_BEAT,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        order: SpreadOrder = SpreadOrder.fromName(group.metadata.defaultOrderName),
        speedMasterUuid: UUID? = null,
        rateSpeedMasterUuid: UUID? = null,
    ): Long where R : GroupableFixture, R : WithDimmer {
        val typedGroup = group.requireCapable<R>()
        return typedGroup.applyDimmerFx(
            engine, effect, FxTiming(cycle), blendMode, order,
            speedMasterUuid, rateSpeedMasterUuid,
        ).also { fxIds.add(it) }
    }

    /**
     * Apply a colour effect to the group.
     * Requires the group to contain fixtures with colour capability.
     */
    inline fun <reified R> colour(
        effect: Fx,
        cycle: Double = CycleLength.ONE_BEAT,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        order: SpreadOrder = SpreadOrder.fromName(group.metadata.defaultOrderName),
        speedMasterUuid: UUID? = null,
        rateSpeedMasterUuid: UUID? = null,
    ): Long where R : GroupableFixture, R : WithColour {
        val typedGroup = group.requireCapable<R>()
        return typedGroup.applyColourFx(
            engine, effect, FxTiming(cycle), blendMode, order,
            speedMasterUuid, rateSpeedMasterUuid,
        ).also { fxIds.add(it) }
    }

    /**
     * Apply a UV effect to the group.
     * Requires the group to contain fixtures with UV capability.
     */
    inline fun <reified R> uv(
        effect: Fx,
        cycle: Double = CycleLength.ONE_BEAT,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        order: SpreadOrder = SpreadOrder.fromName(group.metadata.defaultOrderName),
        speedMasterUuid: UUID? = null,
        rateSpeedMasterUuid: UUID? = null,
    ): Long where R : GroupableFixture, R : WithUv {
        val typedGroup = group.requireCapable<R>()
        return typedGroup.applyUvFx(
            engine, effect, FxTiming(cycle), blendMode, order,
            speedMasterUuid, rateSpeedMasterUuid,
        ).also { fxIds.add(it) }
    }

    /**
     * Apply a position effect to the group.
     * Requires the group to contain fixtures with position capability.
     */
    inline fun <reified R> position(
        effect: Fx,
        cycle: Double = CycleLength.ONE_BEAT,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        order: SpreadOrder = SpreadOrder.fromName(group.metadata.defaultOrderName),
        speedMasterUuid: UUID? = null,
        rateSpeedMasterUuid: UUID? = null,
    ): Long where R : GroupableFixture, R : WithPosition {
        val typedGroup = group.requireCapable<R>()
        return typedGroup.applyPositionFx(
            engine, effect, FxTiming(cycle), blendMode, order,
            speedMasterUuid, rateSpeedMasterUuid,
        ).also { fxIds.add(it) }
    }

    /** All effect IDs created by this builder */
    fun fxIds(): List<Long> = fxIds.toList()
}

/**
 * Apply multiple effects to a group using a DSL builder.
 *
 * @param engine The FX engine
 * @param block Configuration block for adding effects
 * @return List of all effect IDs created
 */
inline fun <reified T : GroupableFixture> FixtureGroup<T>.fx(
    engine: FxEngine,
    block: GroupFxBuilder<T>.() -> Unit
): List<Long> {
    val builder = GroupFxBuilder(engine, this)
    builder.block()
    return builder.fxIds()
}
