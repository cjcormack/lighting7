package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Instant

/*
 * Off-site backups (docs/cloud-protocol.md §"Backups"): the desk encrypts a whole-install archive under
 * its org's data key ([BackupCipher]) and uploads it to [CloudPaths.BACKUPS] in parts, every call
 * signed over a [BackupApiPayload]. The cloud stores ciphertext; restoring is a download and a decrypt
 * on a desk, under the key the archive's header names.
 */

/** The largest part of an upload, 64 MiB; every part but the last is exactly this. */
const val BACKUP_PART_BYTES: Int = 64 * 1024 * 1024

/**
 * How many parts a file of [sizeBytes] is uploaded in, [DeclareBackupRequest.parts]: [sizeBytes] over
 * [BACKUP_PART_BYTES], rounded up, and at least one, so an empty file is one empty part.
 */
fun backupPartCount(sizeBytes: Long): Int {
    require(sizeBytes >= 0) { "A size is not negative" }
    if (sizeBytes == 0L) return 1
    return Math.toIntExact((sizeBytes - 1) / BACKUP_PART_BYTES + 1)
}

/** Why a backup was made, its `kind`. A string on the wire, so a newer kind reads as unknown. */
object BackupKind {
    /** The desk's nightly run. */
    const val SCHEDULED: String = "SCHEDULED"

    /** The portal's *Back up now*, by [Message.BackupRequest]. */
    const val REQUESTED: String = "REQUESTED"

    /** The desk's own *Back up now*. */
    const val MANUAL: String = "MANUAL"
}

/** Where a backup is, its `status`. */
object BackupStatus {
    /** Declared, with parts still to come; the cloud aborts one left so for 24 hours. */
    const val UPLOADING: String = "UPLOADING"

    /** Every part is in and the whole matched its declared hash. */
    const val COMPLETE: String = "COMPLETE"
}

/** The org's data key, `GET` [CloudPaths.BACKUP_KEY] or [CloudPaths.backupKey]: [key] is its 32 bytes, base64url without padding. */
@Serializable
data class BackupKeyDto(val keyId: String, val key: String) {
    /** The key's bytes, or null when [key] is not 32 bytes of unpadded base64url. */
    fun keyBytes(): ByteArray? = runCatching { Base64Url.decode(key) }.getOrNull()?.takeIf { it.size == BackupCipher.KEY_BYTES }

    override fun toString(): String = "BackupKeyDto(keyId=$keyId, key=…)"
}

/**
 * Desk → cloud, `POST` [CloudPaths.BACKUPS]: a backup about to be uploaded. [sizeBytes] and [sha256]
 * (lowercase hex) are the encrypted archive's, [parts] its [backupPartCount].
 */
@Serializable
data class DeclareBackupRequest(
    /** Minted by the desk ([BackupIds]) before encrypting, so the archive's header carries it. */
    val backupId: String,
    /** A [BackupKind]. */
    val kind: String,
    val sizeBytes: Long,
    val parts: Int,
    val sha256: String,
    /** The data key it is encrypted under. */
    val keyId: String,
    val manifest: BackupManifest,
)

/** One backup, as the cloud answers it. */
@Serializable
data class BackupDto(
    val backupId: String,
    /** The install that made it. */
    val installId: String,
    /** That install's friendly name when the backup was declared. */
    val installName: String,
    /** A [BackupKind]. */
    val kind: String,
    /** A [BackupStatus]. */
    val status: String,
    val sizeBytes: Long,
    val parts: Int,
    /** The part numbers (from 1) the cloud holds, ascending; every one once [status] is [BackupStatus.COMPLETE]. */
    val partsReceived: List<Int>,
    val sha256: String,
    val keyId: String,
    val manifest: BackupManifest,
    /** A [Timestamps] string: when it was declared. */
    val createdAt: String,
    val completedAt: String? = null,
)

/** Cloud → desk, `GET` [CloudPaths.BACKUPS]: the org's complete backups, newest first. */
@Serializable
data class BackupList(val backups: List<BackupDto>)

/**
 * What a backup holds, in its archive as `manifest.json` and in its declare: enough for a restoring desk
 * to refuse one it cannot read before it downloads it. Names no secret and nothing a show holds beyond
 * file paths.
 */
@Serializable
data class BackupManifest(
    /** The desk version that made it. */
    val deskVersion: String,
    /** Its database's `PRAGMA user_version`. */
    val schemaVersion: Int,
    /** The sync `formatVersion` that desk writes. */
    val syncFormatVersion: Int,
    /** The source desk's `installs.uuid`. */
    val deskInstallUuid: String,
    val friendlyName: String,
    /** The source desk's cloud install id. */
    val installId: String,
    /** A [Timestamps] string. */
    val createdAt: String,
    /** Every file in the archive but `manifest.json`, in archive order. */
    val files: List<BackupFile>,
)

/** One archived file: its path in the archive (`/`-separated), its size and its SHA-256, lowercase hex. */
@Serializable
data class BackupFile(val path: String, val sizeBytes: Long, val sha256: String)

/** Cloud → desk, answering a confirmed replacement: the install id the desk now is, the old install's. */
@Serializable
data class ReplacedDto(val installId: String)

/**
 * A backup's id, a ULID: 26 characters of Crockford's base32, the first ten its time in milliseconds.
 * The desk mints it; the cloud only checks its form.
 */
object BackupIds {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val SHAPE = Regex("[0-7][$ALPHABET]{25}")

    fun isWellFormed(id: String): Boolean = SHAPE.matches(id)

    fun mint(now: Instant = Instant.now(), random: SecureRandom = SecureRandom()): String {
        val out = StringBuilder(26)
        var time = now.toEpochMilli()
        val timeChars = CharArray(10)
        for (i in 9 downTo 0) {
            timeChars[i] = ALPHABET[(time and 31).toInt()]
            time = time ushr 5
        }
        out.append(timeChars)
        val bytes = ByteArray(10).also(random::nextBytes)
        var bits = 0
        var acc = 0
        for (b in bytes) {
            acc = ((acc shl 8) or (b.toInt() and 0xff)) and 0xffff
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(acc ushr bits) and 31])
            }
        }
        return out.toString()
    }
}
