package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.entriesOf
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Database step 11 run the way a desk meets it — inside `State`'s `initDatabase` — over a database
 * a desk at step 10 wrote: its stacks seeded through the model with the numbers the old free-form
 * scheme allowed, then the database marked back to 10. The schema did not change at step 11, so
 * the model's tables are the old desk's.
 */
class CueNumberMigrationTest {

    private var state: State? = null

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
    }

    @After
    fun tearDown() {
        state?.let { runCatching { it.shutdown() } }
    }

    private fun startDesk(): State = State(testAppConfig()).also { state = it }

    private fun stopDesk() {
        state?.shutdown()
        state = null
    }

    private fun <T> sql(block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection -> connection.createStatement().use(block) }

    private fun userVersion(): Int = sql { s -> s.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-cue-numbers-") }.toList() }
    }

    /** A stack of cues numbered [numbers] (each `number to auto`) on the running desk; answers its id. */
    private fun seedStack(
        desk: State,
        projectId: Int,
        numbers: List<Pair<String?, Boolean>>,
        name: String = "Act 1",
        uuids: Map<Int, UUID> = emptyMap(),
    ): Int = transaction(desk.database) {
        val project = DaoProject[projectId]
        val stack = DaoCueStack.new { this.project = project; this.name = name }
        numbers.forEachIndexed { index, (number, auto) ->
            val cue = DaoCue.new { this.project = project; this.name = "Cue ${index + 1}" }
            DaoCueStackEntry.new {
                this.stack = stack
                this.cue = cue
                ordinal = (index + 1).toDouble()
                cueNumber = number
                cueNumberAuto = auto
                uuids[index]?.let { uuid = it }
            }
        }
        stack.id.value
    }

    private fun numbersOf(desk: State, stackId: Int): List<Pair<String?, Boolean>> = transaction(desk.database) {
        entriesOf(DaoCueStack[stackId]).map { it.cueNumber to it.cueNumberAuto }
    }

    @Test
    fun `every cue number becomes a number, the rest numbered from their places, backed up first`() {
        val desk = startDesk()
        assertEquals(SURFACE_GRAMMAR_USER_VERSION, userVersion(), "a fresh database is marked")
        val projectId = seedMinimalProject(desk)
        val stackId = seedStack(
            desk, projectId,
            listOf(
                "1" to false,
                "S1-3" to false, // not a number: cleared, numbered from its place
                "2.10" to false, // another spelling of 2.1, on the higher uuid: cleared
                "2.1" to false, // the same number, on the lower uuid: kept, as the importer decides
                "3" to true, // an auto number that is already one, kept where it is
                "14A" to false,
                "007" to false,
            ),
            uuids = mapOf(
                2 to UUID.fromString("ffffffff-0000-0000-0000-000000000000"),
                3 to UUID.fromString("00000000-0000-0000-0000-000000000001"),
            ),
        )
        val untouched = seedStack(desk, projectId, listOf("1" to false, "2" to true), name = "Act 2")
        stopDesk()
        sql { it.execute("PRAGMA user_version = $PALETTE_LAYER_USER_VERSION") }

        val (again, logs) = captureLogs("CueNumberMigration") { startDesk() }
        assertEquals(SURFACE_GRAMMAR_USER_VERSION, userVersion())
        assertEquals(1, backups().size, "backed up first")
        assertEquals(
            listOf(
                "1" to false,
                "2" to true,
                "2.05" to true, // one whole number between 1 and 2.1 for two cues: 2, then the midpoint
                "2.1" to false,
                "3" to true,
                "4" to true,
                "7" to false,
            ),
            numbersOf(again, stackId),
        )
        assertEquals(listOf("1" to false, "2" to true), numbersOf(again, untouched))
        assertTrue(logs.any { "'S1-3'" in it && "not a number" in it }, logs.joinToString("\n"))
        assertTrue(logs.any { "'2.10'" in it && "'2.1', which another cue there keeps" in it }, logs.joinToString("\n"))
    }

    @Test
    fun `a database whose numbers are all numbers is only marked`() {
        val desk = startDesk()
        val projectId = seedMinimalProject(desk)
        seedStack(desk, projectId, listOf("1" to false, "2" to true, "2.5" to false))
        stopDesk()
        sql { it.execute("PRAGMA user_version = $PALETTE_LAYER_USER_VERSION") }

        startDesk()
        assertEquals(SURFACE_GRAMMAR_USER_VERSION, userVersion())
        assertEquals(emptyList(), backups(), "nothing to change, nothing backed up")
    }
}
