package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.midi.ControlPart
import uk.me.cormack.lighting7.midi.ColourAxis
import uk.me.cormack.lighting7.midi.ControlState
import uk.me.cormack.lighting7.midi.EncoderBankSelection
import uk.me.cormack.lighting7.midi.ControlSurfaceBindingService
import uk.me.cormack.lighting7.midi.DeviceMatcher
import uk.me.cormack.lighting7.midi.MidiDeviceHandle
import uk.me.cormack.lighting7.midi.ResolvedPart
import uk.me.cormack.lighting7.midi.SoftTakeoverStateMachine
import uk.me.cormack.lighting7.midi.SurfaceIdentify
import uk.me.cormack.lighting7.midi.SurfaceMapFrame
import uk.me.cormack.lighting7.midi.SurfaceJobView

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class SurfaceInMessage : InMessage()

/** Wait for a touch on the desk (D14): the next press or move on [deviceTypeKey]'s devices, or any, is captured and not dispatched. */
@Serializable
@SerialName("surfaceIdentify.begin")
data class SurfaceIdentifyBeginInMessage(val deviceTypeKey: String? = null) : SurfaceInMessage()

@Serializable
@SerialName("surfaceIdentify.cancel")
data class SurfaceIdentifyCancelInMessage(val sessionId: String) : SurfaceInMessage()

@Serializable
@SerialName("surfaceBank.state")
data object SurfaceBankStateInMessage : SurfaceInMessage()

@Serializable
@SerialName("surfaceEncoderBank.set")
data class SurfaceEncoderBankSetInMessage(
    /** The attached device whose encoder page moves (D16). */
    val displayKey: String,
    val attribute: String,
    /** Which axis of a colour property; null (and absent) is hue. See [ColourAxis]. */
    val colourAxis: ColourAxis? = null,
) : SurfaceInMessage()

@Serializable
@SerialName("surfaceEncoderBank.state")
data object SurfaceEncoderBankStateInMessage : SurfaceInMessage()

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

/**
 * This window's identify wait, the banner it draws: [waiting] with its deadline while it waits, and
 * once more when it ends without a capture ([reason] `cancelled` or `timeout`). Unicast.
 */
@Serializable
@SerialName("surfaceIdentify.state")
data class SurfaceIdentifyStateOutMessage(
    val sessionId: String,
    val waiting: Boolean,
    val deviceTypeKey: String? = null,
    val deadlineMs: Long? = null,
    /** How long the wait has left at send, which a client counts down on its own clock. */
    val remainingMs: Long? = null,
    val reason: String? = null,
) : SurfaceOutMessage()

/** The control part the touch named, to the window that waited for it only. */
@Serializable
@SerialName("surfaceIdentify.captured")
data class SurfaceIdentifyCapturedOutMessage(
    val sessionId: String,
    val deviceTypeKey: String,
    val displayKey: String,
    val controlId: String,
    val part: ControlPart,
) : SurfaceOutMessage()

/**
 * One device type's served picture (D15): every control part on every layer, resolved, labelled and
 * judged by the desk. `StateFlow`-backed, so the subscription is the snapshot; a frame is sent again
 * only when it changed.
 */
@Serializable
@SerialName("surfaceMap.state")
data class SurfaceMapStateOutMessage(
    val projectId: Int,
    val deviceTypeKey: String,
    val layers: Map<String, Map<String, ResolvedPart>>,
    val jobs: List<SurfaceJobView> = emptyList(),
) : SurfaceOutMessage() {
    constructor(frame: SurfaceMapFrame) : this(frame.projectId, frame.deviceTypeKey, frame.layers, frame.jobs)
}

@Serializable
enum class BindingChangeType {
    @SerialName("added") ADDED,
    @SerialName("updated") UPDATED,
    @SerialName("removed") REMOVED,
    @SerialName("reloaded") RELOADED,
}

@Serializable
@SerialName("surfaceBank.bindingsChanged")
data class SurfaceBindingsChangedOutMessage(
    val projectId: Int,
    val changeType: BindingChangeType,
    val bindingId: Int? = null,
) : SurfaceOutMessage()

@Serializable
@SerialName("surfaceBank.state")
data class SurfaceBankStateOutMessage(
    /** `displayKey` → that device's active bank id (null values elided). */
    val activeBanks: Map<String, String>,
) : SurfaceOutMessage()

/**
 * Which attribute each device's strip encoders drive. One frame type: it is a StateFlow, so the
 * subscription is both the connect snapshot and the broadcast, and a delta frame would carry
 * nothing the whole map doesn't.
 */
