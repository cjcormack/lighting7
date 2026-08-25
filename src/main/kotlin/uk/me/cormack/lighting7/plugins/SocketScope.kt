package uk.me.cormack.lighting7.plugins

import io.ktor.server.websocket.*
import kotlinx.coroutines.Job
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uk.me.cormack.lighting7.state.State
import java.util.Collections
import java.util.UUID

/**
 * Per-connection context handed to every domain handler and subscription setup.
 *
 * Holds the WebSocket session (which is itself a `CoroutineScope`, so it doubles as the
 * launch scope for flow collectors), the [State], and the connection-scoped mutable bits
 * that individual domains need. Tracks every subscription job started via [subscribe] so
 * the WebSocket teardown only has to call [cancelAll] instead of cancelling each job by
 * hand — historically a source of forgotten cleanups when new subscriptions were added.
 */
class SocketScope(
    val session: DefaultWebSocketServerSession,
    val state: State,
    /** The authenticated caller, or null in bootstrap-open mode (zero users configured). */
    val user: AuthenticatedUser? = null,
) {
    /** The caller's session-token hash — what a live-revocation stream (Session 3) matches on. */
    val sessionTokenHash: String? get() = user?.sessionTokenHash

    private val jobs = mutableListOf<Job>()

    /**
     * Masters this connection has asked for an immediate beat frame on (`null` = master 1
     * before the bank has loaded). A set rather than a flag because each master's indicator
     * mounts independently, and consumed by `remove()` so a request really is one-shot — the
     * throttle takes over again afterwards.
     */
    val pendingBeatRequests: MutableSet<UUID?> = Collections.synchronizedSet(HashSet())

    /**
     * Surface-learn sessions originated by this connection. Bounds incoming Learn-event
     * broadcasts so two `/surfaces` tabs don't see each other's captures.
     */
    val ownedLearnSessions: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    suspend fun send(message: OutMessage) {
        try {
            session.sendSerialized<OutMessage>(message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A push racing connection teardown (client hung up mid-burst, server or test
            // app stopping) becomes a quiet cancellation: the sending job still unwinds —
            // so subscription collectors, and the boot-progress loop in Sockets.kt, stop
            // instead of pumping frames into a dead socket — but nothing reaches the
            // uncaught-exception handler with nobody left to hear it. Anything else (a
            // serialization bug in an OutMessage) stays loud and fails the session scope,
            // exactly as before, so a broken message type can't silently stale the UI.
            if (e.isConnectionTeardown()) {
                throw kotlinx.coroutines.CancellationException("WS send raced connection teardown", e)
            }
            throw e
        }
    }

    fun <T> subscribe(flow: Flow<T>, onEach: suspend (T) -> Unit) {
        jobs += flow.onEach(onEach).launchIn(session)
    }

    fun cancelAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }
}

/**
 * The failures a WS push can hit only because the connection or application is going
 * away — a closed frame channel, a broken pipe, or (in tests) the plugin registry of
 * an already-stopped test application.
 */
private fun Throwable.isConnectionTeardown(): Boolean =
    this is kotlinx.coroutines.channels.ClosedSendChannelException ||
        this is java.io.IOException ||
        this is io.ktor.server.application.MissingApplicationPluginException
