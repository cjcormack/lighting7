package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.BindingTargetJson
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoLookFxEntity
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.assertExportsEqual
import uk.me.cormack.lighting7.testsupport.consolidatedRecord
import uk.me.cormack.lighting7.testsupport.liftRowMoveInDark
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `src/test/resources/sync/legacy-v38/` is `RichProjectFixture` as a desk on main exported it at
 * `formatVersion` 38, with a Circle, a group binding and a flash added for the names v39 moved
 * (terminology plan session 6). Frozen: never regenerate it from a later commit. It imports, every
 * surface binding and centre parameter lands on today's names, and re-exported it is the same
 * archive with only those names moved.
 */
class LegacyV38ArchiveTest {

    private lateinit var state: State
    private lateinit var scratch: Path

    private val frozen: Path = Path.of("src/test/resources/sync/legacy-v38")

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        scratch = Files.createTempDirectory("legacy-v38-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { scratch.toFile().deleteRecursively() }
    }

    @Test
    fun `the frozen archive is v38 and holds the old names`() {
        assertEquals(38, canonicalDecode(FormatVersionJson.serializer(), Files.readString(frozen.resolve("formatVersion.json"))).formatVersion)
        val text = Files.walk(frozen).use { files -> files.filter { it.name.endsWith(".json") }.map(Files::readString).toList() }.joinToString("\n")
        for (old in listOf("fixtureProperty", "groupProperty", "selectionProperty", "\\\"propertyName\\\"", "\"panCenter\"", "\"tiltCenter\"")) {
            assertTrue(old in text, "the frozen archive says $old")
        }
    }

    @Test
    fun `a v38 archive imports onto today's names`() {
        val imported = ProjectImporter(state).import(frozen, nameOverride = null).projectId

        transaction(state.database) {
            val bindings = DaoControlSurfaceBinding.all().filter { it.project.id.value == imported }.associateBy { it.controlId }
            for (binding in bindings.values) {
                assertTrue("Property" !in binding.targetType && "propertyName" !in binding.targetPayload, binding.targetPayload)
            }
            val fader = BindingTargetJson.decodeFromString<BindingTarget>(bindings.getValue("fader-3").targetPayload)
            assertEquals("rgbColour", assertIs<BindingTarget.GroupAttribute>(fader).attribute)
            val flash = assertIs<BindingTarget.Flash>(BindingTargetJson.decodeFromString<BindingTarget>(bindings.getValue("button-13").targetPayload))
            assertEquals("dimmer", assertIs<BindingTarget.FixtureAttribute>(flash.target).attribute)
            assertEquals("selectionAttribute", bindings.getValue("enc-1").targetType)
            val encoder = BindingTargetJson.decodeFromString<BindingTarget>(bindings.getValue("button-6").targetPayload)
            assertEquals("pan", assertIs<BindingTarget.EncoderBankSet>(encoder).attribute)

            // Its Circle's centres take their new spelling, and the importer then stores it as Path.
            val circle = DaoLookFxEntity.all().single { it.look.project.id.value == imported && it.fxType == "Path" }
            assertEquals(
                mapOf("shape" to "CIRCLE", "panCentre" to "100", "tiltCentre" to "140", "panSize" to "30", "tiltSize" to "64"),
                circle.parameters,
            )
        }
    }

    @Test
    fun `re-exported, it is the frozen archive with only its names moved`() {
        val imported = ProjectImporter(state).import(frozen, nameOverride = null).projectId
        val exported = scratch.resolve("exported")
        ProjectExporter(state).export(imported, exported)

        // The frozen archive put into today's words by hand: each folder's records through the
        // importer's own map, its level waves as Wave and its movements as Path, and the version today's.
        val expected = scratch.resolve("expected")
        Files.walk(frozen).use { files -> files.toList() }.filter(Files::isRegularFile).forEach { file ->
            val rel = frozen.relativize(file)
            val folder = rel.getName(0).toString()
            val target = expected.resolve(rel.toString())
            Files.createDirectories(target.parent)
            when {
                rel.toString() == "formatVersion.json" ->
                    Files.writeString(target, canonicalEncode(FormatVersionJson.serializer(), FormatVersionJson()))
                rel.nameCount > 1 && file.name.endsWith(".json") ->
                    Files.writeString(target, consolidatedRecord(LegacyNames.record(folder, Files.readString(file))))
                else -> Files.copy(file, target)
            }
        }

        liftRowMoveInDark(expected)
        assertExportsEqual(expected, exported, installsShapeOnly = true)
    }
}
