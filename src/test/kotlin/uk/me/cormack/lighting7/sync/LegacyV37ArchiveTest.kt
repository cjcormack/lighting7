package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoSpeedMaster
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.sync.dto.ProjectJson
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.assertExportsEqual
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `src/test/resources/sync/legacy-v37/` is `RichProjectFixture` as a desk on main exported it at
 * `formatVersion` 37, before the archive took the glossary's words (terminology plan session 4).
 * Frozen: never regenerate it from a later commit. It imports, every old folder, field and value
 * lands on today's, and re-exported it is the same archive with only its names moved.
 */
class LegacyV37ArchiveTest {

    private lateinit var state: State
    private lateinit var scratch: Path

    private val frozen: Path = Path.of("src/test/resources/sync/legacy-v37")

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        scratch = Files.createTempDirectory("legacy-v37-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { scratch.toFile().deleteRecursively() }
    }

    @Test
    fun `the frozen archive is v37 and holds the old names`() {
        assertEquals(37, canonicalDecode(FormatVersionJson.serializer(), Files.readString(frozen.resolve("formatVersion.json"))).formatVersion)
        assertTrue(frozen.resolve("templates").isDirectory() && frozen.resolve("cueAdHocEffects").isDirectory())
        val text = Files.walk(frozen).use { files -> files.filter { it.name.endsWith(".json") }.map(Files::readString).toList() }.joinToString("\n")
        for (old in listOf("\"effectType\"", "\"beatDivision\"", "\"distribution\"", "\"templateUuid\"", "tmpl:", "pressTemplate", "\"usage\": \"dimmer\"")) {
            assertTrue(old in text, "the frozen archive says $old")
        }
    }

    @Test
    fun `a v37 archive imports onto today's names`() {
        val imported = ProjectImporter(state).import(frozen, nameOverride = null).projectId

        transaction(state.database) {
            data class Stored(val order: String, val family: String, val timing: String?)
            val fx = DaoLookEffect.all().filter { it.look.project.id.value == imported }.map { Stored(it.order, it.family, it.timing) } +
                DaoCueAdHocEffect.all().filter { it.cue.project.id.value == imported }.map { Stored(it.order, it.family, it.timing) }
            assertTrue(fx.isNotEmpty())
            assertTrue(fx.all { it.order in ORDERS }, "${fx.map { it.order }}")
            assertTrue(fx.none { it.family == "dimmer" } && fx.any { it.family == "intensity" }, "${fx.map { it.family }}")
            assertTrue(fx.mapNotNull { it.timing }.toSet().let { it.isNotEmpty() && it.all { t -> t == "CYCLE" || t == "HEAD" } })

            val palettes = DaoTemplate.all().filter { it.project.id.value == imported }
            assertEquals(3, palettes.size)
            val paletteFx = palettes.mapNotNull { it.effect }.single()
            assertTrue(paletteFx.parameters.values.any { it.startsWith("pal:") } && paletteFx.parameters.values.none { "tmpl:" in it })

            assertEquals(listOf("intensity", "position"), DaoSpeedMaster.all().filter { it.project.id.value == imported }.mapNotNull { it.family }.sorted())

            val press = DaoControlSurfaceBinding.all().single { it.project.id.value == imported && it.controlId == "button-8" }
            assertEquals("pressPalette", press.targetType)
            assertTrue(press.targetPayload.startsWith("""{"type":"pressPalette","paletteUuid":"""), press.targetPayload)
        }
    }

    @Test
    fun `re-exported, it is the frozen archive with only its names moved`() {
        val imported = ProjectImporter(state).import(frozen, nameOverride = null).projectId
        val exported = scratch.resolve("exported")
        ProjectExporter(state).export(imported, exported)

        // The frozen archive put into today's words by hand: each folder's records through the
        // importer's own map, the version today's and `fxLibrary` gone, since v38 implies it.
        val expected = scratch.resolve("expected")
        Files.walk(frozen).use { files -> files.toList() }.filter(Files::isRegularFile).forEach { file ->
            val rel = frozen.relativize(file)
            val folder = rel.getName(0).toString()
            val target = if (rel.nameCount > 1) {
                expected.resolve(LegacyNames.folder(folder)).resolve(rel.subpath(1, rel.nameCount).toString())
            } else {
                expected.resolve(rel.toString())
            }
            Files.createDirectories(target.parent)
            when {
                rel.toString() == "formatVersion.json" ->
                    Files.writeString(target, canonicalEncode(FormatVersionJson.serializer(), FormatVersionJson()))
                rel.toString() == "project.json" -> {
                    val project = canonicalDecode(ProjectJson.serializer(), Files.readString(file))
                    Files.writeString(target, canonicalEncode(ProjectJson.serializer(), project.copy(fxLibrary = null)))
                }
                file.name.endsWith(".json") -> Files.writeString(target, LegacyNames.record(LegacyNames.folder(folder), Files.readString(file)))
                else -> Files.copy(file, target)
            }
        }

        assertExportsEqual(expected, exported, installsShapeOnly = true)
    }

    private companion object {
        val ORDERS = setOf("NORMAL", "REVERSE", "CENTRE_OUT", "CENTRE_IN", "RANDOM", "PING_PONG", "POSITIONAL", "UNIFIED")
    }
}
