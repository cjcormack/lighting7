package uk.me.cormack.lighting7.plugins

import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.trait.*
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.models.DaoScene
import uk.me.cormack.lighting7.routes.SceneDetails
import uk.me.cormack.lighting7.routes.details
import uk.me.cormack.lighting7.scriptSettings.ScriptSettingValue
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.state.State
import java.util.*
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.LinkedHashSet

@Serializable
sealed class InMessage

@Serializable
@SerialName("ping")
data object PingInMessage : InMessage()

@Serializable
@SerialName("channelState")
data object ChannelStateInMessage : InMessage()

@Serializable
sealed class OutMessage

@Serializable
data class ChannelState(
    val universe: Int,
    val id: Int,
    val currentLevel: UByte,
)

@Serializable
@SerialName("channelState")
data class ChannelStateOutMessage(
    val channels: List<ChannelState>
): OutMessage()

@Serializable
@SerialName("trackDetails")
data object TrackDetailsInMessage : InMessage()

@Serializable
@SerialName("updateChannel")
data class UpdateChannelInMessage(
    val universe: Int,
    val id: Int,
    val level: UByte,
    val fadeTime: Long,
) : InMessage()

@Serializable
@SerialName("universesState")
data object UniversesStateInMessage : InMessage()
@Serializable
@SerialName("universesState")
data class UniversesStateOutMessage(
    val universes: List<Int>
): OutMessage()

@Serializable
@SerialName("sceneListChanged")
data object ScenesListChangedOutMessage: OutMessage()

@Serializable
@SerialName("sceneChanged")
data class ScenesChangedOutMessage(
    val data: SceneDetails,
): OutMessage()

@Serializable
@SerialName("trackChanged")
data class TrackChangedOutMessage(
    val isPlaying: Boolean,
    val artist: String,
    val name: String,
): OutMessage()

@Serializable
@SerialName("fixturesChanged")
data object FixturesChangedOutMessage: OutMessage()

// Channel mapping messages

@Serializable
@SerialName("channelMappingState")
data object ChannelMappingStateInMessage : InMessage()

@Serializable
data class ChannelMappingEntry(
    val fixtureKey: String,
    val fixtureName: String,
    val description: String
)

@Serializable
@SerialName("channelMappingState")
data class ChannelMappingStateOutMessage(
    val mappings: Map<Int, Map<Int, ChannelMappingEntry>>
) : OutMessage()

// FX-related messages

@Serializable
@SerialName("fxState")
data object FxStateInMessage : InMessage()

@Serializable
@SerialName("setFxBpm")
data class SetFxBpmInMessage(val bpm: Double) : InMessage()

@Serializable
@SerialName("tapTempo")
data object TapTempoInMessage : InMessage()

@Serializable
@SerialName("addFx")
data class AddFxInMessage(
    val effectType: String,
    val fixtureKey: String,
    val propertyName: String,
    val beatDivision: Double = 1.0,
    val blendMode: String = "OVERRIDE",
    val phaseOffset: Double = 0.0
) : InMessage()

@Serializable
@SerialName("removeFx")
data class RemoveFxInMessage(val effectId: Long) : InMessage()

@Serializable
@SerialName("pauseFx")
data class PauseFxInMessage(val effectId: Long) : InMessage()

@Serializable
@SerialName("resumeFx")
data class ResumeFxInMessage(val effectId: Long) : InMessage()

@Serializable
@SerialName("clearFx")
data object ClearFxInMessage : InMessage()

@Serializable
@SerialName("requestBeatSync")
data object RequestBeatSyncInMessage : InMessage()

@Serializable
data class FxEffectState(
    val id: Long,
    val effectType: String,
    val targetKey: String,
    val isRunning: Boolean,
    val phase: Double,
    val blendMode: String
)

@Serializable
@SerialName("fxState")
data class FxStateOutMessage(
    val bpm: Double,
    val isClockRunning: Boolean,
    val activeEffects: List<FxEffectState>
) : OutMessage()

@Serializable
@SerialName("fxChanged")
data class FxChangedOutMessage(
    val changeType: String,  // "added", "removed", "updated", "cleared"
    val effectId: Long? = null
) : OutMessage()

// Beat sync message - sent on each beat for UI synchronization

@Serializable
@SerialName("beatSync")
data class BeatSyncOutMessage(
    val beatNumber: Long,
    val bpm: Double,
    val timestampMs: Long
) : OutMessage()

