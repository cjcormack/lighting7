package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Whether a controller answers ArtPoll (cloud-p3 plan D4). */
enum class Reachability { REACHABLE, UNREACHABLE, UNKNOWN }

data class ControllerReachability(
    val state: Reachability,
    /** When it last answered, in epoch milliseconds; null when it never has. */
    val lastReplyAtMs: Long? = null,
)

/**
 * What the poller knows of the live project's controllers, keyed by each universe's address as the
 * project names it. [polling] is false while no poller reads replies: `dmx.mock`, a desk not started,
 * or 6454 held by another program without `SO_REUSEADDR`. Every controller is then unknown.
 */
data class ArtNetReachability(
    val polling: Boolean = false,
    val controllers: Map<String, ControllerReachability> = emptyMap(),
) {
    /** A controller's state; unknown for an address with none, a broadcast or a universe without one. */
    fun of(address: String?): ControllerReachability =
        address?.trim()?.let(controllers::get) ?: ControllerReachability(Reachability.UNKNOWN)
}

/**
 * Asks each controller the live project's universes name whether it is there (cloud-p3 plan D4): an
 * ArtPoll to every distinct address every [POLL_INTERVAL_MS], read back on its own `artpoll-1` thread
 * from the [socket] every universe sends through. A controller is reachable while it replied within
 * [REPLY_WINDOW_MS], and unreachable after that only once the poller has had an ArtPollReply from
 * some node since it started, proof that replies reach it; before then, inside a controller's first
 * window, or for a broadcast address, it is unknown.
 *
 * [nowMs] is a monotonic clock for every interval; [wallMs] only stamps [ControllerReachability.lastReplyAtMs].
 * While the socket is on its fallback the thread tries 6454 again every [POLL_INTERVAL_MS].
 *
 * The thread touches nothing of the show: it reads [addresses] (a `StateFlow`'s value), the socket and
 * its own maps, and publishes [state].
 */
