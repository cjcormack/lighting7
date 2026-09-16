package uk.me.cormack.lighting7.plugins

import io.ktor.server.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    /**
     * This connection's identity, minted here and stable for its whole life.
     *
     * It is the `id` of this socket's row in the windows registry, what a `windows.show` addresses
     * and what a `selection.state` `source` carries. Minted server-side because the client-minted
     * `windowId` is **not** unique: a duplicated tab copies its `sessionStorage` and announces the
     * same one from a second socket (multi-screen plan D9), and two rows that cannot be told apart
     * could not be addressed apart either.
     */
    val id: String = UUID.randomUUID().toString()

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

    /**
     * The window this connection announced, or null until it does — what a `selection.*` write is
     * stamped with as its `source` (multi-screen plan D7). Set by `windows.announce`'s handler and
     * replaced on every re-announce, so a rename or a route change is reflected in the next stamp.
     */
    @Volatile
    var window: uk.me.cormack.lighting7.state.WindowRegistry.Window? = null

    /**
     * Session 1's name-only stub, kept as the **fallback** for a socket that has not announced.
     *
     * A client that predates the windows registry names itself on the selection frame itself
     * (`sourceName`), and it is remembered here so its later writes carry it without repeating it.
     * [window] wins wherever both exist. Retire both with `FU-WINDOWS-RETIRE-SOURCENAME` once
     * lighting-react's session 2 announces.
     */
    @Volatile
    var windowName: String? = null

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

    /**
     * Push a family's connect snapshot — the explicit half of the one-snapshot rule (see
     * docs/websocket-engineering.md §"Snapshot rule"). Use it for a family whose live stream is a
     * delta, or is a replay-1 `SharedFlow` and so has nothing to replay on a desk where the thing
     * has never happened; a family backed by a `StateFlow` needs nothing here, because
     * [subscribe] already delivers that flow's current value.
     *
     * A launch rather than a straight `send` because setup runs on the connection handler's own
     * coroutine, before it reaches the frame loop. Tracked in [jobs] like a subscription, so a
     * connection that hangs up mid-burst doesn't leave a send pending against a dead socket.
     */
    fun sendSnapshot(block: suspend SocketScope.() -> Unit) {
        jobs += session.launch { block() }
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
