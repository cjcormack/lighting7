package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.me.cormack.lighting7.dmx.packChannelKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Identifies which subsystem asserted a programmer entry, so releasing one subsystem's
 * entries cannot destroy another's (see `docs/lighting-composition-model.md`
 * §"Programmer ownership").
 *
 * Owners with a release path (flash, presets, locate) must clear with the same owner they
 * put with — that is the whole contract. Two writers that share an owner overwrite each
 * other's slot (last write wins), which is why locate-vs-locate overlap is handled by
 * [uk.me.cormack.lighting7.show.LocateManager]'s re-assert loop rather than here.
 */
@JvmInline
value class ProgrammerOwner(val id: String) {
    companion object {
        /** Manual busking from the web UI: `programmer.*` ops and the `updateChannel` shim. */
        val WEB = ProgrammerOwner("web")

        /** MIDI control-surface faders ([uk.me.cormack.lighting7.midi.SurfaceActions]). */
        val SURFACE = ProgrammerOwner("surface")

        /**
         * Surface flash buttons — press asserts, release clears. Separate from [SURFACE] so
         * a flash release restores the fader/busk level underneath instead of wiping it.
         */
        val FLASH = ProgrammerOwner("flash")

        /**
         * Locate toggles (`routes/lightLocate.kt`). One shared owner for every locate
         * target: overlapping locates (group + member) are re-asserted by
         * [uk.me.cormack.lighting7.show.LocateManager] on release, which also re-resolves
         * stale targets — semantics a per-target owner fallback could not replicate.
         */
        val LOCATE = ProgrammerOwner("locate")

        /**
         * Park-release value hand-down ([uk.me.cormack.lighting7.show.Show]'s
         * `unparkValueSink`). Slots are created with `touched = false`: an unpark is not an
         * operator edit, so it is excluded from Record and the Update checklist (Session 3)
         * while remaining releasable/clearable like any manual write.
         */
        val UNPARK = ProgrammerOwner("unpark")

        /**
         * Cue contents loaded into the programmer by Include (`routes/programmerInclude.kt`).
         * Its own slot survives underneath a later operator write, which is what lets Update
         * tell "the operator changed this" from "this is just what I included" — see
         * [ProgrammerStore.valueFor].
         */
        val INCLUDE = ProgrammerOwner("include")

        /**
         * The cooked contribution of the programmer's **Look-layer stack**
         * ([uk.me.cormack.lighting7.fx.ProgrammerLayerStack]).
         *
         * One owner for the whole stack, not one per layer, because the stack is cooked to a
         * single value per key *before* it reaches the store — which is the point of cooking. Per
         * layer owners would put the ordering decision back into the slot stack, whose order is
         * write-recency and therefore the wrong invariant (see [ProgrammerStore]).
         *
         * **Slots owned by this sit at the bottom of a key's stack and carry a `seq` in the
         * reserved [LAYER_SEQ_BASE] band.** Both are needed, for different readers, and together
         * they are what makes "a local write always wins" true regardless of write order —
         * see [ProgrammerStore.putLayerSlots].
         */
        val LAYERS = ProgrammerOwner("layers")

    }

    override fun toString(): String = id
}

/**
 * A value held by the programmer.
 *
 * Carries a concrete [resolved] value, so every read path — including
 * [FxTarget.composeProgrammerOver] and `fallbackFromProgrammer` on the 50 Hz tick — reads
 * `.resolved`.
 *
 * **Why this is still a sealed interface with one arm.** It had a second, [Ref], holding a
 * `ref:{uuid}` named-palette reference plus the literal it currently resolved to; the `ref:` value
 * grammar retired in session 4 of the looks-and-layers plan, and a layer with a `propertyMask` is
 * what replaces it. The shape is left alone because the arm's *reason* has not gone away — a value
 * that remembers where it came from is exactly what nested Looks (`FU-LOOK-NESTED`) would need —
 * and collapsing it to a bare typealias would make reintroducing one a change to every read site
 * rather than a new arm.
 */
sealed interface ProgrammerValue {
    /** The concrete value the entry contributes to the cascade. */
    val resolved: CueAssignmentResolver.PropertyValue

    /** A literal value. */
    data class Hard(override val resolved: CueAssignmentResolver.PropertyValue) : ProgrammerValue
}

/**
 * What Include last pulled into the programmer, and therefore what Update writes back to
 * when no explicit targets are given (Mode A).
 *
 * [targetId] is a cue id or a Look id depending on [kind]; [cueId] / [lookId] narrow it.
 * [cueStackId] is carried so the client can name the stack in the indicator without a second
 * lookup, and [sourceUuid] so a re-import can't leave the indicator pointing at a stranger.
 */
