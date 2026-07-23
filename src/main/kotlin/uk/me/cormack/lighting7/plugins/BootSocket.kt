package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.state.BootStatus

/**
 * Outbound-only boot-progress frame. Sent on connect and, while the show is still warming up,
 * on every [BootStatus] change so a freshly-connected client can render a loading bar without
 * polling `GET /api/rest/status`. There is no inbound counterpart, so no dispatch arm in
 * `Sockets.kt` — mirrors the listener-driven `BroadcastOutMessage` family.
 */
@Serializable
@SerialName("bootProgressState")
data class BootProgressStateOutMessage(val status: BootStatus) : OutMessage()
