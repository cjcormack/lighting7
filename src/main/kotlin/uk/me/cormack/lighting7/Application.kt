package uk.me.cormack.lighting7

import io.ktor.server.application.*
import io.ktor.server.netty.*
import uk.me.cormack.lighting7.plugins.configureHTTP
import uk.me.cormack.lighting7.plugins.configureSockets
import uk.me.cormack.lighting7.routes.configureRouting
import uk.me.cormack.lighting7.state.State

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

    // Initialize the show before configuring routes.
    // This compiles FX scripts and starts the show, which can be slow.
    // Doing it here ensures the server doesn't accept HTTP requests until ready.
    val show = state.initializeShow()
    show.start()

    configureHTTP()
    configureSockets(state)
    configureRouting(state)
}
