package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory active-bank tracker, keyed by the attached device's `displayKey` (surface-setup plan
 * D16): two surfaces of one type share their bindings but each is on its own layer. Banks are
 * ephemeral session state, never persisted: a desk restart starts every device at the default (null,
 * meaning "resolve to bank-agnostic bindings only"), and a device unplugged and plugged back under the
 * same `displayKey` comes back on the layer it left, as the encoder page does.
 *
 * Only the desk flips a bank: a device-side bank button, or a button bound to [Does.Layer], and
 * [SurfaceInputRouter] calls [setBank] here for the device it is on. Nothing in the app does.
 *
 * Changes go out on [changes] so socket clients can mirror the active bank in their UI,
 * and on [active] for passive observers that want a full snapshot.
 */
class ActiveBankState {
    /**
     * Snapshot of `displayKey → activeBank`. Readers on the router hot path call
     * [bankFor] which reads `_active.value[key]` — StateFlow's value read is lock-free
     * and returns the immutable map snapshot directly.
     */
    private val _active = MutableStateFlow<Map<String, String>>(emptyMap())
    val active: StateFlow<Map<String, String>> = _active.asStateFlow()

    private val _changes = MutableSharedFlow<BankChange>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<BankChange> = _changes.asSharedFlow()

    /** Emitted on every bank transition (including the initial set from null → X). */
    data class BankChange(
        val displayKey: String,
        val previousBank: String?,
        val newBank: String?,
    )

    /** Return the active bank for the device [displayKey], or null if none set. */
    fun bankFor(displayKey: String): String? = _active.value[displayKey]

    /**
     * Set the active bank. Pass `null` to clear. Returns true if this was a change.
     * Emits a [BankChange] on [changes] and updates [active].
     */
    fun setBank(displayKey: String, bank: String?): Boolean {
        val previous = _active.value[displayKey]
        if (previous == bank) return false
        _active.update { current ->
            if (bank == null) current - displayKey else current + (displayKey to bank)
        }
        _changes.tryEmit(BankChange(displayKey, previous, bank))
        return true
    }

    /** Clear the active bank for the device [displayKey] (device detach, project switch, etc.). */
    fun clearBank(displayKey: String): Boolean = setBank(displayKey, null)

    /** Wipe all state. Used on project change so banks don't leak across projects. */
    fun clearAll() {
        _active.value = emptyMap()
    }
}
