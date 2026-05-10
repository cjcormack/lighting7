package uk.me.cormack.lighting7.plugins

import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.trait.*
import uk.me.cormack.lighting7.fx.parseExtendedColour
import uk.me.cormack.lighting7.midi.ControlSurfaceBindingService
import uk.me.cormack.lighting7.midi.MidiLearnSessionManager
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.state.State
import java.util.*
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.LinkedHashSet


class SocketConnection(val session: WebSocketServerSession) {
    companion object {
        val lastId = AtomicInteger(0)
    }
    val name = "conn${lastId.getAndIncrement()}"
}

@OptIn(ExperimentalCoroutinesApi::class)
fun Application.configureSockets(state: State) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
    }

    routing {
        val connections = Collections.synchronizedSet<SocketConnection?>(LinkedHashSet())

        webSocket("/api") {
            val thisConnection = SocketConnection(this)
            connections += thisConnection

            val listener = object : FixturesChangeListener {
                override fun channelsChanged(universe: Universe, changes: Map<Int, UByte>) {
                    if (universe.subnet != 0) {
                        return
                    }

                    val changeList = changes.map {
                        ChannelState(universe.universe, it.key, it.value)
                    }
                    launch {
                        sendSerialized<OutMessage>(ChannelStateOutMessage(changeList))
                    }
                }

                override fun controllersChanged() {
                    val universes = state.show.fixtures.controllers.map(DmxController::universe).map(Universe::universe)
                        .sortedBy { it }
                    launch {
                        sendSerialized<OutMessage>(UniversesStateOutMessage(universes))
                    }
                }

                override fun fixturesChanged() {
                    launch {
                        sendSerialized<OutMessage>(FixturesChangedOutMessage)
                        sendSerialized<OutMessage>(buildChannelMappingMessage(state))
                    }
                }

                override fun presetListChanged() {
                    launch {
                        sendSerialized<OutMessage>(PresetListChangedOutMessage)
                    }
                }

                override fun cueListChanged() {
                    launch {
                        sendSerialized<OutMessage>(CueListChangedOutMessage)
                    }
                }

                override fun cueStackListChanged() {
                    launch {
                        sendSerialized<OutMessage>(CueStackListChangedOutMessage)
                    }
                }

                override fun cueSlotListChanged() {
                    launch {
                        sendSerialized<OutMessage>(CueSlotListChangedOutMessage)
                    }
                }

                override fun patchListChanged() {
                    launch {
                        sendSerialized<OutMessage>(PatchListChangedOutMessage)
                    }
                }

                override fun riggingListChanged() {
                    launch {
                        sendSerialized<OutMessage>(RiggingListChangedOutMessage)
                    }
                }

                override fun stageRegionListChanged() {
                    launch {
                        sendSerialized<OutMessage>(StageRegionListChangedOutMessage)
                    }
                }

                override fun showEntriesChanged() {
                    launch {
                        sendSerialized<OutMessage>(ShowEntriesChangedOutMessage)
                    }
                }

                override fun showChanged(
                    projectId: Int,
                    activeEntryId: Int?,
                    activatedStackId: Int?,
                    activatedStackName: String?,
                ) {
                    launch {
                        sendSerialized<OutMessage>(ShowChangedOutMessage(
                            projectId = projectId,
                            activeEntryId = activeEntryId,
                            activatedStackId = activatedStackId,
                            activatedStackName = activatedStackName,
                        ))
                    }
                }
            }
            state.show.fixtures.registerListener(listener)

            // Send initial channel mapping state
            launch {
                sendSerialized<OutMessage>(buildChannelMappingMessage(state))
            }

            // Subscribe to park state changes
            val parkStateJob = state.show.parkManager.parkStateFlow
                .onEach { parkedChannels ->
                    sendSerialized<OutMessage>(ParkStateOutMessage(
                        channels = parkedChannels.map { ParkedChannelState(it.universe, it.channel, it.value) }
                    ))
                }
                .launchIn(this)

            // Subscribe to FX state changes
            val fxStateJob = state.show.fxEngine.fxStateFlow
                .onEach { update ->
                    val effectStates = update.effectStates.values.map { effectState ->
                        FxEffectState(
                            id = effectState.id,
                            effectType = effectState.effectType,
                            targetKey = effectState.targetKey,
                            isRunning = effectState.isRunning,
                            phase = effectState.currentPhase,
                            blendMode = effectState.blendMode.name,
                            cueId = effectState.cueId,
                            cueStackId = effectState.cueStackId,
                        )
                    }
                    sendSerialized<OutMessage>(FxStateOutMessage(
                        bpm = state.show.fxEngine.masterClock.bpm.value,
                        isClockRunning = state.show.fxEngine.masterClock.isRunning.value,
                        activeEffects = effectStates
                    ))
                }
                .launchIn(this)

            // Subscribe to palette changes
            val paletteJob = state.show.fxEngine.paletteFlow
                .onEach { palette ->
                    sendSerialized<OutMessage>(PaletteChangedOutMessage(
                        palette = palette.map { it.toSerializedString() }
                    ))
                }
                .launchIn(this)

            // Subscribe to stack palette changes
            val stackPaletteJob = state.show.fxEngine.stackPaletteFlow
                .onEach { stackPalettes ->
                    sendSerialized<OutMessage>(StackPalettesChangedOutMessage(
                        stackPalettes = stackPalettes.mapValues { (_, colours) ->
                            colours.map { it.toSerializedString() }
                        }
                    ))
                }
                .launchIn(this)

            // Flag to send a beatSync on the next beat boundary (set on requestBeatSync)
            val sendNextBeat = AtomicBoolean(true)

            // Periodic beat sync for UI drift correction (every 16 beats ≈ 8s at 120 BPM),
            // plus immediate sync on next beat when requested
            val beatSyncJob = state.show.fxEngine.masterClock.beatFlow
                .filter { beat -> beat.beatNumber % 16 == 0L || sendNextBeat.get() }
                .onEach { beat ->
                    sendSerialized<OutMessage>(BeatSyncOutMessage(
                        beatNumber = beat.beatNumber,
                        bpm = state.show.fxEngine.masterClock.bpm.value,
                        timestampMs = beat.timestampMs
                    ))
                }
                .launchIn(this)

            // Immediate beat sync whenever BPM changes (tap tempo, setBpm, etc.)
            val bpmChangeJob = state.show.fxEngine.masterClock.bpm
                .drop(1) // Skip initial value emission
                .onEach { newBpm ->
                    val clock = state.show.fxEngine.masterClock
                    val now = System.currentTimeMillis()
                    sendSerialized<OutMessage>(BeatSyncOutMessage(
                        beatNumber = -1, // Indicates this is a BPM-change sync, not a beat boundary
                        bpm = newBpm,
                        timestampMs = now
                    ))
                }
                .launchIn(this)

            // Track current fixtures for listener re-registration on project change
            var currentFixtures = state.show.fixtures

            // Subscribe to project changes
            val projectChangeJob = state.projectManager.projectChangedFlow
                .onEach { event ->
                    // Unregister listener from old fixtures and register on new
                    currentFixtures.unregisterListener(listener)
                    currentFixtures = state.show.fixtures
                    currentFixtures.registerListener(listener)

                    // Broadcast project change
                    sendSerialized<OutMessage>(ProjectChangedOutMessage(
                        previousProjectId = event.previousProjectId,
                        newProjectId = event.newProjectId,
                        newProjectName = event.newProjectName
                    ))
                }
                .launchIn(this)

            // Cloud-sync lifecycle messages — fanned out from State.emitCloudSyncEvent.
            val cloudSyncJob = state.cloudSyncEventsFlow
                .onEach { message -> sendSerialized(message) }
                .launchIn(this)

            // Per-connection cueEdit session. At most one open edit at a time per connection;
            // cleared on disconnect so a client crash doesn't leave a cue stuck in Live mode.
            val cueEditSessionRef = java.util.concurrent.atomic.AtomicReference<CueEditSessionState?>(null)

            // Scopes Learn capture broadcasts to the session's originating client, so two
            // `/surfaces` tabs don't see phantom captures from each other's sessions.
            val ownedLearnSessions: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet<String>())

            val learnEventsJob = state.midiLearnSessionManager.events
                .filter { it.sessionId in ownedLearnSessions }
                .onEach { event ->
                    when (event) {
                        is MidiLearnSessionManager.SessionEvent.Captured -> {
                            val captured = event.session.captured ?: return@onEach
                            sendSerialized<OutMessage>(SurfaceLearnCapturedOutMessage(
                                sessionId = event.sessionId,
                                projectId = event.session.projectId,
                                deviceTypeKey = captured.deviceTypeKey,
                                controlId = captured.controlId,
                            ))
                        }
                        is MidiLearnSessionManager.SessionEvent.TimedOut -> {
                            ownedLearnSessions.remove(event.sessionId)
                            sendSerialized<OutMessage>(SurfaceLearnCancelledOutMessage(
                                sessionId = event.sessionId,
                                reason = "timeout",
                            ))
                        }
                        is MidiLearnSessionManager.SessionEvent.Cancelled ->
                            ownedLearnSessions.remove(event.sessionId)
                        is MidiLearnSessionManager.SessionEvent.Committed ->
                            ownedLearnSessions.remove(event.sessionId)
                        is MidiLearnSessionManager.SessionEvent.Started -> Unit
                    }
                }
                .launchIn(this)

            val bindingChangeJob = state.controlSurfaceBindingService.changes
                .onEach { change ->
                    val message = when (change) {
                        is ControlSurfaceBindingService.BindingChange.Added ->
                            SurfaceBindingsChangedOutMessage(change.projectId, BindingChangeType.ADDED, change.binding.id)
                        is ControlSurfaceBindingService.BindingChange.Updated ->
                            SurfaceBindingsChangedOutMessage(change.projectId, BindingChangeType.UPDATED, change.binding.id)
                        is ControlSurfaceBindingService.BindingChange.Removed ->
                            SurfaceBindingsChangedOutMessage(change.projectId, BindingChangeType.REMOVED, change.bindingId)
                        is ControlSurfaceBindingService.BindingChange.Reloaded ->
                            SurfaceBindingsChangedOutMessage(change.projectId, BindingChangeType.RELOADED, null)
                    }
                    sendSerialized<OutMessage>(message)
                }
                .launchIn(this)

            val bankChangeJob = state.activeBankState.changes
                .onEach { change ->
                    sendSerialized<OutMessage>(
                        SurfaceBankChangedOutMessage(
                            deviceTypeKey = change.deviceTypeKey,
                            previousBank = change.previousBank,
                            newBank = change.newBank,
                        )
                    )
                }
                .launchIn(this)

            // `state.show.globalScalerState` is re-created on project switch, so a plain
            // `combine(...)` at connect time would observe the previous project's facade
            // forever. Re-subscribing via `flatMapLatest` off `projectChangedFlow` (plus
            // an initial Unit to bootstrap the first subscription) makes the outbound flow
            // follow the active show. The `drop(1)` suppresses the combine's initial emit
            // so connect doesn't push state the client hasn't asked for — clients fetch
            // initial state via the `surfaceScaler.state` request message.
            val scalerStateJob = state.projectManager.projectChangedFlow
                .map { Unit }
                .onStart { emit(Unit) }
                .flatMapLatest {
                    combine(
                        state.show.globalScalerState.blackoutEnabled,
                        state.show.globalScalerState.grandMasterEnabled,
                    ) { blackout, grandMaster -> SurfaceScalerStateOutMessage(blackout, grandMaster) }
                }
                .drop(1)
                .onEach { sendSerialized<OutMessage>(it) }
                .launchIn(this)

            val pickupChangeJob = state.surfaceFeedbackPublisher.takeover.changes
                .onEach { change ->
                    sendSerialized<OutMessage>(
                        SurfacePickupChangedOutMessage(
                            displayKey = change.displayKey,
                            controlId = change.controlId,
                            state = change.state,
                            target = change.target?.toInt(),
                        )
                    )
                }
                .launchIn(this)

            // Push the full device list whenever the set of connected ports or matched
            // profiles changes, or when an active bank flips. `distinctUntilChanged` drops
            // no-op emits — e.g. `setBank` calls that land on the already-active bank, or
            // `attached` mutations that don't change the resulting snapshot.
            val devicesStateJob = combine(
                state.midiRegistry.devices,
                state.deviceMatcher.attached,
                state.activeBankState.active,
            ) { devices, attached, banks ->
                buildSurfaceDevicesStateMessage(devices, attached, banks)
            }
                .distinctUntilChanged()
                .drop(1)
                .onEach { sendSerialized<OutMessage>(it) }
                .launchIn(this)

            try {
                for (frame in incoming) {
                    when (val message = converter?.deserialize<InMessage>(frame)) {
                        is PingInMessage -> {}
                        is ChannelStateInMessage -> {
                            // Overlay parked values onto currentValues so clients see what the
                            // fixture is actually emitting, not the underlying buffered value.
                            val parkManager = state.show.parkManager
                            val currentValues = state.show.fixtures.controllers.flatMap { controller ->
                                val universe = controller.universe.universe
                                controller.currentValues.map { (channelNo, value) ->
                                    ChannelState(
                                        universe,
                                        channelNo,
                                        parkManager.getParkedValue(universe, channelNo) ?: value,
                                    )
                                }
                            }

                            sendSerialized<OutMessage>(ChannelStateOutMessage(currentValues))
                        }
                        is UpdateChannelInMessage -> {
                            val controller = state.show.fixtures.controller(Universe(0, message.universe))
                            controller.setValue(message.id, message.level, message.fadeTime)
                            state.show.directWriteStore.put(message.universe, message.id, message.level)
                        }

                        // Park mutations write through ParkManager; the requestTransmit() poke
                        // pushes the new value to the rig before the next 25 ms transmit tick.
                        is ParkStateInMessage -> {
                            sendSerialized<OutMessage>(buildParkStateMessage(state))
                        }
                        is ParkChannelInMessage -> {
                            state.show.parkManager.park(message.universe, message.channel, message.value)
                            state.show.fixtures.controller(Universe(0, message.universe)).requestTransmit()
                        }
                        is UnparkChannelInMessage -> {
                            state.show.parkManager.unpark(message.universe, message.channel)
                            state.show.fixtures.controller(Universe(0, message.universe)).requestTransmit()
                        }
                        is UnparkAllInMessage -> {
                            state.show.parkManager.unparkAll()
                            state.show.fixtures.controllers.forEach { it.requestTransmit() }
                        }

                        is UniversesStateInMessage -> {
                            val universes = state.show.fixtures.controllers.map(DmxController::universe).map(Universe::universe)
                                .sortedBy { it }
                            sendSerialized<OutMessage>(UniversesStateOutMessage(universes))
                        }
                        is ChannelMappingStateInMessage -> {
                            sendSerialized<OutMessage>(buildChannelMappingMessage(state))
                        }

                        // FX-related message handlers
                        is FxStateInMessage -> {
                            sendSerialized<OutMessage>(buildFxStateMessage(state))
                        }
                        is SetFxBpmInMessage -> {
                            state.show.fxEngine.masterClock.setBpm(message.bpm)
                            sendSerialized<OutMessage>(buildFxStateMessage(state))
                        }
                        is TapTempoInMessage -> {
                            state.show.fxEngine.masterClock.tap()
                            sendSerialized<OutMessage>(buildFxStateMessage(state))
                        }
                        is RemoveFxInMessage -> {
                            state.show.fxEngine.removeEffect(message.effectId)
                            sendSerialized<OutMessage>(FxChangedOutMessage("removed", message.effectId))
                        }
                        is PauseFxInMessage -> {
                            state.show.fxEngine.pauseEffect(message.effectId)
                            sendSerialized<OutMessage>(FxChangedOutMessage("updated", message.effectId))
                        }
                        is ResumeFxInMessage -> {
                            state.show.fxEngine.resumeEffect(message.effectId)
                            sendSerialized<OutMessage>(FxChangedOutMessage("updated", message.effectId))
                        }
                        is ClearFxInMessage -> {
                            state.show.fxEngine.clearAllEffects()
                            sendSerialized<OutMessage>(FxChangedOutMessage("cleared"))
                        }
                        is AddFxInMessage -> {
                            // Note: For adding effects via WebSocket, use REST API instead
                            // This is a simplified handler - complex effect creation should use REST
                        }
                        is RequestBeatSyncInMessage -> {
                            sendNextBeat.set(true)
                        }

                        // Palette message handlers
                        is SetPaletteInMessage -> {
                            val colours = message.colours.map { parseExtendedColour(it) }
                            state.show.fxEngine.setPalette(colours)
                        }
                        is SetPaletteColourInMessage -> {
                            state.show.fxEngine.setPaletteColour(
                                message.index,
                                parseExtendedColour(message.colour)
                            )
                        }
                        is AddPaletteColourInMessage -> {
                            state.show.fxEngine.addPaletteColour(parseExtendedColour(message.colour))
                        }
                        is RemovePaletteColourInMessage -> {
                            state.show.fxEngine.removePaletteColour(message.index)
                        }

                        // Group-related message handlers
                        is GroupsStateInMessage -> {
                            sendSerialized<OutMessage>(buildGroupsStateMessage(state))
                        }
                        is AddGroupFxInMessage -> {
                            // Note: For adding group effects via WebSocket, use REST API instead
                            // The REST API provides more complete error handling
                        }
                        is ClearGroupFxInMessage -> {
                            try {
                                val group = state.show.fixtures.untypedGroup(message.groupName)
                                // Remove group-level effects first, then any per-fixture effects
                                val groupCount = state.show.fxEngine.removeEffectsForGroup(message.groupName)
                                val fixtureCount = group.sumOf {
                                    state.show.fxEngine.removeEffectsForFixture(it.key)
                                }
                                sendSerialized<OutMessage>(GroupFxClearedOutMessage(message.groupName, groupCount + fixtureCount))
                            } catch (e: Exception) {
                                // Group not found - ignore
                            }
                        }

                        // Project-related message handlers
                        is ProjectStateInMessage -> {
                            val project = state.projectManager.currentProject
                            sendSerialized<OutMessage>(ProjectStateOutMessage(
                                projectId = project.id.value,
                                projectName = project.name,
                                description = project.description
                            ))
                        }

                        is SurfaceLearnBeginInMessage -> {
                            val session = state.midiLearnSessionManager.begin(
                                projectId = message.projectId,
                                filter = MidiLearnSessionManager.LearnFilter(deviceTypeKey = message.deviceTypeKey),
                            )
                            ownedLearnSessions += session.sessionId
                            sendSerialized<OutMessage>(SurfaceLearnStartedOutMessage(
                                sessionId = session.sessionId,
                                projectId = session.projectId,
                                deviceTypeKey = session.filter.deviceTypeKey,
                                deadlineMs = session.deadlineMs,
                            ))
                        }
                        is SurfaceLearnCancelInMessage -> {
                            val cancelled = state.midiLearnSessionManager.cancel(message.sessionId)
                            ownedLearnSessions.remove(message.sessionId)
                            val reply: OutMessage = if (cancelled != null) {
                                SurfaceLearnCancelledOutMessage(message.sessionId, reason = "cancelled")
                            } else {
                                SurfaceLearnErrorOutMessage(message.sessionId, "Session not found or not cancellable")
                            }
                            sendSerialized(reply)
                        }
                        is SurfaceLearnCommitInMessage -> {
                            val reply = handleSurfaceLearnCommit(state, message)
                            if (reply is SurfaceLearnCommittedOutMessage) {
                                ownedLearnSessions.remove(message.sessionId)
                            }
                            sendSerialized(reply)
                        }

                        is SurfaceBankSetInMessage -> {
                            state.activeBankState.setBank(message.deviceTypeKey, message.bank)
                        }
                        is SurfaceBankStateInMessage -> {
                            sendSerialized<OutMessage>(SurfaceBankStateOutMessage(state.activeBankState.active.value))
                        }
                        is SurfaceDevicesStateInMessage -> {
                            sendSerialized<OutMessage>(
                                buildSurfaceDevicesStateMessage(
                                    state.midiRegistry.devices.value,
                                    state.deviceMatcher.attached.value,
                                    state.activeBankState.active.value,
                                )
                            )
                        }
                        is SurfaceScalerStateInMessage -> {
                            sendSerialized<OutMessage>(
                                SurfaceScalerStateOutMessage(
                                    blackoutEnabled = state.show.globalScalerState.blackoutEnabled.value,
                                    grandMasterEnabled = state.show.globalScalerState.grandMasterEnabled.value,
                                )
                            )
                        }
                        is SurfaceScalerSetBlackoutInMessage -> {
                            state.show.globalScalerState.setBlackout(message.enabled)
                        }
                        is SurfaceScalerSetGrandMasterInMessage -> {
                            state.show.globalScalerState.setGrandMaster(message.enabled)
                        }

                        // Cue-edit session messages — per-connection session held in
                        // [cueEditSessionRef]. See [CueEditSessionHandler] for semantics.
                        is CueEditBeginEditInMessage -> {
                            sendSerialized<OutMessage>(
                                CueEditSessionHandler.beginEdit(state, cueEditSessionRef, message.cueId, message.mode)
                            )
                        }
                        is CueEditEndEditInMessage -> {
                            sendSerialized<OutMessage>(
                                CueEditSessionHandler.endEdit(state, cueEditSessionRef, message.cueId)
                            )
                        }
                        is CueEditSetChannelInMessage -> {
                            sendSerialized<OutMessage>(
                                CueEditSessionHandler.setChannel(
                                    state, cueEditSessionRef, message.cueId,
                                    message.universe, message.channel, message.level,
                                )
                            )
                        }
                        is CueEditSetPropertyInMessage -> {
                            val target = TargetRef.ofOrNull(message.targetType, message.targetKey)
                            sendSerialized<OutMessage>(
                                if (target == null) CueEditErrorOutMessage(
                                    message.cueId, "Unknown targetType '${message.targetType}'",
                                ) else CueEditSessionHandler.setProperty(
                                    state, cueEditSessionRef, message.cueId,
                                    target, message.propertyName, message.value,
                                )
                            )
                        }
                        is CueEditDiscardChangesInMessage -> {
                            sendSerialized<OutMessage>(
                                CueEditSessionHandler.discardChanges(state, cueEditSessionRef, message.cueId)
                            )
                        }
                        is CueEditSetModeInMessage -> {
                            sendSerialized<OutMessage>(
                                CueEditSessionHandler.setMode(state, cueEditSessionRef, message.cueId, message.mode)
                            )
                        }
                        is CueEditClearAssignmentInMessage -> {
                            val target = TargetRef.ofOrNull(message.targetType, message.targetKey)
                            sendSerialized<OutMessage>(
                                if (target == null) CueEditErrorOutMessage(
                                    message.cueId, "Unknown targetType '${message.targetType}'",
                                ) else CueEditSessionHandler.clearAssignment(
                                    state, cueEditSessionRef, message.cueId,
                                    target, message.propertyName,
                                )
                            )
                        }
                        is CueEditSetPaletteInMessage -> {
                            sendSerialized<OutMessage>(
                                CueEditSessionHandler.setPalette(
                                    state, cueEditSessionRef, message.cueId, message.palette,
                                )
                            )
                        }
                        is CueEditAddPresetApplicationInMessage -> {
                            sendSerialized<OutMessage>(
                                CueEditSessionHandler.addPresetApplication(
                                    state, cueEditSessionRef, message.cueId,
                                    message.presetId, message.targets,
                                    message.delayMs, message.intervalMs, message.randomWindowMs,
                                )
                            )
                        }
                        is CueEditAddAdHocEffectInMessage -> {
                            sendSerialized<OutMessage>(
                                CueEditSessionHandler.addAdHocEffect(
                                    state, cueEditSessionRef, message.cueId, message.effect,
                                )
                            )
                        }

                        null -> TODO()
                    }
                }
            } finally {
                connections -= thisConnection
                parkStateJob.cancel()
                fxStateJob.cancel()
                paletteJob.cancel()
                stackPaletteJob.cancel()
                beatSyncJob.cancel()
                bpmChangeJob.cancel()
                projectChangeJob.cancel()
                learnEventsJob.cancel()
                bindingChangeJob.cancel()
                bankChangeJob.cancel()
                scalerStateJob.cancel()
                pickupChangeJob.cancel()
                devicesStateJob.cancel()
                ownedLearnSessions.toList().forEach { state.midiLearnSessionManager.cancel(it) }
                ownedLearnSessions.clear()
                CueEditSessionHandler.endSessionOnDisconnect(state, cueEditSessionRef)
                currentFixtures.unregisterListener(listener)
            }
        }
    }
}

