package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocFxEntity
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookFxEntity
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteFxEntity
import uk.me.cormack.lighting7.models.DaoPaletteRow
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Database step 4 (terminology plan §5): every stored FX onto today's library, run the way a desk
 * meets it — inside `State`'s `initDatabase`, over a database whose `PRAGMA user_version` says it
 * has not run yet. Step 7 follows it there, so a level wave step 4 leaves is stored as Wave.
 */
class FxLibraryMigrationTest {

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

    private fun sql(statement: String) =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { it.executeUpdate(statement) }
        }

    private fun <T> query(statement: String, row: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { s -> s.executeQuery(statement).use { rs -> buildList { while (rs.next()) add(row(rs)) } } }
        }

    private fun userVersion(): Int = query("PRAGMA user_version") { it.getInt(1) }.single()

    /** Every FX of [table], in [order], as the columns step 4 moves. */
    private fun stored(table: String, order: String = "sort_order, id"): List<List<Any?>> = query(
        "SELECT fx_type, family, cycle, spread_order, phase_offset, parameters, direction, runs, spread FROM $table ORDER BY $order",
    ) { rs -> (1..9).map { rs.getObject(it) } }

    private lateinit var red: String
    private lateinit var blue: String

    /** Experiment, written by a desk before the FX library changed: every old name in every FX home. */
    private fun seedOldShow() {
        val desk = startDesk()
        val projectId = seedMinimalProject(desk, projectName = "Experiment")
        transaction(desk.database) {
            val project = DaoProject[projectId]
            fun colourPalette(name: String, hex: String) = DaoPalette.new { this.project = project; this.name = name }.also {
                DaoPaletteRow.new {
                    palette = it; targetType = DEFERRED_TARGET_TYPE; targetKey = ""
                    attribute = "rgbColour"; value = "$hex;policy=rgbonly"
                }
            }
            red = colourPalette("Red", "#ff0000").uuid.toString()
            blue = colourPalette("Blue", "#0000ff").uuid.toString()

            val look = DaoLook.new { this.project = project; name = "S3 Old FX" }
            fun lookFx(fxType: String, category: String, parameters: Map<String, String>, sort: Int, runs: Int? = null, spread: Double = 1.0) =
                DaoLookFxEntity.new {
                    this.look = look; targetType = "group"; targetKey = "front"
                    this.fxType = fxType; this.family = category; attribute = null
                    cycle = 4.0; blendMode = "OVERRIDE"; order = "NORMAL"; phaseOffset = 0.25
                    this.parameters = parameters; sortOrder = sort; this.runs = runs; this.spread = spread
                }
            lookFx("Breathe", "dimmer", mapOf("min" to "0", "max" to "255"), 0)
            lookFx("Sweep", "position", emptyMap(), 1, runs = 2, spread = 0.75)
            lookFx("ColourCycle", "colour", mapOf("colours" to "pal:$red,pal:$blue"), 2)

            val cue = DaoCue.new { this.project = project; name = "Cue 1" }
            DaoCueAdHocFxEntity.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-1"
                fxType = "Triangle"; family = "intensity"; attribute = "dimmer"
                cycle = 1.0; blendMode = "OVERRIDE"; order = "SPLIT"; parameters = emptyMap(); sortOrder = 0
            }
            DaoCueAdHocFxEntity.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-1"
                fxType = "ColourCycle"; family = "colour"; attribute = "rgbColour"
                cycle = 1.0; blendMode = "OVERRIDE"; order = "NORMAL"
                parameters = mapOf("colours" to "pal:$red,#00ff00"); sortOrder = 1
            }
            DaoCueAdHocFxEntity.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-1"
                fxType = "Pulse"; family = "intensity"; attribute = "dimmer"
                cycle = 1.0; blendMode = "OVERRIDE"; order = "NORMAL"; parameters = emptyMap(); sortOrder = 2
            }
            val strike = DaoPalette.new { this.project = project; name = "Strike" }
            DaoPaletteFxEntity.new {
                palette = strike; fxType = "LightningStrike"; family = "composite"; attribute = "dimmer"
                cycle = 4.0; blendMode = "OVERRIDE"; order = "NORMAL"
                parameters = mapOf("maxBrightness" to "200", "flashColour" to "#ffffff")
            }
        }
        stopDesk()
        // The composite mode is gone from the enums the DAO reads, so a database holding it is
        // written as an older desk wrote it.
        sql(
            "INSERT INTO fx_definitions (fx_id, name, family, output_type, fx_mode, script, project_id, uuid) " +
                "VALUES ('my-strike', 'My Strike', 'composite', 'SLIDER', 'COMPOSITE', 'mapOf()', $projectId, X'00112233445566778899aabbccddeeff')",
        )
        sql(
            "INSERT INTO fx_definitions (fx_id, name, family, output_type, fx_mode, script, project_id, uuid) " +
                "VALUES ('my-glow', 'My Glow', 'composite', 'COLOUR', 'COMPOSITE', 'mapOf()', $projectId, X'00112233445566778899aabbccddeef0')",
        )
        sql(
            "INSERT INTO scripts (name, script, project_id, script_type, uuid) " +
                "VALUES ('calc', 'mapOf()', $projectId, 'FX_CALC_COMPOSITE', X'ffeeddccbbaa99887766554433221100')",
        )
        sql("INSERT INTO scripts (name, script, project_id, script_type, uuid) VALUES ('chase', 'effect(\"Triangle\")', $projectId, 'GENERAL', X'0102030405060708090a0b0c0d0e0f10')")
        sql("PRAGMA user_version = $LOOK_LAYER_TARGETS_CLEARED_USER_VERSION")
    }

    @Test
    fun `a fresh database is already past the step`() {
        startDesk()
        stopDesk()
        assertEquals(FX_CONSOLIDATED_USER_VERSION, userVersion())
    }

    @Test
    fun `every FX home is rewritten onto today's library, each named in the log`() {
        seedOldShow()
        val (_, logs) = captureLogs("FxLibraryMigration") {
            startDesk()
            stopDesk()
        }

        assertEquals(
            listOf(
                listOf("Wave", "intensity", 4.0, "NORMAL", 0.0, """{"shape":"SINE","low":"0","high":"255"}""", "FORWARD", null, 1.0),
                listOf("Sweep", "position", 2.0, "NORMAL", 0.5, "{}", "BOUNCE", 4, 1.5),
                listOf("PaletteSteps", "colour", 4.0, "NORMAL", 0.25, """{"palettes":"pal:$red,pal:$blue"}""", "FORWARD", null, 1.0),
            ),
            stored("look_fx"),
        )
        assertEquals(
            listOf(
                listOf(
                    "Wave", "intensity", 1.0, "CENTRE_IN", 0.0, """{"shape":"TRIANGLE","low":"0","high":"255","curve":"LINEAR"}""",
                    "FORWARD", null, 1.0,
                ),
                listOf("ColourCycle", "colour", 1.0, "NORMAL", 0.0, """{"colours":"#ff0000,#00ff00"}""", "FORWARD", null, 1.0),
                listOf(
                    "Wave", "intensity", 1.0, "NORMAL", 0.0,
                    """{"shape":"PULSE","low":"0","high":"255","attack":"0.1","hold":"0.3","curve":"QUAD_OUT"}""",
                    "FORWARD", null, 1.0,
                ),
            ),
            stored("cue_ad_hoc_fx"),
        )
        assertEquals(
            listOf(listOf("LightningStrike", "intensity", 4.0, "NORMAL", 0.0, """{"maxBrightness":"200"}""", "FORWARD", null, 1.0)),
            stored("palette_fx", order = "id"),
        )
        assertEquals(
            listOf("STANDARD" to "intensity", "STANDARD" to "colour"),
            query("SELECT fx_mode, family FROM fx_definitions ORDER BY id") { it.getString(1) to it.getString(2) },
            "a composite is filed under the family of the output it applied",
        )
        assertEquals(listOf("FX_CALC", "GENERAL"), query("SELECT script_type FROM scripts ORDER BY name") { it.getString(1) })
        assertEquals(FX_CONSOLIDATED_USER_VERSION, userVersion())

        fun logged(vararg words: String) = assertTrue(logs.any { line -> words.all { it in line } }, "${words.toList()} in $logs")
        logged("Look 'S3 Old FX' in 'Experiment'", "Breathe is now Sine Wave")
        logged("Look 'S3 Old FX'", "pingPong is now Direction Bounce")
        logged("Look 'S3 Old FX'", "Colour Cycle over 'Red', 'Blue' is now Palette Steps", "each palette now lands on each head")
        logged("Cue 'Cue 1'", "Triangle is now Ramp", "Order SPLIT is now CENTRE_IN")
        logged("Cue 'Cue 1'", "palettes are now the colours")
        logged("Palette 'Strike'", "family composite is now intensity")
        logged("FX definition 'my-strike'", "composite mode is gone")
        logged("Script 'calc'", "composite calculate body is gone")
        logged("Script 'chase'", "needs editing", "Triangle is now Ramp")
        assertTrue(logs.none { "Pulse" in it }, "an FX holding no old name is not logged: $logs")
    }

    @Test
    fun `it runs once, so a second start changes nothing`() {
        seedOldShow()
        startDesk()
        stopDesk()
        val once = listOf(stored("look_fx"), stored("cue_ad_hoc_fx"), stored("palette_fx", order = "id"))

        val (_, logs) = captureLogs("FxLibraryMigration") {
            startDesk()
            stopDesk()
        }

        assertEquals(once, listOf(stored("look_fx"), stored("cue_ad_hoc_fx"), stored("palette_fx", order = "id")))
        assertEquals(emptyList(), logs)
    }
}
