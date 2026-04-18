package uk.me.cormack.lighting7.fx

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

    private fun packKey(universe: Int, channel: Int): Long =
        (universe.toLong() shl 20) or (channel.toLong() and 0xFFFFFL)

    /** Record a direct write at (universe, channel). Overwrites any previous value. */
    fun put(universe: Int, channel: Int, value: UByte) {
        values[packKey(universe, channel)] = value
    }

    /** Read the sticky value at (universe, channel), or null if none. */
    fun get(universe: Int, channel: Int): UByte? = values[packKey(universe, channel)]

    /** Remove the sticky value at (universe, channel). No-op if absent. */
    fun clear(universe: Int, channel: Int) {
        values.remove(packKey(universe, channel))
    }

    /** Remove all sticky values. Primarily for tests and shutdown. */
    fun clearAll() {
        values.clear()
    }

    /** Size, exposed for tests / diagnostics. */
    val size: Int get() = values.size
}