@Serializable
@SerialName("surfaceEncoderBank.state")
data class SurfaceEncoderBankStateOutMessage(
    /**
     * `displayKey` → what that device's strip encoders drive: a property name and, for a colour, which
     * axis (absent is hue). A device absent from the map is on the default, `dimmer`.
     */
    val attributes: Map<String, EncoderBankSelection>,
) : SurfaceOutMessage()

@Serializable
@SerialName("surfaceBank.changed")
data class SurfaceBankChangedOutMessage(
    val displayKey: String,
    /** The device's profile, null when it detached before the frame went out. */
    val deviceTypeKey: String?,
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
    /** This device's own layer from `activeBankState` (keyed by `displayKey`), or null. */
    val activeBank: String?,
)

@Serializable
@SerialName("surfaceDevices.state")
data class SurfaceDevicesStateOutMessage(
    val devices: List<SurfaceDeviceInfo>,
) : SurfaceOutMessage()

/**
 * One attached device's whole control state: the connect snapshot, and the frame after every
 * full resync (attach, bank change, project change, a binding / fixture / selection change).
 * `controls` is keyed by `controlId`; an empty map means the device detached. The view draws
 * exactly this — never a recomputation from DMX (`docs/plans/completed/midi-surface-plan.md` D7).
 */
@Serializable
@SerialName("surfaceControls.state")
data class SurfaceControlsStateOutMessage(
    val displayKey: String,
    val controls: Map<String, ControlState>,
) : SurfaceOutMessage()

/**
 * The controls of one device that changed since the last flush, each at its current state —
 * conflated to at most ~20 Hz per device by [uk.me.cormack.lighting7.midi.ControlStateTracker].
 */
