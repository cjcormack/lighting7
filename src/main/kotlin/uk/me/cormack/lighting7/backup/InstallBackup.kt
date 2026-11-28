package uk.me.cormack.lighting7.backup

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.cloud.protocol.BackupCipher
import uk.me.cormack.lighting7.cloud.protocol.BackupFile
import uk.me.cormack.lighting7.cloud.protocol.BackupManifest
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.promptbook.ScriptTextStore
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Where a backup's files come from (cloud-p3 plan D5): the database, the three directories that hold
 * what the database does not, and `local.conf`, kept for the operator and never restored.
 */
data class BackupSources(
    val databasePath: Path,
    val promptScripts: Path,
    val sceneImages: Path,
    val syncTrees: Path,
    val localConf: Path?,
)

/** What a manifest names besides its files: who made the backup, and what it can be read by. */
data class BackupOrigin(
    val deskVersion: String,
    val syncFormatVersion: Int,
    val deskInstallUuid: String,
    val friendlyName: String,
    val installId: String,
)

/** An encrypted archive in staging, and what its declare says. Kept as `<backupId>.json` beside it until uploaded. */
@Serializable
data class StagedBackup(
    val backupId: String,
    val kind: String,
    val keyId: String,
    val sizeBytes: Long,
    val sha256: String,
    val manifest: BackupManifest,
    /** A [Timestamps] string: when the archive was made. */
    val madeAt: String,
    /** When the cloud first accepted its declare; null until then. */
    val declaredAt: String? = null,
)

/**
 * Makes one backup (cloud-p3 plan D5, D7): a snapshot of the database by `VACUUM INTO` on a connection
 * of its own, then a tar of it, the prompt-book PDFs, the scene images, every project's sync working
 * tree and `local.conf`, encrypted as it is written. The snapshot is the only plaintext that touches the
 * disk, in owner-only [staging], and it is gone once the archive is sealed.
 */
class InstallBackup(private val staging: Path) {
    private val logger = LoggerFactory.getLogger(InstallBackup::class.java)

    /** The staging directory, made owner-only. */
    fun prepareStaging(): Path {
        Files.createDirectories(staging)
        ownerOnly(staging)
        return staging
    }

    /**
     * Delete what a backup stopped part way left: any plaintext snapshot, and an archive with no
     * `.json` beside it (one never finished). An uploaded backup's two files are deleted by its upload.
     */
    fun sweep() {
        if (!staging.exists()) return
        Files.list(staging).use { files ->
            files.forEach { file ->
                val name = file.name
                val orphanArchive = name.endsWith(ARCHIVE_SUFFIX) &&
                    !Files.exists(staging.resolve(name.removeSuffix(ARCHIVE_SUFFIX) + META_SUFFIX))
                if (name.endsWith(SNAPSHOT_SUFFIX) || name.endsWith(PART_SUFFIX) || orphanArchive) {
                    runCatching { Files.deleteIfExists(file) }.onFailure { logger.warn("Couldn't sweep {} from backup staging", name, it) }
                }
            }
        }
    }

    /** The backup staged and not yet uploaded, if there is one. */
    fun staged(): StagedBackup? {
        if (!staging.exists()) return null
        return Files.list(staging).use { files -> files.filter { it.name.endsWith(META_SUFFIX) }.toList() }
            .mapNotNull { meta ->
                runCatching { CloudJson.decodeFromString(StagedBackup.serializer(), Files.readString(meta)) }.getOrNull()
                    ?.takeIf { Files.exists(archiveOf(it.backupId)) }
                    ?: run { discard(meta.name.removeSuffix(META_SUFFIX)); null }
            }
            .maxByOrNull { it.madeAt }
    }

    fun archiveOf(backupId: String): Path = staging.resolve(backupId + ARCHIVE_SUFFIX)

