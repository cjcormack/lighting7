package uk.me.cormack.lighting7.update

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

private val logger = LoggerFactory.getLogger("UpdateMarkerWriter")

/**
 * Writes the backend's half of the apply handshake.
 *
 * The reader lives in the launcher (`launcher/.../UpdateMarker.kt`) and re-validates everything
 * written here. Both sides are round-tripped in one JVM by `UpdateMarkerRoundTripTest` — two
 * independently written parsers that agree only by inspection is exactly how this kind of
 * handshake breaks in the field, and neither side can be exercised on a Mac otherwise.
 *
 * Keep the field names and the schema number in step with the reader. Bumping [SCHEMA] makes
 * older launchers refuse the marker, which is the correct failure: an update applied by a
 * launcher that misunderstood the request is worse than one that didn't happen.
 */
object UpdateMarkerWriter {
    /** Must equal `UpdateMarker.SCHEMA` in the launcher. Pinned by the round-trip test. */
    const val SCHEMA = 1

    fun updatesDir(dataDir: Path): Path = dataDir.resolve("updates")

    fun stagedDir(dataDir: Path): Path = updatesDir(dataDir).resolve("staged")

    fun markerPath(dataDir: Path): Path = updatesDir(dataDir).resolve("update-apply.properties")

    fun consumedMarkerPath(dataDir: Path): Path =
        updatesDir(dataDir).resolve("update-apply.consumed.properties")

    fun resultPath(dataDir: Path): Path = updatesDir(dataDir).resolve("apply-result.properties")

    /**
     * Write the request atomically.
     *
     * The launcher polls for this file twice a second, so it must never observe a partial write —
     * hence write-to-temp then [StandardCopyOption.ATOMIC_MOVE], rather than writing in place.
     */
    fun write(
        dataDir: Path,
        targetVersion: String,
        tag: String,
        msiPath: Path,
        sha256: String,
        sizeBytes: Long,
        relaunch: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): Path {
        val dir = updatesDir(dataDir)
        Files.createDirectories(dir)

        val props = Properties().apply {
            setProperty("schema", SCHEMA.toString())
            setProperty("requestedAtMs", nowMs.toString())
            setProperty("targetVersion", targetVersion)
            setProperty("tag", tag)
            // Properties.store handles Windows backslash escaping; never hand-format this.
            setProperty("msiPath", msiPath.toAbsolutePath().normalize().toString())
            setProperty("sha256", sha256.lowercase())
            setProperty("sizeBytes", sizeBytes.toString())
            setProperty("relaunch", relaunch.toString())
        }

        val tmp = dir.resolve("update-apply.properties.tmp")
        Files.newOutputStream(tmp).use { out ->
            props.store(out, "lighting7 update request — written by the backend, consumed by the launcher.")
        }
        val marker = markerPath(dataDir)
        Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        logger.info("Wrote update marker for $targetVersion at $marker")
        return marker
    }

    /** What the PowerShell wrapper recorded about the last apply, if it ran. */
    data class ApplyResult(
        val exitCode: Int,
        val targetVersion: String,
        val finishedAtMs: Long?,
    ) {
        /** 3010 is ERROR_SUCCESS_REBOOT_REQUIRED — the install worked; Windows wants a restart. */
        val installerSucceeded: Boolean get() = exitCode == 0 || exitCode == REBOOT_REQUIRED

        companion object {
            const val REBOOT_REQUIRED = 3010
        }
    }

    fun readResult(dataDir: Path): ApplyResult? {
        val path = resultPath(dataDir)
        if (!Files.exists(path)) return null
        return try {
            val props = Properties().also { p -> Files.newInputStream(path).use { p.load(it) } }
            val exitCode = props.getProperty("exitCode")?.trim()?.toIntOrNull() ?: return null
            ApplyResult(
                exitCode = exitCode,
                targetVersion = props.getProperty("targetVersion")?.trim().orEmpty(),
                finishedAtMs = props.getProperty("finishedAtMs")?.trim()?.toLongOrNull(),
            )
        } catch (e: Exception) {
            logger.warn("Could not read $path; ignoring the previous apply result.", e)
            null
        }
    }

    fun clearResult(dataDir: Path) {
        runCatching { Files.deleteIfExists(resultPath(dataDir)) }
        runCatching { Files.deleteIfExists(consumedMarkerPath(dataDir)) }
    }
}
