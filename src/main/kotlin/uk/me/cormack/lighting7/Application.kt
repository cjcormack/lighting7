package uk.me.cormack.lighting7

import io.ktor.server.application.*
import io.ktor.server.netty.*
import uk.me.cormack.lighting7.plugins.configureHTTP
import uk.me.cormack.lighting7.plugins.configureSockets
import uk.me.cormack.lighting7.routes.configureRouting
import uk.me.cormack.lighting7.state.MdnsService
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.state.optionalString

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

    registerMdns(state)

    state.autoSyncScheduler.start()

    moduleWithState(state)
}

private fun Application.registerMdns(state: State) {
    val enabled = state.config.optionalString("mdns.enabled")?.toBooleanStrictOrNull() ?: true
    if (!enabled) return

    val port = environment.config.property("ktor.deployment.port").getString().toInt()
    val name = state.config.optionalString("mdns.name") ?: MdnsService.deriveServiceName()
    runCatching { MdnsService.register(port, name) }
        .onSuccess { state.attachMdns(it) }
        .onFailure { log.warn("mDNS registration failed: {}", it.message) }
}

/**
 * Wire HTTP + routing + sockets over an externally-provided [State]. Tests use this
 * to mount routes against an ephemeral Postgres without re-entering the heavyweight
 * [State.initializeShow] bootstrap. Prod continues through [module].
 */
fun Application.moduleWithState(state: State) {
    configureHTTP()
    configureSockets(state)
    configureRouting(state)
}
