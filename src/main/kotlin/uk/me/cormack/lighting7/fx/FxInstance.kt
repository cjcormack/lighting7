package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import java.util.concurrent.atomic.AtomicReference

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
        /**
         * Parse a filter from a string name, or null if the name names none.
         *
         * The nullable form is the primitive: [EffectSpecCoercion] layers the strict (reject)
         * and lenient (default + log) policies on top of it, so that a bad string has one
         * outcome per policy rather than one per call site.
         */
        fun byName(name: String): ElementFilter? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

        /**
         * Parse a filter from a string name, falling back to [ALL].
         *
         * Anything reading a request body or a stored spec should go through
         * [EffectSpecCoercion] instead, so the fallback is a stated policy rather than a
         * property of this enum.
         */
        fun fromName(name: String): ElementFilter = byName(name) ?: ALL
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

/**
 * The identity of one programmer-layer effect instance: which layer spawned it, which of the
 * Look's stored effects it realises, and on which target.
 *
 * Stamped on [FxInstance.programmerLayerEffectKey] at spawn, and matched structurally on recook —
 * [effect] is the whole [LookEffectEntry] rather than an index, so a Look edit that changes an
 * effect produces a *different* key and the stale instance retracts instead of surviving with the
 * old parameters. [targetKey] is [uk.me.cormack.lighting7.models.TargetRef.key], the same string
 * `CueComposer.cookEffects` fans deferred effects out over.
 */
data class ProgrammerLayerEffectKey(
    val layerId: Int,
    val effect: LookEffectEntry,
    val targetKey: String,
) {
    /**
     * Computed once, at construction, rather than on every hash (sweep item C8).
     *
     * [effect] is a whole [LookEffectEntry] — which carries the effect's `parameters` **map** — so
     * the generated `hashCode` walks that map end to end. A recook hashes each key at least twice
     * (into the wanted set, then against the live band), and the live band's keys are re-hashed
     * building the engine's snapshot; every one of those used to re-walk the map.
     *
     * Safe to freeze because every field is a `val` on an immutable snapshot: a Look edit produces
     * a *new* entry and therefore a new key, which is exactly the identity change the retract pass
     * is built on.
     */
    private val hash: Int = (layerId * 31 + effect.hashCode()) * 31 + targetKey.hashCode()

    override fun hashCode(): Int = hash
}

/**
 * The parameters of a running [FxInstance] that request threads may change while the tick
 * loops read them, grouped into one immutable value behind a single atomic reference
 * ([FxInstance.dynamics]).
 *
 * One value rather than per-field `@Volatile`, for two reasons (sweep item A6):
 *
 * - **Torn passes.** These fields are read together, per member, on the tick path;
 *   independent volatile fields let one calculation see field A's new value with field B's
 *   old one — a step chase whose cycle length came from the new strategy under the old
 *   [stepTiming] runs at the wrong speed for a frame. A pass takes [FxInstance.dynamics]
 *   once and works from that snapshot throughout.
 * - **Torn updates.** `FxEngine.updateEffect` changes several of these together; sequential
 *   per-field stores would let a tick land in the middle of one logical update. Replacing
 *   the whole value is atomic.
 *
 * The cell holding this also survives `updateEffect`'s instance swap (see
 * [FxInstance.dynamicsRef]), which is what makes a concurrent [FxInstance.pause] unlosable.
 */
data class FxDynamics(
    /** Whether the effect is painting. Gates the whole per-effect pass on both tick loops. */
    val isRunning: Boolean = true,

    /** Phase offset for syncing multiple effects (e.g., for chase effects). */
    val phaseOffset: Double = 0.0,

    /**
     * Whether the beat division controls per-step timing rather than total cycle time.
     * Initialised from [Effect.defaultStepTiming]; see there for full documentation.
     */
    val stepTiming: Boolean,

    /**
     * How group-member phase offsets are derived. Ignored for fixture targets.
     */
    val distributionStrategy: DistributionStrategy = DistributionStrategy.LINEAR,

    /**
     * Element mode for group effects on multi-element fixtures: distribution per fixture, or
     * across all elements as one flat list. Deliberately *outside* [FxInstance.expansion]'s
     * validity check — see that doc for why both shapes are built up front.
     */
    val elementMode: ElementMode = ElementMode.PER_FIXTURE,

    /**
     * Which elements receive the effect ([ElementFilter.ALL] = no filtering). Part of
     * [FxInstance.expansion]'s validity check: changing it re-derives the expansion.
     */
    val elementFilter: ElementFilter = ElementFilter.ALL,
)

