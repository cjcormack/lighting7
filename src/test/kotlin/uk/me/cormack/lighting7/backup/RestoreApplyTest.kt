package uk.me.cormack.lighting7.backup

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.cloud.CloudErrorCodes
import uk.me.cormack.lighting7.cloud.protocol.BackupManifest
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoUsers
import uk.me.cormack.lighting7.state.SCHEMA_USER_VERSION
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The restore applied at startup (cloud-p3 plan D10): an older backup migrated first, and a failure rolled back. */
class RestoreApplyTest {
    private lateinit var dir: Path
    private var state: State? = null

    private val database: Path get() = dir.resolve("lighting7.db")
    private val staging: RestoreStaging get() = RestoreStaging(dir.resolve("backups/restore"))

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("restore-apply-")
    }

    @After
    fun tearDown() {
        runCatching { state?.shutdown() }
        dir.toFile().deleteRecursively()
    }

    private fun start(): State = State(testAppConfig(
        "database.path" to database.toString(),
        "sync.workingTreeRoot" to dir.resolve("sync").toString(),
        "promptBooks.scriptStoreRoot" to dir.resolve("prompt-scripts").toString(),
        "stage.sceneImageStoreRoot" to dir.resolve("scene-images").toString(),
        "backups.root" to dir.resolve("backups").toString(),
    )).also { state = it }

    /** A desk that ran, with its own user and a file of its own, stopped: answers its install uuid. */
    private fun runningDesk(): String {
        val desk = start()
        seedUser(desk, "carol")
        Files.createDirectories(dir.resolve("prompt-scripts/p-9"))
        Files.writeString(dir.resolve("prompt-scripts/p-9/fresh.pdf"), "the desk's own")
        val uuid = transaction(desk.database) { DaoInstall.all().first().uuid.toString() }
        desk.shutdown()
        state = null
        return uuid
    }

    /** A restore staged as a confirm leaves it, with [database] as its snapshot. */
    private fun stage(database: (Path) -> Unit) {
        val ready = staging.ready
        Files.createDirectories(ready.resolve("prompt-scripts/p-1"))
        Files.writeString(ready.resolve("prompt-scripts/p-1/book.pdf"), "restored")
        database(ready.resolve(InstallBackup.DATABASE_ENTRY))
        val manifest = BackupManifest("0.9.0", 17, 50, "old-uuid", "Old desk", "inst-old", "2026-11-13T15:20:16.713Z", emptyList())
        Files.writeString(staging.marker, CloudJson.encodeToString(RestoreMarker.serializer(), RestoreMarker("01JD0000000000000000000000", false, manifest)))
    }

    private fun userVersion(): Int = DriverManager.getConnection("jdbc:sqlite:$database").use { c ->
        c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }
    }

    private fun tables(): Set<String> = DriverManager.getConnection("jdbc:sqlite:$database").use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    @Test
    fun `a fresh database is left at the user_version a restore compares backups with`() {
        start()
        assertEquals(SCHEMA_USER_VERSION, userVersion(), "a new one-shot database step must move SCHEMA_USER_VERSION in state/MigrationSupport.kt")
    }

    @Test
    fun `a backup from an older schema is migrated before this machine's rows are written`() {
        val uuid = runningDesk()
        stage { target ->
            val sql = javaClass.getResourceAsStream("/db/pre-github-retirement.sql")!!.readBytes().toString(Charsets.UTF_8)
            DriverManager.getConnection("jdbc:sqlite:$target").use { c ->
                c.autoCommit = false
                c.createStatement().use { s ->
                    sql.split(";\n")
                        .map { chunk -> chunk.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { s.execute(it) }
                }
                c.commit()
            }
        }

        val desk = start()
        transaction(desk.database) {
            assertEquals(setOf("Sleeping Beauty", "Hall"), DaoProjects.selectAll().map { it[DaoProjects.name] }.toSet())
            assertTrue(DaoUsers.selectAll().none { it[DaoUsers.username] == "carol" }, "the accounts are the backup's")
            assertEquals(uuid, DaoInstall.all().single().uuid.toString(), "this machine's install row, written after the migrations")
        }
        assertEquals(SCHEMA_USER_VERSION, userVersion())
        assertFalse("oauth_identities" in tables(), "step 18 ran over the restored database")
        assertEquals("restored", Files.readString(dir.resolve("prompt-scripts/p-1/book.pdf")))
        assertFalse(staging.ready.exists())
        assertTrue(Files.list(dir).use { s -> s.anyMatch { it.name.startsWith("lighting7.db.pre-restore-") } }, "the old database is kept")
    }

    @Test
    fun `a failure while applying puts the desk back as it was`() {
        val uuid = runningDesk()
        stage { target -> Files.writeString(target, "this is not a database, and opening it fails") }

        val desk = start()
        transaction(desk.database) {
            assertTrue(DaoUsers.selectAll().any { it[DaoUsers.username] == "carol" }, "the desk's own accounts")
            assertEquals(uuid, DaoInstall.all().single().uuid.toString())
        }
        assertEquals("the desk's own", Files.readString(dir.resolve("prompt-scripts/p-9/fresh.pdf")))
        assertFalse(dir.resolve("prompt-scripts/p-1").exists())
        assertTrue(Files.list(dir).use { s -> s.noneMatch { it.name.contains(".pre-restore-") } }, "the kept files went back")
        assertFalse(staging.ready.exists(), "a failed restore is not tried again")

        desk.restores.start()
        assertEquals(CloudErrorCodes.RESTORE_FAILED, runBlocking { desk.restores.status() }.lastError?.code)
        assertNotNull(desk.cloud.current().notice, "the Cloud tab says the restore failed")
    }

    @Test
    fun `an apply a crash interrupted is undone at the next start`() {
        val uuid = runningDesk()
        stage { target -> Files.copy(database, target) }
        // Swapped in, then the desk died before the migrations ran.
        checkNotNull(apply().begin())

        val desk = start()
        transaction(desk.database) { assertEquals(uuid, DaoInstall.all().single().uuid.toString()) }
        assertEquals("the desk's own", Files.readString(dir.resolve("prompt-scripts/p-9/fresh.pdf")))
        assertFalse(dir.resolve("prompt-scripts/p-1").exists())
        assertFalse(staging.root.resolve("applying.json").exists())
        assertNotNull(staging.readFailure(), "the admin is told")
    }

    private fun apply() = RestoreApply(
        staging,
        database,
        mapOf(InstallBackup.PROMPT_SCRIPTS to dir.resolve("prompt-scripts"), InstallBackup.SCENE_IMAGES to dir.resolve("scene-images"), InstallBackup.SYNC to dir.resolve("sync")),
    )

    private val journalPath: Path get() = staging.root.resolve("applying.json")

    private fun readJournal() = CloudJson.decodeFromString(RestoreJournal.serializer(), Files.readString(journalPath))

    private fun writeJournal(journal: RestoreJournal) = Files.writeString(journalPath, CloudJson.encodeToString(RestoreJournal.serializer(), journal))

    @Test
    fun `a move journalled but never made is undone cleanly at the next start`() {
        val uuid = runningDesk()
        stage { target -> Files.copy(database, target) }
        val kept = dir.resolve("lighting7.db.pre-restore-20261116T000000Z.db")
        staging.prepare()
        writeJournal(RestoreJournal("20261116T000000Z", false, SavedRows(emptyList(), emptyList()), listOf(RestoreMove(database.toString(), kept.toString(), "KEPT"))))

        val desk = start()
        transaction(desk.database) {
            assertEquals(uuid, DaoInstall.all().single().uuid.toString())
            assertTrue(DaoUsers.selectAll().any { it[DaoUsers.username] == "carol" })
        }
        assertFalse(journalPath.exists())
        assertFalse(kept.exists())
        assertFalse(staging.ready.exists())
    }

    @Test
    fun `an undo that fails keeps its journal and never sweeps the kept tree it could not put back`() {
        runningDesk()
        stage { target -> Files.copy(database, target) }
        checkNotNull(apply().begin())
        val journal = readJournal()
        // The restored tree's removal lost: the kept one has its place taken, so it can't go back.
        writeJournal(journal.copy(moves = journal.moves.filterNot { it.kind == "PLACED" && it.to == dir.resolve("prompt-scripts").toString() }))

        start().shutdown()
        state = null
        val desk = start()
        val keptScripts = Files.list(dir).use { s -> s.filter { it.name.startsWith("prompt-scripts${RestoreApply.KEPT_SUFFIX}") }.toList() }
        assertEquals(1, keptScripts.size, "the kept tree outlives the following start")
        assertEquals("the desk's own", Files.readString(keptScripts.single().resolve("p-9/fresh.pdf")))
        assertTrue(journalPath.exists(), "the next start tries again")
        transaction(desk.database) { assertTrue(DaoUsers.selectAll().any { it[DaoUsers.username] == "carol" }, "every other move was undone") }
    }
}
