package uk.me.cormack.lighting7.backup

import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.cloud.protocol.BackupCipher
import uk.me.cormack.lighting7.cloud.protocol.BackupIds
import uk.me.cormack.lighting7.cloud.protocol.BackupKind
import uk.me.cormack.lighting7.cloud.protocol.BackupManifest
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The archive a backup makes (cloud-p3 plan D5): what is in it, what is not, and how it is made. */
class InstallBackupTest {
    private lateinit var dir: Path
    private lateinit var state: State
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("install-backup-")
        IntegrationTestDb.reset()
        state = State(testAppConfig())
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        dir.toFile().deleteRecursively()
    }

    private fun write(relative: String, text: String): Path =
        dir.resolve(relative).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    /** A data directory with the three roots, local.conf and everything a backup must leave out. */
    private fun sources(): BackupSources {
        write("data/prompt-scripts/p-1/abc.pdf", "%PDF-1.4 a prompt book")
        write("data/scene-images/p-1/def.png", "png bytes")
        write("data/scene-images/p-1/def.display.png", "a derived copy")
        write("data/sync/p-1/repo/project.json", """{"name":"Hamlet"}""")
        write("data/sync/p-1/repo/.git/HEAD", "ref: refs/heads/main")
        val deep = "data/sync/p-1/repo/" + "a-long-folder-name/".repeat(12) + "cue.json"
        write(deep, "{}")
        write("data/local.conf", "cloud { baseUrl = \"https://cloud.example\" }")
        for (left in listOf("credentials.enc", "script-cache/x.jar", "exports/show.zip", "sentry-browser/e.json", "link/lighting7-link.pid",
            "updates/u.msi", "logs/desk.log", "lighting7.lock", "RESET-ADMIN", "lighting7.db.pre-cue-model-x.db")) {
            write("data/$left", "must not be backed up")
        }
        return BackupSources(
            databasePath = Path.of(IntegrationTestDb.path),
            promptScripts = dir.resolve("data/prompt-scripts"),
            sceneImages = dir.resolve("data/scene-images"),
            syncTrees = dir.resolve("data/sync"),
            localConf = dir.resolve("data/local.conf"),
        )
    }

    private val origin = BackupOrigin("1.2.3", 56, "install-uuid", "Venue desk", "inst-1")

    /** Every file the archive holds, by path. */
    private fun open(backup: InstallBackup, staged: StagedBackup): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        Files.newInputStream(backup.archiveOf(staged.backupId)).use { raw ->
            val tar = TarReader(BackupCipher.decryptingStream(raw, staged.backupId) { key })
            while (true) {
                val entry = tar.next() ?: break
                files[entry.path] = entry.content.readBytes()
            }
        }
        return files
    }

    @Test
    fun `an archive holds the database, the three roots and local conf, and nothing else`() {
        seedRichProject(state)
        val sources = sources()
        val backup = InstallBackup(dir.resolve("staging"))
        val id = BackupIds.mint()
        val staged = backup.make(sources, origin, id, BackupKind.MANUAL, key, "key-1", Instant.parse("2026-11-16T04:00:00Z"))

        val files = open(backup, staged)
        val expected = setOf(
            "lighting7.db",
            "prompt-scripts/p-1/abc.pdf",
            "scene-images/p-1/def.display.png",
            "scene-images/p-1/def.png",
            "sync/p-1/repo/.git/HEAD",
            "sync/p-1/repo/" + "a-long-folder-name/".repeat(12) + "cue.json",
            "sync/p-1/repo/project.json",
            "extra/local.conf",
            "manifest.json",
        )
        assertEquals(expected, files.keys)
        assertEquals("manifest.json", files.keys.last())

        val manifest = CloudJson.decodeFromString(BackupManifest.serializer(), files.getValue("manifest.json").decodeToString())
        assertEquals(staged.manifest, manifest)
        assertEquals(files.keys.toList().dropLast(1), manifest.files.map { it.path }, "the manifest lists every file in archive order")
        for (file in manifest.files) {
            val bytes = files.getValue(file.path)
            assertEquals(file.sizeBytes, bytes.size.toLong(), file.path)
            assertEquals(file.sha256, sha256(bytes), file.path)
        }
        assertEquals("1.2.3", manifest.deskVersion)
        assertEquals("inst-1", manifest.installId)
        assertEquals("install-uuid", manifest.deskInstallUuid)
        assertEquals(liveUserVersion(), manifest.schemaVersion)

        val snapshot = dir.resolve("restored.db").also { Files.write(it, files.getValue("lighting7.db")) }
        val projects = DriverManager.getConnection("jdbc:sqlite:$snapshot").use { c ->
            c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM projects").use { rs -> rs.next(); rs.getInt(1) } }
        }
        val live = transaction(state.database) { DaoProjects.selectAll().count().toInt() }
        assertEquals(live, projects, "the snapshot is the live database")

        val raw = Files.readAllBytes(backup.archiveOf(staged.backupId))
        assertEquals(staged.sizeBytes, raw.size.toLong())
        assertEquals(staged.sha256, sha256(raw))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("SQLite format 3"), "nothing is stored in plaintext")
    }

    @Test
    fun `staging is owner-only and keeps no snapshot once the archive is sealed`() {
        val backup = InstallBackup(dir.resolve("staging"))
        val staged = backup.make(sources(), origin, BackupIds.mint(), BackupKind.MANUAL, key, "key-1", Instant.now())
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("staging"))))
        val left = Files.list(dir.resolve("staging")).use { s -> s.map { it.name }.toList() }.toSet()
        assertEquals(setOf("${staged.backupId}.l7bk", "${staged.backupId}.json"), left)
        assertEquals(staged, backup.staged())
    }

    @Test
    fun `a left-over snapshot and an unrecorded archive are swept, a staged backup kept`() {
        val backup = InstallBackup(dir.resolve("staging"))
        val staged = backup.make(sources(), origin, BackupIds.mint(), BackupKind.MANUAL, key, "key-1", Instant.now())
        val staging = dir.resolve("staging")
        Files.writeString(staging.resolve("01OLD.snapshot.db"), "SQLite format 3 plaintext")
        Files.writeString(staging.resolve("01OLD.l7bk.part"), "half an archive")
        Files.writeString(staging.resolve("01ORPHAN.l7bk"), "no record")
        backup.sweep()
        val left = Files.list(staging).use { s -> s.map { it.name }.toList() }.toSet()
        assertEquals(setOf("${staged.backupId}.l7bk", "${staged.backupId}.json"), left)
    }

    @Test
    fun `a new backup replaces the staged one, so staging holds at most one`() {
        val backup = InstallBackup(dir.resolve("staging"))
        val first = backup.make(sources(), origin, BackupIds.mint(), BackupKind.MANUAL, key, "key-1", Instant.now())
        val second = backup.make(sources(), origin, BackupIds.mint(), BackupKind.MANUAL, key, "key-1", Instant.now())
        assertEquals(second.backupId, backup.staged()?.backupId)
        assertFalse(Files.exists(backup.archiveOf(first.backupId)))
        backup.discard(second.backupId)
        assertNull(backup.staged())
    }

    @Test
    fun `a write through the pool during the snapshot completes`() {
        seedRichProject(state)
        // Enough rows that the snapshot takes a measurable time.
        transaction(state.database) {
            exec("CREATE TABLE IF NOT EXISTS backup_padding (v BLOB)")
            repeat(200) { exec("INSERT INTO backup_padding VALUES (randomblob(65536))") }
        }
        val backup = InstallBackup(dir.resolve("staging"))
        val writing = AtomicBoolean(true)
        val writes = AtomicInteger()
        val failures = AtomicInteger()
        val writer = thread(name = "pool-writer") {
            while (writing.get()) {
                runCatching { transaction(state.database) { exec("UPDATE projects SET description = 'w${writes.get()}'") } }
                    .onSuccess { writes.incrementAndGet() }
                    .onFailure { failures.incrementAndGet() }
            }
        }
        try {
            val staged = backup.make(sources(), origin, BackupIds.mint(), BackupKind.MANUAL, key, "key-1", Instant.now())
            assertNotNull(staged)
        } finally {
            writing.set(false)
            writer.join(10_000)
        }
        assertEquals(0, failures.get(), "no write through the pool failed")
        assertTrue(writes.get() > 0, "the pool wrote while the backup ran")
    }

    @Test
    fun `a file gone before its turn writes nothing, and the archive after it reads back whole`() {
        val a = write("tree/a.txt", "first")
        val c = write("tree/c.txt", "third")
        val out = ByteArrayOutputStream()
        val tar = TarWriter(out)
        val files = InstallBackup(dir.resolve("staging")).addFiles(tar, "sync", dir.resolve("tree"), listOf(a, dir.resolve("tree/b.txt"), c))
        tar.finish()

        assertEquals(listOf("sync/a.txt", "sync/c.txt"), files.map { it.path })
        assertEquals(mapOf("sync/a.txt" to "first", "sync/c.txt" to "third"), readTar(out.toByteArray()))
    }

    @Test
    fun `a long path of non-ASCII names round-trips`() {
        val path = "sync/" + "ünïcödé-földer/".repeat(20) + "çüé.json"
        val out = ByteArrayOutputStream()
        TarWriter(out).apply {
            file(path, 2, 0) { it.write("{}".toByteArray()) }
            finish()
        }
        assertEquals(mapOf(path to "{}"), readTar(out.toByteArray()))
        assertEquals("é-x", TarWriter.utf8Tail("ü-é-x", 4), "a character is never split")
    }

    private fun readTar(bytes: ByteArray): Map<String, String> {
        val reader = TarReader(bytes.inputStream())
        val files = linkedMapOf<String, String>()
        while (true) {
            val entry = reader.next() ?: break
            files[entry.path] = entry.content.readBytes().decodeToString()
        }
        return files
    }

    private fun liveUserVersion(): Int = DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { c ->
        c.createStatement().use { it.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
