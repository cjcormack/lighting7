package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.PaletteLayerConversion
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.CueLayerJson
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A v46 archive's plain cue palette layers import as reference rows (palette-references plan D17),
 * by the decision database step 10 makes, and every other palette layer as the layer it was.
 */
class PaletteLayerArchiveImportTest {

    private lateinit var state: State
    private lateinit var dir: Path

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        dir = Files.createTempDirectory("palette-layer-archive-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { dir.toFile().deleteRecursively() }
    }

    private fun exportAt(formatVersion: Int): Pair<CueLayerJson, CueLayerJson> {
        ProjectExporter(state).export(seedRichProject(state), dir)
        Files.writeString(dir.resolve("formatVersion.json"), canonicalEncode(FormatVersionJson.serializer(), FormatVersionJson(formatVersion, 5)))
        val plain = Files.list(dir.resolve("cueLayers")).use { files -> files.toList() }
            .map { canonicalDecode(CueLayerJson.serializer(), Files.readString(it)) }
            .single { it.paletteUuid != null }
        val blended = plain.copy(uuid = UUID.randomUUID().toString(), sortOrder = 0, targets = listOf(CueTargetDto("fixture", "hex-3")), blendMode = "MAX")
        Files.writeString(dir.resolve("cueLayers/${blended.uuid}.json"), canonicalEncode(CueLayerJson.serializer(), blended))
        runCatching { state.shutdown() }
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        return plain to blended
    }

    private fun cue1(projectId: Int) = DaoCue.find { DaoCues.project eq projectId }.single { cue -> cue.layers.any { it.palette != null } }

    @Test
    fun `a v46 archive's plain palette layer arrives as reference rows, a blended one as a layer`() {
        val (plain, blended) = exportAt(46)
        val imported = ProjectImporter(state).import(dir, nameOverride = null)

        transaction(state.database) {
            val cue = cue1(imported.projectId)
            assertEquals(listOf(blended.uuid), cue.layers.filter { it.palette != null }.map { it.uuid.toString() })
            val hex2 = TargetRef.Fixture("hex-2")
            val row = cue.attributeAssignments.single { it.target == hex2 && it.attribute == "rgbColour" }
            assertEquals("pal:${plain.paletteUuid}", row.value)
            assertEquals(UUID.fromString(plain.paletteUuid), row.paletteUuid)
            assertEquals(PaletteLayerConversion.rowUuid(UUID.fromString(plain.uuid), hex2, "rgbColour"), row.uuid, "named, so two desks pulling it agree")
            assertTrue(row.sortOrder > cue.attributeAssignments.filter { it.uuid != row.uuid }.maxOf { it.sortOrder })
        }
    }

    @Test
    fun `a v47 archive's palette layers import as they are`() {
        val (plain, blended) = exportAt(47)
        val imported = ProjectImporter(state).import(dir, nameOverride = null)

        transaction(state.database) {
            val cue = cue1(imported.projectId)
            assertEquals(setOf(plain.uuid, blended.uuid), cue.layers.filter { it.palette != null }.map { it.uuid.toString() }.toSet())
        }
    }
}
