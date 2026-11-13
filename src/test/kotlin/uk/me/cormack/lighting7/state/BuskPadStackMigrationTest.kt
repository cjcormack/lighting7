package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.BuskPadKind
import uk.me.cormack.lighting7.models.DaoBuskBank
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Database step 14 (busk-stack plan D9), run the way a desk meets it — inside `State`'s
 * `initDatabase` — over `src/test/resources/db/pre-busk-stack.sql`: a database at step 13 whose
 * `busk_pads` holds the three-arm CHECK and a pad of each kind.
 */
class BuskPadStackMigrationTest {

    private var state: State? = null

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
    }

    @After
    fun tearDown() {
        state?.let { runCatching { it.shutdown() } }
    }

    private fun loadOldDatabase(after: (java.sql.Statement) -> Unit = {}) {
        val sql = javaClass.getResourceAsStream("/db/pre-busk-stack.sql")!!.readBytes().toString(Charsets.UTF_8)
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                sql.split(";\n")
                    .map { chunk -> chunk.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { statement.execute(it) }
                after(statement)
            }
            connection.commit()
        }
    }

    private fun startDesk(): State = State(testAppConfig()).also { state = it }

    private fun <T> sql(block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection -> connection.createStatement().use(block) }

    private fun userVersion(): Int = sql { s -> s.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

    private fun padsDdl(): String = sql { s ->
        s.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'busk_pads'").use { rs -> rs.next(); rs.getString(1) }
    }

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-busk-stack-") }.toList() }
    }

    @Test
    fun `the table is rebuilt with a four-arm check, every pad kept by id, backed up first`() {
        loadOldDatabase()
        assertEquals(RETIRED_REMOTE_ACCESS_USER_VERSION, userVersion())
        assertTrue("cue_stack_id" !in BuskPadStackMigration.checkExpression(padsDdl())!!)

        val (desk, logs) = captureLogs("BuskPadStackMigration") { startDesk() }

        assertEquals(GITHUB_RETIREMENT_USER_VERSION, userVersion())
        assertTrue("cue_stack_id" in BuskPadStackMigration.checkExpression(padsDdl())!!)
        transaction(desk.database) {
            val kinds = DaoBuskPad.all().sortedBy { it.id.value }.map { it.id.value to it.kind }
            assertEquals(listOf(1 to BuskPadKind.PALETTE, 2 to BuskPadKind.LOOK, 3 to BuskPadKind.CUE, 4 to BuskPadKind.CUE), kinds)
            assertEquals("33f4f5bf-ca62-4e17-aab3-8dceb2c46cc0", DaoBuskPad[1].uuid.toString())
            assertEquals(listOf(0, 1, 2), DaoBuskPad.all().filter { it.bank.id.value == 1 }.map { it.sortOrder }.sorted())

        }
        // The point of the step: a pad naming only a stack is now a row the database takes.
        val padId = transaction(desk.database) {
            DaoBuskPad.new { bank = DaoBuskBank[2]; sortOrder = 1; cueStack = DaoCueStack[1] }.id.value
        }
        transaction(desk.database) { assertEquals(BuskPadKind.CUE_STACK, DaoBuskPad[padId].kind) }
        val backup = backups().single()
        DriverManager.getConnection("jdbc:sqlite:$backup").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM busk_pads").use { rs -> rs.next(); assertEquals(4, rs.getInt(1)) }
            }
        }
        assertTrue(logs.any { "4 pads carried" in it }, logs.joinToString("\n"))
    }

    @Test
    fun `an empty table is rebuilt without a backup`() {
        loadOldDatabase { s -> s.execute("DELETE FROM busk_pads") }

        startDesk()

        assertEquals(GITHUB_RETIREMENT_USER_VERSION, userVersion())
        assertTrue("cue_stack_id" in BuskPadStackMigration.checkExpression(padsDdl())!!)
        assertTrue(backups().isEmpty())
    }

    @Test
    fun `a fresh database already has the check and is only marked`() {
        val (desk, logs) = captureLogs("BuskPadStackMigration") { startDesk() }
        assertTrue(logs.none { "rebuilt" in it }, "a fresh table is never rebuilt: ${logs.joinToString("\n")}")
        desk.shutdown()
        state = null
        val ddl = padsDdl()
        assertEquals(GITHUB_RETIREMENT_USER_VERSION, userVersion())
        assertTrue("cue_stack_id" in BuskPadStackMigration.checkExpression(ddl)!!)
        assertTrue(backups().isEmpty())

        startDesk()
        assertEquals(ddl, padsDdl(), "a marked database is left as it is")
    }

    @Test
    fun `the check is read by its parentheses wherever the added column lands`() {
        val ddl = "CREATE TABLE busk_pads (id INTEGER, cue_id INT NULL, " +
            "CONSTRAINT busk_pad_exactly_one_ref CHECK ((cue_id IS NOT NULL) OR (look_id IS NOT NULL)), cue_stack_id INT NULL)"
        assertEquals("((cue_id IS NOT NULL) OR (look_id IS NOT NULL))", BuskPadStackMigration.checkExpression(ddl))
        assertNull(BuskPadStackMigration.checkExpression("CREATE TABLE busk_pads (id INTEGER)"))
        assertNotNull(BuskPadStackMigration.checkExpression(ddl.replace("cue_id IS NOT NULL", "cue_stack_id IS NOT NULL")))
    }
}
