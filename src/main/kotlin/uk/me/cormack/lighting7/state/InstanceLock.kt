package uk.me.cormack.lighting7.state

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("InstanceLock")

/**
 * Ensures only one lighting7 backend uses a given data directory at a time. Two instances
 * sharing a directory would corrupt the single-writer SQLite DB and race on the cloud-sync
 * working trees, so the second instance must refuse to start.
 *
 * The guard is keyed on the data *directory*, not the SQLite file. If `database.path` in
 * `local.conf` relocates the DB outside the data dir, two instances with different data dirs
 * pointed at the same DB file are not caught — keep the DB inside its data dir (the default)
 * to stay protected, or give each instance its own `database.path`.
 *
 * Implemented as an OS advisory lock ([FileChannel.tryLock]) on `<dataDir>/lighting7.lock`.
 * The lock is owned by the OS process, so a crash or `kill -9` releases it automatically —
 * there is no stale-lock file to clean up by hand. The lock file's *contents* (pid + data
 * dir) are advisory diagnostics only; correctness rests entirely on the OS lock.
 *
 * Acquired once from the backend `main` before the DB is opened. Tests construct
 * [State] directly and never call `main`, so they are unaffected.
 */
object InstanceLock {
    // Held for the process lifetime: if the channel were closed (e.g. GC'd) the OS would
    // release the lock. exitProcess on failure and the shutdown hook are the only exits.
    @Suppress("unused")
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /**
     * Acquire the single-instance lock for [dataDir]. If another instance already holds it,
     * print a diagnostic to stderr and terminate the process with exit code 1. Call once,
     * early in `main`, before opening the database.
     */
    fun acquireOrExit(dataDir: Path) {
        val lockFile = dataDir.resolve("lighting7.lock")
        val ch = try {
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            logger.error("Could not open single-instance lock file {}: {}", lockFile, e.message)
            System.err.println("lighting7: could not open lock file $lockFile: ${e.message}")
            exitProcess(1)
        }

        val fl: FileLock? = try {
            ch.tryLock()
        } catch (_: OverlappingFileLockException) {
            null // already locked by this JVM — treated the same as another process holding it
        } catch (e: IOException) {
            runCatching { ch.close() }
            logger.error("Could not acquire single-instance lock at {}: {}", lockFile, e.message)
            System.err.println("lighting7: could not acquire lock $lockFile: ${e.message}")
            exitProcess(1)
        }

        if (fl == null) {
            runCatching { ch.close() }
            System.err.println(
                """
                lighting7: another instance is already using this data directory:
                    $dataDir
                Only one instance may run per data directory (SQLite is single-writer).
                Stop the other instance, or start this one against a different directory
                with LIGHTING7_DATA_DIR=/path (or -Dlighting7.dataDir=/path).
                (The lock is an OS lock released automatically when that instance exits;
                do not delete $lockFile to work around this — it will not help.)
                """.trimIndent(),
            )
            exitProcess(1)
        }

        // Advisory diagnostics only — the OS lock, not this content, enforces exclusivity.
        runCatching {
            ch.truncate(0)
            val info = "pid=${ProcessHandle.current().pid()}\ndataDir=$dataDir\n"
            ch.write(ByteBuffer.wrap(info.toByteArray(StandardCharsets.UTF_8)))
            ch.force(true)
        }

        channel = ch
        lock = fl
        Runtime.getRuntime().addShutdownHook(Thread(::release, "instance-lock-release"))
        logger.info("Acquired single-instance lock at {}", lockFile)
    }

    private fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }
}
