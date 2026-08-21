package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.me.cormack.lighting7.dmx.packChannelKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
         * FX preset toggles and editor live previews (`routes/projectFxPresets.kt`). One
         * owner per preset id, so toggling one preset off cannot release another preset's
         * entry on a shared property. Previews pass their synthetic negative id.
         */
        fun preset(presetId: Int) = ProgrammerOwner("preset:$presetId")
    }

    override fun toString(): String = id
}

/**
 * A value held by the programmer.
 *
 * Both variants carry a concrete [resolved] value, so every read path — including
 * [FxTarget.composeProgrammerOver] and `fallbackFromProgrammer` on the 50 Hz tick — reads
 * `.resolved` without caring which it has. A [Ref] additionally remembers *where* its value came
 * from, which is what lets Record write a reference back out and Update leave an untouched one
 * alone instead of silently hardening it.
 */
sealed interface ProgrammerValue {
    /** The concrete value the entry contributes to the cascade. */
    val resolved: CueAssignmentResolver.PropertyValue

    /** A literal value, not derived from any palette. */
    data class Hard(override val resolved: CueAssignmentResolver.PropertyValue) : ProgrammerValue

    /**
     * A named-palette reference plus the literal it currently resolves to *for this fixture and
     * property* (per-fixture, which is the whole point of a palette — a position palette's value
     * for one head is meaningless on another).
     *
     * The resolved value is cached rather than looked up per read: the alternative is a palette
     * lookup inside the tick loop. The cost of caching is that a palette edit must re-resolve
     * these slots and republish — see `routes/paletteRepublish.kt`, which is the mechanism the
     * whole feature rests on.
     */
    data class Ref(
        val paletteUuid: UUID,
        override val resolved: CueAssignmentResolver.PropertyValue,
    ) : ProgrammerValue
}

/** The palette this value references, or null when it is a literal. */
val ProgrammerValue.paletteUuidOrNull: UUID?
    get() = (this as? ProgrammerValue.Ref)?.paletteUuid

/**
 * Wrap a resolved value as a [ProgrammerValue.Ref] when it came from [paletteUuid], else as a
 * [ProgrammerValue.Hard]. Keeps the "did this come from a palette?" branch in one place rather
 * than at each of the engine's write entry points.
 */
fun programmerValueOf(
    value: CueAssignmentResolver.PropertyValue,
    paletteUuid: UUID?,
): ProgrammerValue =
    if (paletteUuid == null) ProgrammerValue.Hard(value) else ProgrammerValue.Ref(paletteUuid, value)

/**
 * What Include last pulled into the programmer, and therefore what Update writes back to
 * when no explicit targets are given (Mode A).
 *
 * [targetId] is a cue id, a palette id or a Look id depending on [kind]; [cueId] / [paletteId] /
 * [lookId] narrow it. [cueStackId] is carried so the client can name the stack in the indicator
 * without a second lookup, and [paletteUuid] so a re-import can't leave the indicator pointing at
 * a stranger.
 *
 * [Kind.LOOK] and [Kind.PALETTE] are separate arms rather than one, even though a migrated
 * palette's uuid *is* its Look's: the ids come from different tables, and Update writes back
 * through different code (the palette arm into `DaoPalette`, the Look arm not at all until the
 * record rewrite lands). Collapsing them would let a Look-shaped include be written back into a
 * palette row nothing reads.
 */
