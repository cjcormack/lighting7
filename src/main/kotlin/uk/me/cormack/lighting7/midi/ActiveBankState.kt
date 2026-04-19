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
 * In-memory active-bank tracker, keyed by `deviceTypeKey`. Banks are ephemeral session
 * state — not persisted to the DB — so unplug / re-plug of a device re-starts at the
 * default (null, meaning "resolve to bank-agnostic bindings only").
 *
 * Two ways to flip a bank:
 *   1. Device-side bank button → [SurfaceInputRouter] synthesises a [BindingTarget.SetBank]
 *      and calls [setBank] here.
 *   2. Frontend `surfaceBank.set` WebSocket message → handler calls [setBank] directly.
 *
 * Changes go out on [changes] so socket clients can mirror the active bank in their UI,
 * and on [active] for passive observers that want a full snapshot.
 */
class ActiveBankState {
    /**
     * Snapshot of `deviceTypeKey → activeBank`. Readers on the router hot path call
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
        val deviceTypeKey: String,
        val previousBank: String?,
        val newBank: String?,
    )

    /** Return the active bank for [deviceTypeKey], or null if none set. */
    fun bankFor(deviceTypeKey: String): String? = _active.value[deviceTypeKey]

    /**
     * Set the active bank. Pass `null` to clear. Returns true if this was a change.
     * Emits a [BankChange] on [changes] and updates [active].
     */
    fun setBank(deviceTypeKey: String, bank: String?): Boolean {
        val previous = _active.value[deviceTypeKey]
        if (previous == bank) return false
        _active.update { current ->
            if (bank == null) current - deviceTypeKey else current + (deviceTypeKey to bank)
        }
        _changes.tryEmit(BankChange(deviceTypeKey, previous, bank))
        return true
    }

    /** Clear the active bank for [deviceTypeKey] (device detach, project switch, etc.). */
    fun clearBank(deviceTypeKey: String): Boolean = setBank(deviceTypeKey, null)

    /** Wipe all state. Used on project change so banks don't leak across projects. */
    fun clearAll() {
        _active.value = emptyMap()
    }
}
