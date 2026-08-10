package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.packChannelKey
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.midi.PropertyChannelResolver
import java.util.concurrent.ConcurrentHashMap

/**
 * Identifies which subsystem asserted a Layer-4 write, so releasing one subsystem's writes
 * cannot destroy another's (see `docs/lighting-composition-model.md` §"Layer 4 ownership").
 *
 * Owners with a release path (flash, presets, locate) must clear with the same owner they
 * put with — that is the whole contract. Two writers that share an owner overwrite each
 * other's entry (last write wins), which is why locate-vs-locate overlap is handled by
 * [uk.me.cormack.lighting7.show.LocateManager]'s re-assert loop rather than here.
 */
@JvmInline
value class DirectWriteOwner(val id: String) {
    companion object {
        /** Manual busking: web `updateChannel` and the park-release value hand-down. */
        val BUSKING = DirectWriteOwner("busking")

        /** MIDI control-surface faders ([uk.me.cormack.lighting7.midi.SurfaceActions]). */
        val SURFACE = DirectWriteOwner("surface")

        /**
         * Surface flash buttons — press asserts, release clears. Separate from [SURFACE] so
         * a flash release restores the fader/busk level underneath instead of wiping it.
         */
        val FLASH = DirectWriteOwner("flash")

        /**
         * Locate toggles (`routes/lightLocate.kt`). One shared owner for every locate
         * target: overlapping locates (group + member) are re-asserted by
         * [uk.me.cormack.lighting7.show.LocateManager] on release, which also re-resolves
         * stale targets — semantics a per-target owner fallback could not replicate.
         */
        val LOCATE = DirectWriteOwner("locate")

        /**
         * FX preset toggles and editor live previews (`routes/projectFxPresets.kt`). One
         * owner per preset id, so toggling one preset off cannot release another preset's
         * write on a shared channel. Previews pass their synthetic negative id.
         */
        fun preset(presetId: Int) = DirectWriteOwner("preset:$presetId")
    }

    override fun toString(): String = id
}

/**
 * Layer 4 — sticky direct channel writes, tagged by [DirectWriteOwner].
 *
 * Per-channel values set by the operator outside of any cue. Read by [LayerResolver] during
 * effect reset so that manual writes remain visible under running effects instead of being
 * clobbered by the effect reset-to-neutral pass.
 *
 * Each channel holds a small stack of per-owner entries ordered by write recency:
 * - [put] installs or refreshes the owner's entry and moves it to the top — the most recent
 *   write wins on the wire regardless of who made it, matching the old flat-map behaviour
 *   when only one subsystem is touching the channel.
 * - [clear] removes only that owner's entry. If another owner still holds the channel, its
 *   most recent value becomes visible again — releasing a Locate no longer wipes a busked
 *   level or an active preset's assertion on the same channel.
 * - [get] returns the top of the stack, or null when no owner holds the channel.
 *
 * Values persist until every owner clears (or overwrites) its entry. Callers that release
 * writes ([uk.me.cormack.lighting7.fx.FxEngine.clearLayer4Properties] etc.) republish the
 * affected keys so the channel cascades to the surviving owner's value, Layer 3, or baseline.
 *
 * Thread-safety: reads are lock-free via [ConcurrentHashMap]; mutations go through
 * [ConcurrentHashMap.compute], so put/clear for the same channel cannot interleave. The
 * store is read on the FX engine tick thread and written on the caller threads that handle
 * WebSocket / REST / MIDI events.
 *
 * Performance: keyed by a packed `Long` (`universe << 20 | channel`) to avoid allocating a
 * composite key object per read. [get] is on the 50 Hz effect-reset hot path
 * ([FxTarget.fallbackFromDirectWrites]): it is O(1) and allocation-free — the common
 * single-owner case stores one [Entry] whose value field is the boxed `UByte` that [get]
 * returns directly, and only a genuine multi-owner overlap allocates a [Stack]. Mutations
 * (human/MIDI rate) copy-on-write the slot.
 */
class DirectWriteStore {
    /**
     * One owner's write. Doubles as the whole slot in the single-owner common case, so a
     * channel touched by one subsystem never allocates a stack.
     *
     * [value] is declared nullable purely to force boxed storage: [get] then returns the
     * stored box as-is instead of re-boxing a primitive on every hot-path call. It is never
     * null.
     */
    private class Entry(val owner: DirectWriteOwner, val value: UByte?) : Slot {
        override val top: Entry get() = this
    }

    /** Two or more owners on one channel, most recent write first. Always size >= 2. */
    private class Stack(val entries: List<Entry>) : Slot {
        override val top: Entry get() = entries[0]
    }

    private sealed interface Slot {
        val top: Entry
    }

    private val values = ConcurrentHashMap<Long, Slot>()

    /**
     * Record [owner]'s direct write at (universe, channel), replacing any previous value the
     * same owner held there and moving the owner to the top of the channel's stack.
     */
    fun put(owner: DirectWriteOwner, universe: Int, channel: Int, value: UByte) {
        val entry = Entry(owner, value)
        values.compute(packChannelKey(universe, channel)) { _, slot ->
            when (slot) {
                null -> entry
                is Entry -> if (slot.owner == owner) entry else Stack(listOf(entry, slot))
                is Stack -> Stack(buildList(slot.entries.size + 1) {
                    add(entry)
                    for (e in slot.entries) if (e.owner != owner) add(e)
                })
            }
        }
    }

