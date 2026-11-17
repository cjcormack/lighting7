package uk.me.cormack.lighting7.backup

import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.backup.InstallBackup.Companion.toHex
import uk.me.cormack.lighting7.cloud.CloudErrorCodes
import uk.me.cormack.lighting7.cloud.protocol.BackupCipher
import uk.me.cormack.lighting7.cloud.protocol.BackupCipherException
import uk.me.cormack.lighting7.cloud.protocol.BackupFile
import uk.me.cormack.lighting7.cloud.protocol.BackupManifest
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.io.path.exists

/** What a ready restore is, written beside its tree as `ready.json`; [RestoreApply] reads it at startup. */
@Serializable
data class RestoreMarker(
    val backupId: String,
    /**
     * The backup's cloud links still name this desk's cloud identity: true after a replacement, which
     * moved that identity here; false for a backup alone, whose links the apply clears.
     */
    val keepLinks: Boolean,
    val manifest: BackupManifest,
)

/** A backup refused before anything on the desk changed, with the code the restore answers. */
class RestoreRefused(val code: String, message: String) : Exception(message)

/** A restore that failed while the desk started, kept as `failed.json` until the admin's next confirm. */
@Serializable
data class RestoreFailure(val at: String, val message: String)

/**
 * The restore's staging (cloud-p3 plan D9), `<backups>/restore/`: the downloaded archive, then its tree
 * decrypted into `incoming/`, every file checked against the manifest, then moved to `verified/` beside
 * `verified.json`. Only once a replacement the restore asks for is confirmed is it moved to `ready/`
 * beside its `ready.json`, which the next start applies; a start sweeps a `verified/` tree. Owner-only,
 * since the tree is plaintext; everything in it but [failed] is deleted once the restore is applied or
 * declined.
 */
class RestoreStaging(val root: Path) {
    val download: Path get() = root.resolve("download.l7bk")
    val incoming: Path get() = root.resolve("incoming")
    val verified: Path get() = root.resolve("verified")
    val verifiedJson: Path get() = root.resolve("verified.json")
    val ready: Path get() = root.resolve("ready")
    val marker: Path get() = root.resolve("ready.json")
    val failed: Path get() = root.resolve("failed.json")

    fun prepare() {
        Files.createDirectories(root)
        ownerOnly(root)
    }

    /** The restore staged for the next start, if one is. */
    fun readyMarker(): RestoreMarker? = if (ready.exists()) readMarker(marker) else null

    /** A checked tree waiting for its replacement to be confirmed, if one is. */
    fun verifiedMarker(): RestoreMarker? = if (verified.exists()) readMarker(verifiedJson) else null

    private fun readMarker(path: Path): RestoreMarker? {
        if (!path.exists()) return null
        return runCatching { CloudJson.decodeFromString(RestoreMarker.serializer(), Files.readString(path)) }.getOrNull()
    }

    /** Everything a restore left here but [failed]: a download, a half-checked tree, a checked or ready one. */
    fun clear() {
        if (!root.exists()) return
        root.toFile().listFiles()?.filter { it.toPath() != failed }?.forEach { it.deleteRecursively() }
    }

    /** What a download or an extraction left part way, and a checked tree never marked ready, keeping a ready restore. */
    fun sweepPartial() {
        Files.deleteIfExists(download)
        if (incoming.exists()) incoming.toFile().deleteRecursively()
        if (verified.exists()) verified.toFile().deleteRecursively()
        Files.deleteIfExists(verifiedJson)
        if (readyMarker() == null) {
            if (ready.exists()) ready.toFile().deleteRecursively()
            Files.deleteIfExists(marker)
        }
    }

    /**
     * Decrypt [download] under [key] into `incoming/`, checking every file against the manifest it ends
     * with, then move it to `verified/` beside `verified.json`. Refuses ([RestoreRefused]) a file that does
     * not decrypt, an entry outside the archive's layout, a manifest that does not name exactly what came,
     * and a backup newer than [readable] allows; nothing outside this directory is touched either way.
     */
    fun extract(backupId: String, key: ByteArray, keepLinks: Boolean, readable: (BackupManifest) -> String?): RestoreMarker {
        if (incoming.exists()) incoming.toFile().deleteRecursively()
        Files.createDirectories(incoming)
        ownerOnly(incoming)
        try {
            val manifest = BufferedInputStream(Files.newInputStream(download), 1 shl 16).use { raw ->
                val plain = try {
                    BackupCipher.decryptingStream(raw, backupId) { key }
                } catch (e: BackupCipherException) {
                    throw corrupt(e.message)
                }
                readArchive(plain)
            }
            readable(manifest)?.let { throw RestoreRefused(ErrorCode.BACKUP_TOO_NEW, it) }
            if (verified.exists()) verified.toFile().deleteRecursively()
            Files.deleteIfExists(verifiedJson)
            Files.move(incoming, verified, StandardCopyOption.ATOMIC_MOVE)
            val restore = RestoreMarker(backupId, keepLinks, manifest)
            writeJson(verifiedJson, CloudJson.encodeToString(RestoreMarker.serializer(), restore))
            return restore
        } catch (e: Exception) {
            if (incoming.exists()) incoming.toFile().deleteRecursively()
            throw e
        } finally {
            Files.deleteIfExists(download)
        }
    }

