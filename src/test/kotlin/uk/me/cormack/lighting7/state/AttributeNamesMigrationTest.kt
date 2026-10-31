package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoCueAdHocFxEntity
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookFxEntity
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.scripts.ScriptType
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Database step 6 (terminology plan session 6), run the way a desk meets it: a database at step 5
 * holding the surface bindings' and the position FX's old names, reopened.
 */
class AttributeNamesMigrationTest {

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
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-attribute-") }.toList() }
    }

    private fun binding(project: DaoProject, control: String, type: String, payload: String) = DaoControlSurfaceBinding.new {
        this.project = project; deviceTypeKey = "xtouch-mini"; controlId = control; targetType = type; targetPayload = payload
    }

    @Test
    fun `a database at step 5 takes the attribute names, backed up first`() {
        val desk = startDesk()
        transaction(desk.database) {
            val project = DaoProject.new { name = "Old names" }
            binding(project, "fader-1", "fixtureProperty", """{"type":"fixtureProperty","fixtureKey":"hex-1","propertyName":"dimmer"}""")
            binding(project, "fader-2", "groupProperty", """{"type":"groupProperty","groupName":"wash","propertyName":"rgbColour","colourAxis":"saturation"}""")
            binding(project, "enc-1", "selectionProperty", """{"type":"selectionProperty","propertyName":"pan"}""")
            binding(project, "button-1", "encoderBankSet", """{"type":"encoderBankSet","propertyName":"tilt"}""")
            binding(project, "button-2", "flash", """{"type":"flash","target":{"type":"groupProperty","groupName":"wash","propertyName":"dimmer"},"max":200}""")
            binding(project, "button-3", "blackout", """{"type":"blackout"}""")
            val look = DaoLook.new { this.project = project; name = "Swirl" }
            DaoLookFxEntity.new {
                this.look = look; targetType = "group"; targetKey = "movers"
                fxType = "Circle"; family = "position"; attribute = "position"
                cycle = 4.0; blendMode = "OVERRIDE"; order = "NORMAL"
                parameters = mapOf("panCenter" to "100", "tiltCenter" to "140", "panRadius" to "30")
            }
            val cue = DaoCue.new { this.project = project; name = "Q1" }
            DaoCueAdHocFxEntity.new {
                this.cue = cue; targetType = "group"; targetKey = "movers"
                fxType = "Flicker"; family = "intensity"; attribute = "dimmer"
                cycle = 1.0; blendMode = "OVERRIDE"; order = "NORMAL"
                parameters = mapOf("panCenter" to "1")
            }
        }
        stopDesk()
        setUserVersion(TERMINOLOGY_RENAMED_USER_VERSION)

        val reopened = startDesk()
        assertEquals(MOVE_IN_DARK_ROW_FLAG_USER_VERSION, userVersion(), "steps 7 to 9 follow")
        assertEquals(1, backups().size, "the database is backed up before anything moves")
        transaction(reopened.database) {
            val bindings = DaoControlSurfaceBinding.all().associate { it.controlId to (it.targetType to it.targetPayload) }
            assertEquals("fixtureAttribute" to """{"type":"fixtureAttribute","fixtureKey":"hex-1","attribute":"dimmer"}""", bindings["fader-1"])
            assertEquals(
                "groupAttribute" to """{"type":"groupAttribute","groupName":"wash","attribute":"rgbColour","colourAxis":"saturation"}""",
                bindings["fader-2"],
            )
            assertEquals("selectionAttribute" to """{"type":"selectionAttribute","attribute":"pan"}""", bindings["enc-1"])
            assertEquals("encoderBankSet" to """{"type":"encoderBankSet","attribute":"tilt"}""", bindings["button-1"])
            assertEquals(
                "flash" to """{"type":"flash","target":{"type":"groupAttribute","groupName":"wash","attribute":"dimmer"},"max":200}""",
                bindings["button-2"],
            )
            assertEquals("blackout" to """{"type":"blackout"}""", bindings["button-3"])
            // Step 8 then makes the Circle a Path, its centres kept as step 6 named them.
            assertEquals(
                mapOf("shape" to "CIRCLE", "panCentre" to "100", "tiltCentre" to "140", "panSize" to "30", "tiltSize" to "64"),
                DaoLookFxEntity.all().single().parameters,
            )
            assertEquals(mapOf("panCenter" to "1"), DaoCueAdHocFxEntity.all().single().parameters, "only the position FX's centres are renamed")
        }
    }

    @Test
    fun `a script or FX definition saying an old centre is named, never rewritten`() {
        val circle = """wash.fx { position(effect("Circle", "panCenter" to "100"), CycleLength.ONE_BAR) }"""
        val desk = startDesk()
        transaction(desk.database) {
            val project = DaoProject.new { name = "Old centres" }
            DaoScript.new { this.project = project; name = "Swirl"; script = circle; scriptType = ScriptType.FX_APPLICATION }
            DaoScript.new { this.project = project; name = "Plain"; script = "setBpm(120.0)" }
            DaoFxDefinition.new {
                this.project = project; fxId = "my-circle"; name = "My Circle"; family = "position"
                outputType = FxOutputType.POSITION; script = "registerFx(...)"
                parameters = listOf(ParameterInfo("tiltCenter", "int", "128"))
            }
        }
        stopDesk()
        setUserVersion(TERMINOLOGY_RENAMED_USER_VERSION)

        val (reopened, logs) = captureLogs("AttributeNamesMigration") { startDesk() }
        val named = logs.filter { "panCentre and tiltCentre now" in it }
        assertEquals(2, named.size, logs.joinToString("\n"))
        assertTrue(named.any { "script 'Swirl' in 'Old centres'" in it })
        assertTrue(named.any { "FX definition 'My Circle' in 'Old centres'" in it })
        assertEquals(emptyList(), backups(), "nothing is rewritten, so nothing is backed up")
        transaction(reopened.database) {
            assertEquals(circle, DaoScript.all().single { it.name == "Swirl" }.script)
            assertEquals("tiltCenter", DaoFxDefinition.all().single().parameters.single().name)
        }
    }

    @Test
    fun `a database with nothing to rename is marked without a backup`() {
        startDesk()
        stopDesk()
        setUserVersion(TERMINOLOGY_RENAMED_USER_VERSION)
        startDesk()
        assertEquals(MOVE_IN_DARK_ROW_FLAG_USER_VERSION, userVersion(), "steps 7 to 9 follow")
        assertEquals(emptyList(), backups())
    }

    @Test
    fun `a fresh database is born past step 6`() {
        startDesk()
        assertEquals(MOVE_IN_DARK_ROW_FLAG_USER_VERSION, userVersion(), "steps 7 to 9 follow")
        assertEquals(emptyList(), backups())
    }
}