data class IncludedTarget(
    val kind: Kind,
    val targetId: Int,
    val cueStackId: Int? = null,
    val sourceUuid: UUID? = null,
) {
    enum class Kind {
        CUE,
        LOOK,
    }

    /** The cue id, or null when something else is included. */
    val cueId: Int? get() = targetId.takeIf { kind == Kind.CUE }

    /** The Look id, or null when something else is included. */
    val lookId: Int? get() = targetId.takeIf { kind == Kind.LOOK }

    companion object {
        fun cue(cueId: Int, cueStackId: Int?) = IncludedTarget(Kind.CUE, cueId, cueStackId)

        fun look(lookId: Int, lookUuid: UUID) =
            IncludedTarget(Kind.LOOK, lookId, sourceUuid = lookUuid)
    }
}

/**
 * The PROGRAMMER layer — a sparse per-(fixture, property) overlay of manual values, sitting
 * above cues and effects in the composition stack (see `docs/lighting-composition-model.md`).
 * It is simultaneously the live override for busking and the staging buffer that Record
 * (Session 3) serialises.
 *
 * Each (fixture, property) holds a small stack of per-owner [Slot]s ordered by write recency
 * — the old channel-level direct-write store's multi-owner model lifted to property level:
 * - [put] installs or refreshes the owner's slot and moves it to the top — the most recent
 *   write wins regardless of who made it.
 * - [clear] removes only that owner's slot. If another owner still holds the property, its
 *   most recent value becomes visible again — releasing a Locate no longer wipes a busked
 *   level or an active preset's assertion on the same property; releasing a flash restores
 *   the fader value underneath.
 * - [get] returns the top of the stack, or null when no owner holds the property.
 *
 * **Touched flag**: sticky per slot, never value-diffed. `true` marks an operator edit
 * (drives Record and the Update checklist in Session 3); `false` marks a mechanical
 * hand-down ([ProgrammerOwner.UNPARK]).
 *
 * **Channel sideband**: raw channels with no backing property (and every unpark hand-down,
 * which is inherently channel-shaped) live in a parallel per-channel map with the same
 * owner-slot semantics. Across granularities, *recency* arbitrates: a property entry and a
 * sideband slot covering the same channel compare their [Slot.seq] and the newer write
 * wins — the old channel-level store's rule, generalised. A deliberate operator property
 * write additionally *absorbs* the sideband underneath it ([clearChannelsAbsorbedBy] is
 * called by the engine with the property's resolved channels) so a stale raw value cannot
 * resurface when the property entry clears.
 *
 * **Blind**: when [blind] is true the programmer's contribution is excluded from the merge
 * ([LayerResolver] and the effect-suppression pass consult it); the stored state itself is
 * unaffected, so exiting blind restores exactly what was staged.
 *
 * Thread-safety: reads are lock-free via [ConcurrentHashMap]; mutations go through
 * `compute`, so put/clear for the same key cannot interleave. The store is read on the FX
 * engine tick thread and written on the caller threads that handle WebSocket / REST / MIDI
 * events. Publishing (writing the composed cascade to controllers) is the engine's job and
 * serialises on its own lock — the store itself never touches DMX.
 *
 * Performance: [get] and [getChannel] are on the 50 Hz effect-reset hot path
 * ([FxTarget.fallbackFromProgrammer]) — both are O(1) map lookups returning the stored
 * objects without allocation. [epoch] increments on every mutation so per-tick consumers
 * (the effect-suppression snapshot) can cache derived state until the store actually
 * changes. Mutations (human/MIDI rate) copy-on-write the slot stacks.
 */
class ProgrammerStore {
    /** One owner's entry for a property (or, in the sideband, a channel). */
    class Slot(
        val owner: ProgrammerOwner,
        val value: ProgrammerValue,
        /** Sticky operator-edit marker — see class doc. */
        val touched: Boolean,
        /** Group name when the write came through a group control, else null (§7.1). */
        val sourceGroup: String?,
        /**
         * Store-wide monotonic write sequence. Lets consumers arbitrate *across
         * granularities* by recency: when a property entry and a sideband channel slot
         * both cover a channel, the newer write wins — the same rule the old channel-level
         * store applied within one channel (an unpark hand-down must beat an older locate
         * entry on the channel it covers, and a fresh property write must beat a stale
         * sideband value).
         */
        val seq: Long,
    )

    /** Two or more owners on one key, most recent write first. Always size >= 2. */
    private class Stack(val slots: List<Slot>) : Holder {
        override val top: Slot get() = slots[0]
        override fun all(): List<Slot> = slots
    }