    /** The checked tree marked ready for the next start: `verified/` moved to `ready/`, then `ready.json` written. */
    fun promote(): RestoreMarker {
        val restore = verifiedMarker() ?: throw IllegalStateException("No checked backup is staged")
        if (ready.exists()) ready.toFile().deleteRecursively()
        Files.deleteIfExists(marker)
        Files.move(verified, ready, StandardCopyOption.ATOMIC_MOVE)
        writeJson(marker, CloudJson.encodeToString(RestoreMarker.serializer(), restore))
        Files.deleteIfExists(verifiedJson)
        return restore
    }

    fun recordFailure(failure: RestoreFailure) {
        prepare()
        writeJson(failed, CloudJson.encodeToString(RestoreFailure.serializer(), failure))
    }

    fun readFailure(): RestoreFailure? =
        if (!failed.exists()) null
        else runCatching { CloudJson.decodeFromString(RestoreFailure.serializer(), Files.readString(failed)) }.getOrNull()

    fun clearFailure() {
        Files.deleteIfExists(failed)
    }

    private fun writeJson(target: Path, text: String) {
        val temp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(temp, text)
        ownerOnly(temp)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readArchive(plain: InputStream): BackupManifest {
        val tar = TarReader(plain)
        val seen = mutableListOf<BackupFile>()
        var manifest: BackupManifest? = null
        try {
            while (true) {
                val entry = tar.next() ?: break
                if (manifest != null) throw corrupt("The archive goes on past its manifest")
                if (entry.path == InstallBackup.MANIFEST_ENTRY) {
                    if (entry.size > MAX_MANIFEST_BYTES) throw corrupt("The manifest is too large")
                    val text = entry.content.readAllBytes().decodeToString()
                    manifest = runCatching { CloudJson.decodeFromString(BackupManifest.serializer(), text) }.getOrNull()
                        ?: throw corrupt("The manifest can't be read")
                    continue
                }
                if (!isRestorablePath(entry.path)) throw corrupt("The archive holds ${entry.path}, which no backup holds")
                if (seen.any { it.path == entry.path }) throw corrupt("The archive holds ${entry.path} twice")
                seen += copyEntry(entry)
            }
            // The last chunk's tag is checked only at the stream's end.
            val buffer = ByteArray(8192)
            while (plain.read(buffer) != -1) continue
        } catch (e: BackupCipherException) {
            throw corrupt(e.message)
        } catch (e: IOException) {
            throw corrupt(e.message)
        }
        val found = manifest ?: throw corrupt("The archive has no manifest")
        if (found.files != seen) throw corrupt("The archive's files are not the ones its manifest names")
        if (seen.none { it.path == InstallBackup.DATABASE_ENTRY }) throw corrupt("The archive has no database")
        return found
    }

    /** The entry hashed as it is written under `incoming/`; `local.conf` is hashed and never written (D10). */
    private fun copyEntry(entry: TarReader.Entry): BackupFile {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        var size = 0L
        val out = if (entry.path == InstallBackup.LOCAL_CONF_ENTRY) null else {
            val target = incoming.resolve(entry.path)
            Files.createDirectories(target.parent)
            Files.newOutputStream(target)
        }
        out.use { o ->
            while (true) {
                val n = entry.content.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
                o?.write(buffer, 0, n)
                size += n
            }
        }
        return BackupFile(entry.path, size, digest.digest().toHex())
    }

    private fun corrupt(why: String?) =
        RestoreRefused(CloudErrorCodes.BACKUP_CORRUPT, "The backup is damaged, so nothing was restored" + (why?.let { ": $it" } ?: "."))

    companion object {
        private const val MAX_MANIFEST_BYTES = 64L * 1024 * 1024

        private val TREES = listOf(InstallBackup.PROMPT_SCRIPTS, InstallBackup.SCENE_IMAGES, InstallBackup.SYNC)

        /** One of the paths [InstallBackup] writes, with no segment that could leave the restore's tree. */
        fun isRestorablePath(path: String): Boolean {
            if (path == InstallBackup.DATABASE_ENTRY || path == InstallBackup.LOCAL_CONF_ENTRY) return true
            if (path.contains('\\') || path.contains('\u0000')) return false
            val segments = path.split('/')
            if (segments.size < 2 || segments.first() !in TREES) return false
            return segments.drop(1).none { it.isEmpty() || it == "." || it == ".." || it.contains(':') }
        }

        internal fun ownerOnly(path: Path) {
            runCatching {
                val perms = if (Files.isDirectory(path)) "rwx------" else "rw-------"
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms))
            }
        }
    }
}