class FxInstance internal constructor(
    val effect: Effect,
    val target: FxTarget,
    val timing: FxTiming,
    val blendMode: BlendMode,
    /**
     * The cell [dynamics] lives in. `FxEngine.updateEffect`'s swap branch hands the
     * *existing* instance's cell to the replacement, so a pause/resume or dynamics edit
     * racing the swap lands in the cell both instances share rather than dying with the old
     * instance — the swap's read-copy-publish cannot lose it.
     */
    internal val dynamicsRef: AtomicReference<FxDynamics>,
) {
    constructor(
        effect: Effect,
        target: FxTarget,
        timing: FxTiming,
        blendMode: BlendMode = BlendMode.OVERRIDE,
    ) : this(
        effect, target, timing, blendMode,
        AtomicReference(FxDynamics(stepTiming = effect.defaultStepTiming)),
    )

    init {
        // The only place every spawn path meets: routes, scripts, programmer Include, cue and
        // Look fire, MIDI. [FxTarget.acceptedOutputType] explains what a mismatch costs — the
        // apply returns silently, so the operator gets no light and, before this line, no trace
        // either. That is how sweep items A4 and A11 stayed invisible.
        //
        // A warn, not a throw: a cue firing mid-show must not die because one recorded row names
        // the wrong property. The REST add/update paths reject it up front instead
        // (`requireOutputTypeMatch`), which is where an operator can still act on it.
        if (effect.outputType != target.acceptedOutputType) {
            logger.warn(
                "Effect '{}' outputs {} but target {}.{} applies {} — this effect will produce " +
                    "nothing. Check the effect's compatibleProperties.",
                effect.name, effect.outputType,
                target.targetKey, target.propertyName, target.acceptedOutputType,
            )
        }
    }

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
     * Set when this effect was spawned by a **programmer** layer: which layer, which of the
     * Look's stored effects, on which target.
     *
     * This is the instance's full identity within the programmer band, and it lives here — on the
     * engine's record — rather than in a map of [ProgrammerLayerStack]'s own, so the band has one
     * owner. The stack's recook classifies live instances by this key straight off
     * [FxEngine.programmerLayerEffects]; anything that removes an instance behind the stack's back
     * ([FxEngine.removeProgrammerBandEffects], the FX sheet's own remove) is therefore simply no
     * longer in the band, with no shadow bookkeeping to fall out of date (sweep item E6).
     *
     * A full key rather than reusing [lookId], because the programmer's stack may hold the same
     * Look twice and the two have to be retracted and re-ranked independently — the same reason
     * `CookLayer.layerId` exists beside `CookLayer.source.id`. It is also not
     * [ProgrammerFxOrigin], whose `cueId` is non-null and read by Update to replace a cue child in
     * place; a programmer layer belongs to no cue.
     */
    var programmerLayerEffectKey: ProgrammerLayerEffectKey? = null

    /** [ProgrammerLayerEffectKey.layerId], for the stomp lookup and the capture/record filters. */
    val programmerLayerId: Int? get() = programmerLayerEffectKey?.layerId

    /**
     * Set when this effect was spawned by a **cue's** Look layer, naming the `DaoCueLayer` row.
     *
     * A fourth id rather than making do with [lookId], for exactly the reason `CookLayer.layerId`
     * exists: one cue may legitimately layer the same Look twice — a chase built from one Look at
     * two delays is the obvious case — so [lookId] cannot say *which* of them spawned this
     * instance. Within-cue stomp is a statement about one layer, so it needs the id that can.
     *
     * The programmer's twin is [programmerLayerEffectKey] (read as an id via [programmerLayerId]),
     * and they are two ids rather than one because they live in different spaces: `DaoCueLayer`
     * row ids and `ProgrammerStore.mintLayerId`'s in-memory counter, which will collide freely.
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

    /**
     * The mutable-under-the-tick parameters as one immutable snapshot.
     *
     * Tick-path readers must take this **once per pass** and read only the snapshot; any
     * decision touching two of its fields must come from one snapshot, not two property
     * reads. The convenience properties below re-read the cell per access and exist for
     * cold paths and spawn-time configuration.
     */
    val dynamics: FxDynamics get() = dynamicsRef.get()

    /**
     * Atomically replace [dynamics]. This is how several fields change as one update —
     * `FxEngine.updateEffect` funnels its dynamics changes through one call here.
     * [transform] may run more than once under contention; keep it pure.
     */
    internal fun updateDynamics(transform: (FxDynamics) -> FxDynamics): FxDynamics =
        dynamicsRef.updateAndGet(transform)

    /** Whether this effect is currently running; see [pause]/[resume]. */
    val isRunning: Boolean get() = dynamics.isRunning

    /** View of [FxDynamics.phaseOffset]; each store is one atomic single-field update. */
    var phaseOffset: Double
        get() = dynamics.phaseOffset
        set(value) { updateDynamics { it.copy(phaseOffset = value) } }

    /** View of [FxDynamics.stepTiming]. */
    var stepTiming: Boolean
        get() = dynamics.stepTiming
        set(value) { updateDynamics { it.copy(stepTiming = value) } }

    /** View of [FxDynamics.distributionStrategy]. */
    var distributionStrategy: DistributionStrategy
        get() = dynamics.distributionStrategy
        set(value) { updateDynamics { it.copy(distributionStrategy = value) } }

    /** View of [FxDynamics.elementMode]. */
    var elementMode: ElementMode
        get() = dynamics.elementMode
        set(value) { updateDynamics { it.copy(elementMode = value) } }

    /** View of [FxDynamics.elementFilter]. */
    var elementFilter: ElementFilter
        get() = dynamics.elementFilter
        set(value) { updateDynamics { it.copy(elementFilter = value) } }

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

    /**
     * Most recently calculated phase, for state reporting — the reverse direction from
     * [dynamics]: the tick loops write it, request threads read it. Written once per effect
     * per pass — the single-target calculators store it themselves, and for member
     * expansions `FxEngine` stores the last member's phase after its loop — so the
     * `@Volatile` store stays off the per-member path.
     */
    @Volatile
    var lastPhase: Double = 0.0

    /**
     * Length of the current run of consecutive tick passes this effect threw out of. Owned by
     * the tick loops — an instance is on the beat path or the wall-clock path, never both — so
     * it needs no volatile publication, and nothing else reads it.
     * `FxEngine.noteTickFailure` auto-pauses the effect once the run is long enough.
     */
    var consecutiveTickFailures: Int = 0

    /** Timestamp when the effect started (for timing calculations) */
    var startedAtMs: Long = System.currentTimeMillis()

    /** Beat number when the effect started (for beat-quantized start) */
    var startedAtBeat: Long = 0

    /**
     * What this effect resolves to against the fixture register — the group/element expansion
     * and its key lists, cached so the tick loops stop re-deriving them per effect per tick.
     *
     * Owned by `FxEngine.expansionFor`, which is also the only thing that should read it: the
     * validity check — against [FxTargetExpansion.structureVersion] and [elementFilter], and
     * deliberately *not* [elementMode] — lives there. Held on the instance rather than in a side
     * map so it dies with the effect and no removal path can leak it.
     *
     * That exclusion is load-bearing in the other direction: because a mode toggle does not
     * invalidate, `FxEngine.buildExpansion` must keep building both [FxTargetExpansion.flat] and
     * [FxTargetExpansion.perFixture] up front. Build only the one the current mode needs and the
     * next toggle silently serves the wrong key list.
     */
    @Volatile
    internal var expansion: FxTargetExpansion? = null

    /**
     * The per-member phase offsets and [EffectContext]s derived from [expansion] under the
     * current [FxDynamics.distributionStrategy], cached so the tick loops stop rebuilding a
     * synthetic member and a context per element per tick.
     *
     * Owned by `FxEngine.plansFor`, which is also the only thing that should read it. It
     * validates by identity against the [expansion] the plans were built from — so an
     * expansion rebuild invalidates these too, with no second version stamp to keep in step —
     * plus equality on the strategy, which is the only other input.
     */
    @Volatile
    internal var distributionPlans: FxDistributionPlans? = null

    /**
     * Timing source for this effect.
     *
     * BEAT effects are processed on the Master Clock's BPM-synced tick loop.
     * WALL_CLOCK effects are processed on a separate fixed-interval loop (50Hz),
     * independent of BPM. Suitable for ambient/atmospheric effects that should
     * not be tied to the musical beat grid.
     *
     * Deliberately a plain `var`, not part of [dynamics]: every writer runs before the
     * instance reaches `FxEngine`'s active map (the add paths, and `updateEffect`'s
     * not-yet-published swap instance), and the map insert safely publishes it. Which loop
     * processes the effect is decided by `rebuildSortedSnapshots`' partition, not by
     * per-tick reads — so don't add a post-publication write here: it would do nothing
     * until an unrelated rebuild.
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
     * master's `bpm / 120`. Null → unscaled, via the [NO_RATE_MASTER] sentinel — resolving it
     * to slot 0 instead would silently mean "master 1".
     */
    var rateSpeedMasterUuid: java.util.UUID? = null

    /**
     * Runtime slot of [speedMasterUuid] in the show's [SpeedMasterBank]; 0 is master 1.
     * Bound by the engine, never persisted — the hot path indexes an array with this
     * rather than looking up a UUID per tick.
     */
    @Volatile
    var speedMasterSlot: Int = 0

    /**
     * Runtime slot of [rateSpeedMasterUuid], or [NO_RATE_MASTER] when there isn't one; see
     * [speedMasterSlot].
     *
     * The sentinel is why this can't just default to 0 the way [speedMasterSlot] does: slot 0
     * is master 1, so "no rate master" resolving to it scaled every unassigned wall-clock
     * effect by master 1's tempo — the opposite of the unscaled default [rateSpeedMasterUuid]
     * documents.
     */
    @Volatile
    var rateMasterSlot: Int = NO_RATE_MASTER

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
     * @param dynamics The caller's per-pass snapshot; defaults to a fresh read, which is
     *   safe here because only one field ([FxDynamics.phaseOffset]) is consulted
     * @return Phase from 0.0 to 1.0 within the effect cycle
     */
    fun calculatePhase(tick: MasterClock.ClockTick, dynamics: FxDynamics = this.dynamics): Double {
        val basePhase = MasterClock.phaseForDivision(tick.tickNumber, timing.beatDivision)
        val phase = (basePhase + dynamics.phaseOffset) % 1.0
        lastPhase = phase
        return phase
    }

    /**
     * Calculate the phase for a specific group member.
     *
     * [dynamics] is required rather than read here: the caller's whole member pass must run
     * from one snapshot, and a fresh read per call would silently reintroduce the torn-pass
     * race [FxDynamics] exists to close. [distributionOffset] comes in precomputed for the
     * same reason — the caller already derives it from the same snapshot for
     * [EffectContext.distributionOffset], and deriving it twice both doubles the work
     * (RANDOM shuffles an O(n) permutation per call) and let the offset baked into the
     * phase disagree with the one handed to the effect.
     *
     * Does not store [lastPhase] — the caller does, once per pass, after its member loop.
     *
     * @param tick The current clock tick of this effect's speed master
     * @param groupSize Total number of members in the group
     * @param dynamics The caller's per-pass dynamics snapshot
     * @param distributionOffset This member's offset, from the same snapshot's strategy
     * @return Phase from 0.0 to 1.0 within the effect cycle
     */
    fun calculatePhaseForMember(
        tick: MasterClock.ClockTick,
        groupSize: Int,
        dynamics: FxDynamics,
        distributionOffset: Double,
    ): Double {
        val basePhase =
            MasterClock.phaseForDivision(tick.tickNumber, memberCycleDivision(dynamics, groupSize))
        return shapeMemberPhase(basePhase, dynamics, distributionOffset, groupSize)
    }

    /**
     * The effective beat division for a member pass: for step-timed effects, the beat
     * division is scaled by the number of distinct distribution slots so it controls
     * time-per-step rather than total cycle time.
     */
    private fun memberCycleDivision(dynamics: FxDynamics, groupSize: Int): Double =
        if (dynamics.stepTiming && groupSize > 1) {
            timing.beatDivision * dynamics.distributionStrategy.distinctSlots(groupSize)
        } else {
            timing.beatDivision
        }

    /**
     * The member-phase shaping shared by the beat and wall-clock forms, which differ only
     * in where [basePhase] comes from.
     */
    private fun shapeMemberPhase(
        basePhase: Double,
        dynamics: FxDynamics,
        distributionOffset: Double,
        groupSize: Int,
    ): Double {
        // PING_PONG: apply triangle wave remap to the base clock phase so that
        // ALL effects (not just static ones) sweep forward then backward.
        // Scale to [0, (N-1)/N] to match the LINEAR offset range and avoid
        // wrapping artifacts at the turnaround points.
        var shaped = basePhase
        if (dynamics.distributionStrategy.usesTrianglePhase && groupSize > 1) {
            val slots = dynamics.distributionStrategy.distinctSlots(groupSize)
            val tri = if (shaped < 0.5) shaped * 2.0 else 2.0 * (1.0 - shaped)
            shaped = tri * (slots - 1.0) / slots
        }

        // Subtract distribution offset so that higher-offset members are *behind*
        // in the cycle, making the visual sweep flow in the natural direction
        // (element 0 → element N for LINEAR, etc.).
        return (shaped + dynamics.phaseOffset - distributionOffset + 1.0) % 1.0
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
     * @param dynamics The caller's per-pass snapshot; defaults to a fresh read, which is
     *   safe here because only one field ([FxDynamics.phaseOffset]) is consulted
     * @return Phase from 0.0 to 1.0 within the effect cycle
     */
    fun calculateWallClockPhase(dynamics: FxDynamics = this.dynamics): Double {
        val cycleDurationMs = timing.beatDivision * 1000.0
        if (cycleDurationMs <= 0.0) return 0.0
        val phase =
            ((accumulatedScaledMs % cycleDurationMs) / cycleDurationMs + dynamics.phaseOffset) % 1.0
        lastPhase = phase
        return phase
    }

    /**
     * Calculate the wall-clock phase for a specific group member. The parameter contract —
     * required snapshot, precomputed offset, no [lastPhase] store — is
     * [calculatePhaseForMember]'s; see there.
     *
     * @param groupSize Total number of members in the group
     * @param dynamics The caller's per-pass dynamics snapshot
     * @param distributionOffset This member's offset, from the same snapshot's strategy
     * @return Phase from 0.0 to 1.0 within the effect cycle
     */
    fun calculateWallClockPhaseForMember(
        groupSize: Int,
        dynamics: FxDynamics,
        distributionOffset: Double,
    ): Double {
        val cycleDurationMs = memberCycleDivision(dynamics, groupSize) * 1000.0
        if (cycleDurationMs <= 0.0) return 0.0
        val basePhase = (accumulatedScaledMs % cycleDurationMs) / cycleDurationMs
        return shapeMemberPhase(basePhase, dynamics, distributionOffset, groupSize)
    }

    /**
     * Pause the effect. Atomic against concurrent dynamics edits, and — because the
     * dynamics cell is shared across `updateEffect` swaps — cannot be lost to one.
     */
    fun pause() {
        updateDynamics { it.copy(isRunning = false) }
    }

    /** Resume the effect. Same guarantees as [pause]. */
    fun resume() {
        updateDynamics { it.copy(isRunning = true) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FxInstance::class.java)

        /** [rateMasterSlot] when no rate master is assigned: the effect runs unscaled. */
        const val NO_RATE_MASTER: Int = -1
    }
}