class ArtNetPoller(
    private val socket: ArtNetSocket,
    private val addresses: () -> List<String?>,
    private val destinationPort: Int = ArtNetSocket.ART_NET_PORT,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val receiveTimeoutMs: Int = 500,
) : AutoCloseable {
    companion object {
        const val POLL_INTERVAL_MS = 10_000L
        const val REPLY_WINDOW_MS = 30_000L
        const val THREAD_NAME = "artpoll-1"

        private val log = LoggerFactory.getLogger(ArtNetPoller::class.java)
    }

    private val _state = MutableStateFlow(ArtNetReachability())
    val state: StateFlow<ArtNetReachability> = _state.asStateFlow()

    @Volatile
    private var running = false

    private var thread: Thread? = null

    private var tracker = ReachabilityTracker(REPLY_WINDOW_MS)

    /** Each address polled now; null where it is no single controller. */
    private var targets: Map<String, InetAddress?> = emptyMap()

    private var resolvedAt: Long? = null

    private val lastPolledAt = HashMap<String, Long>()

    private var bindTriedAt = 0L

    private var failing = false

    /** Binds the socket if nothing has, then reads it, or waits for 6454 if another program holds it. */
    @Synchronized
    fun start() {
        if (running) return
        tracker = ReachabilityTracker(REPLY_WINDOW_MS)
        targets = emptyMap()
        resolvedAt = null
        lastPolledAt.clear()
        failing = false
        socket.open()
        bindTriedAt = nowMs()
        if (!socket.listening) {
            log.warn("Art-Net port {} is not ours to read; every controller's reachability stays unknown until it is", socket.bindPort)
        }
        running = true
        _state.value = ArtNetReachability()
        thread = Thread(::run, THREAD_NAME).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    @Synchronized
    override fun close() {
        running = false
        thread?.join(receiveTimeoutMs * 4L)
        thread = null
        _state.value = ArtNetReachability()
    }

    private fun run() {
        val buffer = ByteArray(ArtPoll.MAX_PACKET)
        val packet = DatagramPacket(buffer, buffer.size)
        var due = true
        var turnAtNs = 0L
        while (running) {
            val datagram = socket.open() ?: break
            if (!socket.listening) {
                awaitPort()
                continue
            }
            try {
                if (due || System.nanoTime() - turnAtNs >= receiveTimeoutMs * 1_000_000L) {
                    follow()
                    pollDue()
                    publish()
                    turnAtNs = System.nanoTime()
                }
                due = false
                packet.setLength(buffer.size)
                try {
                    datagram.soTimeout = receiveTimeoutMs
                    datagram.receive(packet)
                    ArtPoll.replyFrom(packet)?.let {
                        tracker.replied(it, nowMs(), wallMs())
                        due = true
                    }
                } catch (_: SocketTimeoutException) {
                    due = true
                }
                failing = false
            } catch (e: IOException) {
                due = true
                if (datagram.isClosed) continue
                if (!failing) {
                    failing = true
                    log.warn("Art-Net poll failed; retrying", e)
                }
            } catch (e: Exception) {
                due = true
                if (!failing) {
                    failing = true
                    log.warn("Art-Net poller hit an error; carrying on", e)
                }
            }
        }
    }

    private fun awaitPort() {
        val now = nowMs()
        if (now - bindTriedAt >= POLL_INTERVAL_MS) {
            bindTriedAt = now
            if (socket.retryBind()) return
        }
        try {
            Thread.sleep(receiveTimeoutMs.toLong())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Takes up the addresses the live project names now, resolving and classing them afresh when the
     * set changes and every [POLL_INTERVAL_MS], since an interface's broadcast address can come and go.
     */
    private fun follow() {
        val wanted = addresses().mapNotNullTo(sortedSetOf()) { it?.trim()?.takeIf(String::isNotEmpty) }
        val now = nowMs()
        if (wanted == targets.keys && resolvedAt?.let { now - it < POLL_INTERVAL_MS } == true) return
        val broadcasts = localBroadcasts()
        targets = wanted.associateWith { resolveController(it, broadcasts) }
        resolvedAt = now
        lastPolledAt.keys.retainAll(wanted)
        tracker.forgetExcept(wanted)
    }

    private fun pollDue() {
        val now = nowMs()
        for ((address, to) in targets) {
            if (to == null) continue
            val last = lastPolledAt[address]
            if (last != null && now - last < POLL_INTERVAL_MS) continue
            lastPolledAt[address] = now
            tracker.polled(address, now)
            try {
                socket.send(DatagramPacket(ArtPoll.POLL, ArtPoll.POLL.size, to, destinationPort))
            } catch (e: IOException) {
                log.debug("ArtPoll to {} failed: {}", address, e.toString())
            }
        }
    }

    private fun publish() {
        val now = nowMs()
        _state.value = ArtNetReachability(
            polling = true,
            controllers = targets.mapValues { (address, to) -> tracker.of(address, to, now) },
        )
    }

    private fun resolveController(address: String, broadcasts: Set<InetAddress>): InetAddress? = try {
        InetAddress.getByName(address).takeUnless { ArtPoll.isBroadcast(it, broadcasts) }
    } catch (e: Exception) {
        log.debug("Art-Net controller address {} does not resolve; its reachability is unknown", address)
        null
    }

    private fun localBroadcasts(): Set<InetAddress> = try {
        NetworkInterface.networkInterfaces().toList()
            .flatMap { it.interfaceAddresses }
            .mapNotNullTo(HashSet()) { it.broadcast }
    } catch (_: Exception) {
        emptySet()
    }
}

/**
 * The reachability rule (cloud-p3 plan D4), on whatever monotonic clock it is handed; a reply's wall
 * time is kept only to report. One thread uses it.
 */
internal class ReachabilityTracker(private val windowMs: Long) {
    private class Reply(val atMs: Long, val wallMs: Long)

    private val firstPolledAt = HashMap<String, Long>()
    private val repliedAt = HashMap<InetAddress, Reply>()

    /** An ArtPollReply has arrived since the poller started, from any node. */
    var anyReply = false
        private set

    fun polled(address: String, nowMs: Long) {
        firstPolledAt.putIfAbsent(address, nowMs)
    }

    fun replied(from: Collection<InetAddress>, nowMs: Long, wallMs: Long = nowMs) {
        anyReply = true
        for (address in from) repliedAt[address] = Reply(nowMs, wallMs)
    }

    /** A controller leaving the project starts its window afresh if it comes back. */
    fun forgetExcept(addresses: Set<String>) {
        firstPolledAt.keys.retainAll(addresses)
    }

    fun of(address: String, controller: InetAddress?, nowMs: Long): ControllerReachability {
        if (controller == null) return ControllerReachability(Reachability.UNKNOWN)
        val last = repliedAt[controller]
        val state = when {
            last != null && nowMs - last.atMs <= windowMs -> Reachability.REACHABLE
            !anyReply -> Reachability.UNKNOWN
            firstPolledAt[address]?.let { nowMs - it >= windowMs } != true -> Reachability.UNKNOWN
            else -> Reachability.UNREACHABLE
        }
        return ControllerReachability(state, last?.wallMs)
    }
}

/** ArtPoll and ArtPollReply, from Artistic Licence's Art-Net 4 specification. */
internal object ArtPoll {
    private const val OP_POLL = 0x2000
    private const val OP_POLL_REPLY = 0x2100
    const val MAX_PACKET = 1024

    /** ID, OpCode (little-endian), ProtVer (big-endian), Flags 0 and DiagPriority 0: the 14 bytes every node accepts. */
    val POLL: ByteArray = ByteArray(14).also {
        ArtNetHeader.writeId(it)
        it[8] = OP_POLL.toByte()
        it[9] = (OP_POLL shr 8).toByte()
        it[10] = 0
        it[11] = ArtNetHeader.PROTOCOL_VERSION.toByte()
    }

    /**
     * The node an ArtPollReply came from: its source address and the IP it reports, which differ
     * behind a second interface. Null for anything else on the port, the desk's own ArtDMX included.
     */
    fun replyFrom(packet: DatagramPacket): Set<InetAddress>? {
        val data = packet.data
        val offset = packet.offset
        val length = packet.length
        if (length < 10) return null
        if (!ArtNetHeader.hasId(data, offset)) return null
        val op = (data[offset + 8].toInt() and 0xFF) or ((data[offset + 9].toInt() and 0xFF) shl 8)
        if (op != OP_POLL_REPLY) return null
        val from = mutableSetOf(packet.address)
        if (length >= 14) {
            val reported = data.copyOfRange(offset + 10, offset + 14)
            if (reported.any { it != 0.toByte() }) runCatching { InetAddress.getByAddress(reported) }.getOrNull()?.let(from::add)
        }
        return from
    }

    fun isBroadcast(address: InetAddress, localBroadcasts: Set<InetAddress>): Boolean =
        address.isMulticastAddress ||
            address.isAnyLocalAddress ||
            address.address.all { it == (-1).toByte() } ||
            address in localBroadcasts
}