    private class Single(val slot: Slot) : Holder {
        override val top: Slot get() = slot
        override fun all(): List<Slot> = listOf(slot)
    }

    private sealed interface Holder {
        val top: Slot
        fun all(): List<Slot>
    }

    /** fixtureKey → propertyName → owner stack. Two-level to keep hot-path reads flat. */
    private val properties = ConcurrentHashMap<String, ConcurrentHashMap<String, Holder>>()

    /**
     * Channel sideband: packed `(universe << 20 | channel)` → owner stack. Values are
     * [CueAssignmentResolver.PropertyValue.Slider]-wrapped raw bytes.
     */
    private val channels = ConcurrentHashMap<Long, Holder>()

    /**
     * Blind gate — true excludes the programmer's contribution from the merge without
     * touching the stored state. Consulted by [LayerResolver.fallbackFor] and the effect
     * suppression pass. Setting it does not republish; [FxEngine.setProgrammerBlind] owns
     * the publish.
     */
    @Volatile
    var blind: Boolean = false

    /**
     * What Include last loaded, and therefore what a bare Update writes back to (Mode A).
     * Null means nothing is staged from a cue, so Update falls through to the
     * provenance-derived checklist (Mode B).
     *
     * A [StateFlow] rather than a plain field so the WebSocket layer can push changes with
     * replay-on-connect (a freshly-opened tab gets the current target without polling) and
     * without adding a method to `FixturesChangeListener` for something no other subsystem
     * cares about.
     */
    private val _lastIncludedTarget = MutableStateFlow<IncludedTarget?>(null)

    val lastIncludedTargetFlow: StateFlow<IncludedTarget?> = _lastIncludedTarget.asStateFlow()

    var lastIncludedTarget: IncludedTarget?
        get() = _lastIncludedTarget.value
        set(value) {
            _lastIncludedTarget.value = value
        }

    /**
     * Drop the include target if it points at [cueId]. Called when a cue is deleted, so a
     * later Update can't write into a row that no longer exists. (Update re-validates the
     * target against the DB anyway — this just keeps the operator's indicator honest.)
     */
    fun clearIncludeTargetForCue(cueId: Int) {
        _lastIncludedTarget.value = _lastIncludedTarget.value?.takeIf { it.cueId != cueId }
    }

    /** As [clearIncludeTargetForCue], for a deleted Look. */
    fun clearIncludeTargetForLook(lookId: Int) {
        _lastIncludedTarget.value = _lastIncludedTarget.value?.takeIf { it.lookId != lookId }
    }

    // ── The Look-layer stack ────────────────────────────────────────────────

    /**
     * The programmer's ordered Look layers, most-significant last (see [ProgrammerLayer]).
     *
     * **Held here rather than in [ProgrammerLayerStack] on purpose.** [clearAll] already drops
     * [lastIncludedTarget] because surviving bookkeeping offers an operation that silently does
     * nothing; a layer list living outside the store would be a second thing Clear could forget,
     * and forgetting it is worse than forgetting an include target — Clear would wipe the
     * materialised slots while the layers survived to resurrect them on the next recook, so the
     * stage would light up again by itself.
     *
     * The store keeps no opinion about what the list *means*: cooking it, materialising it and
     * spawning its effects all belong to [ProgrammerLayerStack], which is where the dependencies
     * on `Fixtures`, `LookRegistry` and `FxEngine` live. This is storage.
     *
     * Not on any hot path — the tick reads the materialised slots, never this — so a volatile
     * reference plus a mutation lock is enough.
     */
    @Volatile
    var layers: List<ProgrammerLayer> = emptyList()
        private set

    /**
     * The layer list as it stood at the last Include — Update's structural diff baseline.
     *
     * The layer-stack counterpart to the `INCLUDE` slot's role for local rows: a slot survives
     * underneath a later write so Update can ask "did the operator change this?", and this answers
     * the same question for the stack's *shape*. Both are needed, because reordering a layer changes
     * no slot's value and editing a row changes no layer.
     *
     * Cleared by [clearAll] along with [lastIncludedTarget] and [layers], for the same reason: a
     * baseline outliving the thing it describes would make the next Update diff against a stack that
     * no longer exists.
     */
    @Volatile
    var includedLayerSnapshot: List<ProgrammerLayer> = emptyList()

    private val layersLock = Any()
    private val layerIdCounter = AtomicInteger(1)

