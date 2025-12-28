@file:OptIn(DelicateCoroutinesApi::class)

package uk.me.cormack.lighting7

import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import uk.me.cormack.lighting7.plugins.configureHTTP
import uk.me.cormack.lighting7.plugins.configureSockets
import uk.me.cormack.lighting7.routes.configureRouting
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.trackServer.TrackServer

fun main(argv: Array<String>) {
    val args = mutableListOf("-config=application.conf")
    args += argv.filter { it != "-config=application.conf" }

    if (args.none { it.startsWith("-config=") && it != "-config=application.conf" }) {
        args += "-config=local.conf"
    }

    EngineMain.main(args.toTypedArray())
}

fun Application.module() {
    val state = State(environment.config)
    configureHTTP()
    configureSockets(state)
    configureRouting(state)

    launch {
        // Initialize the show through the project manager
        // This finds or migrates the current project from the database
        val show = state.initializeShow()
        show.start()

        GlobalScope.launch {
            val port = 50051
            val server = TrackServer(port, state.show)
            server.start()
            server.blockUntilShutdown()
        }
    }
}
