package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.packChannelKey
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

        /** Reserved for Include (Session 3) — cue contents loaded into the programmer. */
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
 * A value held by the programmer. Sealed so Session 4 can add
 * `Ref(paletteId, resolved)` — a palette reference that resolves per-fixture at compose
 * time — without touching every consumer.
 */
sealed interface ProgrammerValue {
    /** The concrete value the entry contributes to the cascade. */
    val resolved: Layer3Resolver.PropertyValue

    /** A literal value, not derived from any palette. */
    data class Hard(override val resolved: Layer3Resolver.PropertyValue) : ProgrammerValue
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
     * [Layer3Resolver.PropertyValue.Slider]-wrapped raw bytes.
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
        value: Layer3Resolver.PropertyValue,
        touched: Boolean = true,
        sourceGroup: String? = null,
    ) {
        val slot = Slot(owner, ProgrammerValue.Hard(value), touched, sourceGroup, seq = bumpEpoch())
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
            ProgrammerValue.Hard(Layer3Resolver.PropertyValue.Slider(value)),
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
        return (top.value.resolved as? Layer3Resolver.PropertyValue.Slider)?.value
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
        return (slot.value.resolved as? Layer3Resolver.PropertyValue.Slider)?.value
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

    /** Remove every entry for every owner — property entries and sideband. */
    fun clearAll() {
        properties.clear()
        channels.clear()
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

    /** The (fixture, property) keys currently holding at least one slot. Cold path. */
    fun activeKeys(): Set<Layer3Resolver.Key> = buildSet {
        for ((fixtureKey, byProperty) in properties) {
            for (propertyName in byProperty.keys) {
                add(Layer3Resolver.Key.fixture(fixtureKey, propertyName))
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