    /**
     * A fresh in-memory layer id. Monotonic for the life of the process and deliberately unrelated
     * to any `DaoCueLayer` id: a programmer layer has no row, and two layers over the same Look
     * must be distinguishable.
     */
    fun mintLayerId(): Int = layerIdCounter.getAndIncrement()

    /**
     * Mutate the layer list atomically, returning the new list and whatever [transform] computed.
     *
     * Serialised so that decide-and-install cannot interleave — the same guarantee
     * `swapPresetPreviewSlot` got from `ConcurrentHashMap.compute`, which this replaces. The caller
     * recooks from the returned list rather than re-reading [layers], so a concurrent mutation
     * can't make it cook a list nobody asked for.
     *
     * **Lock order is `layersLock` → `FxEngine.cueAssignmentsLock`, never the reverse**, and
     * nothing inside `FxEngine` calls back into the stack. Cooking must happen *outside* this lock
     * anyway, because `loadLookSnapshot` opens its own transaction.
     */
    fun <T> mutateLayers(transform: (List<ProgrammerLayer>) -> Pair<List<ProgrammerLayer>, T>): Pair<List<ProgrammerLayer>, T> {
        var changed = false
        val result = synchronized(layersLock) {
            val previous = layers
            val (next, extra) = transform(previous)
            layers = next
            changed = next !== previous
            next to extra
        }
        // Emitted outside the lock: a subscriber must never run while `layersLock` is held, or the
        // documented lock order (layersLock -> cueAssignmentsLock) stops being the only one.
        if (changed) _layersFlow.tryEmit(result.first)
        return result
    }

    /**
     * Flow of full layer-stack snapshots, for broadcasting to every connected client.
     *
     * Emitted from [mutateLayers] rather than from `ProgrammerLayerStack.recook`, because
     * `mutateLayers` is the *only* place the list changes: `reset()` deliberately bypasses the
     * recook path, so a hook there would silently miss a full programmer clear — one tab clearing
     * would leave every other tab still drawing the stack.
     *
     * Why this exists at all, given that a layer mutation usually also moves values and so pushes
     * `provenanceState` (which the client answers with a `programmer.state` refetch): *usually* is
     * not always. A layer whose `targets` don't match its bound Look's rows asserts nothing, so
     * adding, reordering or disabling it changes no value, emits no provenance, and left every other
     * tab showing a stale layer list until something unrelated moved. Verified on a desk before this
     * flow existed. Layer-list changes and value changes are different events and need different
     * signals.
     *
     * `replay = 1` so a tab opening mid-show is sent the current stack on connect rather than only
     * after the next mutation — the same reason `lastIncludedTargetFlow` replays.
     *
     * Reference inequality is the emit guard, which exactly skips the no-op mutations that return
     * their input list unchanged (`move` to the index a layer already occupies). Identical *content*
     * still emits; the client compares its own signature before waking the pads.
     */
    private val _layersFlow = MutableSharedFlow<List<ProgrammerLayer>>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Flow of full layer-stack snapshots for WebSocket broadcasting. */
    val layersFlow: SharedFlow<List<ProgrammerLayer>> = _layersFlow.asSharedFlow()

    private val epochCounter = AtomicLong(0)

    /**
     * Monotonic mutation counter. Incremented on every put/clear (property or sideband) and
     * used to stamp [Slot.seq] on writes. Per-tick consumers cache derived snapshots keyed
     * on this and rebuild only on change.
     */
    val epoch: Long get() = epochCounter.get()

    private fun bumpEpoch(): Long = epochCounter.incrementAndGet()

    // ── Property entries ────────────────────────────────────────────────────

    /**
     * Record [owner]'s value for (fixtureKey, propertyName), replacing any previous slot the
     * same owner held there and moving the owner to the top of the stack.
     */
    fun put(
        owner: ProgrammerOwner,
        fixtureKey: String,
        propertyName: String,
        value: CueAssignmentResolver.PropertyValue,
        touched: Boolean = true,
        sourceGroup: String? = null,
    ) = putValue(owner, fixtureKey, propertyName, ProgrammerValue.Hard(value), touched, sourceGroup)

    /**
     * As [put], but for a caller that already has a [ProgrammerValue] — i.e. one writing a
     * a non-default [ProgrammerValue] arm. [put] stays the common door so the many literal call sites don't
     * have to name the wrapper.
     */
    fun putValue(
        owner: ProgrammerOwner,
        fixtureKey: String,
        propertyName: String,
        value: ProgrammerValue,
        touched: Boolean = true,
        sourceGroup: String? = null,
    ) {
        val slot = Slot(owner, value, touched, sourceGroup, seq = bumpEpoch())
        val byProperty = properties.computeIfAbsent(fixtureKey) { ConcurrentHashMap() }
        byProperty.compute(propertyName) { _, holder -> withSlot(holder, slot) }
    }

