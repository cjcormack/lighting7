package uk.me.cormack.lighting7.state

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
 * Returns the per-user application data directory for lighting7, creating it if missing.
 *
 * - Windows: `%APPDATA%\lighting7\`
 * - macOS:   `~/Library/Application Support/lighting7/`
 * - Linux:   `~/.config/lighting7/`
 *
 * Holds the SQLite database, logs, and a writable copy of `local.conf` once packaged.
 */
fun appDataDir(): Path {
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
    val dir = base.resolve("lighting7")
    Files.createDirectories(dir)
    return dir
}
