package uk.me.cormack.lighting7.plugins

import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.models.DaoScene
import uk.me.cormack.lighting7.routes.SceneDetails
import uk.me.cormack.lighting7.routes.details
import uk.me.cormack.lighting7.scriptSettings.ScriptSettingValue
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.state.State
import java.time.Duration
import java.util.*
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

class SocketConnection(val session: WebSocketServerSession) {
    companion object {
        val lastId = AtomicInteger(0)
    }
    val name = "conn${lastId.getAndIncrement()}"
}

fun Application.configureSockets(state: State) {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
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
                            scene.details(state.show)
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

                        null -> TODO()
                    }
                }
            } finally {
                connections -= thisConnection
                fxStateJob.cancel()
                state.show.fixtures.unregisterListener(listener)
            }
        }
    }
}

private fun buildFxStateMessage(state: State): FxStateOutMessage {
    val effectStates = state.show.fxEngine.getActiveEffects().map { effect ->
        FxEffectState(
            id = effect.id,
            effectType = effect.effect.name,
            targetKey = "${effect.target.fixtureKey}.${effect.target.propertyName}",
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