    /** One key's share of a cooked layer stack, ready for [putLayerSlots]. */
    data class LayerSlotWrite(
        val fixtureKey: String,
        val propertyName: String,
        val value: CueAssignmentResolver.PropertyValue,
        /**
         * Rank of the layer that won this key, within the contributing layers
         * ([uk.me.cormack.lighting7.fx.CookWinner.index]). Stamped into the reserved
         * [LAYER_SEQ_BASE] band, never used as a raw `seq`.
         */
        val layerIndex: Int,
        val sourceGroup: String? = null,
    )

    /**
     * Replace the [ProgrammerOwner.LAYERS] contribution wholesale with [writes].
     *
     * The layer stack is cooked outside the store and materialised here, so this is a **set**
     * operation rather than a series of puts: any key the new set doesn't name loses its layer slot.
     * Returns the keys whose *winning* value moved, for the caller to republish.
     *
     * Three properties of the implementation are deliberate.
     *
     * **Puts before clears.** Installing the new slots first and only then releasing the keys that
     * dropped out means no key is ever momentarily uncovered. The other order would let the cue or
     * baseline underneath show through for one publish on every key that survives, which on an
     * effect-covered key is a visible flash on each Look edit.
     *
     * **One epoch bump for the whole swap.** `epoch` gates the per-tick effect-suppression snapshot
     * (`FxEngine.programmerSuppression`); bumping per key would make it rebuild once per key in the
     * middle of a half-applied stack.
     *
     * **`touched = false`.** These are not operator edits, so `RecordSource.TOUCHED` skips them —
     * which is exactly right, because Record saves the layer *stack* as layers and only the
     * operator's own rows as rows. The [ProgrammerOwner.UNPARK] slots use the same flag for the
     * same reason. `RecordSource.ALL` still sees them, which is what makes "flatten what's on
     * stage" mean something.
     *
     * The sideband is untouched: a layer never absorbs a raw channel write, or the operator's
     * channel-level drags would be destroyed on every recook.
     */
    fun putLayerSlots(writes: List<LayerSlotWrite>): Set<CueAssignmentResolver.Key> {
        val moved = HashSet<CueAssignmentResolver.Key>()
        val named = HashSet<Pair<String, String>>(writes.size)

        for (write in writes) {
            named.add(write.fixtureKey to write.propertyName)
            val slot = Slot(
                owner = ProgrammerOwner.LAYERS,
                value = ProgrammerValue.Hard(write.value),
                touched = false,
                sourceGroup = write.sourceGroup,
                seq = LAYER_SEQ_BASE + write.layerIndex,
            )
            val byProperty = properties.computeIfAbsent(write.fixtureKey) { ConcurrentHashMap() }
            var before: ProgrammerValue? = null
            byProperty.compute(write.propertyName) { _, holder ->
                before = holder?.top?.value
                withSlot(holder, slot)
            }
            val after = byProperty[write.propertyName]?.top?.value
            if (before?.resolved != after?.resolved) {
                moved.add(CueAssignmentResolver.Key.fixture(write.fixtureKey, write.propertyName))
            }
        }

        for ((fixtureKey, byProperty) in properties) {
            for (propertyName in byProperty.keys) {
                if (fixtureKey to propertyName in named) continue
                var before: ProgrammerValue? = null
                byProperty.computeIfPresent(propertyName) { _, holder ->
                    before = holder.top.value
                    withoutOwner(holder, ProgrammerOwner.LAYERS)
                }
                val after = byProperty[propertyName]?.top?.value
                if (before != null && before?.resolved != after?.resolved) {
                    moved.add(CueAssignmentResolver.Key.fixture(fixtureKey, propertyName))
                }
            }
        }

        bumpEpoch()
        return moved
    }

    /** The winning (most recent) slot for a property, or null when no owner holds it. */
    fun get(fixtureKey: String, propertyName: String): Slot? =
        properties[fixtureKey]?.get(propertyName)?.top

    /** All slots for a property, most recent first. Cold path — Record/state/tests. */
    fun slotsFor(fixtureKey: String, propertyName: String): List<Slot> =
        properties[fixtureKey]?.get(propertyName)?.all() ?: emptyList()

