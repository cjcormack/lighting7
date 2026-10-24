package uk.me.cormack.lighting7.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.fx.FxMode
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocFxEntity
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookFxEntity
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteFxEntity
import uk.me.cormack.lighting7.models.DaoPaletteRow
import uk.me.cormack.lighting7.scripts.ScriptType
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * An archive written before the FX library changed (terminology plan session 3) imports onto
 * today's names: the old FX ids, Breathe, `pingPong`, `SPLIT`, Colour Cycle's palettes and the
 * composite mode, each through [LegacyNames] — whatever the archive's `formatVersion`, since the
 * maps are by field (P3).
 *
 * The archive is this desk's own export of rows stored under the old names, with the two the
 * schema can no longer hold (a composite FX definition and script) written into its JSON.
 */
class LegacyFxArchiveImportTest {

    private lateinit var state: State
    private lateinit var archive: Path

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        archive = Files.createTempDirectory("legacy-fx-archive-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { archive.toFile().deleteRecursively() }
    }

    private class Ids(val red: String, val blue: String)

    private fun seedOldNames(projectId: Int): Ids = transaction(state.database) {
        val project = DaoProject[projectId]
        fun colourPalette(name: String, hex: String) = DaoPalette.new { this.project = project; this.name = name }.also {
            DaoPaletteRow.new {
                palette = it; targetType = DEFERRED_TARGET_TYPE; targetKey = ""
                attribute = "rgbColour"; value = "$hex;policy=rgbonly"
            }
        }
        val red = colourPalette("Red", "#ff0000").uuid.toString()
        val blue = colourPalette("Blue", "#0000ff").uuid.toString()

        val look = DaoLook.new { this.project = project; name = "Old FX" }
        fun lookFx(
            fxType: String, category: String, attribute: String, parameters: Map<String, String>, sort: Int,
            runs: Int? = null, spread: Double = 1.0,
        ) = DaoLookFxEntity.new {
            this.look = look; targetType = "fixture"; targetKey = "hex-1"
            this.fxType = fxType; this.family = category; this.attribute = attribute
            cycle = 4.0; blendMode = "OVERRIDE"; order = "NORMAL"; phaseOffset = 0.5
            this.parameters = parameters; sortOrder = sort; this.runs = runs; this.spread = spread
        }
        lookFx("Breathe", "dimmer", "dimmer", mapOf("min" to "0", "max" to "255"), 0)
        lookFx("Triangle", "dimmer", "dimmer", emptyMap(), 1)
        lookFx("Sweep", "position", "position", mapOf("pingPong" to "true"), 2, runs = 2, spread = 0.75)
        lookFx("PanSweep", "position", "position", emptyMap(), 3)
        lookFx("ColourCycle", "colour", "rgbColour", mapOf("colours" to "pal:$red,pal:$blue", "fadeRatio" to "0.25"), 4)

        val cue = DaoCue.new { this.project = project; name = "Old cue" }
        DaoCueAdHocFxEntity.new {
            this.cue = cue; targetType = "fixture"; targetKey = "hex-1"
            fxType = "ColourPulse"; family = "colour"; attribute = "rgbColour"
            cycle = 1.0; blendMode = "OVERRIDE"; order = "SPLIT"; parameters = emptyMap()
        }
        val strike = DaoPalette.new { this.project = project; name = "Strike" }
        DaoPaletteFxEntity.new {
            palette = strike; fxType = "LightningStrike"; family = "composite"; attribute = "dimmer"
            cycle = 4.0; blendMode = "OVERRIDE"; order = "NORMAL"
            parameters = mapOf("maxBrightness" to "200", "flashColour" to "#ffffff")
        }
        DaoFxDefinition.new {
            this.project = project; fxId = "my-strike"; name = "My Strike"; family = "intensity"
            outputType = FxOutputType.SLIDER; fxMode = FxMode.STANDARD; script = "mapOf()"
            timingSource = TimingSource.BEAT
        }
        DaoScript.new { this.project = project; name = "composite calc"; script = "mapOf()"; scriptType = ScriptType.FX_CALC }
        Ids(red, blue)
    }

    /** [folder]'s one document, edited as JSON. */
    private fun editOne(folder: String, suffix: String, edit: (JsonObject) -> JsonObject) {
        val file = Files.list(archive.resolve(folder)).use { files -> files.toList().single { it.fileName.toString().endsWith(suffix) } }
        val edited = edit(canonicalJson.parseToJsonElement(Files.readString(file)).jsonObject)
        Files.writeString(file, canonicalJson.encodeToString(JsonObject.serializer(), edited))
    }

