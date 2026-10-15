package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `grandMaster.*` family (venue-visit plan S5, D5): the live project's grand master, a level
 * 0–100 % that scales the rig's output at transmit (`midi/GlobalScalerState.kt`). One inbound
 * frame, for the Grand Master panel's fader, which moves at frame rate; one outbound, the level,
 * streamed. REST (`routes/projectGrandMaster.kt`) reads and sets the same value, and a MIDI `grandMasterFader`
 * binding sets it too. The older `surfaceScaler.*` family still carries the on/off a
 * `grandMasterToggle` button shows.
 */

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class GrandMasterInMessage : InMessage()

/** Set the live project's grand master, clamped into 0–100. */
@Serializable
@SerialName("grandMaster.set")
data class GrandMasterSetInMessage(val level: Double) : GrandMasterInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class GrandMasterOutMessage : OutMessage()

/** The live project's grand master, 0–100 %. The subscription is the connect snapshot. */
@Serializable
@SerialName("grandMaster.state")
data class GrandMasterStateOutMessage(val projectId: Int, val level: Double) : GrandMasterOutMessage()

// ─── Handlers ───────────────────────────────────────────────────────────

internal fun handleGrandMaster(scope: SocketScope, message: GrandMasterInMessage) {
    when (message) {
        is GrandMasterSetInMessage -> scope.state.show.globalScalerState.setGrandMasterLevel(message.level)
    }
}

/**
 * Follows the live show, as `surfaceScaler.state` does: its scaler facade is re-created on a project
 * switch, so the level is re-subscribed off `projectChangedFlow`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun setupGrandMasterSubscriptions(scope: SocketScope) {
    val state = scope.state
    scope.subscribe(
        state.projectManager.projectChangedFlow
            .map { Unit }
            .onStart { emit(Unit) }
            .flatMapLatest {
                val show = state.show
                val projectId = show.project.id.value
                show.globalScalerState.grandMasterLevel.map { GrandMasterStateOutMessage(projectId, it) }
            }
            .distinctUntilChanged(),
    ) { scope.send(it) }
}
