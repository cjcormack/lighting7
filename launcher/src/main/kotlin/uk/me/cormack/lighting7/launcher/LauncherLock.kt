package uk.me.cormack.lighting7.launcher

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/**
 * Single-instance guard for the packaged launcher, mirroring the backend's
 * `uk.me.cormack.lighting7.state.InstanceLock` but on a *separate* `<dataDir>/launcher.lock`
 * file — the backend child takes `lighting7.lock`, so the two must not collide. Refusing
 * here, before any child JVM is spawned, turns a double-launch into one clean log line
 * instead of a second kotlin-compiler-server failing to bind its fixed port and interleaving
 * errors into the shared logs.
 */
object LauncherLock {
    // Held for the process lifetime so GC closing the channel can't release the OS lock.
    @Suppress("unused")
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /**
     * Acquire the launcher lock for [dataDir], or exit if another launcher already holds it.
     * A crash releases the OS lock automatically, so this only fires when a launcher is
     * genuinely running — exit 0 (benign: the app is already up).
     */
    fun acquireOrExit(dataDir: Path) {
        val lockFile = dataDir.resolve("launcher.lock")
        val ch = try {
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            println("Could not open launcher lock $lockFile: ${e.message}")
            exitProcess(1)
        }
        val fl = try {
            ch.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (e: IOException) {
            runCatching { ch.close() }
            println("Could not acquire launcher lock $lockFile: ${e.message}")
            exitProcess(1)
        }
        if (fl == null) {
            runCatching { ch.close() }
            println("lighting7 is already running for data dir $dataDir — exiting this instance.")
            exitProcess(0)
        }
        channel = ch
        lock = fl
        Runtime.getRuntime().addShutdownHook(
            Thread({
                runCatching { fl.release() }
                runCatching { ch.close() }
            }, "launcher-lock-release"),
        )
    }
}