private fun buildFxStateMessage(state: State): FxStateOutMessage {
    val effectStates = state.show.fxEngine.getActiveEffects().map { effect ->
        FxEffectState(
            id = effect.id,
            effectType = effect.effect.name,
            targetKey = "${effect.target.targetKey}.${effect.target.propertyName}",
            isRunning = effect.isRunning,
            phase = effect.lastPhase,
            blendMode = effect.blendMode.name,
            cueId = effect.cueId,
            cueStackId = effect.cueStackId,
            timingSource = effect.timingSource.name,
        )
    }
    return FxStateOutMessage(
        bpm = state.show.fxEngine.masterClock.bpm.value,
        isClockRunning = state.show.fxEngine.masterClock.isRunning.value,
        activeEffects = effectStates,
        palette = state.show.fxEngine.getPalette().map { it.toSerializedString() },
        stackPalettes = state.show.fxEngine.getAllStackPalettes().mapValues { (_, colours) ->
            colours.map { it.toSerializedString() }
        }
    )
}

private fun buildChannelMappingMessage(state: State): ChannelMappingStateOutMessage {
    val mappings = state.show.fixtures.getChannelMappings()
        .mapValues { (_, channels) ->
            channels.mapValues { (_, mapping) ->
                ChannelMappingEntry(
                    fixtureKey = mapping.fixtureKey,
                    fixtureName = mapping.fixtureName,
                    description = mapping.description
                )
            }
        }
    return ChannelMappingStateOutMessage(mappings)
}

