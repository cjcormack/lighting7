package uk.me.cormack.lighting7.cloud

import uk.me.cormack.lighting7.cloud.protocol.HealthProject
import uk.me.cormack.lighting7.cloud.protocol.HealthSync
import uk.me.cormack.lighting7.cloud.protocol.HealthUniverse
import uk.me.cormack.lighting7.cloud.protocol.HealthUpdate
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.dmx.ArtNetReachability
import uk.me.cormack.lighting7.dmx.Reachability
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.sync.SyncHealth
import uk.me.cormack.lighting7.update.UpdateSummary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToLong
import org.slf4j.LoggerFactory

/**
 * The full heartbeat (cloud-p3 plan D2, FR-FLEET-1), assembled on the channel's thread from what is
 * already in memory: `StateFlow`s and lock-free counters, never the database and never a lock the show
 * takes. The project's name and its controllers' addresses are the only strings the operator chose;
 * nothing a show holds is read here (FR-FLEET-5).
 */
class DeskHealth(
    private val project: () -> HealthProject?,
    private val controllers: () -> List<DmxController>,
    /** The poller's view of the live project's controllers (D4); every controller unknown by default. */
    private val reachability: () -> ArtNetReachability = { ArtNetReachability() },
    private val sync: () -> SyncHealth.Summary,
    /** Null where the desk runs no update client. */
    private val update: () -> UpdateSummary?,
    private val dataDir: Path,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** The uptime and version every heartbeat carries. */
    private val bare: () -> Message.Health = ChannelConnection::bareHealth,
) {
    private class DiskReading(val atMs: Long, val freeMb: Long?)

    @Volatile private var disk: DiskReading? = null

    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Each field read on its own: one that throws is left absent, and logged once. */
    fun payload(): Message.Health = bare().copy(
        platform = DeskPlatform.current,
        project = field("project", project),
        universes = field("universes", ::universes),
        sync = field("sync") { syncOf(sync()) },
        diskFreeMb = field("diskFreeMb", ::diskFreeMb),
        update = field("update") {
            update()?.let { HealthUpdate(it.phase.name, it.lastApplySucceeded?.let(::outcomeOf), it.stagedVersion) }
        },
    )

    private fun <T> field(name: String, read: () -> T?): T? = try {
        read()
    } catch (e: Exception) {
        if (failed.add(name)) logger.warn("Couldn't read the heartbeat's {}; leaving it out", name, e)
        null
    }

    /** The live project's Art-Net universes in the order its rig registered them; absent when it has none. */
    private fun universes(): List<HealthUniverse>? {
        val reach = reachability()
        val now = nowMs()
        return controllers()
            .filterIsInstance<ArtNetController>()
            .map { controller ->
                val controllerReach = reach.of(controller.address)
                HealthUniverse(
                    universe = controller.universe.subnet * 16 + controller.universe.universe,
                    address = controller.address,
                    framesPerS = (controller.packetsPerSecond * 10).roundToLong() / 10.0,
                    reachable = when (controllerReach.state) {
                        Reachability.REACHABLE -> true
                        Reachability.UNREACHABLE -> false
                        Reachability.UNKNOWN -> null
                    },
                    lastReplyS = controllerReach.lastReplyAtMs?.let { ((now - it) / 1000).coerceAtLeast(0) },
                )
            }
            .ifEmpty { null }
    }

    /** Free space on the data directory's store, read at most once a [DISK_INTERVAL_MS]. */
    private fun diskFreeMb(): Long? {
        val now = nowMs()
        disk?.takeIf { now - it.atMs < DISK_INTERVAL_MS }?.let { return it.freeMb }
        val free = runCatching { Files.getFileStore(dataDir).usableSpace / (1024 * 1024) }.getOrNull()
        disk = DiskReading(now, free)
        return free
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DeskHealth::class.java)

        const val DISK_INTERVAL_MS = 60_000L

        private fun outcomeOf(succeeded: Boolean): String = if (succeeded) "SUCCEEDED" else "FAILED"

        internal fun syncOf(summary: SyncHealth.Summary): HealthSync? {
            if (summary.lastRunAt == null && summary.lastOkAt == null && summary.failing == null) return null
            return HealthSync(
                lastRunAt = summary.lastRunAt?.let(Timestamps::format),
                lastOkAt = summary.lastOkAt?.let(Timestamps::format),
                failingSince = summary.failing?.since?.let(Timestamps::format),
                code = summary.failing?.code,
            )
        }
    }
}