    /** Export the old rows, then wipe the desk so the archive imports onto an empty one. */
    private fun exportOldNames(): Ids {
        val ids = seedOldNames(seedMinimalProject(state, projectName = "Legacy"))
        val projectId = transaction(state.database) { DaoProject.all().single { it.name == "Legacy" }.id.value }
        ProjectExporter(state).export(projectId, archive)
        return ids
    }

    private fun wipeAndImport(): Int {
        state.shutdown()
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        return ProjectImporter(state).import(archive, nameOverride = "Imported").projectId
    }

    private fun importedLookFx(projectId: Int) = transaction(state.database) {
        val look = DaoLook.all().single { it.project.id.value == projectId && it.name == "Old FX" }
        DaoLookFxEntity.all().filter { it.look.id == look.id }.sortedBy { it.sortOrder }
            .map { listOf(it.fxType, it.cycle, it.direction) }
    }

    @Test
    fun `an archive written before the FX library changed reads a missing pingPong as on`() {
        exportOldNames()
        // A v37 archive from before session 3 carries no marker; from v38 the version says it.
        editOne(".", "formatVersion.json") { JsonObject(it + ("formatVersion" to JsonPrimitive(37))) }
        editOne(".", "project.json") { JsonObject(it - "fxLibrary") }
        val projectId = wipeAndImport()
        val panSweep = importedLookFx(projectId)[3]
        assertEquals(listOf("PanSweep", 2.0, "BOUNCE"), panSweep)
    }

    @Test
    fun `an archive holding the old names imports onto today's`() {
        val ids = exportOldNames()
        assertEquals(null, canonicalJson.parseToJsonElement(Files.readString(archive.resolve("project.json"))).jsonObject["fxLibrary"], "v38 implies the FX library")
        editOne("fxDefinitions", ".json") { JsonObject(it + mapOf("effectMode" to JsonPrimitive("COMPOSITE"), "category" to JsonPrimitive("composite"))) }
        editOne("scripts", ".meta.json") { JsonObject(it + ("scriptType" to JsonPrimitive("FX_CALC_COMPOSITE"))) }
        val importedId = wipeAndImport()

        transaction(state.database) {
            val project = DaoProject[importedId]
            val look = DaoLook.all().single { it.project.id == project.id && it.name == "Old FX" }
            val effects = DaoLookFxEntity.all().filter { it.look.id == look.id }.sortedBy { it.sortOrder }

            val (sine, ramp, sweep, panSweep, steps) = effects
            assertEquals("SineWave", sine.fxType)
            assertEquals(0.25, sine.phaseOffset, 1e-12)
            assertEquals("FORWARD", sine.direction)
            assertEquals("Ramp", ramp.fxType)

            assertEquals(listOf(2.0, 1.0, 4, 1.5, "BOUNCE"), listOf(sweep.cycle, sweep.phaseOffset, sweep.runs, sweep.spread, sweep.direction))
            assertEquals(emptyMap(), sweep.parameters)
            // An archive this desk wrote (v38) reads an FX that names no pingPong as today's.
            assertEquals(listOf(4.0, "FORWARD"), listOf(panSweep.cycle, panSweep.direction))

            assertEquals("PaletteSteps", steps.fxType)
            assertEquals(mapOf("palettes" to "pal:${ids.red},pal:${ids.blue}", "fadeRatio" to "0.25"), steps.parameters)

            val cueFx = DaoCueAdHocFxEntity.all().single { it.cue.project.id == project.id }
            assertEquals(listOf("ColourWave", "CENTRE_IN"), listOf(cueFx.fxType, cueFx.order))

            val strike = DaoPaletteFxEntity.all().single { it.palette.project.id == project.id }
            assertEquals("intensity", strike.family)
            assertEquals(mapOf("maxBrightness" to "200"), strike.parameters)

            val definition = DaoFxDefinition.all().single { it.project.id == project.id }
            assertEquals(FxMode.STANDARD, definition.fxMode)
            assertEquals("intensity", definition.family)
            assertEquals(ScriptType.FX_CALC, DaoScript.all().single { it.project.id == project.id }.scriptType)
        }
    }
}
