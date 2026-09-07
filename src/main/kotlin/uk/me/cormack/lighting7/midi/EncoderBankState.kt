package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * What a device's strip encoders drive: a property, and for a colour property which of its
 * axes. `colourAxis` null is hue ([ColourAxis]); it is meaningless on a slider property and the
 * strip encoder derived from it drops its turn there ([PropertyChannelResolver.toPropertyValue]).
 *
 * `@Serializable` because `surfaceEncoderBank.state` carries it as it stands — a map of these,
 * one per device type, an absent device meaning [EncoderBankState.DEFAULT].
 */
@Serializable
data class EncoderBankSelection(
    val propertyName: String,
    val colourAxis: ColourAxis? = null,
)

/**
 * Which attribute a device's strip encoders drive, keyed by `deviceTypeKey` — the
 * attribute-select bank every console has. Shaped exactly like [ActiveBankState], with an
 * [EncoderBankSelection] where that has a bank id, and for the same reasons: the value read is
 * on the router's hot path and wants to be lock-free, while socket clients want a snapshot.
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
    /** Snapshot of `deviceTypeKey → selection`; absent means [DEFAULT]. */
    private val _selections = MutableStateFlow<Map<String, EncoderBankSelection>>(emptyMap())
    val selections: StateFlow<Map<String, EncoderBankSelection>> = _selections.asStateFlow()

    private val _changes = MutableSharedFlow<EncoderBankChange>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<EncoderBankChange> = _changes.asSharedFlow()

    /** Emitted on every encoder-bank transition — a change of axis on one property included. */
    data class EncoderBankChange(
        val deviceTypeKey: String,
        val previous: EncoderBankSelection,
        val new: EncoderBankSelection,
    )

    /** What [deviceTypeKey]'s strip encoders currently drive. Never null. */
    fun selectionFor(deviceTypeKey: String): EncoderBankSelection = _selections.value[deviceTypeKey] ?: DEFAULT

    /**
     * Point [deviceTypeKey]'s strip encoders at [selection]. Returns true if this was a change —
     * the publisher rebuilds its index and re-arms takeover on one, because every strip encoder's
     * meaning just moved under the operator's hand. Compared through the effective axis, so
     * `(rgbColour, null)` and `(rgbColour, HUE)` are one bank and not a change.
     *
     * The selection is **canonicalised before it is stored** — an explicit [ColourAxis.HUE] is
     * kept as null, the form every pre-axis row and every hue binding carries. Storing it raw
     * would compare through [effective] while storing the other spelling, so `(dimmer, HUE)`
     * would be held where [DEFAULT] belongs and a later `set` naming the same property with no
     * axis would be a no-op that could not clear it. A stored axis on a *slider* property is not
     * cosmetic: [PropertyChannelResolver.toPropertyValue] answers null for any non-null axis on a
     * slider, so every strip encoder on the device would drop its turn and its ring go dark.
     */
    fun set(deviceTypeKey: String, selection: EncoderBankSelection): Boolean {
        val canonical =
            if (selection.colourAxis == ColourAxis.HUE) selection.copy(colourAxis = null) else selection
        val previous = selectionFor(deviceTypeKey)
        if (previous == canonical) return false
        _selections.update { current ->
            if (canonical == DEFAULT) current - deviceTypeKey
            else current + (deviceTypeKey to canonical)
        }
        _changes.tryEmit(EncoderBankChange(deviceTypeKey, previous, canonical))
        return true
    }

    /** [set], for the callers that name a property (and optionally its axis) rather than a selection. */
    fun setProperty(deviceTypeKey: String, propertyName: String, colourAxis: ColourAxis? = null): Boolean =
        set(deviceTypeKey, EncoderBankSelection(propertyName, colourAxis))

    /** Wipe all state, so an encoder bank never leaks across a project switch. */
    fun clearAll() {
        _selections.value = emptyMap()
    }

    companion object {
        /** What a device's encoders drive until something says otherwise. */
        const val DEFAULT_PROPERTY: String = "dimmer"

        /** [DEFAULT_PROPERTY] on its own: the selection an absent device reads as. */
        val DEFAULT: EncoderBankSelection = EncoderBankSelection(DEFAULT_PROPERTY)
    }
}
