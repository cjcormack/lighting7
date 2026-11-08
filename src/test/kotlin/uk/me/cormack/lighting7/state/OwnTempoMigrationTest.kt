package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoPalette
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
 * Database step 12 (fx-speed plan D5), run the way a desk meets it — inside `State`'s
 * `initDatabase` — over `src/test/resources/db/pre-own-tempo.sql`: a database at step 11 whose Look,
 * cue and palettes hold FX on *Seconds*, beat and wall-clock, beside FX on a master.
 */
class OwnTempoMigrationTest {

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
        val sql = javaClass.getResourceAsStream("/db/pre-own-tempo.sql")!!.readBytes().toString(Charsets.UTF_8)
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

    private fun columns(table: String): Set<String> = sql { s ->
        s.executeQuery("PRAGMA table_info($table)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
    }

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-own-tempo-") }.toList() }
    }

    /** One stored FX's clock: its type, cycle, Own tempo and whether it names either master. */
    private data class Clock(val fxType: String, val attribute: String?, val cycle: Double, val ownBpm: Double?, val master: Boolean)

    @Test
    fun `every FX on Seconds becomes Own tempo 120 at twice its cycle, or Natural, backed up first`() {
        loadOldDatabase()
        assertEquals(CUE_NUMBER_USER_VERSION, userVersion())
        assertTrue("seconds" in columns("look_fx"))

        val (desk, logs) = captureLogs("OwnTempoMigration") { startDesk() }

        assertEquals(RETIRED_REMOTE_ACCESS_USER_VERSION, userVersion())
        assertEquals(1, backups().size, "the database is backed up before anything moves")
        for (table in listOf("look_fx", "cue_ad_hoc_fx", "palette_fx")) {
            assertTrue("seconds" !in columns(table), "$table's seconds column is dropped")
        }
        val log = logs.joinToString("\n")
        assertTrue(logs.any { "look_fx 'Breathe': Wave on Seconds now runs on Own tempo 120.0" in it }, log)
        assertTrue(logs.any { "look_fx 'Breathe': CandleFlicker on Seconds now runs on Natural" in it }, log)
        assertTrue(logs.any { "cue_ad_hoc_fx 'Drift': Path on Seconds now runs on Own tempo" in it }, log)
        assertTrue(logs.any { "palette_fx 'Slow pulse': Wave on Seconds now runs on Own tempo" in it }, log)
        assertTrue(logs.any { "palette_fx 'Tube': FluorescentFlicker on Seconds now runs on Natural" in it }, log)
        assertTrue(logs.any { "look_fx 'Haze': Smoke on Seconds now runs on Natural" in it }, "a project's own wall-clock FX:\n$log")
        assertTrue(logs.any { "look_fx 'Haze': candle flicker on Seconds now runs on Natural" in it }, "named as the registry resolves it:\n$log")
        assertTrue(logs.any { "7 FX rewritten from Seconds" in it }, log)

        transaction(desk.database) {
            val look = DaoLook.all().single { it.name == "Breathe" }.effects.sortedBy { it.id.value }
                .map { Clock(it.fxType, it.attribute, it.cycle, it.ownBpm, it.speedMasterUuid != null || it.rateSpeedMasterUuid != null) }
            assertEquals(
                listOf(
                    Clock("Wave", "dimmer", 12.0, 120.0, false),
                    Clock("CandleFlicker", "dimmer", 4.0, null, false),
                    Clock("Wave", "uv", 4.0, null, true),
                ),
                look,
                "six seconds is twelve beats at 120; a wall-clock FX keeps its seconds and loses its rate master; " +
                    "an FX on a master is untouched",
            )
            val haze = DaoLook.all().single { it.name == "Haze" }.effects.sortedBy { it.id.value }.map { it.fxType to (it.cycle to it.ownBpm) }
            assertEquals(listOf("Smoke" to (5.0 to null), "candle flicker" to (3.0 to null)), haze, "both keep their seconds, on Natural")
            val cue = DaoCue.all().single { it.name == "Drift" }.adHocFx.sortedBy { it.id.value }
            assertEquals(listOf(20.0 to 120.0, 2.0 to null), cue.map { it.cycle to it.ownBpm })
            assertEquals("BOUNCE" to 2, cue.first().direction to cue.first().runs, "nothing else moves")
            val palettes = DaoPalette.all().associate { p -> p.name to p.effects.single().let { it.cycle to it.ownBpm } }
            assertEquals(mapOf("Slow pulse" to (6.0 to 120.0), "Tube" to (8.0 to null)), palettes)
        }

        // Never twice: a restart finds the step marked and moves nothing.
        state?.shutdown()
        state = null
        startDesk()
        assertEquals(1, backups().size)
    }

    @Test
    fun `a database whose columns hold no Seconds loses them unbacked`() {
        loadOldDatabase { s ->
            for (table in listOf("look_fx", "cue_ad_hoc_fx", "palette_fx")) s.execute("UPDATE $table SET seconds = 0")
        }
        startDesk()
        assertEquals(RETIRED_REMOTE_ACCESS_USER_VERSION, userVersion())
        assertEquals(emptyList(), backups())
        assertTrue("seconds" !in columns("palette_fx"))
    }
}
