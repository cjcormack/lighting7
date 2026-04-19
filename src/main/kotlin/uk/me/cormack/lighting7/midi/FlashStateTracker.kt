package uk.me.cormack.lighting7.midi

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-binding registry of active flash presses. A Flash binding writes the target property
 * to its `max` value on press and clears that direct-write on release — the tracker tracks
 * which bindings are currently held so overlapping flashes on overlapping properties don't
 * clobber each other's release state.
 *
 * v1 scope: we only need to know "is this binding currently pressed?". On release, the
 * [SurfaceInputRouter] calls [clearPress] and clears the Layer 4 entries the press installed.
 * That restores whatever layer was underneath (Layer 3 from an active cue, or the neutral
 * base value). The tracker itself doesn't need to store the pre-press channel values — the
 * composition model resolves them on next frame via [uk.me.cormack.lighting7.fx.LayerResolver].
 *
 * Thread-safety: backed by a [ConcurrentHashMap]-derived set for lock-free access.
 */
class FlashStateTracker {
    private val activePresses = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Mark a binding as pressed. Returns true if the press was newly registered (false if
     * already held — MIDI retrigger from repeated NoteOn while key still held).
     */
    fun pressed(bindingId: Int): Boolean = activePresses.add(bindingId)

    /**
     * Clear a binding's press state. Returns true if the binding was actually active.
     */
    fun clearPress(bindingId: Int): Boolean = activePresses.remove(bindingId)

    /** True if the given binding is currently held. */
    fun isActive(bindingId: Int): Boolean = activePresses.contains(bindingId)

    /** Number of currently-held flash bindings. */
    val activeCount: Int get() = activePresses.size

    /** Drop all press state. Used on project change / device detach. */
    fun clearAll() {
        activePresses.clear()
    }
}
