package uk.me.cormack.lighting7.process

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The PID file of a child process the desk supervises (lighting7-link), so the next start can stop one
 * a killed desk left running. A child must not outlive the desk: an orphaned sidecar holds a peer the
 * next one cannot know about.
 *
 * The file is the PID, the binary's path and, where the platform reports it, the process's start
 * time. [sweepOrphan] stops the recorded process only while it is alive, still running that binary
 * and, when a start time was recorded, the same process: a PID reused by anything else is left alone
 * (see CLAUDE.md on killing processes the desk didn't start).
 */
class PidFile(val path: Path, private val name: String) {
    private val log = LoggerFactory.getLogger(PidFile::class.java)

    fun write(process: Process, binary: Path) {
        runCatching {
            Files.createDirectories(path.parent)
            val started = process.toHandle().info().startInstant().map { it.toString() }.orElse("")
            Files.writeString(path, "${process.pid()}\n${binary.toAbsolutePath().normalize()}\n$started\n")
        }
    }

    fun delete() {
        runCatching { Files.deleteIfExists(path) }
    }

    /** [delete], only while the file still names [pid]: a newer launch may have written its own since. */
    fun deleteIf(pid: Long) {
        val recorded = runCatching { Files.readAllLines(path).firstOrNull()?.trim()?.toLongOrNull() }.getOrNull()
        if (recorded == pid) delete()
    }

    /** Stop the process a previous run of this desk left behind, if it is still [binary]'s, then forget it. */
    fun sweepOrphan(binary: Path) {
        val lines = runCatching { Files.readAllLines(path) }.getOrNull() ?: return
        val pid = lines.getOrNull(0)?.trim()?.toLongOrNull()
        val recorded = lines.getOrNull(1)?.trim()
        val started = lines.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        if (pid != null && recorded != null && pid != ProcessHandle.current().pid()) {
            ProcessHandle.of(pid).ifPresent { handle ->
                val info = handle.info()
                val command = info.command().orElse(null)
                val sameProcess = started == null || info.startInstant().map { it.toString() == started }.orElse(true)
                if (handle.isAlive && sameProcess && command != null && sameFile(command, recorded) && sameFile(command, binary.toString())) {
                    log.warn("Stopping {} (pid {}), left running by a previous run of this desk", name, pid)
                    handle.destroy()
                    runCatching { handle.onExit().get(3, TimeUnit.SECONDS) }
                        .onFailure { handle.destroyForcibly() }
                }
            }
        }
        delete()
    }

    private fun sameFile(a: String, b: String): Boolean {
        fun canon(s: String) = runCatching { Path.of(s).toRealPath() }.getOrElse { Path.of(s).toAbsolutePath().normalize() }
        return runCatching { canon(a) == canon(b) }.getOrDefault(false)
    }
}
