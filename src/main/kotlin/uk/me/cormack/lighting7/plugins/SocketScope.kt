package uk.me.cormack.lighting7.plugins

import io.ktor.server.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uk.me.cormack.lighting7.state.State
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
) {
    private val jobs = mutableListOf<Job>()

    /** Set on `requestBeatSync`; consumed by the beat-sync subscription on the next beat. */
    val sendNextBeat = AtomicBoolean(true)

    /**
     * Masters this connection has asked for an immediate beat frame on (`null` = master 1),
     * the keyed equivalent of [sendNextBeat]. A set rather than a flag because each master's
     * indicator mounts independently, and consumed by `remove()` so a request really is
     * one-shot — the throttle takes over again afterwards.
     */
    val pendingBeatRequests: MutableSet<UUID?> = Collections.synchronizedSet(HashSet())

    /**
     * Surface-learn sessions originated by this connection. Bounds incoming Learn-event
     * broadcasts so two `/surfaces` tabs don't see each other's captures.
     */
    val ownedLearnSessions: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** At most one open cue-edit session per connection; cleared on disconnect. */
    val cueEditSessionRef: AtomicReference<CueEditSessionState?> = AtomicReference(null)

    suspend fun send(message: OutMessage) {
        session.sendSerialized<OutMessage>(message)
    }

    fun <T> subscribe(flow: Flow<T>, onEach: suspend (T) -> Unit) {
        jobs += flow.onEach(onEach).launchIn(session)
    }

    fun cancelAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }
}
