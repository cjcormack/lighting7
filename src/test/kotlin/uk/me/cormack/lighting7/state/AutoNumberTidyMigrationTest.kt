package uk.me.cormack.lighting7.state

import org.junit.After
import org.junit.Before
import org.junit.Test
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
 * Database step 16 (venue-rig-fixes plan D9), run the way a desk meets it — inside `State`'s
 * `initDatabase` — over `src/test/resources/db/pre-auto-numbers.sql`: a database at step 15 whose
 * stack "Act 1" holds the auto numbers venue-visit plan D11 kept in place (28, 29, a typed 30, 40,
 * a marker, 41) and whose "Act 2" already reads as position gives it (1, 2, 3).
 */
class AutoNumberTidyMigrationTest {

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
        val sql = javaClass.getResourceAsStream("/db/pre-auto-numbers.sql")!!.readBytes().toString(Charsets.UTF_8)
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

    /** A stack's entries in order, each `(number, auto)`, a marker as its label in bars. */
    private fun stack(name: String, path: String = IntegrationTestDb.path): List<Pair<String?, Boolean>> = sql(path) { s ->
        s.executeQuery(
            "SELECT e.kind, e.label, e.cue_number, e.cue_number_auto FROM cue_stack_entries e JOIN cue_stacks s ON s.id = e.stack_id " +
                "WHERE s.name = '$name' ORDER BY e.ordinal, e.uuid",
        ).use { rs ->
            buildList {
                while (rs.next()) {
                    if (rs.getString(1) == "MARKER") add("|${rs.getString(2)}|" to false) else add(rs.getString(3) to rs.getBoolean(4))
                }
            }
        }
    }

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-auto-numbers-") }.toList() }
    }

    @Test
    fun `every stack's auto numbers follow position, typed numbers untouched, backed up first and logged`() {
        loadOldDatabase()
        assertEquals(SURFACE_GRAMMAR_USER_VERSION, userVersion())
        val before = stack("Act 1")

        val (_, logs) = captureLogs("AutoNumberTidyMigration") { startDesk() }
        stopDesk()

        assertEquals(AUTO_NUMBER_TIDY_USER_VERSION, userVersion())
        assertEquals(
            listOf("1" to true, "2" to true, "30" to false, "31" to true, "|Interval|" to false, "32" to true),
            stack("Act 1"),
        )
        assertEquals(listOf("1" to true, "2" to true, "3" to true), stack("Act 2"), "a stack already in place is left alone")

        val backup = backups().single()
        assertEquals(before, stack("Act 1", backup.toString()), "the backup is the database as it was")

        for ((cue, from, to) in listOf(Triple("House out", "28", "1"), Triple("Preset", "29", "2"), Triple("Storm", "40", "31"), Triple("Calm", "41", "32"))) {
            assertTrue(
                logs.any { "cue '$cue' in stack 'Act 1' of 'Rig session' was Q$from and is now Q$to" in it },
                "$cue logged: ${logs.joinToString("\n")}",
            )
        }
        assertTrue(logs.none { "'Act 2'" in it }, logs.joinToString("\n"))
        assertTrue(logs.any { "4 number(s) changed" in it }, logs.joinToString("\n"))
    }

    @Test
    fun `a database whose auto numbers already follow position is only marked, with no backup`() {
        loadOldDatabase()
        sql { s ->
            s.executeUpdate("UPDATE cue_stack_entries SET cue_number = NULL WHERE cue_number_auto = 1 AND stack_id = 1")
            for ((id, number) in listOf(1 to "1", 2 to "2", 4 to "31", 6 to "32")) {
                s.executeUpdate("UPDATE cue_stack_entries SET cue_number = '$number' WHERE id = $id")
            }
        }
        val (_, logs) = captureLogs("AutoNumberTidyMigration") { startDesk() }
        stopDesk()
        assertEquals(AUTO_NUMBER_TIDY_USER_VERSION, userVersion())
        assertTrue(backups().isEmpty())
        assertTrue(logs.isEmpty(), logs.joinToString("\n"))
    }

    @Test
    fun `a second start leaves a marked database as it is`() {
        loadOldDatabase()
        startDesk()
        stopDesk()
        val tidied = stack("Act 1")
        startDesk()
        stopDesk()
        assertEquals(tidied, stack("Act 1"))
        assertEquals(1, backups().size)
    }

    @Test
    fun `a fresh database is only marked`() {
        val (_, logs) = captureLogs("AutoNumberTidyMigration") { startDesk() }
        stopDesk()
        assertEquals(AUTO_NUMBER_TIDY_USER_VERSION, userVersion())
        assertTrue(backups().isEmpty())
        assertTrue(logs.isEmpty(), logs.joinToString("\n"))
    }
}