    fun record(staged: StagedBackup) {
        val meta = staging.resolve(staged.backupId + META_SUFFIX)
        val temp = staging.resolve(staged.backupId + META_SUFFIX + ".tmp")
        Files.writeString(temp, CloudJson.encodeToString(StagedBackup.serializer(), staged))
        Files.move(temp, meta, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Remove a staged backup's archive and its record. */
    fun discard(backupId: String) {
        Files.deleteIfExists(archiveOf(backupId))
        Files.deleteIfExists(staging.resolve(backupId + META_SUFFIX))
    }

    /**
     * Make the archive for [backupId] under [key] ([keyId]), staged and recorded; any other staged
     * backup is discarded first, so staging never holds more than one.
     */
    fun make(
        sources: BackupSources,
        origin: BackupOrigin,
        backupId: String,
        kind: String,
        key: ByteArray,
        keyId: String,
        now: Instant,
    ): StagedBackup {
        prepareStaging()
        staged()?.let { discard(it.backupId) }
        sweep()
        val snapshot = staging.resolve(backupId + SNAPSHOT_SUFFIX)
        val partial = staging.resolve(backupId + PART_SUFFIX)
        try {
            val schemaVersion = snapshotDatabase(sources.databasePath, snapshot)
            val files = mutableListOf<BackupFile>()
            val digest = MessageDigest.getInstance("SHA-256")
            val counting = CountingStream(Files.newOutputStream(partial))
            val sealed = BackupCipher.encryptingStream(DigestOutputStream(counting, digest), key, keyId, backupId)
            var written: BackupManifest? = null
            BufferedOutputStream(sealed, 1 shl 16).use { buffered ->
                val tar = TarWriter(buffered)
                files += addFile(tar, DATABASE_ENTRY, snapshot)
                Files.delete(snapshot)
                // The scripts' text indexes are a cache, rebuilt from the PDFs after a restore.
                files += addTree(tar, PROMPT_SCRIPTS, sources.promptScripts) { !ScriptTextStore.isIndexFile(it.fileName.toString()) }
                files += addTree(tar, SCENE_IMAGES, sources.sceneImages)
                files += addTree(tar, SYNC, sources.syncTrees)
                sources.localConf?.takeIf { Files.isRegularFile(it) }?.let { files += addFile(tar, LOCAL_CONF_ENTRY, it) }
                val manifest = BackupManifest(
                    deskVersion = origin.deskVersion,
                    schemaVersion = schemaVersion,
                    syncFormatVersion = origin.syncFormatVersion,
                    deskInstallUuid = origin.deskInstallUuid,
                    friendlyName = origin.friendlyName,
                    installId = origin.installId,
                    createdAt = Timestamps.format(now),
                    files = files,
                )
                val manifestBytes = CloudJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
                tar.file(MANIFEST_ENTRY, manifestBytes.size.toLong(), now.epochSecond) { it.write(manifestBytes) }
                tar.finish()
                buffered.flush()
                written = manifest
            }
            val manifest = checkNotNull(written)
            Files.move(partial, archiveOf(backupId), StandardCopyOption.ATOMIC_MOVE)
            val staged = StagedBackup(
                backupId = backupId,
                kind = kind,
                keyId = keyId,
                sizeBytes = counting.count,
                sha256 = digest.digest().toHex(),
                manifest = manifest,
                madeAt = Timestamps.format(now),
            )
            record(staged)
            return staged
        } finally {
            Files.deleteIfExists(snapshot)
            Files.deleteIfExists(partial)
        }
    }

    /**
     * `VACUUM INTO` [target] on a connection of its own, never the pool's, so the show's one pooled
     * connection stays free; answers the snapshot's `user_version`.
     */
    private fun snapshotDatabase(database: Path, target: Path): Int {
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = $SNAPSHOT_BUSY_TIMEOUT_MS")
                statement.execute("VACUUM INTO '${target.toString().replace("'", "''")}'")
            }
        }
        ownerOnly(target)
        return DriverManager.getConnection("jdbc:sqlite:$target").use { connection ->
            connection.createStatement().use { it.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
        }
    }

    private fun addTree(tar: TarWriter, root: String, dir: Path, include: (Path) -> Boolean = { true }): List<BackupFile> {
        if (!dir.isDirectory()) return emptyList()
        val files = Files.walk(dir).use { walk ->
            walk.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && include(it) }.toList()
        }.sortedBy { dir.relativize(it).toString() }
        return addFiles(tar, root, dir, files)
    }

    /** [files] under [dir] as `root/…`; one deleted since it was listed (a sync tidying its tree) is left out. */
    internal fun addFiles(tar: TarWriter, root: String, dir: Path, files: List<Path>): List<BackupFile> =
        files.mapNotNull { file ->
            val path = "$root/" + dir.relativize(file).joinToString("/") { it.toString() }
            try {
                addFile(tar, path, file)
            } catch (e: NoSuchFileException) {
                null
            }
        }

    /** The file is opened before anything of it is written, so one that has gone writes nothing. */
    private fun addFile(tar: TarWriter, path: String, file: Path): BackupFile {
        val digest = MessageDigest.getInstance("SHA-256")
        val size: Long
        Files.newByteChannel(file).use { channel ->
            size = channel.size()
            val input = Channels.newInputStream(channel)
            tar.file(path, size, Files.getLastModifiedTime(file).toInstant().epochSecond) { out ->
                val buffer = ByteArray(1 shl 16)
                var left = size
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                    if (n == -1) throw IOException("$path shrank while it was being backed up")
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    left -= n
                }
            }
        }
        return BackupFile(path, size, digest.digest().toHex())
    }

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()

        override fun close() = out.close()
    }

    companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val DATABASE_ENTRY = "lighting7.db"
        const val PROMPT_SCRIPTS = "prompt-scripts"
        const val SCENE_IMAGES = "scene-images"
        const val SYNC = "sync"
        const val LOCAL_CONF_ENTRY = "extra/local.conf"

        const val ARCHIVE_SUFFIX = ".l7bk"
        const val META_SUFFIX = ".json"
        private const val SNAPSHOT_SUFFIX = ".snapshot.db"
        private const val PART_SUFFIX = ".l7bk.part"

        /** How long the snapshot waits for a writer to finish before it gives up. */
        const val SNAPSHOT_BUSY_TIMEOUT_MS = 10_000

        private fun ownerOnly(path: Path) {
            runCatching {
                val perms = if (Files.isDirectory(path)) "rwx------" else "rw-------"
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms))
            }
        }

        internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
