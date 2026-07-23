package uk.me.cormack.lighting7

import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uk.me.cormack.lighting7.plugins.configureHTTP
import uk.me.cormack.lighting7.plugins.configureSockets
import uk.me.cormack.lighting7.routes.configureRouting
import uk.me.cormack.lighting7.state.BootPhase
import uk.me.cormack.lighting7.state.MdnsService
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.state.optionalBoolean
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

    val backgroundInit = state.config.optionalBoolean("show.backgroundInit", default = true)

    if (backgroundInit) {
        // Server-first: mount routes + sockets + static UI immediately so the loading page and
        // status endpoint serve right away, then run the slow show init (FX compile, fixtures,
        // cue pre-warm, mDNS) off the connector-accept path. Show-dependent routes return 503
        // until [State.isShowReady]; clients poll `GET /api/rest/status` / the `bootProgressState`
        // WS message to drive a loading bar. Disable with `show.backgroundInit=false`.
        //
        // Dispatchers.IO (not Default): show init calls `runBlocking` around the parallel FX
        // compile, which fans out onto Dispatchers.Default. Parking the orchestrator on an IO
        // thread keeps the whole Default pool free for those compiles — on a 2-core box, parking
        // it on Default instead would starve the very fan-out it is waiting for.
        moduleWithState(state)
        state.autoSyncScheduler.start()
        launch(Dispatchers.IO) { runShowStartupGuarded(state) }
    } else {
        // Legacy blocking order: fully initialise the show before accepting any connections, so
        // the server never serves traffic before the rig is live. Exceptions propagate and abort
        // server startup — the deliberate fail-fast contract of this mode.
        runShowStartup(state)
        state.autoSyncScheduler.start()
        moduleWithState(state)
    }
}

/**
 * Bring the show up and advance [State.bootProgress] through its phases. In legacy mode this runs
 * inline on the module thread and any exception propagates (fail-fast). In server-first mode it is
 * wrapped by [runShowStartupGuarded], which captures failures into the boot status instead — the
 * server is already accepting connections, so surfacing the error to the loading UI beats a
 * half-open crash.
 */
private fun Application.runShowStartup(state: State) {
    state.bootProgress.update(BootPhase.SHOW_INIT, "Initializing show…", 5)
    // The Show constructor compiles the built-in FX effects, driving FX_COMPILE progress (10–55%).
    val show = state.initializeShow()
    // start() loads fixtures then pre-warms cue scripts — the slow tail after FX compilation.
    state.bootProgress.update(BootPhase.FIXTURES, "Loading fixtures & pre-warming cues…", 60)
    show.start()
    registerMdns(state)
    state.bootProgress.markReady()
    log.info("Show startup complete")
}

private fun Application.runShowStartupGuarded(state: State) {
    try {
        runShowStartup(state)
    } catch (e: Throwable) {
        state.bootProgress.markFailed(e)
        log.error("Show startup failed", e)
    }
}

private fun Application.registerMdns(state: State) {
    val enabled = state.config.optionalBoolean("mdns.enabled", default = true)
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
