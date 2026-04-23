package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-project holder for the two global transmit-time scalers (Blackout, Grand Master).
 *
 * Owns the [MutableStateFlow]s independently of the active [uk.me.cormack.lighting7.show.Show]
 * so operator intent survives project switches within a session. The show-scoped
 * [GlobalScalerState] reads through to this holder and registers as a transmit modifier on
 * the current show's controllers; when the show is re-created on project switch, a fresh
 * [GlobalScalerState] is constructed against the same holder and state is preserved.
 *
 * State is in-memory only — not persisted across backend restarts.
 */
class GlobalScalerStateHolder {

    private val _blackoutEnabled = MutableStateFlow(false)
    val blackoutEnabled: StateFlow<Boolean> = _blackoutEnabled.asStateFlow()

    private val _grandMasterEnabled = MutableStateFlow(true)
    val grandMasterEnabled: StateFlow<Boolean> = _grandMasterEnabled.asStateFlow()

    /**
     * Set blackout state. Returns true when the state actually changed (so callers can skip
     * transmit requests on no-op writes).
     */
    fun setBlackout(enabled: Boolean): Boolean {
        if (_blackoutEnabled.value == enabled) return false
        _blackoutEnabled.value = enabled
        return true
    }

    /** Set Grand Master state. Returns true when the state actually changed. */
    fun setGrandMaster(enabled: Boolean): Boolean {
        if (_grandMasterEnabled.value == enabled) return false
        _grandMasterEnabled.value = enabled
        return true
    }

    /** Toggle blackout. Returns the new state. */
    fun toggleBlackout(): Boolean {
        val next = !_blackoutEnabled.value
        _blackoutEnabled.value = next
        return next
    }

    /** Toggle Grand Master. Returns the new state. */
    fun toggleGrandMaster(): Boolean {
        val next = !_grandMasterEnabled.value
        _grandMasterEnabled.value = next
        return next
    }
}
