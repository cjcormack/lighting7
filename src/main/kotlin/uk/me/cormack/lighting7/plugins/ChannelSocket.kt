package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.state.State

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class ChannelInMessage : InMessage()

@Serializable
@SerialName("ping")
data object PingInMessage : ChannelInMessage()

@Serializable
@SerialName("channelState")
data object ChannelStateInMessage : ChannelInMessage()

@Serializable
@SerialName("updateChannel")
data class UpdateChannelInMessage(
    val universe: Int,
    val id: Int,
    val level: UByte,
    val fadeTime: Long,
) : ChannelInMessage()

@Serializable
@SerialName("universesState")
data object UniversesStateInMessage : ChannelInMessage()

@Serializable
@SerialName("channelMappingState")
data object ChannelMappingStateInMessage : ChannelInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class ChannelOutMessage : OutMessage()

@Serializable
data class ChannelState(
    val universe: Int,
    val id: Int,
    val currentLevel: UByte,
)

@Serializable
@SerialName("channelState")
data class ChannelStateOutMessage(
    val channels: List<ChannelState>,
) : ChannelOutMessage()

@Serializable
@SerialName("universesState")
data class UniversesStateOutMessage(
    val universes: List<Int>,
) : ChannelOutMessage()

@Serializable
data class ChannelMappingEntry(
    val fixtureKey: String,
    val fixtureName: String,
    val description: String,
)

@Serializable
@SerialName("channelMappingState")
data class ChannelMappingStateOutMessage(
    val mappings: Map<Int, Map<Int, ChannelMappingEntry>>,
) : ChannelOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleChannel(scope: SocketScope, message: ChannelInMessage) {
    val state = scope.state
    when (message) {
        is PingInMessage -> Unit
        is ChannelStateInMessage -> {
            // Overlay parked values onto currentValues so clients see what the fixture is
            // actually emitting, not the underlying buffered value.
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
            scope.send(ChannelStateOutMessage(currentValues))
        }
        is UpdateChannelInMessage -> {
            val controller = state.show.fixtures.controller(Universe(0, message.universe))
            controller.setValue(message.id, message.level, message.fadeTime)
            state.show.directWriteStore.put(message.universe, message.id, message.level)
        }
        is UniversesStateInMessage -> {
            scope.send(UniversesStateOutMessage(buildUniverseList(state)))
        }
        is ChannelMappingStateInMessage -> {
            scope.send(buildChannelMappingMessage(state))
        }
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────

internal fun buildUniverseList(state: State): List<Int> =
    state.show.fixtures.controllers.map(DmxController::universe).map(Universe::universe).sortedBy { it }

internal fun buildChannelMappingMessage(state: State): ChannelMappingStateOutMessage {
    val mappings = state.show.fixtures.getChannelMappings()
        .mapValues { (_, channels) ->
            channels.mapValues { (_, mapping) ->
                ChannelMappingEntry(
                    fixtureKey = mapping.fixtureKey,
                    fixtureName = mapping.fixtureName,
                    description = mapping.description,
                )
            }
        }
    return ChannelMappingStateOutMessage(mappings)
}
