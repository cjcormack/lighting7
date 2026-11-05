package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.Serializable

/** A STUN or TURN server for a remote session, in WebRTC's `RTCIceServer` shape. */
@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

/** One ICE candidate, in WebRTC's `RTCIceCandidateInit` shape, relayed without being read. */
@Serializable
data class IceCandidate(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val usernameFragment: String? = null,
)

/** The MCP grant a relayed call comes under: its id, and the name its client registered with. */
@Serializable
data class McpGrant(val id: String, val clientName: String)

/** How a desk answered a [Message.LinkAccepted]. */
@Serializable
enum class LinkOutcome {
    /** The link is written. */
    LINKED,

    /** Nothing was written; [Message.LinkAck.reason] says why. */
    REFUSED,
}