data class IncludedTarget(
    val kind: Kind,
    val targetId: Int,
    val cueStackId: Int? = null,
    val paletteUuid: UUID? = null,
) {
    enum class Kind {
        CUE,
        PALETTE,
        LOOK,
    }

    /** The cue id, or null when something else is included. */
    val cueId: Int? get() = targetId.takeIf { kind == Kind.CUE }

    /** The palette id, or null when something else is included. */
    val paletteId: Int? get() = targetId.takeIf { kind == Kind.PALETTE }

    /** The Look id, or null when something else is included. */
    val lookId: Int? get() = targetId.takeIf { kind == Kind.LOOK }

    companion object {
        fun cue(cueId: Int, cueStackId: Int?) = IncludedTarget(Kind.CUE, cueId, cueStackId)

        fun palette(paletteId: Int, paletteUuid: UUID) =
            IncludedTarget(Kind.PALETTE, paletteId, paletteUuid = paletteUuid)

        fun look(lookId: Int, lookUuid: UUID) =
            IncludedTarget(Kind.LOOK, lookId, paletteUuid = lookUuid)
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

    /** As [clearIncludeTargetForCue], for a deleted palette. */
    fun clearIncludeTargetForPalette(paletteId: Int) {
        _lastIncludedTarget.value = _lastIncludedTarget.value?.takeIf { it.paletteId != paletteId }
    }

    /** As [clearIncludeTargetForCue], for a deleted Look. */
    fun clearIncludeTargetForLook(lookId: Int) {
        _lastIncludedTarget.value = _lastIncludedTarget.value?.takeIf { it.lookId != lookId }
    }

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
     * [ProgrammerValue.Ref]. [put] stays the common door so the many literal call sites don't
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

    /** Snapshot of every sideband entry. Cold path. */
    fun channelEntries(): List<ChannelEntryView> = buildList {
        for ((packed, holder) in channels) {
            add(ChannelEntryView(unpackUniverse(packed), unpackChannel(packed), holder.all()))
        }
    }

    /**
     * Rewrite slot *values* in place, leaving every other property of each slot alone. Returns the
     * keys whose winning value actually changed, so the caller can republish only those.
     *
     * [transform] receives `(fixtureKey, propertyName, slot)` and answers a replacement value, or
     * null to leave that slot as it is. Used to re-resolve [ProgrammerValue.Ref] slots after a
     * palette edit, and to harden them (Make Hard).
     *
     * **[Slot.seq] is preserved deliberately.** Re-resolving a reference is not a new operator
     * write. `seq` is what arbitrates a property entry against a channel-sideband slot covering the
     * same channel, so bumping it here would let a palette edit quietly outrank a *newer* raw
     * channel drag — changing which value Record captures, for a reason no operator could see.
     * [touched] and [sourceGroup] survive for the same reason: neither a re-resolve nor a harden
     * is an edit, and Record and the Update checklist read both.
     */
    fun rewriteSlotValues(
        transform: (fixtureKey: String, propertyName: String, slot: Slot) -> ProgrammerValue?,
    ): Set<CueAssignmentResolver.Key> {
        val changed = HashSet<CueAssignmentResolver.Key>()
        // Tracked separately from [changed]: hardening a ref replaces the slot without moving its
        // resolved value, so there is nothing to republish but the store *has* mutated and
        // epoch-cached consumers must re-read.
        var mutated = false
        for ((fixtureKey, byProperty) in properties) {
            for (propertyName in byProperty.keys) {
                byProperty.computeIfPresent(propertyName) { _, holder ->
                    val before = holder.top.value.resolved
                    var any = false
                    val rewritten = holder.all().map { slot ->
                        val next = transform(fixtureKey, propertyName, slot)
                        if (next == null || next == slot.value) {
                            slot
                        } else {
                            any = true
                            Slot(slot.owner, next, slot.touched, slot.sourceGroup, slot.seq)
                        }
                    }
                    if (!any) return@computeIfPresent holder
                    mutated = true
                    val next = if (rewritten.size == 1) Single(rewritten[0]) else Stack(rewritten)
                    if (next.top.value.resolved != before) {
                        changed.add(CueAssignmentResolver.Key.fixture(fixtureKey, propertyName))
                    }
                    next
                }
            }
        }
        // One bump for the whole sweep: per-tick consumers cache on `epoch` and only need to know
        // that *something* moved.
        if (mutated) bumpEpoch()
        return changed
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

    private fun withSlot(holder: Holder?, slot: Slot): Holder = when (holder) {
        null -> Single(slot)
        is Single ->
            if (holder.slot.owner == slot.owner) Single(slot)
            else Stack(listOf(slot, holder.slot))
        is Stack -> Stack(buildList(holder.slots.size + 1) {
            add(slot)
            for (s in holder.slots) if (s.owner != slot.owner) add(s)
        })
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
}
