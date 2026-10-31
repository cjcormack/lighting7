package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.CueMoveInDarkMode
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.moveInDarkSettings
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Database step 9 (move-in-dark plan D13), run the way a desk meets it — inside `State`'s
 * `initDatabase` — over `src/test/resources/db/pre-move-in-dark.sql`: a database at step 8 whose cues
 * flag position rows move-in-dark, one of them twice and one loose, beside a cue that flags none.
 */
class MoveInDarkRowFlagMigrationTest {

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
        val sql = javaClass.getResourceAsStream("/db/pre-move-in-dark.sql")!!.readBytes().toString(Charsets.UTF_8)
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

    private fun userVersion(): Int = sql { s -> s.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

    private fun setUserVersion(version: Int) = sql { it.execute("PRAGMA user_version = $version") }

    private fun hasFlagColumn(): Boolean = sql { s ->
        s.executeQuery("PRAGMA table_info(cue_attribute_assignments)").use { rs ->
            var found = false
            while (rs.next()) if (rs.getString("name") == "move_in_dark") found = true
            found
        }
    }

    private fun <T> sql(block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection -> connection.createStatement().use(block) }

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-move-in-dark-") }.toList() }
    }

    @Test
    fun `every cue that flags a row moves its heads Early, and the column goes, backed up first`() {
        loadOldDatabase()
        assertEquals(FX_SIBLINGS_USER_VERSION, userVersion())
        assertTrue(hasFlagColumn())

        val (desk, logs) = captureLogs("MoveInDarkRowFlagMigration") { startDesk() }

        assertEquals(MOVE_IN_DARK_ROW_FLAG_USER_VERSION, userVersion())
        assertFalse(hasFlagColumn(), "the column is dropped")
        assertEquals(1, backups().size, "the database is backed up before anything moves")
        for (name in listOf("Flagged", "Twice flagged", "Loose flagged")) {
            assertTrue(logs.any { "cue '$name' flagged a row" in it }, logs.joinToString("\n"))
        }
        assertTrue(logs.any { "3 cue(s) set Early" in it }, logs.joinToString("\n"))

        transaction(desk.database) {
            val modes = DaoCue.all().associate { it.name to it.moveInDarkSettings.mode }
            assertEquals(
                mapOf(
                    "Lit" to CueMoveInDarkMode.STACK,
                    "Flagged" to CueMoveInDarkMode.EARLY,
                    "Twice flagged" to CueMoveInDarkMode.EARLY,
                    "Plain" to CueMoveInDarkMode.STACK,
                    "Loose flagged" to CueMoveInDarkMode.EARLY,
                ),
                modes,
            )
            assertEquals(7, DaoCueAttributeAssignment.all().count(), "every row is kept")
        }
    }

    @Test
    fun `a database flagging no row loses the column without a backup`() {
        loadOldDatabase()
        sql { it.execute("UPDATE cue_attribute_assignments SET move_in_dark = 0") }

        val desk = startDesk()

        assertEquals(MOVE_IN_DARK_ROW_FLAG_USER_VERSION, userVersion())
        assertFalse(hasFlagColumn())
        assertEquals(emptyList(), backups())
        transaction(desk.database) {
            assertTrue(DaoCue.all().all { it.moveInDarkSettings.mode == CueMoveInDarkMode.STACK })
        }
    }

    @Test
    fun `it never runs twice`() {
        loadOldDatabase()
        startDesk()
        stopDesk()
        val before = backups()

        val (_, logs) = captureLogs("MoveInDarkRowFlagMigration") { startDesk() }

        assertEquals(before, backups())
        assertTrue(logs.none { "set Early" in it }, logs.joinToString("\n"))
    }

    @Test
    fun `a fresh database is born at step 9`() {
        startDesk()
        assertEquals(MOVE_IN_DARK_ROW_FLAG_USER_VERSION, userVersion())
        assertFalse(hasFlagColumn())
        assertEquals(emptyList(), backups())
        stopDesk()
        setUserVersion(FX_SIBLINGS_USER_VERSION)
        startDesk()
        assertEquals(MOVE_IN_DARK_ROW_FLAG_USER_VERSION, userVersion(), "a database with no column is only marked")
    }
}
