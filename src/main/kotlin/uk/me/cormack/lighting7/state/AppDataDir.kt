package uk.me.cormack.lighting7.state

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/** Read an optional config string, treating absent or blank values as `null`. */
fun ApplicationConfig.optionalString(key: String): String? =
    propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }

private val configLogger = LoggerFactory.getLogger("Config")

/**
 * Read an optional boolean flag, accepting the common HOCON/English spellings
 * (`true/false`, `yes/no`, `on/off`, `1/0`, case-insensitive). Absent or blank → [default].
 * An unrecognised non-blank value logs a warning and falls back to [default] rather than being
 * silently ignored (which is what `String.toBooleanStrictOrNull()` would do for e.g. `off`).
 */
fun ApplicationConfig.optionalBoolean(key: String, default: Boolean): Boolean {
    val raw = optionalString(key) ?: return default
    return when (raw.trim().lowercase()) {
        "true", "yes", "on", "1" -> true
        "false", "no", "off", "0" -> false
        else -> {
            configLogger.warn("Unrecognised boolean for {}: '{}' — using default {}", key, raw, default)
            default
        }
    }
}

/**
 * Returns the application data directory for lighting7, creating it if missing.
 *
 * Resolution order:
 *  1. The `lighting7.dataDir` JVM system property (`-Dlighting7.dataDir=…`).
 *  2. The `LIGHTING7_DATA_DIR` environment variable.
 *  3. The per-user OS default:
 *     - Windows: `%APPDATA%\lighting7\`
 *     - macOS:   `~/Library/Application Support/lighting7/`
 *     - Linux:   `~/.config/lighting7/`
 *
 * An explicit override (1 or 2) is used verbatim as the data directory — the `lighting7`
 * leaf is NOT appended, since the caller is naming the directory itself. A leading `~` is
 * expanded to the user's home directory. It is deliberately not a `local.conf` key: the data
 * dir must be resolvable before any config is read (the single-instance lock is taken in
 * `main` before Ktor parses config, and both the launcher and `main` look for `local.conf`
 * here).
 *
 * The result is deterministic for the process lifetime, so it is resolved and created once
 * and cached. Holds the SQLite database, logs, the single-instance lock, and a writable copy
 * of `local.conf` once packaged.
 */
fun appDataDir(): Path = cachedAppDataDir

private val cachedAppDataDir: Path by lazy {
    val dir = claimSlot(resolveAppDataDir(rawDataDirOverride()))
    try {
        Files.createDirectories(dir)
    } catch (e: FileAlreadyExistsException) {
        throw IllegalStateException(
            "lighting7 data directory $dir exists but is not a directory — " +
                "point LIGHTING7_DATA_DIR / -Dlighting7.dataDir at a directory path.",
            e,
        )
    }
    dir
}

/** Held for the life of the JVM: releasing it would let another process claim the same slot. */
private var slotLock: FileLock? = null

/**
 * With `-Dlighting7.dataDir.slots=N`, several JVMs share one data-dir root: each takes the first
 * of `slot-1` … `slot-N` whose lock file it can lock, and uses that directory. The test suite's
 * forks use this so no two share a compiled-script cache, which is written in place with no
 * lock, while each fork still finds the same directory, and so a warm cache, run after run.
 */
private fun claimSlot(root: Path): Path {
    val slots = System.getProperty("lighting7.dataDir.slots")?.toIntOrNull() ?: return root
    Files.createDirectories(root)
    for (n in 1..slots) {
        val channel = FileChannel.open(root.resolve("slot-$n.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = try {
            channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            null
        }
        if (lock != null) {
            slotLock = lock
            return root.resolve("slot-$n")
        }
        channel.close()
    }
    throw IllegalStateException("All $slots data-dir slots under $root are in use.")
}

/** Raw override string from the system property (preferred) or env var; null if neither is set non-blank. */
private fun rawDataDirOverride(): String? =
    System.getProperty("lighting7.dataDir")?.takeIf { it.isNotBlank() }
        ?: System.getenv("LIGHTING7_DATA_DIR")?.takeIf { it.isNotBlank() }

/**
 * Pure resolution of the data-dir path from an optional [override] string — no filesystem
 * side effects, so it is unit-testable. Expands a leading `~` and normalises an override to an
 * absolute path; falls back to the per-OS default when [override] is null or blank.
 */
internal fun resolveAppDataDir(override: String?): Path {
    val raw = override?.takeIf { it.isNotBlank() } ?: return defaultAppDataDir()
    return expandUserHome(raw).toAbsolutePath().normalize()
}

/** Expand a leading `~` / `~/` (or `~\` on Windows) to the user's home directory. */
private fun expandUserHome(path: String): Path {
    val home = System.getProperty("user.home")
    return when {
        path == "~" -> Paths.get(home)
        path.startsWith("~/") || path.startsWith("~" + File.separator) -> Paths.get(home, path.substring(2))
        else -> Paths.get(path)
    }
}

private fun defaultAppDataDir(): Path {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    val base = when {
        os.contains("win") -> {
            val appData = System.getenv("APPDATA")
            if (!appData.isNullOrBlank()) Paths.get(appData) else Paths.get(home, "AppData", "Roaming")
        }
        os.contains("mac") -> Paths.get(home, "Library", "Application Support")
        else -> Paths.get(home, ".config")
    }
    return base.resolve("lighting7")
}