    /**
     * Remove [owner]'s slot at (fixtureKey, propertyName). No-op if that owner holds no
     * entry there; other owners' slots are untouched and the most recent of them becomes
     * the value [get] reports.
     *
     * Emptied per-fixture inner maps are deliberately NOT removed from [properties]: an
     * empty-check-then-remove would race a concurrent [put] on another property of the
     * same fixture — [put]'s `computeIfAbsent` can return the same inner map instance an
     * instant before the reference-equality `remove(key, value)` deletes it, silently
     * losing the fresh write. Empty inner maps are bounded by the fixture count and cost
     * nothing ([coversFixture]/[entries]/[size] all tolerate them).
     */
    fun clear(owner: ProgrammerOwner, fixtureKey: String, propertyName: String) {
        val byProperty = properties[fixtureKey] ?: return
        var changed = false
        byProperty.computeIfPresent(propertyName) { _, holder ->
            val next = withoutOwner(holder, owner)
            if (next !== holder) changed = true
            next
        }
        if (changed) bumpEpoch()
    }

    /** [owner]'s own slot at (fixtureKey, propertyName), whether or not it is on top. */
    fun valueFor(owner: ProgrammerOwner, fixtureKey: String, propertyName: String): ProgrammerValue? =
        properties[fixtureKey]?.get(propertyName)?.all()?.firstOrNull { it.owner == owner }?.value

    // ── Channel sideband ────────────────────────────────────────────────────

    /**
     * Record [owner]'s raw channel value in the sideband. Used for channels with no backing
     * property (`updateChannel` on an unmapped channel) and for unpark hand-downs, which are
     * channel-shaped by nature — lifting a single unparked channel to a property entry would
     * freeze its sibling channels into the programmer.
     */
    fun putChannel(
        owner: ProgrammerOwner,
        universe: Int,
        channel: Int,
        value: UByte,
        touched: Boolean = true,
    ) {
        val slot = Slot(
            owner,
            ProgrammerValue.Hard(CueAssignmentResolver.PropertyValue.Slider(value)),
            touched,
            sourceGroup = null,
            seq = bumpEpoch(),
        )
        channels.compute(packChannelKey(universe, channel)) { _, holder -> withSlot(holder, slot) }
    }

    /** The winning sideband slot at (universe, channel), or null. Hot path. */
    fun getChannelSlot(universe: Int, channel: Int): Slot? =
        channels[packChannelKey(universe, channel)]?.top

    /** The winning sideband value at (universe, channel), or null. Hot path. */
    fun getChannel(universe: Int, channel: Int): UByte? {
        val top = getChannelSlot(universe, channel) ?: return null
        return (top.value.resolved as? CueAssignmentResolver.PropertyValue.Slider)?.value
    }

    /** Remove [owner]'s sideband slot at (universe, channel). */
    fun clearChannel(owner: ProgrammerOwner, universe: Int, channel: Int) {
        var changed = false
        channels.computeIfPresent(packChannelKey(universe, channel)) { _, holder ->
            val next = withoutOwner(holder, owner)
            if (next !== holder) changed = true
            next
        }
        if (changed) bumpEpoch()
    }

    /** [owner]'s own sideband value at (universe, channel), whether or not it is on top. */
    fun channelValueFor(owner: ProgrammerOwner, universe: Int, channel: Int): UByte? {
        val slot = channels[packChannelKey(universe, channel)]?.all()?.firstOrNull { it.owner == owner }
            ?: return null
        return (slot.value.resolved as? CueAssignmentResolver.PropertyValue.Slider)?.value
    }

    /**
     * Drop every sideband slot (all owners) on the given channels. Called by the engine
     * when a deliberate property write lands, so a stale unpark or raw-channel value under
     * the property cannot resurface when the property entry is later cleared — property
     * entries absorb the sideband beneath them rather than stacking across granularities.
     */
    fun clearChannelsAbsorbedBy(channelKeys: Iterable<Long>) {
        var removed = false
        for (key in channelKeys) {
            if (channels.remove(key) != null) removed = true
        }
        if (removed) bumpEpoch()
    }

    // ── Sweeps ──────────────────────────────────────────────────────────────

    /**
     * Remove every slot [owner] holds anywhere in the store (property entries and sideband),
     * returning the number of keys swept. Cold-path stale-record recovery: when a release
     * can no longer resolve the fixture an entry was recorded against (rekeyed fixture,
     * rebuilt group), the targeted clear is unreachable and the stranded slot would
     * otherwise survive forever — resurfacing later as a ghost value once an owner stacked
     * above it clears. Sweeping is safe whenever all of an owner's entries are released
     * together, which holds for every current owner with a release path.
     */
    fun clearOwner(owner: ProgrammerOwner): Int {
        var swept = 0
        // Emptied inner maps are left in place — see [clear] for the race a
        // check-then-remove would open against a concurrent [put] on the same fixture.
        for (byProperty in properties.values) {
            for (propertyName in byProperty.keys) {
                byProperty.computeIfPresent(propertyName) { _, holder ->
                    val next = withoutOwner(holder, owner)
                    if (next !== holder) swept++
                    next
                }
            }
        }
        for (key in channels.keys) {
            channels.computeIfPresent(key) { _, holder ->
                val next = withoutOwner(holder, owner)
                if (next !== holder) swept++
                next
            }
        }
        if (swept > 0) bumpEpoch()
        return swept
    }

