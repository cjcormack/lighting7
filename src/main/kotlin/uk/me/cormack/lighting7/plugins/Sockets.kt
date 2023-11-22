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
import uk.me.cormack.lighting7.artnet.IChannelChangeListener
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.Show
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

            try {
                state.show.fixtures.raspberryPiUniverse.registerListener(object : IChannelChangeListener {
                    override fun channelsChanged(changes: Map<Int, UByte>) {
                        val changeList = changes.map {
                            ChannelState(state.show.fixtures.raspberryPiUniverse.universe, it.key, it.value)
                        }
                        launch {
                            sendSerialized<OutMessage>(ChannelStateOutMessage(changeList))
                        }
                    }
                })
//                Show.fixtures.openDmxUniverse.registerListener(object : IChannelChangeListener {
//                    override fun channelsChanged(changes: Map<Int, UByte>) {
//                        val changeList = changes.map {
//                            ChannelState(Show.fixtures.openDmxUniverse.universe, it.key, it.value)
//                        }
//                        launch {
//                            sendSerialized<OutMessage>(ChannelStateOutMessage(changeList))
//                        }
//                    }
//                })
                state.show.fixtures.lightStripUniverse.registerListener(object : IChannelChangeListener {
                    override fun channelsChanged(changes: Map<Int, UByte>) {
                        val changeList = changes.map {
                            ChannelState(state.show.fixtures.lightStripUniverse.universe, it.key, it.value)
                        }
                        launch {
                            sendSerialized<OutMessage>(ChannelStateOutMessage(changeList))
                        }
                    }
                })

                for (frame in incoming) {
                    when (val message = converter?.deserialize<InMessage>(frame)) {
                        is PingInMessage -> {}
                        is ChannelStateInMessage -> {
                            val currentValues = state.show.fixtures.raspberryPiUniverse.currentValues.map {
                                ChannelState(state.show.fixtures.raspberryPiUniverse.universe, it.key, it.value)
//                            } + state.show.fixtures.openDmxUniverse.currentValues.map {
//                                ChannelState(state.show.fixtures.openDmxUniverse.universe, it.key, it.value)
                            } + state.show.fixtures.lightStripUniverse.currentValues.map {
                                ChannelState(state.show.fixtures.lightStripUniverse.universe, it.key, it.value)
                            }
                            sendSerialized<OutMessage>(ChannelStateOutMessage(currentValues))
                        }
                        is TrackDetailsInMessage -> {
                            println("CJC TrackDetailsMessage")
                            println(message)
                        }
                        is UpdateChannelInMessage -> {
                            val artnet = when (message.universe) {
                                0 -> state.show.fixtures.raspberryPiUniverse
//                                1 -> state.show.fixtures.openDmxUniverse
                                2 -> state.show.fixtures.lightStripUniverse
                                else -> throw Error("Unknown universe ${message.universe}")
                            }
                            artnet.setValue(message.id, message.level, message.fadeTime)
                        }

                        null -> TODO()
                    }
                }
            } finally {
                connections -= thisConnection
            }
        }
    }
}
