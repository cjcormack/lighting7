package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.group.DistributionMemberInfo
import uk.me.cormack.lighting7.fx.group.DistributionStrategy

/**
 * Filter for selecting which elements of a multi-element fixture receive an effect.
 *
 * When applied to a group, the filter is evaluated per-fixture (the element
 * index is the local index within each fixture, not the global flat index).
 */
enum class ElementFilter {
    /** All elements receive the effect (no filtering). */
    ALL,

    /** Only odd-indexed elements (1, 3, 5, ...) — using 1-based numbering. */
    ODD,

    /** Only even-indexed elements (2, 4, 6, ...) — using 1-based numbering. */
    EVEN,

    /** Only the first half of elements. */
    FIRST_HALF,

    /** Only the second half of elements. */
    SECOND_HALF;

    /**
     * Test whether a zero-based element index passes this filter.
     *
     * @param zeroBasedIndex The element index (0, 1, 2, ...)
     * @param totalElements The total number of elements in the fixture
     * @return true if the element should receive the effect
     */
    fun includes(zeroBasedIndex: Int, totalElements: Int): Boolean = when (this) {
        ALL -> true
        ODD -> zeroBasedIndex % 2 == 0   // 0-based index 0 = element 1 (odd)
        EVEN -> zeroBasedIndex % 2 == 1  // 0-based index 1 = element 2 (even)
        FIRST_HALF -> zeroBasedIndex < (totalElements + 1) / 2
        SECOND_HALF -> zeroBasedIndex >= (totalElements + 1) / 2
    }

    companion object {
        fun fromName(name: String): ElementFilter {
            return try {
                valueOf(name.uppercase())
            } catch (_: IllegalArgumentException) {
                ALL
            }
        }
    }
}

/**
 * Controls how group effects interact with multi-element fixture members.
 *
 * When a group contains multi-element fixtures (e.g. quad moving head bars)
 * and the effect targets a property only the elements have (e.g. colour),
 * this mode determines the distribution dimension.
 *
 * Has no effect when group members directly have the target property.
 */
enum class ElementMode {
    /**
     * Each fixture gets the effect applied independently to its own elements.
     *
     * Distribution runs within each fixture's heads separately.
     * Head #0 on fixture A = head #0 on fixture B (all fixtures look the same).
     * Group size for distribution = element count per fixture.
     */
    PER_FIXTURE,

    /**
     * All elements across all fixtures form one flat list.
     *
     * Distribution runs across the entire flat list of elements.
     * 2×4-head fixtures = 8 elements total, distributed as indices 0-7.
     * Creates chase effects that sweep across all heads sequentially.
     */
    FLAT
}

/**
 * Configuration for effect timing relative to the master clock.
 *
 * @param beatDivision Length of one effect cycle in beats (see [BeatDivision])
 * @param startOnBeat If true, quantize effect start to the next beat
 */
data class FxTiming(
    val beatDivision: Double = BeatDivision.QUARTER,
    val startOnBeat: Boolean = true
)

/**
 * How an effect's output blends with the fixture's current value.
 */
enum class BlendMode {
    /** Effect value completely replaces fixture value */
    OVERRIDE,

    /** Effect value is added to fixture value (clamped to 0-255) */
    ADDITIVE,

    /** Effect value is multiplied with fixture value */
    MULTIPLY,

    /** Maximum of effect and fixture value */
    MAX,

    /** Minimum of effect and fixture value */
    MIN
}

/**
 * A running instance of an effect bound to a specific target.
 *
 * FxInstance tracks the state of an active effect, including its phase
 * and whether it's currently running. Multiple instances of the same
 * effect can run simultaneously on different targets.
 *
 * @param effect The effect to run
 * @param target The fixture or group property to apply the effect to
 * @param timing Timing configuration relative to master clock
 * @param blendMode How to blend effect output with fixture value
 */
class FxInstance(
    val effect: Effect,
    val target: FxTarget,
    val timing: FxTiming,
    val blendMode: BlendMode = BlendMode.OVERRIDE
) {
    /** Unique identifier assigned by FxEngine */
    var id: Long = 0

    /** If this effect was applied as part of a preset, the preset ID. Null otherwise. */
    var presetId: Int? = null

    /** Whether this effect is currently running */
    var isRunning: Boolean = true

    /** Most recently calculated phase (for state reporting) */
    var lastPhase: Double = 0.0

    /** Phase offset for syncing multiple effects (e.g., for chase effects) */
    var phaseOffset: Double = 0.0

    /** Timestamp when the effect started (for timing calculations) */
    var startedAtMs: Long = System.currentTimeMillis()

    /** Beat number when the effect started (for beat-quantized start) */
    var startedAtBeat: Long = 0

    /**
     * Distribution strategy for group targets.
     * Determines how phase offsets are calculated for each group member.
     * Ignored for fixture targets.
     */
    var distributionStrategy: DistributionStrategy = DistributionStrategy.LINEAR

    /**
     * Element mode for group effects on multi-element fixtures.
     *
     * Determines whether distribution runs per-fixture (each fixture looks
     * the same) or across all elements as a flat list (chase sweeps across
     * all heads). Only relevant when group members are multi-element fixtures
     * and the target property is at the element level.
     *
     * Ignored for fixture targets and groups where members directly have
     * the target property.
     */
    var elementMode: ElementMode = ElementMode.PER_FIXTURE

    /**
     * Optional filter to restrict which elements the effect applies to.
     *
     * When set, only elements whose indices match the filter will receive
     * the effect. Other elements are skipped entirely during processing.
     *
     * @see ElementFilter
     */
    var elementFilter: ElementFilter = ElementFilter.ALL

    /**
     * Whether this effect targets a group (vs individual fixture).
     */
    val isGroupEffect: Boolean get() = target.isGroupTarget

    /**
     * The group name if this is a group effect, null otherwise.
     */
    val groupName: String? get() = if (isGroupEffect) target.targetKey else null

    /**
     * Calculate the current phase for this effect based on clock timing.
     *
     * @param tick The current clock tick
     * @param clock The master clock for timing calculations
     * @return Phase from 0.0 to 1.0 within the effect cycle
     */
    fun calculatePhase(tick: MasterClock.ClockTick, clock: MasterClock): Double {
        val basePhase = clock.phaseForDivision(tick, timing.beatDivision)
        val phase = (basePhase + phaseOffset) % 1.0
        lastPhase = phase
        return phase
    }

    /**
     * Calculate the phase for a specific group member (includes distribution offset).
     *
     * @param tick The current clock tick
     * @param clock The master clock for timing calculations
     * @param memberInfo The member's distribution info (index and normalized position)
     * @param groupSize Total number of members in the group
     * @return Phase from 0.0 to 1.0 within the effect cycle
     */
    fun calculatePhaseForMember(
        tick: MasterClock.ClockTick,
        clock: MasterClock,
        memberInfo: DistributionMemberInfo,
        groupSize: Int
    ): Double {
        val basePhase = clock.phaseForDivision(tick, timing.beatDivision)

        // Calculate distribution offset using the member's position
        val distributionOffset = distributionStrategy.calculateOffset(memberInfo, groupSize)

        val phase = (basePhase + phaseOffset + distributionOffset) % 1.0
        lastPhase = phase // Store last calculated (might be last member)
        return phase
    }

    /** Pause the effect */
    fun pause() {
        isRunning = false
    }

    /** Resume the effect */
    fun resume() {
        isRunning = true
    }
}
