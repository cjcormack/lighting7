package uk.me.cormack.lighting7.state

import io.ktor.server.config.ApplicationConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Read an optional config string, treating absent or blank values as `null`. */
fun ApplicationConfig.optionalString(key: String): String? =
    propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }

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
