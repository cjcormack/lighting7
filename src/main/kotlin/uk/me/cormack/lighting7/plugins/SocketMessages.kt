package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.Serializable

/**
 * Root of every WebSocket inbound frame. Each domain defines an intermediate sealed
 * subclass (e.g. [FxInMessage]) under which its concrete leaf messages live, so the
 * top-level dispatcher in `Sockets.kt` only enumerates domains and the per-domain
 * handler exhaustively matches its own messages.
 *
 * **Adding or removing a leaf anywhere under this hierarchy needs this file recompiled.** The
 * polymorphic scope is the sealed serializer kotlinx generates *here*, enumerating every leaf
 * across every file at compile time, and Gradle's incremental Kotlin compile does not re-run it
 * for a change in another file — the new frame then fails with "not found in the polymorphic
 * scope" while every file compiles green. A content change to this file (or `--rerun-tasks`) is
 * the fix; a `touch` is not, because the compile is keyed on content, not on timestamps.
 */
@Serializable
sealed class InMessage

/** Mirror of [InMessage] for outbound frames. See [InMessage] for the layering rationale. */
@Serializable
sealed class OutMessage
