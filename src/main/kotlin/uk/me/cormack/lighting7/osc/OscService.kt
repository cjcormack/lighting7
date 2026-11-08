package uk.me.cormack.lighting7.osc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoOscSender
import uk.me.cormack.lighting7.models.DaoOscSendersTable
import uk.me.cormack.lighting7.models.DaoOscSettings
import uk.me.cormack.lighting7.models.OSC_DEFAULT_PORT
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.perf.DeskMetrics
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.FixturesChangeListener
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * OSC in (OSC plan, `docs/osc-engineering.md`): this machine's settings and allowlist, the
 * [OscReceiver] on `osc-1` and the [OscTcpListener] on the same port, the channel they hand messages to, the one collector that runs each
 * through [handler], the address log and the counts — and `osc.state`, which every window on the
 * desk's own listener reads.
 *
 * Started with the desk, before the show, and listening once an admin enables it; a bind failure is
 * [OscStateDto.error], never a failed startup. Nothing here takes a lock the show takes, and the receive path never waits on the
 * collector (D8): the channel holds [CHANNEL_CAPACITY] and drops the oldest, counting each drop.
 * A sender with replies on is answered once its message is logged, through a queue the collector
 * only offers to (D14).
 */
class OscService(
    private val database: Database,
    private val handler: OscHandler,
    private val metrics: DeskMetrics = DeskMetrics.global,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val received = AtomicLong()
    private val dropped = AtomicLong()
    private val refused = AtomicLong()

    private val channel = Channel<OscInbound>(CHANNEL_CAPACITY, BufferOverflow.DROP_OLDEST) {
        dropped.incrementAndGet()
        metrics.osc(DeskMetrics.OscResult.DROPPED)
        changed()
    }

    @Volatile
    private var senders: Map<String, OscSender> = emptyMap()

    @Volatile
    private var settings: OscSettingsDto = OscSettingsDto(enabled = false, bindAddress = null, port = OSC_DEFAULT_PORT, senders = emptyList())

    /** The receiver's lifecycle; never held while the receive thread could want [data]. */
    private val lifecycle = Any()

    /** The log, the refused senders and the received set. */
    private val data = Any()
    private var receiver: OscReceiver? = null
    private var tcp: OscTcpListener? = null

    @Volatile
    private var listening = false

    @Volatile
    private var tcpListening = false

    @Volatile
    private var error: String? = null
    private var udpError: String? = null
    private var tcpError: String? = null
    private var started = false
    private var loopsLaunched = false

    @Volatile
    private var loaded = false

    private val recent = ArrayDeque<OscLogRowDto>()
    private val refusedSenders = LinkedHashMap<String, OscRefusedSenderDto>()

    /** Every address resolved since the desk started, by project, and what it reached when last read. */
    private val resolvedAddresses = object : LinkedHashMap<Received, OscReach>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Received, OscReach>?) = size > MAX_RESOLVED
    }
    private val warnings = ArrayDeque<OscAddressWarningDto>()

    /** From the boot's clock, so a window that outlives a desk restart still reads the next as new. */
    private var nextWarningId = System.currentTimeMillis()

    private data class Received(val projectId: Int, val address: String)

    /** A change that may have moved what an address reaches; conflated, and read after [CHECK_DEBOUNCE_MS]. */
    private val checks = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var watched: Fixtures? = null

    private val listChanged = object : FixturesChangeListener {
        override fun cueStackListChanged() = addressesMayHaveMoved()
        override fun cueListChanged() = addressesMayHaveMoved()
        override fun lookListChanged() = addressesMayHaveMoved()
        override fun speedMasterListChanged() = addressesMayHaveMoved()
        override fun buskLayoutChanged(pageIds: List<Int>) = addressesMayHaveMoved()
    }

    private val _state = MutableStateFlow(snapshot())

    /** `osc.state`, republished at most [PUBLISH_INTERVAL_MS] apart. */
    val state: StateFlow<OscStateDto> = _state.asStateFlow()

    private val publish = Channel<Unit>(Channel.CONFLATED)

    /**
     * Read the settings and, when they say so, start listening. Idempotent, and safe to call again
     * after a failure: nothing counts as started until the settings are read.
     */
    fun start() {
        synchronized(lifecycle) {
            if (started) return
            if (!loopsLaunched) {
                loopsLaunched = true
                launchLoops()
            }
            load()
            started = true
        }
        applyListener()
    }

    private fun launchLoops() {
        // On the collector's thread, so a check and a message never read the received set at once.
        scope.launch {
            for (unit in checks) {
                delay(CHECK_DEBOUNCE_MS)
                checks.tryReceive()
                runCatching { checkReceived() }.onFailure { logger.warn("OSC address check failed: {}", it.toString()) }
            }
        }
        scope.launch {
            for (inbound in channel) {
                collect(inbound)
                // A channel that never empties never suspends the loop; let anything else on the
                // collector's thread run between messages.
                yield()
            }
        }
        // Not on the collector's thread: under a flood the collector is always busy, and the
        // state must still say so.
        scope.launch(Dispatchers.Default) {
            for (unit in publish) {
                _state.value = snapshot()
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    /** The stored settings, as `GET /install/osc` answers them — read from the database before [start] too. */
    fun settings(): OscSettingsDto {
        if (!loaded) load()
        return settings
    }

    /** Every problem with [request] at once, or none. */
    fun problems(request: OscSettingsRequest): List<String> = buildList {
        if (request.port !in 1024..65535) add("The port must be from 1024 to 65535")
        val bind = request.bindAddress?.trim()?.takeIf { it.isNotEmpty() }
        if (bind != null && ipLiteral(bind) == null) add("The bind address `$bind` is not an IP address")
        val seen = HashSet<String>()
        request.senders.forEachIndexed { i, sender ->
            val name = sender.name.trim()
            if (name.isEmpty()) add("Sender ${i + 1} has no name")
            if (name.length > 100) add("Sender ${i + 1}'s name is longer than 100 characters")
            val address = ipLiteral(sender.address.trim())
            when {
                address == null -> add("Sender ${i + 1}'s address `${sender.address}` is not an IP address")
                !seen.add(address) -> add("`${sender.address}` is listed twice")
            }
        }
    }

    /** Store [request] — already free of [problems] — and restart the listener if it moved. */
    fun update(request: OscSettingsRequest): OscSettingsDto {
        require(problems(request).isEmpty()) { "OSC settings are invalid" }
        transaction(database) {
            val row = DaoOscSettings.all().firstOrNull() ?: DaoOscSettings.new { updatedAt = nowUtc() }
            row.enabled = request.enabled
            row.bindAddress = request.bindAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { ipLiteral(it) }
            row.port = request.port
            row.updatedAt = nowUtc()
            val keep = request.senders.mapNotNull { it.id }
            DaoOscSendersTable.deleteWhere { DaoOscSendersTable.id notInList keep }
            for (sender in request.senders) {
                val existing = sender.id?.let { DaoOscSender.findById(it) }
                val dao = existing ?: DaoOscSender.new {}
                dao.name = sender.name.trim()
                dao.address = ipLiteral(sender.address.trim())!!
                dao.replies = sender.replies
            }
        }
        load()
        synchronized(lifecycle) { tcp?.closeUnlisted() }
        applyListener()
        return settings
    }

    /** The UDP port bound now, or null when not listening — the system's choice where a test asked for none. */
    internal fun boundPort(): Int? = synchronized(lifecycle) { receiver?.boundPort?.takeIf { listening } }

    /** The TCP port bound now, or null when not listening on TCP. */
    internal fun boundTcpPort(): Int? = synchronized(lifecycle) { tcp?.boundPort?.takeIf { tcpListening } }

    /** The addresses resolved since the desk started, lower-cased. */
    fun resolvedAddresses(): Set<String> = synchronized(data) { resolvedAddresses.keys.mapTo(LinkedHashSet()) { it.address } }

    /**
     * Watch [fixtures]' show for the changes that can move an address — a rename, a renumber, an
     * insert or delete that relabels auto numbers, a bank or pad moved, a speed master deleted — and
     * warn when an address received since the desk started now reaches something else, or nothing
     * (OSC plan §4). The show's listener only signals; the check runs on the collector.
     */
    fun attach(fixtures: Fixtures) {
        val previous = watched
        if (previous === fixtures) return
        previous?.unregisterListener(listChanged)
        fixtures.registerListener(listChanged)
        watched = fixtures
        // A reload or a switch arrives with its changes made — a sync pull's rename among them — and
        // fires no list hook of its own on the show it brings.
        addressesMayHaveMoved()
    }

    private fun addressesMayHaveMoved() {
        checks.trySend(Unit)
    }

    /** Re-read what each address received in the current project reaches, warning for each that moved. */
    internal fun checkReceived() {
        val projectId = handler.currentProject() ?: return
        val prior = synchronized(data) {
            resolvedAddresses.entries.filter { it.key.projectId == projectId }.associate { it.key.address to it.value }
        }
        if (prior.isEmpty()) return
        val now = handler.reach(projectId, prior.keys)
        val changes = prior.mapNotNull { (address, was) ->
            val next = now[address] ?: return@mapNotNull null
            val same = when {
                was is OscReach.To && next is OscReach.To -> was.identity == next.identity
                else -> was is OscReach.Gone && next is OscReach.Gone
            }
            if (same) return@mapNotNull null
            OscAddressChangeDto(
                address = address,
                was = (was as? OscReach.To)?.reached,
                now = (next as? OscReach.To)?.reached,
                reason = (next as? OscReach.Gone)?.message,
            )
        }
        synchronized(data) {
            for ((address, reach) in now) {
                val key = Received(projectId, address)
                if (resolvedAddresses.containsKey(key)) resolvedAddresses[key] = reach
            }
            if (changes.isNotEmpty()) {
                warnings.addFirst(OscAddressWarningDto(nextWarningId++, nowUtc().toIsoUtc(), changes))
                while (warnings.size > MAX_WARNINGS) warnings.removeLast()
            }
        }
        if (changes.isNotEmpty()) {
            logger.info("OSC: {} received address(es) moved: {}", changes.size, changes.joinToString { it.address })
            changed()
        }
    }

    private fun load() {
        val read = transaction(database) {
            val row = DaoOscSettings.all().firstOrNull()
            val list = DaoOscSender.all().sortedBy { it.id.value }.map { OscSenderDto(it.id.value, it.name, it.address, it.replies) }
            OscSettingsDto(
                enabled = row?.enabled ?: false,
                bindAddress = row?.bindAddress,
                port = row?.port ?: OSC_DEFAULT_PORT,
                senders = list,
            )
        }
        settings = read
        loaded = true
        senders = read.senders.associate { it.address to OscSender(it.id, it.name, it.address, it.replies) }
        // A sender allowed since it was refused leaves the refused list.
        synchronized(data) { refusedSenders.keys.removeAll(senders.keys) }
        changed()
    }

    /**
     * Make the listeners match [settings]: closed when off, rebound when the port or interface moved.
     * UDP and TCP bind the same port, each reconciled on its own: a save that moves neither leaves a
     * running one alone — a QLab TCP connection open, say — even while the other cannot bind, and
     * [OscStateDto.error] names whichever failed.
     */
    private fun applyListener() {
        synchronized(lifecycle) {
            if (!started) return
            val s = settings
            val bind = s.bindAddress?.let { InetAddress.getByName(it) }

            val udp = receiver
            if (!s.enabled || udp == null || udp.bindAddress != bind || udp.port != s.port) {
                udp?.close()
                receiver = null
                listening = false
                udpError = null
                if (s.enabled) {
                    val next = OscReceiver(bind, s.port, { senders }, ::deliver, ::refusedFrom)
                    try {
                        next.start()
                        receiver = next
                        listening = true
                        logger.info("OSC listening on UDP {}:{}", s.bindAddress ?: "*", next.boundPort)
                    } catch (e: Exception) {
                        next.close()
                        udpError = "Couldn't listen on UDP ${s.port}: ${e.message ?: e::class.simpleName}"
                        logger.warn(udpError)
                    }
                }
            }

            // On the port UDP bound, which is a test's system-chosen one where it asked for none.
            val tcpPort = receiver?.boundPort ?: s.port
            val running = tcp
            if (!s.enabled || running == null || running.bindAddress != bind || running.port != tcpPort) {
                running?.close()
                tcp = null
                tcpListening = false
                tcpError = null
                if (s.enabled) {
                    val next = OscTcpListener(bind, tcpPort, { senders }, ::deliver, ::refusedFrom)
                    try {
                        next.start()
                        tcp = next
                        tcpListening = true
                        logger.info("OSC listening on TCP {}:{}", s.bindAddress ?: "*", next.boundPort)
                    } catch (e: Exception) {
                        next.close()
                        tcpError = "Couldn't listen on TCP $tcpPort: ${e.message ?: e::class.simpleName}"
                        logger.warn(tcpError)
                    }
                }
            }
            error = listOfNotNull(udpError, tcpError).joinToString("; ").ifEmpty { null }
        }
        changed()
    }

    /** A message as though [OscReceiver] had read it, for a test that needs no socket. */
    internal fun offer(inbound: OscInbound) = deliver(inbound)

    /** Receive thread: never blocks. */
    private fun deliver(inbound: OscInbound) {
        received.incrementAndGet()
        metrics.osc(DeskMetrics.OscResult.RECEIVED)
        channel.trySend(inbound)
        changed()
    }

    /** Receive thread: one row per refused address, at most [MAX_REFUSED_SENDERS], the stalest going first. */
    private fun refusedFrom(address: String) {
        refused.incrementAndGet()
        metrics.osc(DeskMetrics.OscResult.REFUSED)
        val at = nowUtc().toIsoUtc()
        synchronized(data) {
            val prior = refusedSenders.remove(address)
            if (prior == null && refusedSenders.size >= MAX_REFUSED_SENDERS) {
                refusedSenders.remove(refusedSenders.keys.first())
            }
            refusedSenders[address] = OscRefusedSenderDto(address, (prior?.count ?: 0) + 1, at)
        }
        changed()
    }

    private fun collect(inbound: OscInbound) {
        val handled = try {
            handler.handle(inbound)
        } catch (e: Exception) {
            OscHandled(OscOutcome.FAILED, e.message ?: e::class.simpleName ?: "error")
        }
        val message = (inbound.item as? OscPacket.Item.Message)?.message
        val received = message?.address ?: (inbound.item as OscPacket.Item.Bad).address
        val address = received ?: "–"
        val row = OscLogRowDto(
            at = inbound.at.truncatedToMillis().toIsoUtc(),
            sender = inbound.sender.name,
            address = address,
            args = message?.args?.map { it.render() }.orEmpty(),
            outcome = handled.outcome,
            detail = handled.detail,
        )
        synchronized(data) {
            recent.addFirst(row)
            while (recent.size > MAX_RECENT) recent.removeLast()
            val reach = handled.reach
            val projectId = handled.projectId
            if (reach != null && projectId != null) resolvedAddresses[Received(projectId, address.lowercase())] = reach
        }
        inbound.replyTo?.let { replyTo ->
            oscReply(received, handled.outcome, handled.detail)?.let { runCatching { replyTo.send(it) } }
        }
        changed()
    }

    private fun changed() {
        publish.trySend(Unit)
    }

    private fun snapshot(): OscStateDto = synchronized(data) {
        OscStateDto(
            enabled = settings.enabled,
            listening = listening,
            tcpListening = tcpListening,
            port = settings.port,
            error = error,
            received = received.get(),
            dropped = dropped.get(),
            refused = refused.get(),
            refusedSenders = refusedSenders.values.reversed(),
            recent = recent.toList(),
            warnings = warnings.toList(),
        )
    }

    override fun close() {
        synchronized(lifecycle) {
            receiver?.close()
            tcp?.close()
            receiver = null
            tcp = null
            listening = false
            tcpListening = false
        }
        watched?.unregisterListener(listChanged)
        watched = null
        channel.close()
        checks.close()
        publish.close()
        scope.cancel()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OscService::class.java)

        const val CHANNEL_CAPACITY = 64
        const val MAX_RECENT = 50
        const val MAX_REFUSED_SENDERS = 20
        const val MAX_RESOLVED = 1_000
        const val PUBLISH_INTERVAL_MS = 250L
        const val MAX_WARNINGS = 10

        /** A burst of changes — a renumber writes every entry — is read once. */
        const val CHECK_DEBOUNCE_MS = 300L

        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /**
         * [raw] as a canonical IP literal, or null when it is not one. A name is never looked up:
         * a hostname would put DNS on the settings route, and the allowlist compares addresses.
         */
        fun ipLiteral(raw: String): String? {
            val looksLiteral = IPV4.matches(raw) || (raw.contains(':') && raw.all { it.isLetterOrDigit() || it == ':' || it == '.' })
            if (!looksLiteral) return null
            if (IPV4.matches(raw) && raw.split('.').any { it.toInt() > 255 }) return null
            return runCatching { InetAddress.getByName(raw).hostAddress }.getOrNull()
        }
    }
}

private fun Instant.truncatedToMillis(): Instant = truncatedTo(java.time.temporal.ChronoUnit.MILLIS)

/** `GET /install/osc` and the `PUT`'s answer. */
@Serializable
data class OscSettingsDto(
    val enabled: Boolean,
    /** Null listens on every interface. */
    val bindAddress: String? = null,
    val port: Int,
    val senders: List<OscSenderDto>,
)

/** [replies]: each message this sender sends is answered with a `/lighting7/reply` (D14). */
@Serializable
data class OscSenderDto(val id: Int, val name: String, val address: String, val replies: Boolean = false)

/** The whole document: a sender without an `id` is new, and one left out is removed. */
@Serializable
data class OscSettingsRequest(
    val enabled: Boolean,
    val bindAddress: String? = null,
    val port: Int = OSC_DEFAULT_PORT,
    val senders: List<OscSenderRequest> = emptyList(),
)

@Serializable
data class OscSenderRequest(val id: Int? = null, val name: String, val address: String, val replies: Boolean = false)

/** `osc.state` (OSC plan §3 "Wire"). Counts are since the desk started. */
@Serializable
data class OscStateDto(
    val enabled: Boolean,
    /** On UDP. */
    val listening: Boolean,
    /** On TCP, the same port, SLIP-framed. */
    val tcpListening: Boolean = false,
    val port: Int,
    val error: String? = null,
    val received: Long,
    val dropped: Long,
    val refused: Long,
    /** Most recently refused first. */
    val refusedSenders: List<OscRefusedSenderDto>,
    /** Newest first, at most 50. */
    val recent: List<OscLogRowDto>,
    /** Received addresses a change moved, newest first, at most 10. */
    val warnings: List<OscAddressWarningDto> = emptyList(),
)

/** One change's moved addresses: [id] increases, so a window toasts only those it has not seen. */
@Serializable
data class OscAddressWarningDto(val id: Long, val at: String, val changes: List<OscAddressChangeDto>)

/**
 * A received [address] that now reaches something else ([now]), or nothing ([now] null, [reason]
 * why); [was] what it reached when last read, null when it reached nothing then.
 */
@Serializable
data class OscAddressChangeDto(val address: String, val was: String?, val now: String?, val reason: String? = null)

@Serializable
data class OscRefusedSenderDto(val address: String, val count: Long, val lastAt: String)

@Serializable
data class OscLogRowDto(
    val at: String,
    val sender: String,
    val address: String,
    val args: List<String>,
    val outcome: OscOutcome,
    val detail: String,
)