    /** Read the sticky value at (universe, channel) — the most recent owner's — or null. */
    fun get(universe: Int, channel: Int): UByte? = values[packChannelKey(universe, channel)]?.top?.value

    /**
     * Remove [owner]'s entry at (universe, channel). No-op if that owner holds no write
     * there; other owners' entries are untouched and the most recent of them becomes the
     * value [get] reports.
     */
    fun clear(owner: DirectWriteOwner, universe: Int, channel: Int) {
        values.computeIfPresent(packChannelKey(universe, channel)) { _, slot ->
            withoutOwner(slot, owner)
        }
    }

    /** [slot] with [owner]'s entry removed: null when empty, the same instance when absent. */
    private fun withoutOwner(slot: Slot, owner: DirectWriteOwner): Slot? = when (slot) {
        is Entry -> if (slot.owner == owner) null else slot
        is Stack -> {
            val rest = slot.entries.filter { it.owner != owner }
            when (rest.size) {
                slot.entries.size -> slot
                1 -> rest[0]
                else -> Stack(rest)
            }
        }
    }

    /**
     * Remove every entry [owner] holds anywhere in the store, returning the number of
     * channels swept. Cold-path stale-record recovery: when a release can no longer resolve
     * the fixture a write was recorded against (rekeyed fixture, rebuilt group), the
     * targeted per-channel clear is unreachable and the stranded entry would otherwise
     * survive forever — resurfacing later as a ghost value once an owner stacked above it
     * clears. Sweeping is safe whenever all of an owner's writes are released together,
     * which holds for every current owner with a release path (per-preset toggles and
     * previews; locate once no target is located).
     */
    fun clearOwner(owner: DirectWriteOwner): Int {
        var swept = 0
        for (key in values.keys) {
            values.computeIfPresent(key) { _, slot ->
                val next = withoutOwner(slot, owner)
                if (next !== slot) swept++
                next
            }
        }
        return swept
    }

    /** Remove all sticky values for every owner. Primarily for tests and shutdown. */
    fun clearAll() {
        values.clear()
    }

    /** Number of channels holding at least one write. Exposed for tests / diagnostics. */
    val size: Int get() = values.size

    /**
     * [owner]'s own entry at (universe, channel), whether or not it is on top. Cold path —
     * tests and diagnostics only.
     */
    fun valueFor(owner: DirectWriteOwner, universe: Int, channel: Int): UByte? =
        when (val slot = values[packChannelKey(universe, channel)]) {
            null -> null
            is Entry -> if (slot.owner == owner) slot.value else null
            is Stack -> slot.entries.firstOrNull { it.owner == owner }?.value
        }

    /**
     * Property-level write: record [owner]'s direct-write value for every channel that backs
     * the named property on [fixture]. Returns the resolved
     * [PropertyChannelResolver.ChannelWrite] list so the caller can push the same values
     * through to the DMX controller.
     *
     * Takes a 7-bit MIDI-style value so surface fader events can be fed straight in; 8-bit
     * callers can still use the raw channel-level [put].
     */
    fun putProperty(
        owner: DirectWriteOwner,
        fixture: Fixture,
        propertyName: String,
        midiValue7Bit: UByte,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val writes = PropertyChannelResolver.resolveFixtureProperty(fixture, propertyName, midiValue7Bit)
        for (w in writes) put(owner, w.universe.universe, w.channel, w.value)
        return writes
    }

    /**
     * Clear [owner]'s sticky values for every channel that backs the named property on
     * [fixture], restoring whatever owner or layer was underneath. Store-only counterpart
     * of [FxEngine.clearLayer4Property] for callers that publish the fallback themselves.
     */
    fun clearProperty(
        owner: DirectWriteOwner,
        fixture: Fixture,
        propertyName: String,
    ): List<PropertyChannelResolver.ChannelWrite> {
        // Resolve with a dummy value so we get the channel list; value is not used.
        val channels = PropertyChannelResolver.resolveFixtureProperty(fixture, propertyName, 0u)
        for (c in channels) clear(owner, c.universe.universe, c.channel)
        return channels
    }

    /**
     * Group-level property write: fan out to every fixture in the group's `fixtures` list
     * (including sub-group members via [FixtureGroup.allMembers]). Returns the flattened
     * list of all channel writes so the caller can push them through to controllers.
     */
    fun putGroupProperty(
        owner: DirectWriteOwner,
        group: FixtureGroup<*>,
        propertyName: String,
        midiValue7Bit: UByte,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val all = mutableListOf<PropertyChannelResolver.ChannelWrite>()
        for (fixture in group.fixtures) {
            if (fixture is Fixture) {
                all += putProperty(owner, fixture, propertyName, midiValue7Bit)
            }
        }
        return all
    }

    /** Group-level flash release: clears [owner]'s sticky values for every member fixture. */
    fun clearGroupProperty(
        owner: DirectWriteOwner,
        group: FixtureGroup<*>,
        propertyName: String,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val all = mutableListOf<PropertyChannelResolver.ChannelWrite>()
        for (fixture in group.fixtures) {
            if (fixture is Fixture) {
                all += clearProperty(owner, fixture, propertyName)
            }
        }
        return all
    }
}
