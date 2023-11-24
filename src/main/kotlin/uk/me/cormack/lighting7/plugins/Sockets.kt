package uk.me.cormack.lighting7.plugins

import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.dmx.IChannelChangeListener
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

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
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
                state.show.fixtures.controllers.forEach { controller ->
                    state.show.fixtures.registerListener(controller.subnet, controller.universe, object : IChannelChangeListener {
                        override fun channelsChanged(changes: Map<Int, UByte>) {
                            val changeList = changes.map {
                                ChannelState(controller.universe, it.key, it.value)
                            }
                            launch {
                                sendSerialized<OutMessage>(ChannelStateOutMessage(changeList))
                            }
                        }
                    })
                }

                for (frame in incoming) {
                    when (val message = converter?.deserialize<InMessage>(frame)) {
                        is PingInMessage -> {}
                        is ChannelStateInMessage -> {
                            val currentValues = state.show.fixtures.controllers.map { controller ->
                                controller.currentValues.map {
                                    ChannelState(controller.universe, it.key, it.value)
                                }
                            }.flatten()

                            sendSerialized<OutMessage>(ChannelStateOutMessage(currentValues))
                        }
                        is TrackDetailsInMessage -> {
                            println("CJC TrackDetailsMessage")
                            println(message)
                        }
                        is UpdateChannelInMessage -> {
                            val controller = state.show.fixtures.controller(0, message.universe)
                            controller.setValue(message.id, message.level, message.fadeTime)
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
