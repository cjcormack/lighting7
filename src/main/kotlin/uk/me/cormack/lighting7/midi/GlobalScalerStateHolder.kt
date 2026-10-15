package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.me.cormack.lighting7.models.GRAND_MASTER_FULL
import uk.me.cormack.lighting7.models.ProjectScalerStateSnapshot

/**
 * Per-project holder for the two global transmit-time scalers: Blackout, and the Grand Master's
 * level (0–100 %).
 *
 * Owns the [MutableStateFlow]s independently of the active [uk.me.cormack.lighting7.show.Show]
 * so operator intent survives project switches within a session. The show-scoped
 * [GlobalScalerState] reads through to this holder and registers as a transmit modifier on
 * the current show's controllers; when the show is re-created on project switch, a fresh
 * [GlobalScalerState] is constructed against the same holder and state is preserved.
 *
 * The grand master is one value. Its on/off ([grandMasterEnabled], the `GrandMasterToggle`
 * button) is that value read as `level > 0`: turning it off takes the level to 0, and turning it
 * on restores the last level above 0 this holder saw, or full.
 *
 * Cross-restart persistence is opt-in via [initial] (for seeding from a persisted row) and
 * [persist] (a write-through callback fired on every actual state change).
 * [uk.me.cormack.lighting7.state.State.scalerHolderFor] wires the callback to a
 * `project_scaler_states` DB row; tests construct holders without persistence.
 */
class GlobalScalerStateHolder(
    initial: ProjectScalerStateSnapshot = ProjectScalerStateSnapshot(),
    private val persist: (ProjectScalerStateSnapshot) -> Unit = {},
) {

    private val _blackoutEnabled = MutableStateFlow(initial.blackout)
    val blackoutEnabled: StateFlow<Boolean> = _blackoutEnabled.asStateFlow()

    private val _safeBlackout = MutableStateFlow(false)

    /**
     * *Make rig safe*'s blackout (venue-visit plan S6): the same effect on output as [blackoutEnabled],
     * but runtime only and never persisted, so a desk restarted the next day does not boot dark with
     * nothing on screen saying why. Independent of the MIDI `Blackout` toggle; *Undo* or *Release*
     * clears it (`state/RigSafetyService.kt`).
     */
    val safeBlackout: StateFlow<Boolean> = _safeBlackout.asStateFlow()

    /** Set [safeBlackout]. Returns true when it changed. */
    fun setSafeBlackout(enabled: Boolean): Boolean = synchronized(this) {
        if (_safeBlackout.value == enabled) return false
        _safeBlackout.value = enabled
        true
    }

    private val initialLevel = clampLevel(initial.grandMasterLevel) ?: GRAND_MASTER_FULL

    private val _grandMasterLevel = MutableStateFlow(initialLevel)

    /** The grand master, 0–100 %. */
    val grandMasterLevel: StateFlow<Double> = _grandMasterLevel.asStateFlow()

    private val _grandMasterEnabled = MutableStateFlow(initialLevel > 0.0)

    /** The grand master read as on/off: true while its level is above 0. */
    val grandMasterEnabled: StateFlow<Boolean> = _grandMasterEnabled.asStateFlow()

    /** What turning the grand master back on restores. */
    private var restoreLevel = if (initialLevel > 0.0) initialLevel else GRAND_MASTER_FULL

    /**
     * Set blackout state. Returns true when the state actually changed (so callers can skip
     * transmit requests on no-op writes).
     */
    fun setBlackout(enabled: Boolean): Boolean = synchronized(this) {
        if (_blackoutEnabled.value == enabled) return false
        _blackoutEnabled.value = enabled
        notifyPersist()
        true
    }

    /** Toggle blackout. Returns the new state. */
    fun toggleBlackout(): Boolean = synchronized(this) {
        val next = !_blackoutEnabled.value
        _blackoutEnabled.value = next
        notifyPersist()
        next
    }

    /**
     * Set the grand master's level, clamped into 0–100. A non-finite [level] is ignored. Returns
     * true when the level actually changed.
     */
    fun setGrandMasterLevel(level: Double): Boolean = synchronized(this) {
        val clamped = clampLevel(level) ?: return false
        if (_grandMasterLevel.value == clamped) return false
        _grandMasterLevel.value = clamped
        _grandMasterEnabled.value = clamped > 0.0
        if (clamped > 0.0) restoreLevel = clamped
        notifyPersist()
        true
    }

    /**
     * Turn the grand master off (level 0) or back on (the last level above 0). Returns true when
     * the level changed.
     */
    fun setGrandMaster(enabled: Boolean): Boolean = synchronized(this) {
        if (_grandMasterEnabled.value == enabled) return false
        setGrandMasterLevel(if (enabled) restoreLevel else 0.0)
    }

    /** Toggle the grand master between 0 and its last level. Returns the new on/off. */
    fun toggleGrandMaster(): Boolean = synchronized(this) {
        setGrandMaster(!_grandMasterEnabled.value)
        _grandMasterEnabled.value
    }

    private fun notifyPersist() {
        persist(ProjectScalerStateSnapshot(
            blackout = _blackoutEnabled.value,
            grandMasterLevel = _grandMasterLevel.value,
        ))
    }

    private companion object {
        fun clampLevel(level: Double): Double? =
            // `+ 0.0` folds -0.0 into 0.0, which a StateFlow (comparing boxed) would see as a change.
            if (level.isFinite()) level.coerceIn(0.0, GRAND_MASTER_FULL) + 0.0 else null
    }
}