// Group-related messages

@Serializable
@SerialName("groupsState")
data object GroupsStateInMessage : InMessage()

@Serializable
@SerialName("groupsState")
data class GroupsStateOutMessage(
    val groups: List<GroupSummary>
) : OutMessage()

@Serializable
data class GroupSummary(
    val name: String,
    val memberCount: Int,
    val capabilities: List<String>
)

@Serializable
@SerialName("addGroupFx")
data class AddGroupFxInMessage(
    val groupName: String,
    val effectType: String,
    val propertyName: String,
    val beatDivision: Double = 1.0,
    val blendMode: String = "OVERRIDE",
    val distribution: String = "LINEAR",
    val phaseOffset: Double = 0.0
) : InMessage()

@Serializable
@SerialName("groupFxAdded")
data class GroupFxAddedOutMessage(
    val groupName: String,
    val effectId: Long
) : OutMessage()

@Serializable
@SerialName("clearGroupFx")
data class ClearGroupFxInMessage(val groupName: String) : InMessage()

@Serializable
@SerialName("groupFxCleared")
data class GroupFxClearedOutMessage(
    val groupName: String,
    val removedCount: Int
) : OutMessage()

// Project-related messages

@Serializable
@SerialName("projectState")
data object ProjectStateInMessage : InMessage()

@Serializable
@SerialName("projectState")
data class ProjectStateOutMessage(
    val projectId: Int,
    val projectName: String,
    val description: String?
) : OutMessage()

@Serializable
@SerialName("projectChanged")
data class ProjectChangedOutMessage(
    val previousProjectId: Int?,
    val newProjectId: Int,
    val newProjectName: String
) : OutMessage()

class SocketConnection(val session: WebSocketServerSession) {
    companion object {
        val lastId = AtomicInteger(0)
    }
    val name = "conn${lastId.getAndIncrement()}"
}

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

                override fun sceneListChanged() {
                    launch {
                        sendSerialized<OutMessage>(ScenesListChangedOutMessage)
                    }
                }

                override fun sceneChanged(id: Int) {
                    launch {
                        val sceneDetails = transaction(state.database) {
                            val scene = DaoScene.findById(id) ?: throw Error("Scene not found")
                            scene.details(state.show, isCurrentProject = true) // WebSocket broadcasts are always for current project
                        }

                        sendSerialized<OutMessage>(ScenesChangedOutMessage(sceneDetails))
                    }
                }

                override fun trackChanged(isPlaying: Boolean, artist: String, name: String) {
                    launch {
                        sendSerialized<OutMessage>(TrackChangedOutMessage(isPlaying, artist, name))
                    }
                }
            }
            state.show.fixtures.registerListener(listener)

            // Send initial channel mapping state
            launch {
                sendSerialized<OutMessage>(buildChannelMappingMessage(state))
            }

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
                            blendMode = effectState.blendMode.name
                        )
                    }
                    sendSerialized<OutMessage>(FxStateOutMessage(
                        bpm = state.show.fxEngine.masterClock.bpm.value,
                        isClockRunning = state.show.fxEngine.masterClock.isRunning.value,
                        activeEffects = effectStates
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

            try {
                for (frame in incoming) {
                    when (val message = converter?.deserialize<InMessage>(frame)) {
                        is PingInMessage -> {}
                        is ChannelStateInMessage -> {
                            val currentValues = state.show.fixtures.controllers.map { controller ->
                                controller.currentValues.map {
                                    ChannelState(controller.universe.universe, it.key, it.value)
                                }
                            }.flatten()

                            sendSerialized<OutMessage>(ChannelStateOutMessage(currentValues))
                        }
                        is TrackDetailsInMessage -> {
                            state.show.requestCurrentTrackDetails()
                        }
                        is UpdateChannelInMessage -> {
                            val controller = state.show.fixtures.controller(Universe(0, message.universe))
                            controller.setValue(message.id, message.level, message.fadeTime)
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

                        null -> TODO()
                    }
                }
            } finally {
                connections -= thisConnection
                fxStateJob.cancel()
                beatSyncJob.cancel()
                bpmChangeJob.cancel()
                projectChangeJob.cancel()
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
            blendMode = effect.blendMode.name
        )
    }
    return FxStateOutMessage(
        bpm = state.show.fxEngine.masterClock.bpm.value,
        isClockRunning = state.show.fxEngine.masterClock.isRunning.value,
        activeEffects = effectStates
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
