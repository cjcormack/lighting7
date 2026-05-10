package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.state.State

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class ParkInMessage : InMessage()

@Serializable
@SerialName("parkState")
data object ParkStateInMessage : ParkInMessage()

@Serializable
@SerialName("parkChannel")
data class ParkChannelInMessage(
    val universe: Int,
    val channel: Int,
    val value: UByte,
) : ParkInMessage()

@Serializable
@SerialName("unparkChannel")
data class UnparkChannelInMessage(
    val universe: Int,
    val channel: Int,
) : ParkInMessage()

@Serializable
@SerialName("unparkAll")
data object UnparkAllInMessage : ParkInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class ParkOutMessage : OutMessage()

@Serializable
data class ParkedChannelState(
    val universe: Int,
    val channel: Int,
    val value: UByte,
)

@Serializable
@SerialName("parkState")
data class ParkStateOutMessage(
    val channels: List<ParkedChannelState>,
) : ParkOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handlePark(scope: SocketScope, message: ParkInMessage) {
    val parkManager = scope.state.show.parkManager
    val controllers = scope.state.show.fixtures
    when (message) {
        is ParkStateInMessage -> scope.send(buildParkStateMessage(scope.state))
        is ParkChannelInMessage -> {
            parkManager.park(message.universe, message.channel, message.value)
            controllers.controller(Universe(0, message.universe)).requestTransmit()
        }
        is UnparkChannelInMessage -> {
            parkManager.unpark(message.universe, message.channel)
            controllers.controller(Universe(0, message.universe)).requestTransmit()
        }
        is UnparkAllInMessage -> {
            parkManager.unparkAll()
            controllers.controllers.forEach { it.requestTransmit() }
        }
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupParkSubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.show.parkManager.parkStateFlow) { parked ->
        scope.send(ParkStateOutMessage(
            channels = parked.map { ParkedChannelState(it.universe, it.channel, it.value) },
        ))
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────

private fun buildParkStateMessage(state: State): ParkStateOutMessage {
    val parked = state.show.parkManager.getAllParked()
    return ParkStateOutMessage(
        channels = parked.map { ParkedChannelState(it.universe, it.channel, it.value) },
    )
}
