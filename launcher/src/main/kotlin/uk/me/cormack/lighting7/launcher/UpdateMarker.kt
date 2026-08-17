package uk.me.cormack.lighting7.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * The backend → launcher handshake for "an update is staged; quit and install it".
 *
 * The backend cannot install the update itself: it is a *child* of the launcher, and every file
 * `msiexec` needs to replace — `runtime/`, all three jars, `lighting7.exe` — is held open by the
 * launcher and its children while the backend is alive. Only the launcher can outlive them.
 *
 * There is no other IPC between the two processes, and this deliberately does not add one. The
 * launcher already polls its children every 500 ms; a file it checks in the same loop costs
 * nothing, whereas a loopback HTTP server would need `jdk.httpserver` added to the jlink module
 * list, a port to discover, and a shared secret so no other local process could stop the desk.
 *
 * **`.properties`, not JSON.** `java.util.Properties` is in `java.base` and [LauncherMain]
 * already reads `compiler-server.properties` this way. JSON would mean hand-rolling a parser in
 * the one module whose entire design rule is zero dependencies — and `Properties` handles Windows
 * backslash escaping correctly, which a hand-rolled parser gets wrong on roughly one path in a
 * hundred.
 */
object UpdateMarker {
    const val SCHEMA = 1

    private const val FILENAME = "update-apply.properties"
    private const val CONSUMED_FILENAME = "update-apply.consumed.properties"
    const val RESULT_FILENAME = "apply-result.properties"

    /** Everything the launcher is allowed to act on lives under this directory. */
    private const val STAGED_SUBPATH = "updates/staged"

    data class Request(
        val targetVersion: String,
        val tag: String,
        val msiPath: Path,
        val sha256: String,
        val sizeBytes: Long,
        val relaunch: Boolean,
    )

    fun markerPath(dataDir: Path): Path = dataDir.resolve("updates").resolve(FILENAME)

    private fun consumedPath(dataDir: Path): Path = dataDir.resolve("updates").resolve(CONSUMED_FILENAME)

    /**
     * Drop any marker left over from a previous run, before children are spawned.
     *
     * Without this, an apply that was interrupted — the machine lost power, the user killed the
     * launcher, `msiexec` never ran — would fire again on the next boot, days later, with no
     * user present to have asked for it. Quitting from the tray therefore also cancels a pending
     * update, which is the behaviour we want.
     */
    fun clearStale(dataDir: Path) {
        listOf(markerPath(dataDir), consumedPath(dataDir)).forEach { path ->
            runCatching { Files.deleteIfExists(path) }
                .onSuccess { deleted -> if (deleted) println("Cleared stale update marker $path") }
                .onFailure { println("WARNING: could not clear stale update marker $path (${it.message}).") }
        }
    }

    /**
     * Read and fully validate a pending request, or return null.
     *
     * **Never throws.** A malformed marker must leave the desk running the version it already
     * has; the app carrying on is always better than the app dying over a file it could ignore.
     *
     * Everything the backend asserted is re-checked here rather than trusted. The two processes
     * have the same privileges so this is not a security boundary, but it is the difference
     * between "refused to install a corrupt file" and "ran msiexec against 350 MB of truncated
     * download".
     */
    fun readIfPresent(
        dataDir: Path,
        launcherStartMs: Long,
        // Injectable so the protocol can be round-tripped against the backend's writer on a Mac.
        // Everything else about this handshake is platform-neutral; only the *apply* is Windows.
        isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win"),
    ): Request? {
        val path = markerPath(dataDir)
        if (!Files.exists(path)) return null

        return try {
            validate(path, dataDir, launcherStartMs, isWindows)
        } catch (e: Exception) {
            println("WARNING: update marker at $path could not be read (${e.message}); ignoring it.")
            null
        }
    }

    private fun validate(path: Path, dataDir: Path, launcherStartMs: Long, isWindows: Boolean): Request? {
        val props = Properties().also { p -> Files.newInputStream(path).use { p.load(it) } }

        fun reject(reason: String): Request? {
            println("WARNING: refusing update marker $path — $reason")
            return null
        }

        val schema = props.getProperty("schema")?.toIntOrNull()
        if (schema != SCHEMA) return reject("schema=$schema, expected $SCHEMA")

        if (!isWindows) {
            return reject("in-place update is Windows-only (os.name=${System.getProperty("os.name")})")
        }

        // A marker written before this launcher started belongs to a previous run. Combined with
        // clearStale() this is belt and braces, but the failure it prevents — installing an
        // update nobody asked for, at an arbitrary later date — is worth two guards.
        val requestedAtMs = props.getProperty("requestedAtMs")?.toLongOrNull()
            ?: return reject("requestedAtMs is missing or not a number")
        if (requestedAtMs < launcherStartMs) {
            return reject("stale: requested at $requestedAtMs, before this launcher started at $launcherStartMs")
        }

        val targetVersion = props.getProperty("targetVersion")?.takeIf { it.isNotBlank() }
            ?: return reject("targetVersion is missing")
        val tag = props.getProperty("tag")?.takeIf { it.isNotBlank() } ?: targetVersion
        val sha256 = props.getProperty("sha256")?.takeIf { it.isNotBlank() }?.lowercase()
            ?: return reject("sha256 is missing")
        val sizeBytes = props.getProperty("sizeBytes")?.toLongOrNull()
            ?: return reject("sizeBytes is missing or not a number")

        val rawPath = props.getProperty("msiPath")?.takeIf { it.isNotBlank() }
            ?: return reject("msiPath is missing")
        val msiPath = Path.of(rawPath).toAbsolutePath().normalize()

        // Path-traversal guard. The marker is a plain file in a user-writable directory, so the
        // only thing constraining what it can point at is this check.
        val stagedRoot = dataDir.resolve(STAGED_SUBPATH).toAbsolutePath().normalize()
        if (!msiPath.startsWith(stagedRoot)) {
            return reject("msiPath $msiPath is outside $stagedRoot")
        }
        if (!Files.isRegularFile(msiPath)) return reject("msiPath $msiPath is not a file")

        val actualSize = Files.size(msiPath)
        if (actualSize != sizeBytes) {
            return reject("size mismatch: marker says $sizeBytes, file is $actualSize")
        }

        val actualDigest = sha256Of(msiPath)
        if (!actualDigest.equals(sha256, ignoreCase = true)) {
            return reject("sha256 mismatch: marker says $sha256, file hashes to $actualDigest")
        }

        return Request(
            targetVersion = targetVersion,
            tag = tag,
            msiPath = msiPath,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            // Default true: a desk that silently fails to come back is far worse than one that
            // restarts when it didn't strictly need to.
            relaunch = props.getProperty("relaunch")?.toBooleanStrictOrNull() ?: true,
        )
    }

    /**
     * Rename the marker aside. Called *before* anything irreversible, so a crash between here
     * and `msiexec` cannot re-trigger the same apply on the next boot.
     */
    fun consume(dataDir: Path): Boolean =
        runCatching {
            Files.move(
                markerPath(dataDir),
                consumedPath(dataDir),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.getOrElse {
            println("WARNING: could not consume the update marker (${it.message}); not applying.")
            false
        }

    internal fun sha256Of(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
