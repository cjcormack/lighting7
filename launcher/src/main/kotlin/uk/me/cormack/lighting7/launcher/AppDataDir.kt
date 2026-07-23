package uk.me.cormack.lighting7.launcher

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Application data directory for lighting7, mirrored from the backend's
 * [uk.me.cormack.lighting7.state.appDataDir]. Duplicated rather than depended on so
 * the launcher stays a tiny pure-JDK module — see launcher/build.gradle.kts. Keep the
 * resolution rules in sync with the backend copy.
 *
 * Resolution order:
 *  1. The `lighting7.dataDir` JVM system property.
 *  2. The `LIGHTING7_DATA_DIR` environment variable.
 *  3. The per-user OS default:
 *     - Windows: `%APPDATA%\lighting7\`
 *     - macOS:   `~/Library/Application Support/lighting7/`
 *     - Linux:   `~/.config/lighting7/`
 *
 * An explicit override is used verbatim (the `lighting7` leaf is not appended); a leading
 * `~` is expanded to the user's home directory. The launcher forwards its resolved value to
 * the backend child via `LIGHTING7_DATA_DIR` so both processes always agree — see LauncherMain.
 */
fun appDataDir(): Path = cachedAppDataDir

private val cachedAppDataDir: Path by lazy {
    val dir = resolveAppDataDir(rawDataDirOverride())
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

private fun rawDataDirOverride(): String? =
    System.getProperty("lighting7.dataDir")?.takeIf { it.isNotBlank() }
        ?: System.getenv("LIGHTING7_DATA_DIR")?.takeIf { it.isNotBlank() }

private fun resolveAppDataDir(override: String?): Path {
    val raw = override?.takeIf { it.isNotBlank() } ?: return defaultAppDataDir()
    return expandUserHome(raw).toAbsolutePath().normalize()
}

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
