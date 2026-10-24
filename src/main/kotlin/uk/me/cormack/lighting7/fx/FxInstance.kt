package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.models.LayerSource
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
 * Which way each head runs its own cycle (effects plan D5, §3.3 step 6): applied to the member's
 * phase after the spread, so it is a field of every running effect rather than a parameter of the
 * few effects that once wrote one. Colour Fade, Focus Pull and the sweeps bounce by it, by default
 * ([Effect.defaultDirection]).
 */
enum class EffectDirection {
    /** The phase as the clock gives it. */
    FORWARD,

    /** `1 − p`: the cycle runs from its end to its start. */
    BACKWARD,

    /** Forward on even cycles and backward on odd ones, counted on the member's own clock. */
    BOUNCE;

    /** [phase] in `[0, 1)` read in this direction, [cycle] being the member's own cycle count. */
    fun apply(phase: Double, cycle: Long): Double = when (this) {
        FORWARD -> phase
        BACKWARD -> reverse(phase)
        BOUNCE -> if (cycle and 1L == 0L) phase else reverse(phase)
    }

    companion object {
        /** The nullable primitive — see [ElementFilter.byName]; [EffectSpecCoercion] owns the policies. */
        fun byName(name: String): EffectDirection? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /**
         * `1 − p`, kept inside `[0, 1)`: phase 0 reads as the very end of the cycle rather than 1.0,
         * which an effect indexing a list by `floor(phase × n)` would read one past its last entry.
         */
        private fun reverse(phase: Double): Double = minOf(1.0 - phase, Math.nextDown(1.0))
    }
}

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
 * [childUuid] is the child's uuid, which is what identifies it once the DTO has been turned into a
 * live instance: its `sortOrder` is not, since Update writes the children in apply order.
 */
data class ProgrammerFxOrigin(
    val cueId: Int,
    val kind: Kind,
    val childUuid: String?,
) {
    enum class Kind {
        AD_HOC,
    }
}

/**
 * Which of a Look's stored effects an Include-spawned programmer-band instance realises, so Update
 * can write the operator's edits back onto that effect (`updateIncludedLook`) rather than beside it.
 * [lookEffectId] is the `DaoLookEffect` row.
 */
data class IncludedLookEffect(
    val lookId: Int,
    val lookEffectId: Int,
    /**
     * The instance's [LookEffectSettings] as Include spawned it — Update's "changed since Include"
     * baseline, the INCLUDE slot's job for a value. Compared against the instance rather than the
     * stored row because a running effect reports its whole parameter set, defaults filled in, so
     * a row that leaves a default unsaid would always read as edited.
     */
    val baseline: LookEffectSettings,
)

/**
 * How an effect runs, as a Look stores it — everything a `DaoLookEffect` row holds but where it
 * runs (target and property) and in what order. One mapping from a running instance, shared by
 * Record into a Look and by Update's write-back of an included effect, so the two cannot store an
 * effect two ways.
 */
data class LookEffectSettings(
    val effectType: String,
    val beatDivision: Double,
    val blendMode: String,
    val distribution: String,
    val phaseOffset: Double,
    val elementMode: String?,
    val elementFilter: String?,
    val stepTiming: Boolean?,
    val parameters: Map<String, String>,
    val speedMasterUuid: java.util.UUID?,
    val rateSpeedMasterUuid: java.util.UUID?,
    /** [FxInstance.seconds] — D7's Seconds for a beat effect. */
    val seconds: Boolean = false,
    /** [FxDynamics.size] — effects plan D2. */
    val size: Double = 1.0,
    /** [FxDynamics.direction] — effects plan D5. */
    val direction: String = EffectDirection.FORWARD.name,
    /** [FxDynamics.nShot] — effects plan D9; null runs forever. */
    val nShot: Int? = null,
    /** [FxDynamics.spread], [FxDynamics.wings], [FxDynamics.blocks], [FxDynamics.groups] — effects plan D4. */
    val spread: Double = 1.0,
    val wings: Int = 1,
    val blocks: Int = 1,
    val groups: Int = 0,
)

/**
 * The phase offset to **store** for this instance — a Record into a cue or a Look, programmer
 * capture: [FxDynamics.phaseOffset] from [dyn], less a split-off copy's member offset
 * ([SplitMember]), so a recorded copy starts where it ran. Every other instance stores its own.
 */
