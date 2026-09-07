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
 * Which attribute a device's strip encoders drive, keyed by `deviceTypeKey` — the
 * attribute-select bank every console has. Shaped exactly like [ActiveBankState], with a
 * property name where that has a bank id, and for the same reasons: the value read is on the
 * router's hot path and wants to be lock-free, while socket clients want a snapshot.
 *
 * Two ways to move it:
 *   1. A button bound to [BindingTarget.EncoderBankSet] → [SurfaceInputRouter] calls
 *      [setProperty] for the device the button is on.
 *   2. The frontend's `surfaceEncoderBank.set` message → the handler calls [setProperty].
 *
 * Session state, like the active bank: it survives a device unplug, and unlike the active bank
 * it is **reset on project switch**, because the vocabulary it draws from is the patch's
 * (`docs/plans/completed/midi-surface-plan.md` D5).
 */
class EncoderBankState {
    /** Snapshot of `deviceTypeKey → propertyName`; absent means [DEFAULT_PROPERTY]. */
    private val _properties = MutableStateFlow<Map<String, String>>(emptyMap())
    val properties: StateFlow<Map<String, String>> = _properties.asStateFlow()

    private val _changes = MutableSharedFlow<EncoderBankChange>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<EncoderBankChange> = _changes.asSharedFlow()

    /** Emitted on every encoder-bank transition. */
    data class EncoderBankChange(
        val deviceTypeKey: String,
        val previousProperty: String,
        val newProperty: String,
    )

    /** The property [deviceTypeKey]'s strip encoders currently drive. Never null. */
    fun propertyFor(deviceTypeKey: String): String = _properties.value[deviceTypeKey] ?: DEFAULT_PROPERTY

    /**
     * Point [deviceTypeKey]'s strip encoders at [propertyName]. Returns true if this was a
     * change — the publisher rebuilds its index and re-arms takeover on one, because every strip
     * encoder's meaning just moved under the operator's hand.
     */
    fun setProperty(deviceTypeKey: String, propertyName: String): Boolean {
        val previous = propertyFor(deviceTypeKey)
        if (previous == propertyName) return false
        _properties.update { current ->
            if (propertyName == DEFAULT_PROPERTY) current - deviceTypeKey
            else current + (deviceTypeKey to propertyName)
        }
        _changes.tryEmit(EncoderBankChange(deviceTypeKey, previous, propertyName))
        return true
    }

    /** Wipe all state, so an encoder bank never leaks across a project switch. */
    fun clearAll() {
        _properties.value = emptyMap()
    }

    companion object {
        /** What a device's encoders drive until something says otherwise. */
        const val DEFAULT_PROPERTY: String = "dimmer"
    }
}
