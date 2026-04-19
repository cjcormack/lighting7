package uk.me.cormack.lighting7.midi

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which touch-sensitive fader controls are currently held by the operator, per physical
 * device. While a fader is touched, the [SurfaceFeedbackPublisher] suppresses outbound motor
 * writes to that control so the motor doesn't fight the finger.
 *
 * Keyed by `(displayKey, controlId)` — each physical X-Touch has its own fader touch state
 * even if several share the same `deviceTypeKey`.
 *
 * Thread-safety: backed by a [ConcurrentHashMap]-derived set for lock-free access.
 */
class TouchStateTracker {
    private val touched = ConcurrentHashMap.newKeySet<String>()

    private fun key(displayKey: String, controlId: String): String = "$displayKey|$controlId"

    /**
     * Mark or clear touched state. Returns true if this was a transition (add when not present
     * or remove when present) — lets callers fire edge-triggered side effects without racing.
     */
    fun setTouched(displayKey: String, controlId: String, down: Boolean): Boolean {
        val k = key(displayKey, controlId)
        return if (down) touched.add(k) else touched.remove(k)
    }

    fun isTouched(displayKey: String, controlId: String): Boolean =
        touched.contains(key(displayKey, controlId))

    /** Drop all state for a specific device (detach, project switch). */
    fun clearDevice(displayKey: String) {
        val prefix = "$displayKey|"
        touched.removeIf { it.startsWith(prefix) }
    }

    fun clearAll() {
        touched.clear()
    }
}
