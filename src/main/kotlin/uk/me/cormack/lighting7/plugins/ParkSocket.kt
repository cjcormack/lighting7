package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    when (message) {
        is ParkStateInMessage -> scope.send(buildParkStateMessage(scope.state))
        is ParkChannelInMessage -> {
            // Nothing to nudge: controllers consult the ParkSource at transmit time, so the
            // park lands on the next frame of the affected universe.
            parkManager.park(message.universe, message.channel, message.value)
            scope.state.show.fxEngine.provenance.emitUpdate()
        }
        is UnparkChannelInMessage -> {
            parkManager.unpark(message.universe, message.channel)
            scope.state.show.fxEngine.provenance.emitUpdate()
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
