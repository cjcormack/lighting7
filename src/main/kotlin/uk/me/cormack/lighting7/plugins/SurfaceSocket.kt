package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.ControlSurfaceBindingService
import uk.me.cormack.lighting7.midi.DeviceMatcher
import uk.me.cormack.lighting7.midi.MidiDeviceHandle
import uk.me.cormack.lighting7.midi.MidiLearnSessionManager
import uk.me.cormack.lighting7.midi.SoftTakeoverStateMachine
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.state.State

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class SurfaceInMessage : InMessage()

@Serializable
@SerialName("surfaceLearn.begin")
data class SurfaceLearnBeginInMessage(
    val projectId: Int,
    val deviceTypeKey: String? = null,
) : SurfaceInMessage()

@Serializable
@SerialName("surfaceLearn.cancel")
data class SurfaceLearnCancelInMessage(val sessionId: String) : SurfaceInMessage()

@Serializable
@SerialName("surfaceLearn.commit")
data class SurfaceLearnCommitInMessage(
    val sessionId: String,
    val bank: String? = null,
    val target: BindingTarget,
    val takeoverPolicy: String? = null,
) : SurfaceInMessage()

@Serializable
@SerialName("surfaceBank.set")
data class SurfaceBankSetInMessage(
    val deviceTypeKey: String,
    val bank: String?,
) : SurfaceInMessage()

@Serializable
@SerialName("surfaceBank.state")
data object SurfaceBankStateInMessage : SurfaceInMessage()

@Serializable
@SerialName("surfaceScaler.state")
data object SurfaceScalerStateInMessage : SurfaceInMessage()

@Serializable
@SerialName("surfaceScaler.setBlackout")
data class SurfaceScalerSetBlackoutInMessage(val enabled: Boolean) : SurfaceInMessage()

@Serializable
@SerialName("surfaceScaler.setGrandMaster")
data class SurfaceScalerSetGrandMasterInMessage(val enabled: Boolean) : SurfaceInMessage()

@Serializable
@SerialName("surfaceDevices.state")
data object SurfaceDevicesStateInMessage : SurfaceInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class SurfaceOutMessage : OutMessage()

@Serializable
@SerialName("surfaceLearn.started")
data class SurfaceLearnStartedOutMessage(
    val sessionId: String,
    val projectId: Int,
    val deviceTypeKey: String?,
    val deadlineMs: Long,
) : SurfaceOutMessage()

@Serializable
@SerialName("surfaceLearn.captured")
data class SurfaceLearnCapturedOutMessage(
    val sessionId: String,
    val projectId: Int,
    val deviceTypeKey: String,
    val controlId: String,
) : SurfaceOutMessage()

@Serializable
@SerialName("surfaceLearn.committed")
data class SurfaceLearnCommittedOutMessage(
    val sessionId: String,
    val bindingId: Int,
    val projectId: Int,
) : SurfaceOutMessage()

@Serializable
@SerialName("surfaceLearn.cancelled")
data class SurfaceLearnCancelledOutMessage(
    val sessionId: String,
    val reason: String,
) : SurfaceOutMessage()

@Serializable
@SerialName("surfaceLearn.error")
data class SurfaceLearnErrorOutMessage(
    val sessionId: String?,
    val message: String,
) : SurfaceOutMessage()

@Serializable
enum class BindingChangeType {
    @SerialName("added") ADDED,
    @SerialName("updated") UPDATED,
    @SerialName("removed") REMOVED,
    @SerialName("reloaded") RELOADED,
}

@Serializable
@SerialName("surfaceBindingsChanged")
data class SurfaceBindingsChangedOutMessage(
    val projectId: Int,
    val changeType: BindingChangeType,
    val bindingId: Int? = null,
) : SurfaceOutMessage()

@Serializable
@SerialName("surfaceBank.state")
data class SurfaceBankStateOutMessage(
    /** `deviceTypeKey` → active bank id (null values elided). */
    val activeBanks: Map<String, String>,
) : SurfaceOutMessage()

@Serializable
@SerialName("surfaceBank.changed")
data class SurfaceBankChangedOutMessage(
    val deviceTypeKey: String,
    val previousBank: String?,
    val newBank: String?,
) : SurfaceOutMessage()

@Serializable
@SerialName("surfaceScaler.state")
data class SurfaceScalerStateOutMessage(
    val blackoutEnabled: Boolean,
    val grandMasterEnabled: Boolean,
) : SurfaceOutMessage()

/**
 * Emitted when a non-motor fader transitions between engaged and pickup-awaiting state.
 * Frontend renders a pickup indicator near the relevant control.
 */
@Serializable
@SerialName("surfacePickup.changed")
data class SurfacePickupChangedOutMessage(
    val displayKey: String,
    val controlId: String,
    val state: SoftTakeoverStateMachine.State,
    /** Target 7-bit value that the fader must cross to re-engage. Null when engaged. */
    val target: Int?,
) : SurfaceOutMessage()