    /**
     * Remove every entry for every owner — property entries and sideband.
     *
     * Also drops [lastIncludedTarget]: Clear releases everything Include staged, so there is
     * nothing left to write back and a surviving target would offer an Update that silently
     * did nothing.
     */
    fun clearAll() {
        properties.clear()
        channels.clear()
        _lastIncludedTarget.value = null
        // The stack goes with the slots it produced. Leaving it would let the next recook — any
        // Look edit, anywhere — put the whole look back on stage after the operator cleared it.
        synchronized(layersLock) { layers = emptyList() }
        includedLayerSnapshot = emptyList()
        bumpEpoch()
    }

    // ── Enumeration (cold path: Record, blind republish, state broadcast) ───

    /** One property entry as seen from outside: winning slot first. */
    data class EntryView(
        val fixtureKey: String,
        val propertyName: String,
        /** Most recent first; `slots[0]` is the winning contribution. */
        val slots: List<Slot>,
    )

    /** One sideband entry as seen from outside. */
    data class ChannelEntryView(
        val universe: Int,
        val channel: Int,
        val slots: List<Slot>,
    )

    /** Snapshot of every property entry. Cold path. */
    fun entries(): List<EntryView> = buildList {
        for ((fixtureKey, byProperty) in properties) {
            for ((propertyName, holder) in byProperty) {
                add(EntryView(fixtureKey, propertyName, holder.all()))
            }
        }
    }

    /**
     * For every property key whose **winning** slot is a [ProgrammerOwner.LAYERS] contribution, the
     * rank of the layer that wrote it.
     *
     * The rank is recovered from the slot's reserved `seq` band rather than stored a second time:
     * [putLayerSlots] stamps `LAYER_SEQ_BASE + layerIndex` precisely so layer slots stay *mutually*
     * ordered by rank, which makes the rank readable back. The decode belongs here rather than in
     * the caller because [LAYER_SEQ_BASE] is this class's invariant, and a second site
     * reconstructing ranks from raw `seq` values is a second site to get the band arithmetic wrong.
     *
     * Only *winning* slots are reported, and that restriction is the whole contract. A layer slot
     * sits at the tail of its stack, so any local busk over the same key outranks it; naming the
     * layer for such a key would tell the operator their colour came from a Look when it came from
     * their own hand. Keys won by `web`, `surface`, `flash`, `locate`, `unpark` or park are absent.
     *
     * Cold path — provenance recomputes on layer events, never per frame.
     */
    fun layerWinnerRankByKey(): Map<CueAssignmentResolver.Key, Int> = buildMap {
        for ((fixtureKey, byProperty) in properties) {
            for ((propertyName, holder) in byProperty) {
                val top = holder.top
                if (top.owner != ProgrammerOwner.LAYERS) continue
                put(
                    CueAssignmentResolver.Key.fixture(fixtureKey, propertyName),
                    (top.seq - LAYER_SEQ_BASE).toInt(),
                )
            }
        }
    }

    /** Snapshot of every sideband entry. Cold path. */
    fun channelEntries(): List<ChannelEntryView> = buildList {
        for ((packed, holder) in channels) {
            add(ChannelEntryView(unpackUniverse(packed), unpackChannel(packed), holder.all()))
        }
    }

    /** The (fixture, property) keys currently holding at least one slot. Cold path. */
    fun activeKeys(): Set<CueAssignmentResolver.Key> = buildSet {
        for ((fixtureKey, byProperty) in properties) {
            for (propertyName in byProperty.keys) {
                add(CueAssignmentResolver.Key.fixture(fixtureKey, propertyName))
            }
        }
    }

    /**
     * fixtureKey → property names with at least one slot. Feeds the per-tick effect
     * suppression snapshot; cache the result keyed on [epoch].
     */
    fun activePropertiesByFixture(): Map<String, Set<String>> {
        if (properties.isEmpty()) return emptyMap()
        val out = HashMap<String, Set<String>>(properties.size)
        for ((fixtureKey, byProperty) in properties) {
            if (byProperty.isEmpty()) continue
            out[fixtureKey] = HashSet(byProperty.keys)
        }
        return out
    }

