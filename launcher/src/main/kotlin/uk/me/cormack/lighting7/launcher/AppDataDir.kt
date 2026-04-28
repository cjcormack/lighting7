package uk.me.cormack.lighting7.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Per-user application data directory for lighting7, mirrored from the backend's
 * [uk.me.cormack.lighting7.state.appDataDir]. Duplicated rather than depended on so
 * the launcher stays a tiny pure-JDK module — see launcher/build.gradle.kts.
 *
 * - Windows: `%APPDATA%\lighting7\`
 * - macOS:   `~/Library/Application Support/lighting7/`
 * - Linux:   `~/.config/lighting7/`
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
