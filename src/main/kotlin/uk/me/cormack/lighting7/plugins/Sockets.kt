package uk.me.cormack.lighting7.plugins

import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.state.State
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class SocketConnection(val session: WebSocketServerSession) {
    companion object {
        val lastId = AtomicInteger(0)
    }
    val name = "conn${lastId.getAndIncrement()}"
}

/**
 * Configures the WebSocket plugin and the single `/api` connection endpoint.
 *
 * Lifecycle: per connection we open a [SocketScope], let each domain register its flow
 * subscriptions via `setupXxxSubscriptions(scope)`, then dispatch incoming frames into the
 * matching `handleXxx(scope, message)`. Cleanup is automatic via [SocketScope.cancelAll]
 * — domains add jobs by calling [SocketScope.subscribe], not by tracking jobs locally.
 *
 * Adding a new domain is a one-file change: define a sealed `XxxInMessage : InMessage()`
 * with leaf cases, add a `handleXxx(scope, message)` and (optionally) a
 * `setupXxxSubscriptions(scope)` in that file, then add one arm to the dispatch `when`
 * below + one call to setup. The Kotlin sealed-class exhaustiveness check enforces that
 * every leaf gets a handler arm.
 */
fun Application.configureSockets(state: State) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
    }

    routing {
        val connections = Collections.synchronizedSet<SocketConnection?>(LinkedHashSet())

        webSocket("/api") {
            val thisConnection = SocketConnection(this)
            connections += thisConnection
            val scope = SocketScope(this, state)

            val unregisterBroadcastListener = setupBroadcastSubscriptions(scope)
            setupParkSubscriptions(scope)
            setupFxSubscriptions(scope)
            setupPaletteSubscriptions(scope)
            setupProjectSubscriptions(scope)
            setupSurfaceSubscriptions(scope)
            setupCloudSyncSubscriptions(scope)

            try {
                for (frame in incoming) {
                    when (val message = converter?.deserialize<InMessage>(frame)) {
                        is ChannelInMessage -> handleChannel(scope, message)
                        is ParkInMessage -> handlePark(scope, message)
                        is FxInMessage -> handleFx(scope, message)
                        is PaletteInMessage -> handlePalette(scope, message)
                        is GroupInMessage -> handleGroup(scope, message)
                        is ProjectInMessage -> handleProject(scope, message)
                        is SurfaceInMessage -> handleSurface(scope, message)
                        is CueEditInMessage -> handleCueEdit(scope, message)
                        null -> TODO()
                    }
                }
            } finally {
                connections -= thisConnection
                scope.cancelAll()
                scope.ownedLearnSessions.toList().forEach { state.midiLearnSessionManager.cancel(it) }
                scope.ownedLearnSessions.clear()
                CueEditSessionHandler.endSessionOnDisconnect(state, scope.cueEditSessionRef)
                unregisterBroadcastListener()
            }
        }
    }
}
