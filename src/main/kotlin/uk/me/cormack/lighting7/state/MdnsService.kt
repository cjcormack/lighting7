package uk.me.cormack.lighting7.state

import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

private val logger = LoggerFactory.getLogger("MdnsService")

/**
 * Advertises the lighting7 backend on the LAN so iPad / desktop clients reach it at
 * `lighting7-<hostname>.local:<port>` without entering an IP address. Wraps a
 * [JmDNS] instance so [State.shutdown] can close it cleanly.
 */
class MdnsService private constructor(private val jmdns: JmDNS) : Closeable {
    override fun close() {
        // JmDNS.close() unregisters all registered services internally.
        runCatching { jmdns.close() }
    }

    companion object {
        /** Service type for unencrypted HTTP — every Bonjour client knows this string. */
        private const val SERVICE_TYPE = "_http._tcp.local."

        fun register(port: Int, name: String): MdnsService {
            val jmdns = JmDNS.create(InetAddress.getLocalHost())
            val info = ServiceInfo.create(SERVICE_TYPE, name, port, 0, 0, mapOf("path" to "/"))
            jmdns.registerService(info)
            logger.info("mDNS registered: {}.{} on port {}", name, SERVICE_TYPE, port)
            return MdnsService(jmdns)
        }

        /**
         * `lighting7-<sanitized-hostname>`. DNS-SD only allows `[a-z0-9-]`, so we lowercase
         * the host's short name and substitute anything else with `-`. Falls back to
         * `lighting7-host` if the local hostname lookup throws.
         */
        fun deriveServiceName(): String {
            val raw = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            val short = raw?.lowercase()?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: "host"
            val sanitized = short.replace(Regex("[^a-z0-9-]"), "-").trim('-').ifEmpty { "host" }
            return "lighting7-$sanitized"
        }
    }
}
