package uk.me.cormack.lighting7.state

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
 * `lighting7-<hostname>.local:<port>` without entering an IP address. Wraps the
 * per-interface [JmDNS] instances so [State.shutdown] can close them cleanly.
 */
class MdnsService private constructor(private val instances: List<JmDNS>) : Closeable {
    override fun close() {
        // JmDNS.close() unregisters all registered services internally.
        instances.forEach { runCatching { it.close() } }
    }

    companion object {
        /** Service type for unencrypted HTTP — every Bonjour client knows this string. */
        private const val SERVICE_TYPE = "_http._tcp.local."

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

        fun register(port: Int, name: String): MdnsService {
            val addresses = pickLanAddresses()
            if (addresses.isEmpty()) {
                logger.warn("mDNS: no usable LAN IPv4 interface found; falling back to InetAddress.getLocalHost()")
            }
            val targets = addresses.ifEmpty { listOf(InetAddress.getLocalHost()) }

            // Pass `name` as JmDNS's hostname so it publishes an A record for
            // `<name>.local` (e.g. `lighting7-selwyn.local`). Without this, JmDNS uses
            // the system short hostname (e.g. `selwyn`) and competes with the macOS
            // mDNSResponder that already owns `selwyn.local`.
            val instances = targets.map { addr ->
                val jmdns = JmDNS.create(addr, name)
                val info = ServiceInfo.create(SERVICE_TYPE, name, port, 0, 0, mapOf("path" to "/"))
                jmdns.registerService(info)
                logger.info(
                    "mDNS registered: {}.{} on port {} — browse to http://{}.local:{}/ (bound to {})",
                    name, SERVICE_TYPE, port, name, port, addr.hostAddress,
                )
                jmdns
            }
            return MdnsService(instances)
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