    /** Number of properties holding at least one slot. Tests / diagnostics. */
    val size: Int get() = properties.values.sumOf { it.size }

    /** Number of sideband channels holding at least one slot. Tests / diagnostics. */
    val channelCount: Int get() = channels.size

    /** True when no property entry or sideband slot exists. Cold path (walks fixtures). */
    val isEmpty: Boolean get() = channels.isEmpty() && properties.values.all { it.isEmpty() }

    /**
     * O(1) hot-path gate: does the programmer hold any property entry for [fixtureKey]?
     * (The sideband is checked separately via [hasSidebandEntries] — per-channel coverage
     * can't be answered per fixture without resolving the patch.)
     */
    fun coversFixture(fixtureKey: String): Boolean =
        properties[fixtureKey]?.isNotEmpty() == true

    /** O(1) hot-path gate: does the sideband hold any slot at all? */
    val hasSidebandEntries: Boolean get() = channels.isNotEmpty()

    // ── Slot-stack mechanics (shared by properties and sideband) ────────────

    private fun withSlot(holder: Holder?, slot: Slot): Holder {
        // The layer stack's cooked contribution goes to the **bottom** of the key's stack, not the
        // top. Its rank is author-declared — later layers win, and the local layer wins over all of
        // them — whereas this stack's order is write-recency, re-derived on every put. Head-inserting
        // it would hide the operator's own WEB/SURFACE slot underneath it, because [get] returns
        // `top` and both SliderTarget and SettingTarget take the property entry's value
        // unconditionally, without consulting `seq`.
        if (slot.owner == ProgrammerOwner.LAYERS) return withLayerSlot(holder, slot)
        return when (holder) {
            null -> Single(slot)
            is Single ->
                if (holder.slot.owner == slot.owner) Single(slot)
                else Stack(listOf(slot, holder.slot))
            is Stack -> Stack(buildList(holder.slots.size + 1) {
                add(slot)
                for (s in holder.slots) if (s.owner != slot.owner) add(s)
            })
        }
    }

    private fun withLayerSlot(holder: Holder?, slot: Slot): Holder = when (holder) {
        null -> Single(slot)
        is Single ->
            if (holder.slot.owner == slot.owner) Single(slot)
            else Stack(listOf(holder.slot, slot))
        is Stack -> {
            val rest = holder.slots.filter { it.owner != slot.owner }
            if (rest.isEmpty()) Single(slot) else Stack(rest + slot)
        }
    }

    /** [holder] with [owner]'s slot removed: null when empty, same instance when absent. */
    private fun withoutOwner(holder: Holder, owner: ProgrammerOwner): Holder? = when (holder) {
        is Single -> if (holder.slot.owner == owner) null else holder
        is Stack -> {
            val rest = holder.slots.filter { it.owner != owner }
            when (rest.size) {
                holder.slots.size -> holder
                1 -> Single(rest[0])
                else -> Stack(rest)
            }
        }
    }

    private fun unpackUniverse(packed: Long): Int = (packed shr 20).toInt()
    private fun unpackChannel(packed: Long): Int = (packed and 0xFFFFF).toInt()

    companion object {
        /**
         * Base of the reserved [Slot.seq] band for layer-materialised slots.
         *
         * Every real write gets `seq >= 1` ([bumpEpoch] is `incrementAndGet` from 0), so this band
         * sits strictly below all of them: any operator write beats a layer in
         * [uk.me.cormack.lighting7.fx.FxTarget.composeProgrammerOver]'s cross-granularity recency
         * comparison, whichever happened first in wall-clock time.
         *
         * **Two things about this constant are load-bearing.**
         *
         * It is not `0`, and it is not a single value shared by every layer slot. Layer slots are
         * stamped `LAYER_SEQ_BASE + layerIndex`, so they stay *mutually* ordered by layer rank.
         * A flat value would make two layer slots tie on `seq` — which cannot happen anywhere else
         * in this store, and which the four `composeProgrammerOver` overrides resolve
         * **inconsistently**: `SliderTarget` lets an explicit `white` row beat a Colour entry's
         * bundled white on a tie, while `ColourTarget.extendedComponent` lets the Colour entry win.
         * Both write the same DMX channel, so a tie makes the output depend on which `FxTarget` was
         * inferred last for the key.
         *
         * And it is not `Long.MIN_VALUE`, which those same overrides use as the "nothing here"
         * sentinel. A slot stamped with it would be read as absent and lose to any sideband value.
         */
        const val LAYER_SEQ_BASE: Long = Long.MIN_VALUE / 2
    }
}
