package uk.me.cormack.lighting7

import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.launch
import uk.me.cormack.lighting7.dao.DatabaseFactory
import uk.me.cormack.lighting7.plugins.*
import uk.me.cormack.lighting7.show.Show

fun main(argv: Array<String>) {
    val args = mutableListOf("-config=application.conf")
    args += argv.filter { it != "-config=application.conf" }

    if (args.none { it.startsWith("-config=") && it != "-config=application.conf" }) {
        args += "-config=local.conf"
    }

    EngineMain.main(args.toTypedArray())
}

fun Application.module() {
    configureHTTP()
    DatabaseFactory.init(environment.config)
    configureSockets()
    configureRouting()

    launch {
        Show.start()
    }
}
