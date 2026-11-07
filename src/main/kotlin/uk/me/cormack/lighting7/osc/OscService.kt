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
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * OSC in (OSC plan, `docs/osc-engineering.md`): this machine's settings and allowlist, the
 * [OscReceiver] on `osc-1`, the channel it hands messages to, the one collector that runs each
 * through [handler], the address log and the counts — and `osc.state`, which every window on the
 * desk's own listener reads.
 *
 * Started with the desk, before the show, and listening once an admin enables it; a bind failure is
 * [OscStateDto.error], never a failed startup. Nothing here takes a lock the show takes, and the receive path never waits on the
 * collector (D8): the channel holds [CHANNEL_CAPACITY] and drops the oldest, counting each drop.
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

    @Volatile
    private var listening = false

    @Volatile
    private var error: String? = null
    private var started = false
    private var loopsLaunched = false

    @Volatile
    private var loaded = false

    private val recent = ArrayDeque<OscLogRowDto>()
    private val refusedSenders = LinkedHashMap<String, OscRefusedSenderDto>()
    private val resolvedAddresses = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > MAX_RESOLVED
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
            }
        }
        load()
        applyListener()
        return settings
    }

    /** The UDP port bound now, or null when not listening — the system's choice where a test asked for none. */
    internal fun boundPort(): Int? = synchronized(lifecycle) { receiver?.boundPort?.takeIf { listening } }

    /** The addresses resolved since the desk started, lower-cased. */
    fun resolvedAddresses(): Set<String> = synchronized(data) { resolvedAddresses.keys.toSet() }

    private fun load() {
        val read = transaction(database) {
            val row = DaoOscSettings.all().firstOrNull()
            val list = DaoOscSender.all().sortedBy { it.id.value }.map { OscSenderDto(it.id.value, it.name, it.address) }
            OscSettingsDto(
                enabled = row?.enabled ?: false,
                bindAddress = row?.bindAddress,
                port = row?.port ?: OSC_DEFAULT_PORT,
                senders = list,
            )
        }
        settings = read
        loaded = true
        senders = read.senders.associate { it.address to OscSender(it.id, it.name, it.address) }
        // A sender allowed since it was refused leaves the refused list.
        synchronized(data) { refusedSenders.keys.removeAll(senders.keys) }
        changed()
    }

    /** Make the listener match [settings]: closed when off, rebound when the port or interface moved. */
    private fun applyListener() {
        synchronized(lifecycle) {
            if (!started) return
            val s = settings
            val bind = s.bindAddress?.let { InetAddress.getByName(it) }
            val running = receiver
            if (running != null && listening && s.enabled && running.matches(bind, s.port)) return
            running?.close()
            receiver = null
            listening = false
            error = null
            if (s.enabled) {
                val next = OscReceiver(bind, s.port, { senders }, ::deliver, ::refusedFrom)
                try {
                    next.start()
                    receiver = next
                    listening = true
                    logger.info("OSC listening on UDP {}:{}", s.bindAddress ?: "*", next.boundPort)
                } catch (e: Exception) {
                    next.close()
                    error = "Couldn't listen on UDP ${s.port}: ${e.message ?: e::class.simpleName}"
                    logger.warn(error)
                }
            }
        }
        changed()
    }

    private fun OscReceiver.matches(bind: InetAddress?, port: Int) = bindAddress == bind && this.port == port

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
        val address = message?.address ?: (inbound.item as OscPacket.Item.Bad).address ?: "–"
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
            if (handled.resolved) resolvedAddresses[address.lowercase()] = Unit
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
            port = settings.port,
            error = error,
            received = received.get(),
            dropped = dropped.get(),
            refused = refused.get(),
            refusedSenders = refusedSenders.values.reversed(),
            recent = recent.toList(),
        )
    }

    override fun close() {
        synchronized(lifecycle) {
            receiver?.close()
            receiver = null
            listening = false
        }
        channel.close()
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

@Serializable
data class OscSenderDto(val id: Int, val name: String, val address: String)

/** The whole document: a sender without an `id` is new, and one left out is removed. */
@Serializable
data class OscSettingsRequest(
    val enabled: Boolean,
    val bindAddress: String? = null,
    val port: Int = OSC_DEFAULT_PORT,
    val senders: List<OscSenderRequest> = emptyList(),
)

@Serializable
data class OscSenderRequest(val id: Int? = null, val name: String, val address: String)

/** `osc.state` (OSC plan §3 "Wire"). Counts are since the desk started. */
@Serializable
data class OscStateDto(
    val enabled: Boolean,
    val listening: Boolean,
    val port: Int,
    val error: String? = null,
    val received: Long,
    val dropped: Long,
    val refused: Long,
    /** Most recently refused first. */
    val refusedSenders: List<OscRefusedSenderDto>,
    /** Newest first, at most 50. */
    val recent: List<OscLogRowDto>,
)

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
