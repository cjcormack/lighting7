package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.models.DaoCueAdHocFxEntity
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoLookFxEntity
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoScript
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
 * Database step 8 (fx-settings plan session 3), run the way a desk meets it — inside `State`'s
 * `initDatabase` — over `src/test/resources/db/pre-fx-siblings.sql`: a database at step 7 whose Look,
 * cue and palettes hold every movement Path replaced, every FX whose parameters were renamed, Colour
 * Cycle with a `fadeRatio`, and Colour Wave and Colour Fade, which stay legacy.
 */
class FxSiblingsMigrationTest {

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
        val sql = javaClass.getResourceAsStream("/db/pre-fx-siblings.sql")!!.readBytes().toString(Charsets.UTF_8)
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

    private fun userVersion(): Int =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }
        }

    private fun setUserVersion(version: Int) =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = $version") }
        }

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-fx-siblings-") }.toList() }
    }

    /** One stored FX as the test reads it back. */
    private data class Row(
        val fxType: String,
        val family: String,
        val parameters: Map<String, String>,
        val cycle: Double = 4.0,
        val direction: String = "FORWARD",
    )

    @Test
    fun `every movement is Path and every renamed parameter its new name, backed up first`() {
        loadOldDatabase()
        assertEquals(FX_CONSOLIDATED_USER_VERSION, userVersion())

        val (desk, logs) = captureLogs("FxSiblingsMigration") { startDesk() }

        assertEquals(FX_SIBLINGS_USER_VERSION, userVersion())
        assertEquals(1, backups().size, "the database is backed up before anything moves")
        assertTrue(logs.any { "Look 'Moves' in 'Old movements': Circle — Circle is now Path, shape CIRCLE" in it }, logs.joinToString("\n"))
        assertTrue(logs.any { "Cue 'Q1' in 'Old movements': Flicker — 'min' is now 'low'; 'max' is now 'high'" in it }, logs.joinToString("\n"))
        assertTrue(logs.any { "Palette 'Warm to cool' in 'Old movements': ColourCycle — 'fadeRatio' is now 'FADE'" in it }, logs.joinToString("\n"))
        assertTrue(logs.any { "'stale' named nothing it read" in it }, logs.joinToString("\n"))
        assertTrue(logs.any { "FluorescentFlicker" in it && "'high' named nothing it read" in it }, logs.joinToString("\n"))
        assertTrue(logs.none { "ColourWave" in it || "ColourFade" in it }, "Colour Wave and Colour Fade stay legacy")

        transaction(desk.database) {
            val centre = mapOf("panCentre" to "128", "tiltCentre" to "128")
            val look = DaoLookFxEntity.all().sortedBy { it.sortOrder }.map { Row(it.fxType, it.family, it.parameters, it.cycle, it.direction) }
            assertEquals(
                listOf(
                    Row("Path", "position", mapOf("shape" to "CIRCLE") + centre + mapOf("panSize" to "40", "tiltSize" to "64")),
                    Row("Path", "position", mapOf("shape" to "FIGURE_8") + centre + mapOf("panSize" to "64", "tiltSize" to "32")),
                    Row("Path", "position", mapOf("shape" to "RANDOM") + centre + mapOf("panSize" to "20", "tiltSize" to "255")),
                    Row(
                        "Path", "position",
                        mapOf("shape" to "LINE", "startPan" to "10", "startTilt" to "128", "endPan" to "192", "endTilt" to "128", "curve" to "QUAD_IN"),
                        cycle = 2.0, direction = "BOUNCE",
                    ),
                    Row(
                        "Path", "position",
                        mapOf("shape" to "LINE", "startPan" to "64", "endPan" to "192", "startTilt" to "90", "endTilt" to "90", "curve" to "SINE_IN_OUT"),
                        cycle = 2.0, direction = "BOUNCE",
                    ),
                    Row(
                        "Path", "position",
                        mapOf("shape" to "LINE", "startTilt" to "30", "endTilt" to "192", "startPan" to "128", "endPan" to "128", "curve" to "SINE_IN_OUT"),
                        cycle = 2.0, direction = "BOUNCE",
                    ),
                    Row("ColourWave", "colour", mapOf("colourA" to "#ff0000")),
                    Row("ColourFade", "colour", emptyMap(), cycle = 2.0, direction = "BOUNCE"),
                ),
                look,
            )

            val cue = DaoCueAdHocFxEntity.all().sortedBy { it.sortOrder }.map { Row(it.fxType, it.family, it.parameters) }
            assertEquals(
                listOf(
                    Row("Flicker", "intensity", mapOf("low" to "20", "high" to "220")),
                    Row("CandleFlicker", "intensity", mapOf("base" to "150", "settle" to "0.9")),
                    // Its stray `high` it never read goes: it ran at onLevel's default, 255, and still does.
                    Row("FluorescentFlicker", "intensity", mapOf("low" to "10", "burstMs" to "400", "speed" to "0.5")),
                    Row("LightningStrike", "intensity", mapOf("high" to "200")),
                    Row("ColourStrobe", "colour", mapOf("width" to "0.2", "onColour" to "#ffffff")),
                    Row("ColourCycle", "colour", mapOf("colours" to "#ff0000,#0000ff", "between" to "CUT")),
                ),
                cue,
            )

            val palettes = DaoPalette.all().associateBy { it.name }
            val orbit = palettes.getValue("Orbit").effect!!
            assertEquals("Path" to "position", orbit.fxType to orbit.family)
            assertEquals(mapOf("shape" to "CIRCLE") + centre + mapOf("panSize" to "64", "tiltSize" to "64"), orbit.parameters)
            assertEquals(
                mapOf("colours" to "#ff8000,#0080ff", "between" to "FADE", "fade" to "0.75"),
                palettes.getValue("Warm to cool").effect!!.parameters,
            )

            assertTrue("effect(\"Circle\"" in DaoScript.all().single().script, "a script is left as it is: it still runs")
        }
    }

    @Test
    fun `a script or FX definition looking a movement up, and a definition named Path, are named, never rewritten`() {
        val lookup = """registerFx(fxRegistry.getRegistration("Sweep")!!.copy(id = "my-sweep"))"""
        val desk = startDesk()
        transaction(desk.database) {
            val project = DaoProject.new { name = "Old lookups" }
            DaoScript.new { this.project = project; name = "Looks up"; script = """val p = fxRegistry.createFx("Figure8")""" }
            DaoScript.new { this.project = project; name = "Cuts"; script = """val c = fxRegistry.createFx("ColourCycle", mapOf("fadeRatio" to "0"))""" }
            // Palette Steps still reads its own fadeRatio, so this one is not named.
            DaoScript.new { this.project = project; name = "Steps"; script = """val s = fxRegistry.createFx("PaletteSteps", mapOf("fadeRatio" to "0.5"))""" }
            DaoFxDefinition.new {
                this.project = project; fxId = "my-sweep"; name = "My Sweep"; family = "position"
                outputType = FxOutputType.POSITION; script = lookup
            }
            DaoFxDefinition.new {
                this.project = project; fxId = "Path"; name = "My Path"; family = "position"
                outputType = FxOutputType.POSITION; script = "FxOutput.Position(0u, 0u)"
            }
        }
        stopDesk()
        setUserVersion(FX_CONSOLIDATED_USER_VERSION)

        val (reopened, logs) = captureLogs("FxSiblingsMigration") { startDesk() }
        val named = logs.filter { "FX siblings:" in it && " in 'Old lookups'" in it }
        assertEquals(4, named.size, logs.joinToString("\n"))
        assertTrue(named.any { "script 'Cuts' in 'Old lookups' names fadeRatio, parameters renamed" in it })
        assertTrue(named.any { "script 'Looks up' in 'Old lookups' names Figure8" in it })
        assertTrue(named.any { "FX definition 'my-sweep' in 'Old lookups' names Sweep" in it })
        assertTrue(named.any { "FX definition 'Path' in 'Old lookups' takes the built-in Path's id" in it })
        assertEquals(emptyList(), backups(), "nothing is rewritten, so nothing is backed up")
        transaction(reopened.database) {
            assertEquals(lookup, DaoFxDefinition.all().single { it.fxId == "my-sweep" }.script)
        }
    }

    @Test
    fun `a fresh database is born at step 8`() {
        startDesk()
        assertEquals(FX_SIBLINGS_USER_VERSION, userVersion())
        assertEquals(emptyList(), backups())
    }
}
