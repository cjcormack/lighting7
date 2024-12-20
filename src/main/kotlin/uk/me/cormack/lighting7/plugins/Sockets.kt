package uk.me.cormack.lighting7.plugins

import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
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

                        null -> TODO()
                    }
                }
            } finally {
                connections -= thisConnection

                state.show.fixtures.unregisterListener(listener)
            }
        }
    }
}