fun FxInstance.storedPhaseOffset(dyn: FxDynamics = dynamics): Double =
    dyn.phaseOffset - (splitMember?.offset ?: 0.0)

/**
 * This instance's [LookEffectSettings]. Element mode is a group effect's alone, and an element
 * filter or step timing is stored only where it differs from the default, so a row says what the
 * operator chose rather than what the effect would do anyway.
 */
fun FxInstance.lookEffectSettings(): LookEffectSettings {
    // One dynamics snapshot — the fields must agree with each other.
    val dyn = dynamics
    return LookEffectSettings(
        effectType = effectTypeId,
        beatDivision = timing.beatDivision,
        blendMode = blendMode.name,
        distribution = dyn.distributionStrategy.javaClass.simpleName,
        phaseOffset = storedPhaseOffset(dyn),
        elementMode = if (spreadsOverMembers) dyn.elementMode.name else null,
        elementFilter = if (dyn.elementFilter != ElementFilter.ALL) dyn.elementFilter.name else null,
        stepTiming = if (dyn.stepTiming != effect.defaultStepTiming) dyn.stepTiming else null,
        parameters = effect.parameters,
        speedMasterUuid = speedMasterUuid,
        rateSpeedMasterUuid = rateSpeedMasterUuid,
        seconds = seconds,
        size = dyn.size,
        direction = dyn.direction.name,
        nShot = dyn.nShot,
        spread = dyn.spread,
        wings = dyn.wings,
        blocks = dyn.blocks,
        groups = dyn.groups,
    )
}

/**
 * [lookEffectSettings] for asking whether two instances run one effect alike: master 1 spelled as
 * [master1Uuid] whether stored as null or by its uuid, and only the master its timing reads — a beat
 * effect's speed master, a wall-clock effect's rate master.
 */
fun FxInstance.comparableEffectSettings(master1Uuid: java.util.UUID?): LookEffectSettings {
    val settings = lookEffectSettings()
    return if (registrationTimingSource == TimingSource.BEAT) {
        settings.copy(speedMasterUuid = settings.speedMasterUuid ?: master1Uuid, rateSpeedMasterUuid = null)
    } else {
        settings.copy(speedMasterUuid = null)
    }
}

/**
 * What an in-place edit between two instances reads of each: [settings] as
 * [comparableEffectSettings] answers them, the [dynamics] they were read from, the
 * [timingSource] its type runs on and whether it is [onSeconds]. Taken of an instance before an edit,
 * so the edit can be undone.
 */
data class EffectSettingsSnapshot(
    val settings: LookEffectSettings,
    val dynamics: FxDynamics,
    val timingSource: TimingSource,
    val onSeconds: Boolean,
)

fun FxInstance.settingsSnapshot(master1Uuid: java.util.UUID?): EffectSettingsSnapshot =
    EffectSettingsSnapshot(comparableEffectSettings(master1Uuid), dynamics, registrationTimingSource, onSeconds)

/**
 * The identity of one programmer-layer effect instance: which layer spawned it, which of the
 * Look's stored effects it realises, and on which target.
 *
 * Stamped on [FxInstance.programmerLayerEffectKey] at spawn, and matched structurally on recook —
 * [effect] is the whole [StoredEffect] rather than an index, so a Look edit that changes an
 * effect produces a *different* key and the stale instance retracts instead of surviving with the
 * old parameters. [target] is as `CueComposer.cookEffects` hands it on — a Look effect's own, or
 * one of a palette layer's — whole rather than its key, which a fixture and a group can share.
 */
data class ProgrammerLayerEffectKey(
    val layerId: Int,
    val effect: StoredEffect,
    val target: uk.me.cormack.lighting7.models.EffectTargetRef,
) {
    /**
     * Computed once, at construction, rather than on every hash (sweep item C8).
     *
     * [effect] is a whole [StoredEffect] — which carries the effect's `parameters` **map** — so
     * the generated `hashCode` walks that map end to end. A recook hashes each key at least twice
     * (into the wanted set, then against the live band), and the live band's keys are re-hashed
     * building the engine's snapshot; every one of those used to re-walk the map.
     *
     * Safe to freeze because every field is a `val` on an immutable snapshot: a Look edit produces
     * a *new* entry and therefore a new key, which is exactly the identity change the retract pass
     * is built on.
     */
    private val hash: Int = (layerId * 31 + effect.hashCode()) * 31 + target.hashCode()

    override fun hashCode(): Int = hash

    companion object {
        /**
         * The key for [effect] on [target] — without the effect's row [StoredEffect.uuid], which is
         * no part of the identity: a palette save recreates its effect row under a new uuid, and an
         * unchanged effect must keep its running instance.
         */
        fun of(layerId: Int, effect: StoredEffect, target: uk.me.cormack.lighting7.models.EffectTargetRef) =
            ProgrammerLayerEffectKey(layerId, effect.copy(uuid = null), target)
    }
}

