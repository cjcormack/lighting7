package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

private val logger = LoggerFactory.getLogger("MdnsService")

/**
 * Advertises the lighting7 backend on the LAN so iPad / desktop clients reach it at
 * `lighting7-<hostname>.local:<port>` without entering an IP address.
 *
 * One [JmDNS] instance per usable LAN IPv4 address; a background coroutine polls
 * [NetworkInterface.getNetworkInterfaces] and reconciles so that interfaces appearing
 * (Wi-Fi enabled mid-show, USB-Ethernet plugged in) or disappearing get picked up at
 * runtime instead of being frozen at the addresses present at startup.
 *
 * Why not [javax.jmdns.JmmDNS]: it routes through the default
 * [javax.jmdns.NetworkTopologyDiscovery], which re-includes the pseudo-interfaces
 * ([VIRTUAL_INTERFACE_PREFIXES]) we deliberately exclude. Replacing the discovery
 * delegate is a process-wide side effect, so we keep our own filter and poll instead.
 */
class MdnsService private constructor(
    private val port: Int,
    private val name: String,
    private val refreshIntervalMs: Long,
) : Closeable {
    private val instances = mutableMapOf<InetAddress, JmDNS>()
    private val scope = CoroutineScope(SupervisorJob() + CoroutineName("MdnsService") + Dispatchers.IO)
    private var pollJob: Job? = null

    private fun start() {
        reconcile()
        if (instances.isEmpty()) {
            logger.warn(
                "mDNS: no usable LAN IPv4 interface found at startup; will retry every {} ms",
                refreshIntervalMs,
            )
        }
        pollJob = scope.launch {
            while (isActive) {
                delay(refreshIntervalMs)
                runCatching { reconcile() }
                    .onFailure { logger.warn("mDNS interface reconcile failed: {}", it.message) }
            }
        }
    }

    @Synchronized
    private fun reconcile() {
        val desired = pickLanAddresses().toSet()
        val current = instances.keys

        for (addr in current - desired) {
            val jmdns = instances.remove(addr) ?: continue
            runCatching { jmdns.close() }
            logger.info("mDNS unregistered {}.local on {} (interface no longer usable)", name, addr.hostAddress)
        }

        for (addr in desired - current) {
            runCatching {
                val jmdns = JmDNS.create(addr, name)
                val info = ServiceInfo.create(SERVICE_TYPE, name, port, 0, 0, mapOf("path" to "/"))
                jmdns.registerService(info)
                instances[addr] = jmdns
                logger.info(
                    "mDNS registered: {}.{} on port {} — browse to http://{}.local:{}/ (bound to {})",
                    name, SERVICE_TYPE, port, name, port, addr.hostAddress,
                )
            }.onFailure {
                logger.warn("mDNS: failed to register on {}: {}", addr.hostAddress, it.message)
            }
        }
    }

    @Synchronized
    override fun close() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
        instances.values.forEach { runCatching { it.close() } }
        instances.clear()
    }

    companion object {
        private const val SERVICE_TYPE = "_http._tcp.local."

        private const val DEFAULT_REFRESH_INTERVAL_MS = 15_000L

        /**
         * macOS exposes a fleet of pseudo-interfaces (Apple wireless link, IPv6 tunnels,
         * Parallels / VMware / Docker bridges, AP-mode shims). They look like normal
         * UP+MULTICAST adapters to Java, but advertising on them gives clients an
         * unreachable IP. Skip anything whose name starts with one of these prefixes.
         */
        private val VIRTUAL_INTERFACE_PREFIXES = listOf(
            "awdl", "llw", "anpi", "gif", "stf", "ap",
            "bridge", "vmenet", "vmnet", "vboxnet", "utun",
        )

        fun register(
            port: Int,
            name: String,
            refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
        ): MdnsService = MdnsService(port, name, refreshIntervalMs).also { it.start() }

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

        /**
         * Every site-local IPv4 address on a real (non-virtual, multicast-capable) NIC.
         * Returning all of them lets the service advertise on every LAN the machine is
         * connected to — useful when wired + Wi-Fi are on different subnets.
         */
        private fun pickLanAddresses(): List<InetAddress> {
            return NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { iface ->
                    runCatching {
                        iface.isUp &&
                            !iface.isLoopback &&
                            !iface.isPointToPoint &&
                            iface.supportsMulticast() &&
                            VIRTUAL_INTERFACE_PREFIXES.none { iface.name.startsWith(it) }
                    }.getOrDefault(false)
                }
                .flatMap { iface -> iface.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter {
                    !it.isLoopbackAddress &&
                        !it.isLinkLocalAddress &&
                        !it.isAnyLocalAddress &&
                        it.isSiteLocalAddress
                }
                .distinct()
        }
    }
}
