package uk.me.cormack.lighting7.cloud.protocol

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupsTest {
    private val approved = """{"status":"approved","installId":"inst_01JD2R8K6P","org":{"id":"o","name":"O"},"account":{"id":"a","email":"e","displayName":"D"}"""

    private fun decodePoll(restore: String): AdoptionPollResult =
        CloudJson.decodeFromString(AdoptionPollResult.serializer(), "$approved,\"restore\":$restore}")

    @Test
    fun `an approval without restore is a P2 approval`() {
        val result = CloudJson.decodeFromString(AdoptionPollResult.serializer(), "$approved}")
        assertNull((result as AdoptionPollResult.Approved).restore)
    }

    @Test
    fun `a restore naming neither an install nor a backup is refused`() {
        assertFailsWith<IllegalArgumentException> { decodePoll("{}") }
        assertFailsWith<IllegalArgumentException> { decodePoll("""{"replacesName":"Front-of-house desk"}""") }
        assertFailsWith<IllegalArgumentException> { AdoptionRestore() }
    }

    @Test
    fun `a replaced install comes with its name`() {
        assertFailsWith<IllegalArgumentException> { decodePoll("""{"replacesInstallId":"inst_01JBX3Q5Z8"}""") }
        assertFailsWith<IllegalArgumentException> { decodePoll("""{"backupId":"01JC9T4M2Z8Q6V0B3N5K7R1W4X","replacesName":"x"}""") }
    }

    @Test
    fun `a minted backup id is a well-formed ULID carrying its time`() {
        val at = Instant.parse("2026-11-15T04:00:00Z")
        val id = BackupIds.mint(at)
        assertTrue(BackupIds.isWellFormed(id), id)
        assertEquals(26, id.length)
        assertEquals(BackupIds.mint(at).take(10), id.take(10))
        assertTrue(BackupIds.mint(at.plusMillis(1)).take(10) > id.take(10))
        assertTrue(BackupIds.isWellFormed("01JC9T4M2Z8Q6V0B3N5K7R1W4X"))
        assertFalse(BackupIds.isWellFormed("key"))
        assertFalse(BackupIds.isWellFormed("01JC9T4M2Z8Q6V0B3N5K7R1W4"))
        assertFalse(BackupIds.isWellFormed("81JC9T4M2Z8Q6V0B3N5K7R1W4X"))
        assertFalse(BackupIds.isWellFormed("01JC9T4M2Z8Q6V0B3N5K7R1W4U"))
        assertFalse(BackupIds.isWellFormed("01jc9t4m2z8q6v0b3n5k7r1w4x"))
    }

    @Test
    fun `a data key decodes only as 32 bytes of unpadded base64url, and never prints`() {
        val key = BackupKeyDto("dk_2026_11", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        assertContentEquals(ByteArray(32) { it.toByte() }, key.keyBytes())
        assertNull(key.copy(key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=").keyBytes())
        assertNull(key.copy(key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHg").keyBytes())
        assertNull(key.copy(key = "not a key").keyBytes())
        assertFalse(key.key in key.toString())
    }

    @Test
    fun `the backup paths`() {
        assertEquals("/v1/backups/key/dk_2026_11", CloudPaths.backupKey("dk_2026_11"))
        assertEquals("/v1/backups?installId=inst_01JBX3Q5Z8", CloudPaths.backupsOf("inst_01JBX3Q5Z8"))
        assertEquals("/v1/backups?installId=inst+01%2F%26", CloudPaths.backupsOf("inst 01/&"))
        assertEquals("/v1/backups/01JC9T4M2Z8Q6V0B3N5K7R1W4X", CloudPaths.backup("01JC9T4M2Z8Q6V0B3N5K7R1W4X"))
        assertEquals("/v1/backups/01JC9T4M2Z8Q6V0B3N5K7R1W4X/file", CloudPaths.backupFile("01JC9T4M2Z8Q6V0B3N5K7R1W4X"))
        assertEquals("/v1/backups/01JC9T4M2Z8Q6V0B3N5K7R1W4X/parts/3", CloudPaths.backupPart("01JC9T4M2Z8Q6V0B3N5K7R1W4X", 3))
        assertEquals("/v1/backups/01JC9T4M2Z8Q6V0B3N5K7R1W4X/complete", CloudPaths.backupComplete("01JC9T4M2Z8Q6V0B3N5K7R1W4X"))
        assertEquals("/v1/installs/inst_01JD2R8K6P/replace", CloudPaths.installReplace("inst_01JD2R8K6P"))
    }

    @Test
    fun `a backup's part count rounds up, and an empty file is one part`() {
        val part = BACKUP_PART_BYTES.toLong()
        assertEquals(1, backupPartCount(0))
        assertEquals(1, backupPartCount(1))
        assertEquals(1, backupPartCount(part))
        assertEquals(2, backupPartCount(part + 1))
        assertEquals(3, backupPartCount(3 * part))
        assertEquals(48, backupPartCount(3L * 1024 * 1024 * 1024))
        assertEquals(49, backupPartCount(3L * 1024 * 1024 * 1024 + 1))
        assertFailsWith<IllegalArgumentException> { backupPartCount(-1) }
    }

    @Test
    fun `an adoption request without capabilities is a desk that announces none`() {
        val p2 = CloudJson.decodeFromString(AdoptionRequest.serializer(), fixtureJson("adoption/adoption-request.json").toString())
        assertNull(p2.capabilities)
        val p3 = CloudJson.decodeFromString(AdoptionRequest.serializer(), fixtureJson("adoption/adoption-request-p3.json").toString())
        assertTrue(Capability.BACKUP_V1 in requireNotNull(p3.capabilities))
    }
}
