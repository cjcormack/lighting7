package uk.me.cormack.lighting7.link

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the sidecar to run is (cloud-p1 plan D10): `cloud.link.binary` when `local.conf` names one,
 * else `-Dlighting7.link` (what `./gradlew run` and the tests pass: the host's build), else the
 * installer's, beside `lighting7.jar`, named for this machine: `lighting7-link-<goos>-<goarch>`,
 * `.exe` on Windows. A Mac installer carries both architectures.
 */
object LinkBinary {
    private val log = LoggerFactory.getLogger(LinkBinary::class.java)

    /** This machine's Go target, as the sidecars are named. */
    val target: String by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val goos = when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "darwin"
            else -> "linux"
        }
        val goarch = when (val a = System.getProperty("os.arch").orEmpty().lowercase()) {
            "amd64", "x86_64", "x64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> a
        }
        "$goos-$goarch"
    }

    val fileName: String get() = "lighting7-link-$target" + if (target.startsWith("windows")) ".exe" else ""

    /** [jar] is the jar the desk runs from, null when it runs from classes (`./gradlew run`, the tests). */
    fun resolve(configured: String?, jar: Path? = runningJar()): Path? {
        configured?.let { return Path.of(it) }
        System.getProperty("lighting7.link")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        val bundled = jar?.resolveSibling(fileName) ?: return null
        if (!Files.isRegularFile(bundled)) {
            log.info("No sidecar at {}: remote sessions are unavailable", bundled)
            return null
        }
        if (!Files.isExecutable(bundled) && !bundled.toFile().setExecutable(true)) {
            log.warn("The sidecar at {} is not executable: remote sessions are unavailable", bundled)
            return null
        }
        return bundled
    }

    private fun runningJar(): Path? {
        val location = runCatching { LinkBinary::class.java.protectionDomain?.codeSource?.location?.toURI() }.getOrNull() ?: return null
        val jar = runCatching { Path.of(location) }.getOrNull() ?: return null
        return jar.takeIf { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
    }
}
