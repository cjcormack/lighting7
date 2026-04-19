package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.packChannelKey
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.midi.PropertyChannelResolver
import java.util.concurrent.ConcurrentHashMap

/**
 * Layer 4 — sticky direct channel writes from `updateChannel` handlers.
 *
 * Per-channel values set by the operator outside of any cue. Read by [LayerResolver] during
 * effect reset so that manual writes remain visible under running effects instead of being
 * clobbered by the effect reset-to-neutral pass.
 *
 * Values persist until:
 * - A new cue is triggered whose Layer 3 contribution covers the channel (caller invokes [clear]
 *   — Phase 1 wires this);
 * - `clearAssignment` is called for the target (Phase 1);
 * - A fresh `updateChannel` overwrites the value via [put].
 *
 * Thread-safety: reads and writes are lock-free via [ConcurrentHashMap]. The store is read on
 * the FX engine tick thread and written on the caller threads that handle WebSocket / REST
 * `updateChannel` messages.
 *
 * Performance: keyed by a packed `Long` (`universe << 20 | channel`) to avoid allocating a
 * composite key object per read. Universe numbers fit in the high bits (ArtNet universes are
 * 15-bit) and DMX channels fit in 9 bits (1–512), leaving room for subnet if we ever need it.
 */
class DirectWriteStore {
    private val values = ConcurrentHashMap<Long, UByte>()

    /** Record a direct write at (universe, channel). Overwrites any previous value. */
    fun put(universe: Int, channel: Int, value: UByte) {
        values[packChannelKey(universe, channel)] = value
    }

    /** Read the sticky value at (universe, channel), or null if none. */
    fun get(universe: Int, channel: Int): UByte? = values[packChannelKey(universe, channel)]

    /** Remove the sticky value at (universe, channel). No-op if absent. */
    fun clear(universe: Int, channel: Int) {
        values.remove(packChannelKey(universe, channel))
    }

    /** Remove all sticky values. Primarily for tests and shutdown. */
    fun clearAll() {
        values.clear()
    }

    /** Size, exposed for tests / diagnostics. */
    val size: Int get() = values.size

    /**
     * Property-level write: record the direct-write value for every channel that backs the
     * named property on [fixture]. Returns the resolved [PropertyChannelResolver.ChannelWrite]
     * list so the caller can push the same values through to the DMX controller.
     *
     * Takes a 7-bit MIDI-style value so surface fader events can be fed straight in; 8-bit
     * callers can still use the raw channel-level [put].
     */
    fun putProperty(
        fixture: Fixture,
        propertyName: String,
        midiValue7Bit: UByte,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val writes = PropertyChannelResolver.resolveFixtureProperty(fixture, propertyName, midiValue7Bit)
        for (w in writes) put(w.universe.universe, w.channel, w.value)
        return writes
    }

    /**
     * Clear the sticky values for every channel that backs the named property on [fixture].
     * Used on Flash release to restore whatever layer was underneath.
     */
    fun clearProperty(
        fixture: Fixture,
        propertyName: String,
    ): List<PropertyChannelResolver.ChannelWrite> {
        // Resolve with a dummy value so we get the channel list; value is not used.
        val channels = PropertyChannelResolver.resolveFixtureProperty(fixture, propertyName, 0u)
        for (c in channels) clear(c.universe.universe, c.channel)
        return channels
    }

    /**
     * Group-level property write: fan out to every fixture in the group's `fixtures` list
     * (including sub-group members via [FixtureGroup.allMembers]). Returns the flattened
     * list of all channel writes so the caller can push them through to controllers.
     */
    fun putGroupProperty(
        group: FixtureGroup<*>,
        propertyName: String,
        midiValue7Bit: UByte,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val all = mutableListOf<PropertyChannelResolver.ChannelWrite>()
        for (fixture in group.fixtures) {
            if (fixture is Fixture) {
                all += putProperty(fixture, propertyName, midiValue7Bit)
            }
        }
        return all
    }

    /** Group-level flash release: clears sticky values for every member fixture. */
    fun clearGroupProperty(
        group: FixtureGroup<*>,
        propertyName: String,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val all = mutableListOf<PropertyChannelResolver.ChannelWrite>()
        for (fixture in group.fixtures) {
            if (fixture is Fixture) {
                all += clearProperty(fixture, propertyName)
            }
        }
        return all
    }
}
