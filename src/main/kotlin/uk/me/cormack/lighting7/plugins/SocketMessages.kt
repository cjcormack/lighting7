package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.Serializable

/**
 * Root of every WebSocket inbound frame. Each domain defines an intermediate sealed
 * subclass (e.g. [FxInMessage]) under which its concrete leaf messages live, so the
 * top-level dispatcher in `Sockets.kt` only enumerates domains and the per-domain
 * handler exhaustively matches its own messages.
 */
@Serializable
sealed class InMessage

/** Mirror of [InMessage] for outbound frames. See [InMessage] for the layering rationale. */
@Serializable
sealed class OutMessage