@Serializable
@SerialName("surfaceControls.changed")
data class SurfaceControlsChangedOutMessage(
    val displayKey: String,
    val controls: Map<String, ControlState>,
) : SurfaceOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleSurface(scope: SocketScope, message: SurfaceInMessage) {
    val state = scope.state
    when (message) {
        is SurfaceIdentifyBeginInMessage -> {
            // One wait per window: a second begin replaces the first.
            scope.ownedIdentifySessions.toList().forEach { state.surfaceIdentify.cancel(it) }
            scope.ownedIdentifySessions.clear()
            val session = state.surfaceIdentify.begin(message.deviceTypeKey)
            scope.ownedIdentifySessions += session.sessionId
            scope.send(SurfaceIdentifyStateOutMessage(
                session.sessionId, waiting = true, session.deviceTypeKey, session.deadlineMs,
                remainingMs = state.surfaceIdentify.remainingMs(session),
            ))
        }
        is SurfaceIdentifyCancelInMessage -> {
            scope.ownedIdentifySessions.remove(message.sessionId)
            if (state.surfaceIdentify.cancel(message.sessionId)) {
                scope.send(SurfaceIdentifyStateOutMessage(message.sessionId, waiting = false, reason = "cancelled"))
            }
        }
        is SurfaceBankStateInMessage ->
            scope.send(SurfaceBankStateOutMessage(state.activeBankState.active.value))
        is SurfaceEncoderBankSetInMessage ->
            state.encoderBankState.set(
                message.displayKey, EncoderBankSelection(message.attribute, message.colourAxis),
            )
        is SurfaceEncoderBankStateInMessage ->
            scope.send(SurfaceEncoderBankStateOutMessage(state.encoderBankState.selections.value))
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

    // `surfaceBank.changed` carries previous→new, so a client that never saw a switch has
    // nothing to render from — the bank family needs a state frame of its own. Taken as a
    // subscription to the `active` StateFlow rather than a one-shot read of `.value`: a switch
    // landing between a one-shot snapshot and the delta subscription below starting to collect
    // would be lost in both, and nothing pushes full bank state again to correct it.
    // `surfaceScaler.state` and `surfaceDevices.state` get theirs the same way, below.
    scope.subscribe(state.activeBankState.active) { scope.send(SurfaceBankStateOutMessage(it)) }

    // Same rule, one frame: the encoder bank has no "changed" delta, so the StateFlow
    // subscription is snapshot and broadcast both.
    scope.subscribe(state.encoderBankState.selections) { scope.send(SurfaceEncoderBankStateOutMessage(it)) }

    // A capture goes to the window that waited for it, and to no other.
    scope.subscribe(state.surfaceIdentify.events.filter { it.sessionId in scope.ownedIdentifySessions }) { event ->
        scope.ownedIdentifySessions.remove(event.sessionId)
        when (event) {
            is SurfaceIdentify.Event.Captured -> scope.send(SurfaceIdentifyCapturedOutMessage(
                sessionId = event.sessionId,
                deviceTypeKey = event.capture.deviceTypeKey,
                displayKey = event.capture.displayKey,
                controlId = event.capture.controlId,
                part = event.capture.part,
            ))
            is SurfaceIdentify.Event.Ended ->
                scope.send(SurfaceIdentifyStateOutMessage(event.sessionId, waiting = false, reason = event.reason))
        }
    }

    // The map is a StateFlow, so the first emission is the connect snapshot; after it only a frame
    // that changed is sent, since one change moves one device type's map at most.
    val sentMaps = HashMap<String, SurfaceMapFrame>()
    scope.subscribe(state.surfaceMapService.maps.filterNotNull()) { maps ->
        for ((typeKey, frame) in maps.frames) {
            if (sentMaps[typeKey] == frame) continue
            sentMaps[typeKey] = frame
            scope.send(SurfaceMapStateOutMessage(frame))
        }
    }

    scope.subscribe(state.controlSurfaceBindingService.changes) { change ->
        val frame = when (change) {
            is ControlSurfaceBindingService.BindingChange.Added ->
                SurfaceBindingsChangedOutMessage(change.projectId, BindingChangeType.ADDED, change.binding.id)
            is ControlSurfaceBindingService.BindingChange.Updated ->
                SurfaceBindingsChangedOutMessage(change.projectId, BindingChangeType.UPDATED, change.binding.id)
            is ControlSurfaceBindingService.BindingChange.Removed ->
                SurfaceBindingsChangedOutMessage(change.projectId, BindingChangeType.REMOVED, change.bindingId)
            is ControlSurfaceBindingService.BindingChange.Reloaded ->
                SurfaceBindingsChangedOutMessage(change.projectId, BindingChangeType.RELOADED, null)
            // A job moves no row of the binding list; its parts reach every window on the map.
            is ControlSurfaceBindingService.BindingChange.JobsChanged -> null
        }
        frame?.let { scope.send(it) }
    }

    scope.subscribe(state.activeBankState.changes) { change ->
        scope.send(SurfaceBankChangedOutMessage(
            displayKey = change.displayKey,
            deviceTypeKey = state.deviceMatcher.attached.value[change.displayKey]?.typeKey,
            previousBank = change.previousBank,
            newBank = change.newBank,
        ))
    }

    // `state.show.globalScalerState` is re-created on project switch, so a plain
    // `combine(...)` at connect time would observe the previous project's facade forever.
    // Re-subscribing via `flatMapLatest` off `projectChangedFlow` (plus an initial Unit to
    // bootstrap the first subscription) makes the outbound flow follow the active show. The
    // combine's initial emit — both at connect and after a switch — *is* the connect snapshot
    // under the one-snapshot rule; `surfaceScaler.state` stays as explicit resync.
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
            .distinctUntilChanged(),
    ) { scope.send(it) }

    scope.subscribe(state.surfaceFeedbackPublisher.takeover.changes) { change ->
        scope.send(SurfacePickupChangedOutMessage(
            displayKey = change.displayKey,
            controlId = change.controlId,
            state = change.state,
            target = change.target?.toInt(),
        ))
    }

    // `surfaceControls.*`: the deltas are a replay-0 stream and the snapshots fire only on a
    // resync, so the connect frame is an explicit `sendSnapshot` — one `.state` per attached
    // device, from the tracker's own store.
    val controlStates = state.surfaceFeedbackPublisher.controlStates
    scope.sendSnapshot {
        for (snapshot in controlStates.snapshots()) {
            send(SurfaceControlsStateOutMessage(snapshot.displayKey, snapshot.controls))
        }
    }
    scope.subscribe(controlStates.snapshots) { snapshot ->
        scope.send(SurfaceControlsStateOutMessage(snapshot.displayKey, snapshot.controls))
    }
    scope.subscribe(controlStates.deltas) { delta ->
        scope.send(SurfaceControlsChangedOutMessage(delta.displayKey, delta.controls))
    }

    // Push the full device list on connect (all three sources are StateFlows, so the combine
    // emits immediately) and again whenever the set of connected ports or matched profiles
    // changes, or an active bank flips. `distinctUntilChanged` drops no-op emits.
    scope.subscribe(
        combine(
            state.midiRegistry.devices,
            state.deviceMatcher.attached,
            state.activeBankState.active,
        ) { devices, attached, banks ->
            buildSurfaceDevicesStateMessage(devices, attached, banks)
        }
            .distinctUntilChanged(),
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
            activeBank = if (match != null) banks[handle.displayKey] else null,
        )
    }
    return SurfaceDevicesStateOutMessage(devices = infos)
}
