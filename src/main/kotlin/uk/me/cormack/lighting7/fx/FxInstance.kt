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
/**
 * Which cue FX child an Include-spawned programmer-band instance came from, so Update can
 * write the operator's edits back over that child rather than beside it.
 *
 * [childSortOrder] is the child's `sortOrder` in the cue, which is what identifies it once
 * the DTO has been turned into a live instance.
 */
data class ProgrammerFxOrigin(
    val cueId: Int,
    val kind: Kind,
    val childSortOrder: Int,
) {
    enum class Kind {
        AD_HOC,
    }
}

class FxInstance(
    val effect: Effect,
    val target: FxTarget,
    val timing: FxTiming,
    val blendMode: BlendMode = BlendMode.OVERRIDE
) {
    /** Unique identifier assigned by FxEngine */
    var id: Long = 0

    /**
     * The canonical [EffectRegistration.id] this instance was created from, when it was created
     * from one. Null for effects a script constructed directly, which never went through the
     * registry.
     *
     * The field exists because the alternative — `effect.name.replace(" ", "")` — is only a
     * registration id by coincidence. It holds for built-ins, whose display name is their id with
     * spaces in it, and fails for every user-defined FX definition, which sets `id` independently
     * of `name`. Code that asks "are these two instances running the same effect type?" must read
     * this, not the display name.
     */
    var registrationId: String? = null

    /**
     * The effect type to *persist or report* for this instance: [registrationId] when there is
     * one, and the display-name approximation only as a last resort for a script-constructed
     * effect that never went through the registry.
     *
     * Everything that writes an `effectType` — a recorded Look or cue child, an API DTO the
     * client hands back on Update — must go through this. Writing `effect.name.replace(" ", "")`
     * directly stores a string the registry cannot resolve for any user-defined FX definition,
     * and the failure only surfaces later, when the row is applied.
     */
    val effectTypeId: String get() = registrationId ?: effect.name.replace(" ", "")

    /** If this effect was spawned by a cue's Look layer, the Look's ID. Null otherwise. */
    var lookId: Int? = null

    /**
     * Set when this effect was spawned by a **programmer** layer, naming
     * [ProgrammerLayer.layerId].
     *
     * A third id rather than reusing [lookId], because the programmer's stack may hold the same
     * Look twice and the two have to be retracted and re-ranked independently — the same reason
     * `CookLayer.layerId` exists beside `CookLayer.lookId`. It is also not
     * [ProgrammerFxOrigin], whose `cueId` is non-null and read by Update to replace a cue child in
     * place; a programmer layer belongs to no cue.
     */
    var programmerLayerId: Int? = null

    /**
     * Set when this effect was spawned by a **cue's** Look layer, naming the `DaoCueLayer` row.
     *
     * A fourth id rather than making do with [lookId], for exactly the reason `CookLayer.layerId`
     * exists: one cue may legitimately layer the same Look twice — a chase built from one Look at
     * two delays is the obvious case — so [lookId] cannot say *which* of them spawned this
     * instance. Within-cue stomp is a statement about one layer, so it needs the id that can.
     *
     * The programmer's twin is [programmerLayerId], and they are two fields rather than one because
     * they are ids in different spaces: `DaoCueLayer` row ids and `ProgrammerStore.mintLayerId`'s
     * in-memory counter, which will collide freely.
     */
    var cueLayerId: Int? = null

    /** If this effect was applied as part of a cue, the cue ID. Null otherwise. */
    var cueId: Int? = null

    /** If this effect belongs to a cue stack, the stack ID. Null otherwise. */
    var cueStackId: Int? = null

    /**
     * Set when Include spawned this instance into the programmer band from a cue's FX child,
     * naming the child it came from so Update can replace that child in place instead of
     * appending a duplicate.
     *
     * Distinct from [cueId]/[cueStackId], which stay null on included instances on purpose:
     * a band effect must not be swept by `removeEffectsForCue` when the cue it was included
     * from stops. Cleared for free by `removeProgrammerBandEffects()`.
     */
    var programmerOrigin: ProgrammerFxOrigin? = null

    /**
     * Composition priority. Effects with lower priority compose first; higher priority effects
     * blend on top, making them dominant under non-OVERRIDE blend modes. Ties break on [id]
     * (monotonic, so insertion order is the stable tie-break).
     *
     * Manual / ad-hoc effects default to 0. Cue-owned effects receive a derived priority from
     * their cue's stack position so cue playback is deterministic across app restarts.
     */
    @Volatile
    var priority: Int = 0

    /** Whether this effect is currently running */
    var isRunning: Boolean = true

    /**
     * Intensity multiplier (0.0 = silent, 1.0 = full). The FxEngine multiplies effect
     * output by this value during processing.
     *
     * Used by manual / scripted effect fades. Cue transitions do NOT touch this field —
     * effects snap on cue transition (outgoing removed, incoming start at full intensity);
     * only Layer 4 property assignments crossfade. See [CueStackManager] for rationale.
     */
    @Volatile
    var intensityMultiplier: Double = 1.0

    /** Most recently calculated phase (for state reporting) */
    var lastPhase: Double = 0.0

    /** Phase offset for syncing multiple effects (e.g., for chase effects) */
    var phaseOffset: Double = 0.0

    /**
     * Whether the beat division controls per-step timing rather than total cycle time.
     *
     * Initialised from [Effect.defaultStepTiming] but can be overridden per-instance
     * via the API. See [Effect.defaultStepTiming] for full documentation.
     */
    var stepTiming: Boolean = effect.defaultStepTiming

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
     * Timing source for this effect.
     *
     * BEAT effects are processed on the Master Clock's BPM-synced tick loop.
     * WALL_CLOCK effects are processed on a separate fixed-interval loop (50Hz),
     * independent of BPM. Suitable for ambient/atmospheric effects that should
     * not be tied to the musical beat grid.
     */
    var timingSource: TimingSource = TimingSource.BEAT

    /**
     * Speed master this BEAT effect subscribes to, as the persisted master **uuid**
     * (null → master 1). Uuid rather than int id so a stored reference survives clone and
     * import — see `LookEffectSpec.speedMasterUuid`. The engine resolves it to
     * [speedMasterSlot] at add/update time and re-resolves when the bank's membership
     * changes; a uuid the bank no longer knows resolves to master 1.
     */
    var speedMasterUuid: java.util.UUID? = null

    /**
     * Optional rate master for WALL_CLOCK effects: the effective cycle is scaled by the
     * master's `bpm / 120`. Null → unscaled (current behaviour preserved).
     */
    var rateSpeedMasterUuid: java.util.UUID? = null

    /**
     * Runtime slot of [speedMasterUuid] in the show's [SpeedMasterBank]; 0 is master 1.
     * Bound by the engine, never persisted — the hot path indexes an array with this
     * rather than looking up a UUID per tick.
     */
    @Volatile
    var speedMasterSlot: Int = 0

    /** Runtime slot of [rateSpeedMasterUuid]; see [speedMasterSlot]. */
    @Volatile
    var rateMasterSlot: Int = 0

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
     * Takes only the tick — deliberately no clock parameter. Phase is a pure function of
     * the tick counter ([MasterClock.phaseForDivision]), and with one clock per speed
     * master an ignored clock argument would make "this master's tick, that master's
     * clock" silently plausible. The caller passes the tick of *this effect's* master
     * (`frame.tick(speedMasterSlot)`).
     *
     * @param tick The current clock tick of this effect's speed master
     * @return Phase from 0.0 to 1.0 within the effect cycle
     */
    fun calculatePhase(tick: MasterClock.ClockTick): Double {
        val basePhase = MasterClock.phaseForDivision(tick.tickNumber, timing.beatDivision)
        val phase = (basePhase + phaseOffset) % 1.0
        lastPhase = phase
        return phase
    }

    /**
     * Calculate the phase for a specific group member (includes distribution offset).
     *
     * @param tick The current clock tick of this effect's speed master
     * @param memberInfo The member's distribution info (index and normalized position)
     * @param groupSize Total number of members in the group
     * @return Phase from 0.0 to 1.0 within the effect cycle
     */
    fun calculatePhaseForMember(
        tick: MasterClock.ClockTick,
        memberInfo: DistributionMemberInfo,
        groupSize: Int
    ): Double {
        // For step-timed effects, scale the beat division by the number of
        // distinct distribution slots so the beat division controls time-per-step
        // rather than total cycle time.
        val effectiveDivision = if (stepTiming && groupSize > 1) {
            timing.beatDivision * distributionStrategy.distinctSlots(groupSize)
        } else {
            timing.beatDivision
        }
        var basePhase = MasterClock.phaseForDivision(tick.tickNumber, effectiveDivision)

        // PING_PONG: apply triangle wave remap to the base clock phase so that
        // ALL effects (not just static ones) sweep forward then backward.
        // Scale to [0, (N-1)/N] to match the LINEAR offset range and avoid
        // wrapping artifacts at the turnaround points.
        if (distributionStrategy.usesTrianglePhase && groupSize > 1) {
            val slots = distributionStrategy.distinctSlots(groupSize)
            val tri = if (basePhase < 0.5) basePhase * 2.0 else 2.0 * (1.0 - basePhase)
            basePhase = tri * (slots - 1.0) / slots
        }

        // Subtract distribution offset so that higher-offset members are *behind*
        // in the cycle, making the visual sweep flow in the natural direction
        // (element 0 → element N for LINEAR, etc.).
        val distributionOffset = distributionStrategy.calculateOffset(memberInfo, groupSize)

        val phase = (basePhase + phaseOffset - distributionOffset + 1.0) % 1.0
        lastPhase = phase // Store last calculated (might be last member)
        return phase
    }

    /**
     * Scaled elapsed time, in milliseconds, since this effect started — the wall-clock
     * analogue of a beat effect's tick counter.
     *
     * Phase derives from *this* rather than from `now - startedAtMs` so that changing a rate
     * master mid-cycle is continuous: the cycle length stays fixed and only the rate of
     * accumulation moves, where dividing a fixed elapsed time by a changing cycle length
     * made the phase jump. Advanced once per wall-clock pass by [advanceWallClock].
     */
    @Volatile
    var accumulatedScaledMs: Double = 0.0

    /**
     * Advance [accumulatedScaledMs] by one pass of [deltaMs] at [rateScale]
     * (`master.bpm / 120`, 1.0 when no rate master is assigned).
     *
     * Called once per effect per wall-clock pass, before any phase is read, so every phase
     * call within a pass sees one coherent value.
     */
    fun advanceWallClock(deltaMs: Long, rateScale: Double = 1.0) {
        val scale = if (rateScale > 0.0) rateScale else 1.0
        accumulatedScaledMs += deltaMs * scale
    }

    /**
     * Calculate the current phase for this effect using accumulated wall-clock time.
     *
     * For wall-clock effects, [FxTiming.beatDivision] is reinterpreted as cycle
     * duration in seconds (e.g., 4.0 = 4 second cycle).
     *
     * @return Phase from 0.0 to 1.0 within the effect cycle
     */
    fun calculateWallClockPhase(): Double {
        val cycleDurationMs = timing.beatDivision * 1000.0
        if (cycleDurationMs <= 0.0) return 0.0
        val phase = ((accumulatedScaledMs % cycleDurationMs) / cycleDurationMs + phaseOffset) % 1.0
        lastPhase = phase
        return phase
    }

    /**
     * Calculate the wall-clock phase for a specific group member (includes distribution offset).
     *
     * @param memberInfo The member's distribution info
     * @param groupSize Total number of members in the group
     * @return Phase from 0.0 to 1.0 within the effect cycle
     */
    fun calculateWallClockPhaseForMember(
        memberInfo: DistributionMemberInfo,
        groupSize: Int,
    ): Double {
        val effectiveDivision = if (stepTiming && groupSize > 1) {
            timing.beatDivision * distributionStrategy.distinctSlots(groupSize)
        } else {
            timing.beatDivision
        }
        val cycleDurationMs = effectiveDivision * 1000.0
        if (cycleDurationMs <= 0.0) return 0.0

        var basePhase = (accumulatedScaledMs % cycleDurationMs) / cycleDurationMs

        if (distributionStrategy.usesTrianglePhase && groupSize > 1) {
            val slots = distributionStrategy.distinctSlots(groupSize)
            val tri = if (basePhase < 0.5) basePhase * 2.0 else 2.0 * (1.0 - basePhase)
            basePhase = tri * (slots - 1.0) / slots
        }

        val distributionOffset = distributionStrategy.calculateOffset(memberInfo, groupSize)
        val phase = (basePhase + phaseOffset - distributionOffset + 1.0) % 1.0
        lastPhase = phase
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
