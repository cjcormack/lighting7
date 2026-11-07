package uk.me.cormack.lighting7.mcp

import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.moduleWithState
import uk.me.cormack.lighting7.state.State

private val log = LoggerFactory.getLogger("PublicListener")

/**
 * Start the desk's **public listener**: a second Netty server on `mcp.host:mcp.port` serving
 * [publicModule], with every request on it treated as remote. It is what `lighting7-link` forwards
 * remote sessions into. Called from `Application.module()` — production only; tests mount
 * [publicModule] on a test application directly.
 *
 * Tells the cloud where it bound, since the sidecar is started only while it is.
 */
fun startPublicListener(state: State): EmbeddedServer<*, *>? {
    val config = state.mcpConfig
    if (!config.enabled) return null
    return runCatching {
        embeddedServer(Netty, port = config.port, host = config.host) { publicModule(state) }.start(wait = false)
    }.onSuccess {
        log.info("Public listener on {}:{}", config.host, config.port)
        val port = if (config.port != 0) config.port else runBlocking { it.engine.resolvedConnectors().first().port }
        state.cloud.publicListenerBound(config.host, port)
    }.onFailure {
        // A busy port must not take the desk down with it: remote access is an extra.
        log.error("Public listener failed to start on {}:{}: {}", config.host, config.port, it.message)
    }.getOrNull()
}

/**
 * The public listener's application: [installRemoteHardening] first, so every request is marked
 * remote before anything else sees it, then the desk exactly as the LAN port serves it. See
 * `docs/mcp-engineering.md` §"The public listener".
 */
fun Application.publicModule(state: State) {
    installRemoteHardening(state)
    moduleWithState(state)
}
