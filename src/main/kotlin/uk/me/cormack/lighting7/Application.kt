package uk.me.cormack.lighting7

import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.launch
import uk.me.cormack.lighting7.plugins.configureHTTP
import uk.me.cormack.lighting7.plugins.configureSockets
import uk.me.cormack.lighting7.routes.configureRouting
import uk.me.cormack.lighting7.state.State
import java.awt.Color

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
        state.show.start()
    }

//    state.show.fixtures.fixture<HexFixture>("hex1").rgbColor = Color.RED
//    state.show.fixtures.fixture<HexFixture>("hex1").level = 255U
}