/**
 * Where a one-head copy came from, when it was split off an effect spread over several heads
 * (apply-model plan §3.4, `FxEngine.headCopies`): the member it was, so its phase is computed
 * exactly as that member's was — the same call, with the same three inputs.
 *
 * - [groupSize] is the size of the list the member was spread over. A step-timed member's cycle is
 *   its division times the list's distinct slots, and a ping-pong's sweep is scaled by its slot count
 *   ([FxPhase.memberDivision], [FxPhase]'s shaping); a copy of one head would have neither.
 *   Agreed with Chris during session 2: the split carries them.
 * - [offset] is the member's distribution offset, which puts the copy where it was in the cycle, and
 *   holds an N-shot's window back by it and counts Bounce's cycles from it, as a member's are.
 * - [context] is the member's [EffectContext], for an effect that reads its place in the spread.
 *
 * The offset sits beside the copy's [FxDynamics.phaseOffset] rather than in it, so every copy keeps
 * the parent's phase offset: an edit that follows the selection sends one value to every copy it
 * covers, and a phase offset per copy would collapse them into unison. Stored data has no field for
 * it (apply-model plan P7), so whatever stores a copy folds it in — [storedPhaseOffset] — and a
 * recorded copy keeps its phase, though not its set's step cycle or sweep. Runtime only otherwise.
 */
