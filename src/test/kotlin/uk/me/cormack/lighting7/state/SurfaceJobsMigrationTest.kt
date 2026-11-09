package uk.me.cormack.lighting7.state

import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.midi.Binding
import uk.me.cormack.lighting7.midi.ControlPart
import uk.me.cormack.lighting7.midi.Does
import uk.me.cormack.lighting7.midi.EncoderBankSelection
import uk.me.cormack.lighting7.midi.JobItem
import uk.me.cormack.lighting7.midi.JobKind
import uk.me.cormack.lighting7.midi.Subject
import uk.me.cormack.lighting7.midi.decodeBinding
import uk.me.cormack.lighting7.midi.decodeJobItems
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Database step 17, run the way a desk meets it — inside `State`'s
 * `initDatabase` — over `src/test/resources/db/pre-surface-jobs.sql`: a database at step 16 whose
 * channel strips are binding rows at strip ids (its header says which).
 */
class SurfaceJobsMigrationTest {

    private var state: State? = null

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
    }

    @After
    fun tearDown() {
        state?.let { runCatching { it.shutdown() } }
    }

    private fun loadOldDatabase() {
        val sql = javaClass.getResourceAsStream("/db/pre-surface-jobs.sql")!!.readBytes().toString(Charsets.UTF_8)
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                sql.split(";\n")
                    .map { chunk -> chunk.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { statement.execute(it) }
            }
            connection.commit()
        }
    }

    private fun startDesk(): State = State(testAppConfig()).also { state = it }

    private fun stopDesk() {
        state?.shutdown()
        state = null
    }

    private fun <T> sql(path: String = IntegrationTestDb.path, block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection -> connection.createStatement().use(block) }

    private fun userVersion(): Int = sql { s -> s.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

    private data class Job(val project: Int, val region: String, val bank: String?, val kind: String, val items: List<JobItem?>)

    private fun jobs(path: String = IntegrationTestDb.path): List<Job> = sql(path) { s ->
        s.executeQuery("SELECT project_id, region, bank, kind, items FROM surface_jobs ORDER BY project_id, region, bank").use { rs ->
            buildList { while (rs.next()) add(Job(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), decodeJobItems(rs.getString(5))!!)) }
        }
    }

    private data class Row(val project: Int, val controlId: String, val bank: String?, val type: String, val payload: String)

    private fun bindings(path: String = IntegrationTestDb.path): List<Row> = sql(path) { s ->
        s.executeQuery("SELECT project_id, control_id, bank, target_type, target_payload FROM control_surface_bindings ORDER BY id").use { rs ->
            buildList { while (rs.next()) add(Row(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))) }
        }
    }

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-surface-jobs-") }.toList() }
    }

    @Test
    fun `every project's strips become jobs, a strip of the other kind its controls' own, backed up first`() {
        loadOldDatabase()
        assertEquals(AUTO_NUMBER_TIDY_USER_VERSION, userVersion())

        val (_, logs) = captureLogs("SurfaceJobsMigration") { startDesk() }
        stopDesk()

        assertEquals(SURFACE_JOBS_USER_VERSION, userVersion())
        assertEquals(
            listOf(
                Job(1, "master", null, JobKind.GROUPS.id, listOf(JobItem.Group("front"))),
                Job(1, "strips", null, JobKind.GROUPS.id, listOf(JobItem.Group("front"), JobItem.Group("back"), null, JobItem.Group("sides"))),
                Job(1, "strips", "layer-a", JobKind.FIXTURES.id, listOf(JobItem.Fixture("hex-2"))),
                Job(2, "strips", null, JobKind.FIXTURES.id, listOf(JobItem.Fixture("spot-1"), JobItem.Fixture("spot-2"))),
            ),
            jobs(),
        )
        val rows = bindings()
        assertTrue(rows.none { it.controlId.startsWith("strip-") }, "no strip row is left, the unreadable one included")
        val hex1 = Subject.Fixture("hex-1")
        assertEquals(
            mapOf(
                "btn-29" to Binding(Does.Select, Subject.Group("back")),
                "btn-3" to Binding(Does.Blackout),
                "fader-5" to Binding(Does.Level("dimmer"), hex1),
                "enc-5" to Binding(Does.PageLevel, hex1),
                "btn-5" to Binding(Does.Flash("dimmer"), hex1),
            ),
            rows.associate { it.controlId to decodeBinding(it.payload, it.type) },
            "strip 5's own select button kept its own binding; its other controls kept what the strip did",
        )
        assertTrue(logs.any { "4 job(s) made, 3 binding(s) given their own, 1 strip row(s) dropped unread" in it }, logs.joinToString("\n"))

        val backup = backups().single()
        assertEquals(6 + 2 + 1, bindings(backup.toString()).count { it.controlId.startsWith("strip-") }, "the backup is the database before the step")
    }

    @Test
    fun `the desk reads the jobs as the strips read`() {
        loadOldDatabase()
        val desk = startDesk()
        val service = desk.controlSurfaceBindingService
        val device = "x-touch-compact-standard"
        fun on(controlId: String, bank: String?) = service.resolve(1, device, controlId, bank, EncoderBankSelection("dimmer"))?.binding
        assertEquals(Binding(Does.Level("dimmer"), Subject.Group("back")), on("fader-2", null))
        assertEquals(Binding(Does.Level("dimmer"), Subject.Group("front")), on("fader-9", "layer-b"))
        assertEquals(Binding(Does.Level("dimmer"), Subject.Fixture("hex-2")), on("fader-1", "layer-a"))
        assertEquals(Binding(Does.Level("dimmer"), Subject.Group("front")), on("fader-1", "layer-b"))
        assertEquals(null, on("fader-3", null), "strip 3 was empty")
        assertEquals(Binding(Does.Select, Subject.Group("back")), on("btn-29", "layer-a"))
        assertEquals(null, service.resolve(1, device, "enc-1", null, EncoderBankSelection("dimmer"), ControlPart.PUSH))
    }

    @Test
    fun `a database with no strip is only marked, with no backup`() {
        loadOldDatabase()
        sql { s -> s.executeUpdate("DELETE FROM control_surface_bindings WHERE control_id LIKE 'strip-%'") }
        val (_, logs) = captureLogs("SurfaceJobsMigration") { startDesk() }
        stopDesk()
        assertEquals(SURFACE_JOBS_USER_VERSION, userVersion())
        assertTrue(backups().isEmpty())
        assertTrue(jobs().isEmpty())
        assertTrue(logs.none { "job(s) made" in it }, logs.joinToString("\n"))
    }

    @Test
    fun `a second start leaves a marked database as it is`() {
        loadOldDatabase()
        startDesk()
        stopDesk()
        val before = jobs() to bindings()
        Files.delete(backups().single())
        startDesk()
        stopDesk()
        assertEquals(before, jobs() to bindings())
        assertTrue(backups().isEmpty())
    }
}
