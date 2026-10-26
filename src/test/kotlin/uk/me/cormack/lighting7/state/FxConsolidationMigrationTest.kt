package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.fx.AttributeFamily
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.models.DaoCueAdHocFxEntity
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoLookFxEntity
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.routes.familyOf
import uk.me.cormack.lighting7.scripts.ScriptType
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
 * Database step 7 (fx-settings plan session 2), run the way a desk meets it — inside `State`'s
 * `initDatabase` — over `src/test/resources/db/pre-fx-consolidation.sql`: a database at step 6 whose
 * Look, cue and palettes hold every level wave Wave replaced, a beam FX naming no attribute among
 * them, beside a Flicker and a script that names `SineWave`.
 */
class FxConsolidationMigrationTest {

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
        val sql = javaClass.getResourceAsStream("/db/pre-fx-consolidation.sql")!!.readBytes().toString(Charsets.UTF_8)
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
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-fx-consolidation-") }.toList() }
    }

    /** One stored FX as the test reads it back. */
    private data class Row(
        val fxType: String,
        val family: String,
        val attribute: String?,
        val parameters: Map<String, String>,
        val cycle: Double = 4.0,
        val direction: String = "FORWARD",
    )

    @Test
    fun `every level wave is Wave, backed up first, and runs as it did`() {
        loadOldDatabase()
        assertEquals(ATTRIBUTE_NAMES_USER_VERSION, userVersion())

        val (desk, logs) = captureLogs("FxConsolidationMigration") { startDesk() }

        assertEquals(FX_CONSOLIDATED_USER_VERSION, userVersion())
        assertEquals(1, backups().size, "the database is backed up before anything moves")
        assertTrue(logs.any { "Look 'Levels' in 'Old waves': SineWave — SineWave is now Wave, shape SINE" in it }, logs.joinToString("\n"))
        assertTrue(logs.any { "Cue 'Q1' in 'Old waves': IrisPulse" in it && "it names 'iris'" in it }, logs.joinToString("\n"))
        assertTrue(
            logs.any { "Palette 'Frost breathe'" in it && "output moves: on a group where not every head has 'frost' it now runs on those that do" in it },
            "the one output a migration moves is named, by its owner (the plan's recorded exception to D3)",
        )
        assertTrue(logs.any { "'stale' named nothing it read" in it }, logs.joinToString("\n"))

        transaction(desk.database) {
            val look = DaoLookFxEntity.all().sortedBy { it.sortOrder }
                .map { Row(it.fxType, it.family, it.attribute, it.parameters, it.cycle, it.direction) }
            assertEquals(
                listOf(
                    Row("Wave", "intensity", "dimmer", mapOf("shape" to "SINE", "low" to "40", "high" to "255")),
                    Row("Wave", "intensity", "dimmer", mapOf("shape" to "RAMP_UP", "low" to "10", "high" to "240", "curve" to "QUAD_IN")),
                    Row("Wave", "intensity", "uv", mapOf("shape" to "RAMP_DOWN", "low" to "0", "high" to "255", "curve" to "LINEAR")),
                    Row("Wave", "intensity", "dimmer", mapOf("shape" to "TRIANGLE", "low" to "0", "high" to "200", "curve" to "LINEAR"), direction = "BOUNCE"),
                    Row("Wave", "intensity", "dimmer", mapOf("shape" to "SQUARE", "low" to "0", "high" to "255", "width" to "0.25")),
                    Row(
                        "Wave", "intensity", "dimmer",
                        mapOf("shape" to "PULSE", "low" to "0", "high" to "255", "attack" to "0.2", "hold" to "0.3", "curve" to "QUAD_OUT"),
                    ),
                    Row("Wave", "intensity", "dimmer", mapOf("shape" to "SQUARE", "low" to "0", "high" to "255", "width" to "0.05"), cycle = 0.5),
                    Row("Flicker", "intensity", "dimmer", mapOf("min" to "20")),
                ),
                look,
            )

            val cue = DaoCueAdHocFxEntity.all().sortedBy { it.sortOrder }
                .map { Row(it.fxType, it.family, it.attribute, it.parameters, it.cycle, it.direction) }
            assertEquals(
                listOf(
                    Row("Wave", "intensity", "zoom", mapOf("shape" to "SINE", "low" to "64", "high" to "192")),
                    Row(
                        "Wave", "intensity", "iris",
                        mapOf("shape" to "PULSE", "low" to "0", "high" to "200", "attack" to "0.1", "hold" to "0.3", "curve" to "QUAD_OUT"),
                    ),
                    Row(
                        "Wave", "intensity", "focus",
                        mapOf("shape" to "RAMP_UP", "low" to "80", "high" to "160", "curve" to "SINE_IN_OUT"),
                        cycle = 2.0, direction = "BOUNCE",
                    ),
                ),
                cue,
            )

            val palettes = DaoPalette.all().associateBy { it.name }
            val frost = palettes.getValue("Frost breathe").effect!!
            assertEquals("Wave" to "frost", frost.fxType to frost.attribute)
            assertEquals(
                mapOf("shape" to "PULSE", "low" to "0", "high" to "160", "attack" to "0.1", "hold" to "0.3", "curve" to "QUAD_OUT"),
                frost.parameters,
            )
            val strobe = palettes.getValue("Strobe").effect!!
            assertEquals("Wave" to null, strobe.fxType to strobe.attribute)
            assertEquals(mapOf("shape" to "SQUARE", "low" to "0", "high" to "200", "width" to "0.1"), strobe.parameters)

            // A Wave on frost is a Beam palette, on the dimmer an Intensity one (D5), as they were.
            assertEquals(AttributeFamily.BEAM, palettes.getValue("Frost breathe").familyOf())
            assertEquals(AttributeFamily.INTENSITY, palettes.getValue("Strobe").familyOf())

            assertTrue("effect(\"SineWave\"" in DaoScript.all().single().script, "a script is left as it is: it still runs")
        }
    }

    @Test
    fun `a script or FX definition looking an old id up, and a definition named Wave, are named, never rewritten`() {
        val lookup = """registerFx(fxRegistry.getRegistration("Pulse")!!.copy(id = "my-pulse"))"""
        val desk = startDesk()
        transaction(desk.database) {
            val project = DaoProject.new { name = "Old lookups" }
            DaoScript.new { this.project = project; name = "Runs"; script = """dimmer(effect("SineWave", "min" to "40"))"""; scriptType = ScriptType.FX_APPLICATION }
            DaoScript.new { this.project = project; name = "Looks up"; script = """val p = fxRegistry.createFx("Ramp")""" }
            DaoFxDefinition.new {
                this.project = project; fxId = "my-pulse"; name = "My Pulse"; family = "intensity"
                outputType = FxOutputType.SLIDER; script = lookup
            }
            DaoFxDefinition.new {
                this.project = project; fxId = "Wave"; name = "My Wave"; family = "intensity"
                outputType = FxOutputType.SLIDER; script = "FxOutput.Slider(0u)"
            }
        }
        stopDesk()
        setUserVersion(ATTRIBUTE_NAMES_USER_VERSION)

        val (reopened, logs) = captureLogs("FxConsolidationMigration") { startDesk() }
        val named = logs.filter { "FX consolidation:" in it }
        assertEquals(3, named.size, logs.joinToString("\n"))
        assertTrue(named.any { "script 'Looks up' in 'Old lookups' names Ramp" in it })
        assertTrue(named.any { "FX definition 'my-pulse' in 'Old lookups' names Pulse" in it })
        assertTrue(named.any { "FX definition 'Wave' in 'Old lookups' takes the built-in Wave's id" in it })
        assertEquals(emptyList(), backups(), "nothing is rewritten, so nothing is backed up")
        transaction(reopened.database) {
            assertEquals(lookup, DaoFxDefinition.all().single { it.fxId == "my-pulse" }.script)
        }
    }

    @Test
    fun `a database with nothing to rewrite is marked without a backup`() {
        startDesk()
        stopDesk()
        setUserVersion(ATTRIBUTE_NAMES_USER_VERSION)
        startDesk()
        assertEquals(FX_CONSOLIDATED_USER_VERSION, userVersion())
        assertEquals(emptyList(), backups())
    }

    @Test
    fun `a fresh database is born at step 7`() {
        startDesk()
        assertEquals(FX_CONSOLIDATED_USER_VERSION, userVersion())
        assertEquals(emptyList(), backups())
    }
}