data class SplitMember(
    val groupSize: Int,
    val offset: Double,
    val context: EffectContext,
)

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
    /** Whether the effect is painting. Gates the whole per-effect work of a compose pass. */
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
    val distributionStrategy: DistributionStrategy = DistributionStrategy.NORMAL,

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

    /**
     * Size (effects plan D2): how much of the effect reaches the stage over what is underneath,
     * 0.0–1.0. The stored half of the weight P3 applies after the blend — `out = before +
     * (blended − before) × w`, per channel, `w` = this × [FxInstance.intensityMultiplier] (the
     * envelope). See [FxInstance.weight].
     */
    val size: Double = 1.0,

    /** Which way each member runs its cycle (D5); applied by [FxInstance] to every member phase. */
    val direction: EffectDirection = EffectDirection.FORWARD,

    /**
     * Run this many cycles and stop (D9), null for forever. Counted from the effect's own start on
     * its clock; each member runs its N from its own offset, and the instance is spent once the
     * clock has run N cycles past the largest one. See [FxInstance.nShotPending].
     */
    val nShot: Int? = null,

    /**
     * Spread (effects plan D4, §3.3 step 5): how much of the cycle the members cover, 0.0–2.0; 1.0 is
     * the Order's own offsets. With [wings], [blocks] and [groups] it is the spread's shape — see
     * [SpreadShape], [SpreadShaping] — edited in place like the Order, phase kept.
     */
    val spread: Double = 1.0,

    /** Wings (D4, step 3): the members split into this many mirrored wings; 1 is none. */
    val wings: Int = 1,

    /** Blocks (D4, step 2): this many neighbouring members share one phase; 1 is none. */
    val blocks: Int = 1,

    /** Groups (D4, step 4): the spread repeats every this many members; 0 is off. */
    val groups: Int = 0,
) {
    /** The four shape fields as one value, for the plan cache and [DistributionPlan.of]. Cold path. */
    val spreadShape: SpreadShape get() = SpreadShape(spread, wings, blocks, groups)

    /**
     * The rank count left after the spread's shape over [groupSize] members — [DistributionPlan.slots]
     * in constant time and with no allocation, for the per-member step timing. (Ping-pong's turnaround
     * reads the Order's own count: the shape sets offsets, never the sweep.)
     */
    fun distinctSlots(groupSize: Int): Int =
        SpreadShaping.slots(
            distributionStrategy.distinctSlots(groupSize), wings, blocks, groups, !distributionStrategy.isMirrored,
        )
}

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
    /**
     * The cell the [envelope] lives in, shared across `FxEngine.updateEffect`'s swap as
     * [dynamicsRef] is, so a fade or a retirement racing the swap reaches both instances.
     */
    private val envelopeRef: AtomicReference<EffectEnvelope> = AtomicReference(EffectEnvelope.FULL),
) {
    constructor(
        effect: Effect,
        target: FxTarget,
        timing: FxTiming,
        blendMode: BlendMode = BlendMode.OVERRIDE,
    ) : this(
        effect, target, timing, blendMode,
        AtomicReference(FxDynamics(stepTiming = effect.defaultStepTiming, direction = effect.defaultDirection)),
    )

    /** [envelopeRef], for the swap that shares it. */
    internal val envelopeCell: AtomicReference<EffectEnvelope> get() = envelopeRef

    init {
        // The only place every spawn path meets: routes, scripts, programmer Include, cue and
        // Look fire, MIDI. [FxTarget.acceptedOutputType] explains what a mismatch costs — the
        // apply returns silently, so the operator gets no light and, before this line, no trace
        // either. That is how sweep items A4 and A11 stayed invisible.
        //
        // A warn, not a throw: a cue firing mid-show must not die because one recorded row names
        // the wrong property. The REST add/update paths reject it up front instead
        // (`requireOutputTypeMatch`), which is where an operator can still act on it.
        if (!target.accepts(effect.outputType)) {
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
     * Where this effect composes among those of its priority, before [id] breaks a tie: its own id,
     * except for a copy split off another effect (`FxEngine.headCopies`), which keeps its parent's
     * place so a split moves nothing in the order. Set by the engine.
     */
    var composeOrder: Long = 0

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
     * one, else the id the registry made [effect] under (a script's `effect(id)`), and the
     * display-name approximation only as a last resort for an effect a script constructed itself.
     *
     * Everything that writes an `effectType` — a recorded Look or cue child, an API DTO the
     * client hands back on Update — must go through this. Writing `effect.name.replace(" ", "")`
     * directly stores a string the registry cannot resolve for any user-defined FX definition,
     * and the failure only surfaces later, when the row is applied.
     */
    val effectTypeId: String get() = registrationId ?: effect.registrationId ?: effect.name.replace(" ", "")

    /**
     * The library record whose layer spawned this effect — a Look **or a template** — or null for
     * an effect that belongs to no layer (a cue's ad-hoc child, a script's, the programmer's own
     * `+ Effect`).
     *
     * One [LayerSource] rather than the `lookId: Int?` it replaces, and for the reason that type's
     * own KDoc gives: once a template can own an effect too (fx-templates D7), an `Int` field named
     * `lookId` would quietly hold a template's id, and no compiler could find the readers that care
     * — of which [uk.me.cormack.lighting7.routes.captureCurrentState] is the one that would corrupt
     * a Record. Collapsing to the kind-carrying value makes every reader visit once.
     *
     * [name] is what earns the third field: `FX running` says *in Amber Wave* without a second
     * lookup, the way provenance already does for a layer.
     */
    var source: LayerSource? = null

    /**
     * [source]'s id when it is a **Look**, null otherwise — the field the busking pads' active ring
     * matches on, unchanged in meaning.
     *
     * Derived rather than stored, and deliberately Look-only: a bare id cannot be polymorphic here,
     * because template id 3 would ring Look id 3's pad. [templateId] is the other half.
     */
    val lookId: Int? get() = source?.takeUnless { it.isTemplate }?.id

    /** [source]'s id when it is a **template**, null otherwise. */
    val templateId: Int? get() = source?.takeIf { it.isTemplate }?.id

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
     * A full key rather than reusing [source], because the programmer's stack may hold the same
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
     * A fourth id rather than making do with [source], for exactly the reason `CookLayer.layerId`
     * exists: one cue may legitimately layer the same Look twice — a chase built from one Look at
     * two delays is the obvious case — so [source] cannot say *which* of them spawned this
     * instance. Within-cue stomp is a statement about one layer, so it needs the id that can.
     *
     * The programmer's twin is [programmerLayerEffectKey] (read as an id via [programmerLayerId]),
     * and they are two ids rather than one because they live in different spaces: `DaoCueLayer`
     * row ids and `ProgrammerStore.mintLayerId`'s in-memory counter, which will collide freely.
     */
    var cueLayerId: Int? = null

    /** If this effect was applied as part of a cue, the cue ID. Null otherwise. */
    var cueId: Int? = null

    /**
     * The stored effect row a cue spawned this from — a Look effect's, a palette effect's or the
     * cue's own ad-hoc row's uuid — or null for anything else. What a GO matches a continuing layer
     * effect on (effects plan P4): the incoming cue keeps an outgoing cue's instance running the same
     * row on the same target, phase intact, rather than fading one out and another in. An ad-hoc
     * effect handed on by its [runUuid] is restamped with the incoming row's.
     */
    var layerEffectUuid: java.util.UUID? = null

    /**
     * The run key of the cue ad-hoc effect row this was spawned from (stack-timeline plan D7), null
     * for anything else. A GO hands the outgoing cue's instance with the same key, target and
     * property to the incoming cue rather than starting the incoming row afresh ([CueHolds]).
     */
    var runUuid: java.util.UUID? = null

    /**
     * The stack of the hold that brought this effect's cue on, null for a loose one or no cue. Not
     * the stack whose effect size weighs it, which is the cue's highest hold ([weighingStackOf]).
     */
    var cueStackId: Int? = null

    /**
     * Set when Include spawned this instance into the programmer band from a cue's FX child,
     * naming the child it came from so Update can replace that child in place instead of
     * appending a duplicate.
     *
     * Distinct from [cueId]/[cueStackId], which stay null on included instances on purpose:
     * a band effect must not be swept by `removeCueEffects` when the cue it was included
     * from stops. Cleared for free by `removeProgrammerBandEffects()`.
     */
    var programmerOrigin: ProgrammerFxOrigin? = null

    /**
     * [programmerOrigin]'s counterpart for a Look: set when Include spawned this instance into the
     * programmer band from one of a Look's effects, so Update writes the edit back onto it. A band
     * effect like any other — Clear takes it, and nothing about the Look reads it.
     */
    var includedLookEffect: IncludedLookEffect? = null

    /**
     * The operator's own busked effect: in the programmer priority band, and belonging to
     * nothing — no Look or template ([source]), no programmer layer ([programmerLayerEffectKey]),
     * no cue ([cueId]). What the programmer's Release takes off a fixture with its values
     * (`programmer.clearTarget`, fixture-fx-sheets plan W2), and the rule the grid's ⌫ applies
     * client-side (`cellEffects.isLocalEffect`), moved to the desk.
     *
     * An effect that came out of a Look, a template layer or a cue is that thing's, not the
     * operator's: stopping it would either be undone by the next recook or quietly edit a library
     * record. Include's ad-hoc children *are* local — they are the operator's busk being written
     * back, which is why [programmerOrigin] does not exclude one.
     *
     * A [leaving] instance is nobody's: a release has already let it go, and only its fade holds it.
     */
    val isLocalEffect: Boolean
        get() = FxEngine.isProgrammerFxPriority(priority) &&
            source == null && programmerLayerEffectKey == null && cueId == null && !leaving

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
     * The **envelope** (effects plan §3.1, §3.5) as it stands — a level, or a fade from one level to
     * another on the clock ([EffectEnvelope]). A live instance's envelope changes only by
     * compare-and-set through [updateEnvelope], so a cue brought back on and the engine retiring the
     * same instance cannot both win.
     */
    val envelope: EffectEnvelope get() = envelopeRef.get()

    /**
     * Replace the envelope with [transform] of the current one, unless the instance has been
     * retired ([EffectEnvelope.RETIRED]). False when it had been: the engine has let it go, and
     * nothing may bring it back. [transform] may run more than once under contention; keep it pure.
     */
    internal fun updateEnvelope(transform: (EffectEnvelope) -> EffectEnvelope): Boolean {
        while (true) {
            val current = envelopeRef.get()
            if (current === EffectEnvelope.RETIRED) return false
            if (envelopeRef.compareAndSet(current, transform(current))) return true
        }
    }

    /**
     * Set the envelope of an instance not yet in the engine — a spawn's starting ramp, a split's copy
     * taking its parent's. Not for a live instance: a fade there goes through [updateEnvelope].
     */
    internal fun carryEnvelope(envelope: EffectEnvelope) = envelopeRef.set(envelope)

    /**
     * Mark this instance retired if its envelope is still [expected] — the engine's half of the
     * race [updateEnvelope] guards. True when this call retired it.
     */
    internal fun retire(expected: EffectEnvelope): Boolean =
        expected !== EffectEnvelope.RETIRED && envelopeRef.compareAndSet(expected, EffectEnvelope.RETIRED)

    /**
     * True while the instance is on its way out: its owner let it go on a fade, and it is removed
     * once its envelope reaches 0 ([FxEngine.retireFinishedLeaving]). It belongs to nothing meanwhile
     * — no recook, Release or carry counts it — though a cue brought back on mid-fade reclaims its
     * own.
     */
    val leaving: Boolean get() = envelopeRef.get().leaving

    /**
     * The envelope's level now, 0.0–1.0: the runtime part of the effect's weight, which a fade moves —
     * a cue's GO (D1) and the programmer's press, release and Clear (D13). Never stored or recorded:
     * Record takes the row's own [FxDynamics.size].
     *
     * It does not scale the effect's raw output. That scaling is what once dropped an `OVERRIDE`
     * effect to 0 on a fade; the envelope is one factor of [weight], applied after the blend (P3).
     * Setting it holds the level still, cancelling any fade and a leaving mark — but never a
     * retirement.
     */
    var intensityMultiplier: Double
        get() = envelopeRef.get().let { if (it.isConstant) it.to else it.at(System.currentTimeMillis()) }
        set(value) { updateEnvelope { EffectEnvelope.constant(value) } }

    /**
     * The weight P3 applies after the blend on this pass: [FxDynamics.size] × [intensityMultiplier]
     * × [stackSize] (its stack's effect size, D10 — [StackEffectSizes.weightOf]), in `[0, 1]`.
     * Takes the caller's per-pass snapshot, like every other tick-path read.
     */
    fun weight(dynamics: FxDynamics, stackSize: Double = 1.0): Double =
        FxBlend.weight(dynamics.size, intensityMultiplier, stackSize)

    /**
     * Where an N-shot's count began ([FxDynamics.nShot], D9): the master tick of a beat effect's first
     * pass with a count, or [UNANCHORED]. Owned by the engine's compose pass, so plain fields; set
     * again from the next pass whenever the count is cleared, so a count given to a running effect
     * runs from then. Carried across `FxEngine.updateEffect`'s swap.
     */
    var nShotStartTick: Long = UNANCHORED

    /** [nShotStartTick]'s wall-clock twin: [accumulatedScaledMs] at the first pass with a count, or NaN. */
    var nShotStartScaledMs: Double = Double.NaN

    /**
     * Set by the phase functions on a pass when some member of an N-shot is still to finish its runs
     * (running, or waiting for its offset to come round). The compose pass clears it and [nShotEvaluated]
     * before the effect's pass and reads them after: evaluated but nothing pending means the clock
     * has run N cycles past the largest member offset, and the effect is spent. Owned by the compose pass.
     */
    var nShotPending: Boolean = false

    /** Set when a phase was computed for an N-shot on this pass; see [nShotPending]. Owned by the compose pass. */
    var nShotEvaluated: Boolean = false

    /**
     * An N-shot that has run out but is kept in the engine: a programmer layer's, whose recook would
     * otherwise spawn it afresh and fire it again (see `FxEngine.finishNShots`). Every member holds
     * what is underneath; the layer's release removes it. Any other instance is removed when spent.
     */
    @Volatile
    var spent: Boolean = false

    /**
     * Most recently calculated phase, for state reporting — the reverse direction from
     * [dynamics]: the compose pass writes it, request threads read it. Written once per effect
     * per pass — the single-target calculators store it themselves, and for member
     * expansions `FxEngine` stores the last member's phase after its loop — so the
     * `@Volatile` store stays off the per-member path.
     */
    @Volatile
    var lastPhase: Double = 0.0

    /**
     * Length of the current run of consecutive passes, woken by this effect's own clock, that it
     * threw out of. Owned by the engine's compose pass, which runs one at a time, so it needs no
     * volatile publication, and nothing else reads it.
     * `FxEngine.noteTickFailure` auto-pauses the effect once the run is long enough.
     */
    var consecutiveTickFailures: Int = 0

    /**
     * A [StatefulEffect]'s last output per painted key, which a pass its own clock did not wake
     * repaints instead of stepping it (`FxEngine.calculateEffectOutput`). Pass-owned; null for any
     * other effect.
     */
    internal val statefulOutputs: HashMap<String, FxOutput>? = if (effect is StatefulEffect) HashMap() else null

    /** Timestamp when the effect started (for timing calculations) */
    var startedAtMs: Long = System.currentTimeMillis()

    /** Beat number when the effect started (for beat-quantized start) */
    var startedAtBeat: Long = 0

    /**
     * What this effect resolves to against the fixture register — the group/element expansion
     * and its key lists, cached so the compose pass stops re-deriving them per effect per tick.
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
     * current [FxDynamics.distributionStrategy], cached so the compose pass stops rebuilding a
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
     * A BEAT effect's phase comes from its speed master's ticks; a WALL_CLOCK effect's from real
     * time, independent of BPM — for ambient/atmospheric effects that should not be tied to the
     * musical beat grid. Both are painted by the same compose pass.
     *
     * Deliberately a plain `var`, not part of [dynamics]: every writer runs before the
     * instance reaches `FxEngine`'s active map (the add paths, and `updateEffect`'s
     * not-yet-published swap instance), and the map insert safely publishes it. The compose pass
     * reads it on every pass, so a post-publication write would race the pass on an unpublished
     * field — don't add one; swap the instance instead.
     */
    var timingSource: TimingSource = TimingSource.BEAT

    /**
     * A **beat** effect on *Seconds* (venue-visit plan D7): its own cycle, [FxTiming.beatDivision]
     * read as seconds, with no master. Such an instance runs on the wall clock
     * ([timingSource] is `WALL_CLOCK`) with both masters null, which is exactly an unscaled
     * wall-clock effect — the engine has nothing new to do for it.
     *
     * Always false for a wall-clock effect: its division is seconds already, and its Seconds is
     * having no rate master. [onSeconds] is the one reading of D7's choice for either kind, and
     * [applyTimingChoice] the one writer. Set before the instance reaches the engine, as
     * [timingSource] is.
     */
    var seconds: Boolean = false

    /**
     * Set on a one-head copy split off a spread effect (apply-model plan §3.4): the member it was —
     * see [SplitMember]. Null for every other instance. Written only before the instance reaches the
     * engine, as [timingSource] is, and carried across `FxEngine.updateEffect`'s swap.
     */
    var splitMember: SplitMember? = null

    /**
     * D7's reading: this effect follows no master — a beat effect on [seconds], or a wall-clock
     * effect with no rate master.
     */
    val onSeconds: Boolean
        get() = seconds || (timingSource == TimingSource.WALL_CLOCK && rateSpeedMasterUuid == null)

    /** The timing source of the effect's *type*: [timingSource], except a beat effect on [seconds]. */
    val registrationTimingSource: TimingSource
        get() = if (seconds) TimingSource.BEAT else timingSource

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

    /** Whether this effect targets a selection of fixtures and groups (effects plan D3). */
    val isSelectionEffect: Boolean get() = target.isSelectionTarget

    /** Whether the engine spreads it over members — a group's, or a selection's. */
    val spreadsOverMembers: Boolean get() = isGroupEffect || isSelectionEffect

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
     * The whole-target form of [calculatePhaseForMember]: one member at offset 0, so direction and
     * N-shot apply exactly as to a member. A copy split off a spread ([splitMember]) runs exactly as
     * the member it was: its set's size and its own distribution offset.
     *
     * @param tick The current clock tick of this effect's speed master
     * @param dynamics The caller's per-pass snapshot
     * @return Phase from 0.0 to 1.0 within the effect cycle, or NaN while an N-shot is not running
     *   ([isHolding]) — the caller then applies nothing and the value underneath stands
     */
    fun calculatePhase(tick: MasterClock.ClockTick, dynamics: FxDynamics = this.dynamics): Double {
        val carry = splitMember
        val phase = if (carry == null) {
            beatPhase(tick, timing.beatDivision, 1, dynamics, 0.0)
        } else {
            beatPhase(tick, memberCycleDivision(dynamics, carry.groupSize), carry.groupSize, dynamics, carry.offset)
        }
        if (!phase.isNaN()) lastPhase = phase
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
     * @return Phase from 0.0 to 1.0 within the effect cycle, or NaN while this member of an N-shot is
     *   not running ([isHolding])
     */
    fun calculatePhaseForMember(
        tick: MasterClock.ClockTick,
        groupSize: Int,
        dynamics: FxDynamics,
        distributionOffset: Double,
    ): Double = beatPhase(tick, memberCycleDivision(dynamics, groupSize), groupSize, dynamics, distributionOffset)

    /**
     * A beat effect's phase at [tick] ([FxPhase.beatPhaseAt]) — from the effect's own start for an
     * N-shot, anchoring [nShotStartTick] on its first pass, from the master's tick 0 otherwise.
     */
    private fun beatPhase(
        tick: MasterClock.ClockTick,
        division: Double,
        groupSize: Int,
        dynamics: FxDynamics,
        distributionOffset: Double,
    ): Double {
        // A cycle too short to count leaves the anchor alone.
        if (FxPhase.beatCycleTicks(division) <= 0) return 0.0
        val ticks = tick.tickNumber
        if (dynamics.nShot == null) {
            nShotStartTick = UNANCHORED
        } else if (nShotStartTick == UNANCHORED || ticks < nShotStartTick) {
            // A master whose count went backwards (a reload) re-anchors rather than counting negative.
            nShotStartTick = ticks
        }
        return FxPhase.beatPhaseAt(ticks, nShotStartTick, division, groupSize, dynamics, distributionOffset, nShotTally)
    }

    /** The effective cycle length for a member pass; see [FxPhase.memberDivision]. */
    private fun memberCycleDivision(dynamics: FxDynamics, groupSize: Int): Double =
        FxPhase.memberDivision(timing.beatDivision, dynamics, groupSize)

    /** Counts this pass's N-shot members for the compose pass; see [nShotPending]. */
    private val nShotTally = NShotTally { pending ->
        nShotEvaluated = true
        if (pending) nShotPending = true
    }

    /**
     * Scaled elapsed time, in milliseconds, since this effect started — the wall-clock
     * analogue of a beat effect's tick counter.
     *
     * Phase derives from *this* rather than from `now - startedAtMs` so that changing a rate
     * master mid-cycle is continuous: the cycle length stays fixed and only the rate of
     * accumulation moves, where dividing a fixed elapsed time by a changing cycle length
     * made the phase jump. Advanced once per compose pass by [advanceWallClock].
     */
    @Volatile
    var accumulatedScaledMs: Double = 0.0

    /**
     * Advance [accumulatedScaledMs] by one pass of [deltaMs] at [rateScale]
     * (`master.bpm / 120`, 1.0 when no rate master is assigned).
     *
     * Called once per effect per compose pass, before any phase is read, so every phase
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
     * @param dynamics The caller's per-pass snapshot
     * @return Phase from 0.0 to 1.0 within the effect cycle, or NaN while an N-shot is not running
     */
    fun calculateWallClockPhase(dynamics: FxDynamics = this.dynamics): Double {
        val carry = splitMember
        val phase = if (carry == null) {
            wallClockPhase(timing.beatDivision, 1, dynamics, 0.0)
        } else {
            wallClockPhase(memberCycleDivision(dynamics, carry.groupSize), carry.groupSize, dynamics, carry.offset)
        }
        if (!phase.isNaN()) lastPhase = phase
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
     * @return Phase from 0.0 to 1.0 within the effect cycle, or NaN while this member of an N-shot is
     *   not running
     */
    fun calculateWallClockPhaseForMember(
        groupSize: Int,
        dynamics: FxDynamics,
        distributionOffset: Double,
    ): Double = wallClockPhase(memberCycleDivision(dynamics, groupSize), groupSize, dynamics, distributionOffset)

    /** [beatPhase]'s wall-clock twin, over [accumulatedScaledMs] and [nShotStartScaledMs]. */
    private fun wallClockPhase(
        division: Double,
        groupSize: Int,
        dynamics: FxDynamics,
        distributionOffset: Double,
    ): Double {
        if (FxPhase.wallClockCycleMs(division) <= 0.0) return 0.0
        val elapsed = accumulatedScaledMs
        if (dynamics.nShot == null) {
            nShotStartScaledMs = Double.NaN
        } else if (nShotStartScaledMs.isNaN() || elapsed < nShotStartScaledMs) {
            nShotStartScaledMs = elapsed
        }
        return FxPhase.wallClockPhaseAt(
            elapsed, nShotStartScaledMs, division, groupSize, dynamics, distributionOffset, nShotTally,
        )
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

        /** [nShotStartTick] before an N-shot's first pass. */
        const val UNANCHORED: Long = -1

        /**
         * Whether a phase from [calculatePhase] and its siblings means "apply nothing": a member of an
         * N-shot outside its runs, which holds what is underneath (D9).
         */
        fun isHolding(phase: Double): Boolean = phase.isNaN()
    }
}