private fun buildParkStateMessage(state: State): ParkStateOutMessage {
    val parked = state.show.parkManager.getAllParked()
    return ParkStateOutMessage(
        channels = parked.map { ParkedChannelState(it.universe, it.channel, it.value) }
    )
}

private fun buildSurfaceDevicesStateMessage(
    devices: List<uk.me.cormack.lighting7.midi.MidiDeviceHandle>,
    attached: Map<String, uk.me.cormack.lighting7.midi.DeviceMatcher.Attached>,
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

private fun handleSurfaceLearnCommit(state: State, message: SurfaceLearnCommitInMessage): OutMessage {
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

private fun buildGroupsStateMessage(state: State): GroupsStateOutMessage {
    val groups = state.show.fixtures.groups.map { group ->
        val capabilities = mutableListOf<String>()
        if (group.isNotEmpty()) {
            val first = group.first().fixture
            if (first is WithDimmer && group.all { it.fixture is WithDimmer }) {
                capabilities.add("dimmer")
            }
            if (first is WithColour && group.all { it.fixture is WithColour }) {
                capabilities.add("colour")
            }
            if (first is WithPosition && group.all { it.fixture is WithPosition }) {
                capabilities.add("position")
            }
            if (first is WithUv && group.all { it.fixture is WithUv }) {
                capabilities.add("uv")
            }
        }
        GroupSummary(
            name = group.name,
            memberCount = group.size,
            capabilities = capabilities
        )
    }
    return GroupsStateOutMessage(groups)
}