@Serializable
data class SurfaceDeviceInfo(
    val displayKey: String,
    val displayName: String,
    /** Null when the device did not match any `@ControlSurfaceType`. */
    val typeKey: String?,
    val isMatched: Boolean,
    val hasInputPort: Boolean,
    val hasOutputPort: Boolean,
    /** Convenience mirror of `activeBankState` for the device's `typeKey`, or null. */
    val activeBank: String?,
)

@Serializable
@SerialName("surfaceDevices.state")
data class SurfaceDevicesStateOutMessage(
    val devices: List<SurfaceDeviceInfo>,
) : SurfaceOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleSurface(scope: SocketScope, message: SurfaceInMessage) {
    val state = scope.state
    when (message) {
        is SurfaceLearnBeginInMessage -> {
            val session = state.midiLearnSessionManager.begin(
                projectId = message.projectId,
                filter = MidiLearnSessionManager.LearnFilter(deviceTypeKey = message.deviceTypeKey),
            )
            scope.ownedLearnSessions += session.sessionId
            scope.send(SurfaceLearnStartedOutMessage(
                sessionId = session.sessionId,
                projectId = session.projectId,
                deviceTypeKey = session.filter.deviceTypeKey,
                deadlineMs = session.deadlineMs,
            ))
        }
        is SurfaceLearnCancelInMessage -> {
            val cancelled = state.midiLearnSessionManager.cancel(message.sessionId)
            scope.ownedLearnSessions.remove(message.sessionId)
            scope.send(if (cancelled != null) {
                SurfaceLearnCancelledOutMessage(message.sessionId, reason = "cancelled")
            } else {
                SurfaceLearnErrorOutMessage(message.sessionId, "Session not found or not cancellable")
            })
        }
        is SurfaceLearnCommitInMessage -> {
            val reply = handleSurfaceLearnCommit(state, message)
            if (reply is SurfaceLearnCommittedOutMessage) {
                scope.ownedLearnSessions.remove(message.sessionId)
            }
            scope.send(reply)
        }
        is SurfaceBankSetInMessage -> state.activeBankState.setBank(message.deviceTypeKey, message.bank)
        is SurfaceBankStateInMessage ->
            scope.send(SurfaceBankStateOutMessage(state.activeBankState.active.value))
        is SurfaceDevicesStateInMessage ->
            scope.send(buildSurfaceDevicesStateMessage(
                state.midiRegistry.devices.value,
                state.deviceMatcher.attached.value,
                state.activeBankState.active.value,
            ))
        is SurfaceScalerStateInMessage ->
            scope.send(SurfaceScalerStateOutMessage(
                blackoutEnabled = state.show.globalScalerState.blackoutEnabled.value,
                grandMasterEnabled = state.show.globalScalerState.grandMasterEnabled.value,
            ))
        is SurfaceScalerSetBlackoutInMessage -> state.show.globalScalerState.setBlackout(message.enabled)
        is SurfaceScalerSetGrandMasterInMessage -> state.show.globalScalerState.setGrandMaster(message.enabled)
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
fun setupSurfaceSubscriptions(scope: SocketScope) {
    val state = scope.state

    // Learn-event broadcasts are filtered to sessions this connection started, so two
    // `/surfaces` tabs don't see phantom captures from each other's sessions.
    scope.subscribe(state.midiLearnSessionManager.events.filter { it.sessionId in scope.ownedLearnSessions }) { event ->
        when (event) {
            is MidiLearnSessionManager.SessionEvent.Captured -> {
                val captured = event.session.captured ?: return@subscribe
                scope.send(SurfaceLearnCapturedOutMessage(
                    sessionId = event.sessionId,
                    projectId = event.session.projectId,
                    deviceTypeKey = captured.deviceTypeKey,
                    controlId = captured.controlId,
                ))
            }
            is MidiLearnSessionManager.SessionEvent.TimedOut -> {
                scope.ownedLearnSessions.remove(event.sessionId)
                scope.send(SurfaceLearnCancelledOutMessage(
                    sessionId = event.sessionId,
                    reason = "timeout",
                ))
            }
            is MidiLearnSessionManager.SessionEvent.Cancelled ->
                scope.ownedLearnSessions.remove(event.sessionId)
            is MidiLearnSessionManager.SessionEvent.Committed ->
                scope.ownedLearnSessions.remove(event.sessionId)
            is MidiLearnSessionManager.SessionEvent.Started -> Unit
        }
    }

    scope.subscribe(state.controlSurfaceBindingService.changes) { change ->
        scope.send(when (change) {
            is ControlSurfaceBindingService.BindingChange.Added ->
                SurfaceBindingsChangedOutMessage(change.projectId, BindingChangeType.ADDED, change.binding.id)
            is ControlSurfaceBindingService.BindingChange.Updated ->
                SurfaceBindingsChangedOutMessage(change.projectId, BindingChangeType.UPDATED, change.binding.id)
            is ControlSurfaceBindingService.BindingChange.Removed ->
                SurfaceBindingsChangedOutMessage(change.projectId, BindingChangeType.REMOVED, change.bindingId)
            is ControlSurfaceBindingService.BindingChange.Reloaded ->
                SurfaceBindingsChangedOutMessage(change.projectId, BindingChangeType.RELOADED, null)
        })
    }

    scope.subscribe(state.activeBankState.changes) { change ->
        scope.send(SurfaceBankChangedOutMessage(
            deviceTypeKey = change.deviceTypeKey,
            previousBank = change.previousBank,
            newBank = change.newBank,
        ))
    }

    // `state.show.globalScalerState` is re-created on project switch, so a plain
    // `combine(...)` at connect time would observe the previous project's facade forever.
    // Re-subscribing via `flatMapLatest` off `projectChangedFlow` (plus an initial Unit to
    // bootstrap the first subscription) makes the outbound flow follow the active show. The
    // `drop(1)` suppresses the combine's initial emit so connect doesn't push state the
    // client hasn't asked for — clients fetch initial state via the `surfaceScaler.state`
    // request message.
    scope.subscribe(
        state.projectManager.projectChangedFlow
            .map { Unit }
            .onStart { emit(Unit) }
            .flatMapLatest {
                combine(
                    state.show.globalScalerState.blackoutEnabled,
                    state.show.globalScalerState.grandMasterEnabled,
                ) { blackout, grandMaster -> SurfaceScalerStateOutMessage(blackout, grandMaster) }
            }
            .drop(1),
    ) { scope.send(it) }

    scope.subscribe(state.surfaceFeedbackPublisher.takeover.changes) { change ->
        scope.send(SurfacePickupChangedOutMessage(
            displayKey = change.displayKey,
            controlId = change.controlId,
            state = change.state,
            target = change.target?.toInt(),
        ))
    }

    // Push the full device list whenever the set of connected ports or matched profiles
    // changes, or when an active bank flips. `distinctUntilChanged` drops no-op emits.
    scope.subscribe(
        combine(
            state.midiRegistry.devices,
            state.deviceMatcher.attached,
            state.activeBankState.active,
        ) { devices, attached, banks ->
            buildSurfaceDevicesStateMessage(devices, attached, banks)
        }
            .distinctUntilChanged()
            .drop(1),
    ) { scope.send(it) }
}

// ─── Helpers ────────────────────────────────────────────────────────────

private fun buildSurfaceDevicesStateMessage(
    devices: List<MidiDeviceHandle>,
    attached: Map<String, DeviceMatcher.Attached>,
    banks: Map<String, String>,
): SurfaceDevicesStateOutMessage {
    val infos = devices.map { handle ->
        val match = attached[handle.displayKey]
        SurfaceDeviceInfo(
            displayKey = handle.displayKey,
            displayName = handle.displayName,
            typeKey = match?.typeKey,
            isMatched = match != null,
            hasInputPort = handle.inputPort != null,
            hasOutputPort = handle.outputPort != null,
            activeBank = match?.typeKey?.let(banks::get),
        )
    }
    return SurfaceDevicesStateOutMessage(devices = infos)
}

private fun handleSurfaceLearnCommit(state: State, message: SurfaceLearnCommitInMessage): SurfaceOutMessage {
    val session = state.midiLearnSessionManager.get(message.sessionId)
        ?: return SurfaceLearnErrorOutMessage(message.sessionId, "Session not found")
    val captured = session.captured
        ?: return SurfaceLearnErrorOutMessage(message.sessionId, "Session not yet captured")

    val takeover = message.takeoverPolicy?.let(BindingTakeoverPolicy::parseOrNull)
    if (message.takeoverPolicy != null && takeover == null) {
        return SurfaceLearnErrorOutMessage(
            message.sessionId,
            "Unknown takeoverPolicy: ${message.takeoverPolicy}",
        )
    }

    return try {
        val binding = state.controlSurfaceBindingService.create(
            projectId = session.projectId,
            deviceTypeKey = captured.deviceTypeKey,
            controlId = captured.controlId,
            bank = message.bank,
            target = message.target,
            takeoverPolicy = takeover,
        )
        state.midiLearnSessionManager.commit(message.sessionId)
        SurfaceLearnCommittedOutMessage(
            sessionId = message.sessionId,
            bindingId = binding.id,
            projectId = session.projectId,
        )
    } catch (e: IllegalStateException) {
        SurfaceLearnErrorOutMessage(message.sessionId, e.message ?: "Binding slot already taken")
    } catch (e: IllegalArgumentException) {
        SurfaceLearnErrorOutMessage(message.sessionId, e.message ?: "Invalid binding target")
    }
}
