package uk.me.cormack.lighting7.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.process.PidFile
import uk.me.cormack.lighting7.process.WindowsJob
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The sidecar's supervision, as `cloud.state`'s `link` reports it (cloud-p1 plan D11). */
@Serializable
enum class LinkStatus {
    /** No sidecar is wanted: the channel is down, *Remote sessions* is off, or none is installed. */
    OFF,

    /** A launch is starting and has not said it serves. */
    STARTING,
    RUNNING,

    /** A launch ended; the next starts after the backoff. */
    RESTARTING,
}

/** What [LinkProcess] supervises: one launch of the sidecar at a time. [LinkSidecar] is the real one. */
interface SidecarLaunches {
    val running: Boolean

    /** Launch, unless one runs. False when the process could not be started. */
    fun start(): Boolean

    /** Stop for good. */
    fun stop()
}

/**
 * The sidecar supervised (cloud-p1 plan D11, NFR-SAFE-2): a launch that ends —
 * it exited, crashed, or was killed as hung — is followed by another after a backoff of 1 s doubling
 * to 30 s, reset once a launch has served for 60 s. The sidecar must not outlive the desk, so each
 * launch is in the desk's Windows job, a shutdown hook stops the current one, and each records itself
 * in a [PidFile], which the desk's next start sweeps (`CloudService.start`).
 *
 * [onStatus] is told the status changed, on whichever thread changed it and holding no lock of this
 * class; it reads [status] for the value.
 */
class LinkProcess<L : SidecarLaunches>(
    private val scope: CoroutineScope,
    private val onStatus: () -> Unit,
    private val pidFile: PidFile? = null,
    private val backoffMs: (attempt: Int) -> Long = ::restartBackoffMs,
    private val stableAfterMs: Long = STABLE_AFTER_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    launches: (Supervisor) -> L,
) {
    /** What a launch tells its supervisor, and how it starts its process. */
    interface Supervisor {
        /** The launch has said it serves (true), or stopped serving (false). */
        fun serving(running: Boolean)

        /** The launch ended other than by [LinkProcess.stop]. */
        fun ended()

        /** Start [command] as the sidecar's process, recorded so it cannot outlive the desk. */
        fun launch(command: List<String>): Process
    }

    private val lock = Any()
    private var closed = false
    private var restart: Job? = null
    private var attempt = 0
    private var servingSince: Long? = null
    @Volatile private var current: Process? = null

    @Volatile var status: LinkStatus = LinkStatus.OFF
        private set

    /** The current launch's process id; the safety test kills and stops it. */
    internal val pid: Long? get() = current?.pid()

    private val shutdownHook = Thread({ current?.let(::destroy) }, "link-shutdown")

    private val supervisor = object : Supervisor {
        override fun serving(running: Boolean) {
            if (!running) return
            val changed = synchronized(lock) {
                // A launch that has already ended may still say it serves; only the one starting counts.
                if (closed || status != LinkStatus.STARTING) return
                servingSince = now()
                set(LinkStatus.RUNNING)
            }
            if (changed) onStatus()
        }

        override fun ended() = this@LinkProcess.ended()

        override fun launch(command: List<String>): Process {
            val process = ProcessBuilder(command).start()
            current = process
            pidFile?.write(process, Path.of(command.first()))
            WindowsJob.assign(process, "lighting7-link")
            process.onExit().thenRun { pidFile?.deleteIf(process.pid()) }
            return process
        }
    }

    /** The launches this supervises. */
    val sidecar: L = launches(supervisor)

    init {
        runCatching { Runtime.getRuntime().addShutdownHook(shutdownHook) }
    }

    /** Launch the sidecar. */
    fun start() {
        val changed = synchronized(lock) {
            if (closed || restart != null) return
            set(LinkStatus.STARTING)
        }
        if (changed) onStatus()
        if (!sidecar.start()) ended()
    }

    /** Stop for good: no further launch, and the current one asked to exit. Never blocks. */
    fun stop() {
        synchronized(lock) {
            if (closed) return
            closed = true
            restart?.cancel()
            restart = null
            set(LinkStatus.OFF)
        }
        sidecar.stop()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        onStatus()
    }

    private fun ended() {
        val changed = synchronized(lock) {
            if (closed || restart != null) return
            val since = servingSince
            if (since != null && now() - since >= stableAfterMs) attempt = 0
            servingSince = null
            val wait = backoffMs(attempt++)
            restart = scope.launch {
                delay(wait)
                val again = synchronized(lock) {
                    if (closed) return@launch
                    restart = null
                    set(LinkStatus.STARTING)
                }
                if (again) onStatus()
                if (!sidecar.start()) ended()
            }
            set(LinkStatus.RESTARTING)
        }
        if (changed) onStatus()
    }

    /** Must hold [lock]. True when [next] is a change. */
    private fun set(next: LinkStatus): Boolean = (status != next).also { status = next }

    private fun destroy(process: Process) {
        runCatching {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    companion object {
        /** A launch that served this long resets the backoff. */
        const val STABLE_AFTER_MS = 60_000L

        /** The wait before the next launch: 1 s, doubling to 30 s. */
        fun restartBackoffMs(attempt: Int): Long = minOf(30_000L, 1_000L shl minOf(attempt, 5))
    }
}
