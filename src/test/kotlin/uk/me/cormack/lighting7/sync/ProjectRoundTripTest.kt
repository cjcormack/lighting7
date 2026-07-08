package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.fx.EffectMode
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCuePresetApplication
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueSlot
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueTrigger
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoFxPreset
import uk.me.cormack.lighting7.models.DaoFxPresetPropertyAssignment
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoPromptBook
import uk.me.cormack.lighting7.models.DaoPromptBookAnchor
import uk.me.cormack.lighting7.models.DaoPromptBookAnnotation
import uk.me.cormack.lighting7.models.PromptBookRectDto
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.DaoShowEntry
import uk.me.cormack.lighting7.models.DaoStageRegion
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.FxPresetEffectDto
import uk.me.cormack.lighting7.models.TriggerType
import uk.me.cormack.lighting7.scripts.ScriptType
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.InstallsJson
import uk.me.cormack.lighting7.sync.dto.UniverseConfigJson
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1 verification: build a project, export it, wipe the DB, import it, re-export, and
 * assert byte-for-byte identical output. This exercises FK-by-UUID rewriting, canonical
 * determinism, and topological insert order in one scenario.
 */
class ProjectRoundTripTest {

    private lateinit var state: State
    private lateinit var exportDirA: Path
    private lateinit var exportDirB: Path

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        exportDirA = Files.createTempDirectory("sync-export-a-")
        exportDirB = Files.createTempDirectory("sync-export-b-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { exportDirA.toFile().deleteRecursively() }
        runCatching { exportDirB.toFile().deleteRecursively() }
    }

    @Test
    fun `round-trip preserves project byte-for-byte`() {
        val projectId = seedRichProject(state)

        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)

        assertExportsEqual(exportDirA, exportDirB)
    }

    @Test
    fun `import refuses duplicate project UUID`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        // Project still exists in the DB; importing the same export must refuse.
        val ex = assertFailsWith<ImportError> {
            ProjectImporter(state).import(exportDirA, nameOverride = "different-name")
        }
        assertEquals(io.ktor.http.HttpStatusCode.Conflict, ex.status)
        assertTrue(ex.message?.contains("UUID") == true)
    }

    @Test
    fun `import refuses on name collision`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()
        // Re-create a project with the same name but a different UUID — name collision.
        transaction(state.database) {
            DaoProject.new {
                name = "round-trip-rich"
                description = "blocker"
                isCurrent = false
            }
        }
        val ex = assertFailsWith<ImportError> {
            ProjectImporter(state).import(exportDirA, nameOverride = null)
        }
        assertEquals(io.ktor.http.HttpStatusCode.Conflict, ex.status)
    }

    @Test
    fun `installs json contains the local install identity on export`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val installsFile = exportDirA.resolve("installs.json")
        val installs = canonicalDecode(InstallsJson.serializer(), Files.readString(installsFile))
        val (localUuid, localFriendlyName) = transaction(state.database) {
            val row = DaoInstall.all().first()
            row.uuid.toString() to row.friendlyName
        }
        assertEquals(1, installs.installs.size, "exactly one local install entry expected")
        assertEquals(localFriendlyName, installs.installs[localUuid])
    }

    @Test
    fun `universe config exporter strips machine-local address field`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val universeDir = exportDirA.resolve("universeConfigs")
        val first = Files.list(universeDir).use { it.findFirst().get() }
        val text = Files.readString(first)
        // address is the machine-local IP per docs/plans/cloud-sync.md — must never be in JSON.
        assertFalse(text.contains("\"address\""), "address field leaked into export: $text")
        // Round-trips without trouble — the DTO doesn't have an address field at all.
        canonicalDecode(UniverseConfigJson.serializer(), text)
    }

    @Test
    fun `import forces isCurrent false even if source project was current`() {
        // The seeded project is current. Export it, wipe, import — imported project must NOT
        // be marked current; otherwise importing would silently reassign which project the user
        // is operating on.
        val projectId = seedRichProject(state)
        transaction(state.database) {
            DaoProject.findById(projectId)!!.isCurrent = true
        }
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        transaction(state.database) {
            assertEquals(false, DaoProject.findById(imported.projectId)!!.isCurrent)
        }
    }

    @Test
    fun `import with bad cue stack reference rolls back atomically`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        // Corrupt one cue's cueStackUuid so FK resolution fails mid-import.
        val cueDir = exportDirA.resolve("cues")
        val firstCue = Files.list(cueDir).use { it.findFirst().get() }
        val original = Files.readString(firstCue)
        val corrupt = original.replace(
            Regex("\"cueStackUuid\": \"[0-9a-f-]+\""),
            "\"cueStackUuid\": \"00000000-0000-0000-0000-000000000000\""
        )
        Files.writeString(firstCue, corrupt)

        val ex = assertFailsWith<ImportError> {
            ProjectImporter(state).import(exportDirA, nameOverride = null)
        }
        assertEquals(io.ktor.http.HttpStatusCode.BadRequest, ex.status)
        // No partial state — the project that would have been inserted must be absent.
        transaction(state.database) {
            assertEquals(0, DaoProject.all().count(),
                "import that errored mid-flight should leave DB empty")
        }
    }

    private fun wipeDatabase() {
        // Reset to a fresh SQLite file and rebuild the State / schema. Simpler than DELETE
        // cascade because the FK graph is wide; the test just needs an empty DB.
        runCatching { state.shutdown() }
        IntegrationTestDb.reset()
        state = State(testAppConfig())
    }

    private fun assertExportsEqual(a: Path, b: Path) {
        val filesA = walk(a)
        val filesB = walk(b)
        assertEquals(filesA.keys, filesB.keys, "export file sets differ")
        filesA.forEach { (rel, contentA) ->
            val contentB = filesB.getValue(rel)
            // installs.json reflects the *source* install, which legitimately differs across the
            // DB reset wipeDatabase() does between export A and B (a fresh install row is
            // bootstrapped with a new UUID). Round-trip portability is about the project graph,
            // not the metadata stamp. Verify shape only: one entry, valid UUID, non-blank name.
            if (rel == "installs.json") {
                val installsB = canonicalDecode(InstallsJson.serializer(), contentB)
                assertEquals(1, installsB.installs.size, "installs.json must contain one entry")
                installsB.installs.forEach { (uuid, name) ->
                    java.util.UUID.fromString(uuid)
                    assertTrue(name.isNotBlank(), "installs.json friendlyName must be non-blank")
                }
                return@forEach
            }
            assertEquals(contentA, contentB, "byte mismatch in $rel")
        }
    }

    private fun walk(root: Path): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Files.walk(root).use { stream ->
            stream.filter(Files::isRegularFile).forEach { p ->
                out[root.relativize(p).toString()] = Files.readString(p)
            }
        }
        return out
    }

    private fun seedRichProject(state: State): Int = transaction(state.database) {
        val project = DaoProject.new {
            name = "round-trip-rich"
            description = "exercises every synced table"
            isCurrent = true
            stageWidthM = 12.0
            stageDepthM = 8.0
            stageHeightM = 6.0
        }

        // 2 universes, 4 patches, 2 groups. The address is now machine-local (Phase 2 cloud sync)
        // and lives in machine_overrides — set via Overrides.setUniverseAddress, not on the column.
        val u0 = DaoUniverseConfig.new {
            this.project = project
            subnet = 0; universe = 0; controllerType = "MOCK"
        }
        val u1 = DaoUniverseConfig.new {
            this.project = project
            subnet = 0; universe = 1; controllerType = "MOCK"
        }
        Overrides.setUniverseAddress(project.id.value, u0.uuid, "10.0.0.1")

        // 2 riggings — one fully populated (covers all pose fields), one mostly null
        // (covers the omit-null canonical encoder for riggings too).
        val rigFront = DaoRigging.new {
            this.project = project
            name = "FOH Truss"
            kind = "TRUSS"
            positionX = 0.0
            positionY = -2.0
            positionZ = 6.0
            yawDeg = 0.0
            pitchDeg = 0.0
            rollDeg = 0.0
            sortOrder = 0
        }
        val rigBoom = DaoRigging.new {
            this.project = project
            name = "Boom-SL"
            sortOrder = 1
        }

        // 2 stage regions — main stage + a thrust extension downstage.
        DaoStageRegion.new {
            this.project = project
            name = "main"
            centerX = 0.0; centerY = 0.0; centerZ = 0.0
            widthM = 12.0; depthM = 8.0; heightM = 0.0
            yawDeg = 0.0
            sortOrder = 0
        }
        DaoStageRegion.new {
            this.project = project
            name = "thrust"
            centerY = -5.0
            widthM = 4.0; depthM = 2.0
            sortOrder = 1
        }

        val patches = (1..4).map { i ->
            DaoFixturePatch.new {
                this.project = project
                universeConfig = if (i <= 2) u0 else u1
                fixtureTypeKey = "hex-fixture"
                key = "hex-$i"; displayName = "Hex $i"; startChannel = i * 10; sortOrder = i
                // Patches 1 & 2 hang from the FOH truss (offsets in its local frame);
                // patch 3 is a free-standing fixture; patch 4 has no geometry at all.
                if (i in 1..2) rigging = rigFront
                if (i == 3) rigging = rigBoom
                if (i <= 3) {
                    stageX = (i - 2).toDouble()      // -1, 0, 1
                    stageY = 4.5
                    stageZ = -2.0 + i * 0.5
                    baseYawDeg = if (i == 1) -90.0 else 45.0
                    basePitchDeg = if (i == 2) 30.0 else null
                }
            }
        }
        val groupA = DaoFixtureGroup.new { this.project = project; name = "front-wash" }
        DaoFixtureGroupMember.new {
            group = groupA; fixturePatch = patches[0]; sortOrder = 0
        }
        DaoFixtureGroupMember.new {
            group = groupA; fixturePatch = patches[1]; sortOrder = 1; panOffset = 30.0
        }
        val groupB = DaoFixtureGroup.new { this.project = project; name = "back-wash" }
        DaoFixtureGroupMember.new {
            group = groupB; fixturePatch = patches[2]; sortOrder = 0
        }

        // 2 scripts
        val script1 = DaoScript.new {
            this.project = project; name = "intro"; script = "// hello\nfixture(\"hex-1\")"
            scriptType = ScriptType.GENERAL
        }
        DaoScript.new {
            this.project = project; name = "fx-pack"; script = "// fx defs"
            scriptType = ScriptType.FX_DEFINITION
        }

        // 1 fx definition
        DaoFxDefinition.new {
            this.project = project
            effectId = "custom-flicker"; name = "Custom Flicker"
            category = "dimmer"; outputType = FxOutputType.SLIDER
            effectMode = EffectMode.STANDARD
            script = "// effect"
            timingSource = TimingSource.BEAT
            compatibleProperties = listOf("dimmer")
        }

        // 1 fx preset with property assignments
        val preset = DaoFxPreset.new {
            this.project = project
            name = "warm-pulse"; fixtureType = "hex-fixture"
            description = "warm pulse"
            effects = listOf(
                FxPresetEffectDto(
                    effectType = "Pulse", category = "dimmer",
                    propertyName = "dimmer", beatDivision = 0.5,
                    blendMode = "OVERRIDE", distribution = "LINEAR",
                )
            )
            palette = listOf("#ff8800")
        }
        DaoFxPresetPropertyAssignment.new {
            this.preset = preset; propertyName = "dimmer"; value = "200"; sortOrder = 0
        }

        // 2 cue stacks, 3 cues, with property assignments + ad-hoc + preset apps + triggers
        val stack1 = DaoCueStack.new {
            this.project = project; name = "show-1"; palette = emptyList(); loop = false
        }
        val stack2 = DaoCueStack.new {
            this.project = project; name = "show-2"; palette = emptyList(); loop = true
        }
        val cue1 = DaoCue.new {
            this.project = project; name = "open"; cueStack = stack1; sortOrder = 0
            palette = listOf("#000000"); fadeDurationMs = 1000L
        }
        DaoCuePropertyAssignment.new {
            cue = cue1; targetType = "fixture"; targetKey = "hex-1"
            propertyName = "dimmer"; value = "255"; sortOrder = 0
        }
        DaoCueAdHocEffect.new {
            cue = cue1; targetType = "fixture"; targetKey = "hex-1"
            effectType = "Pulse"; category = "dimmer"; beatDivision = 0.5
            blendMode = "OVERRIDE"; distribution = "LINEAR"
            parameters = emptyMap()
        }
        DaoCuePresetApplication.new {
            cue = cue1; this.preset = preset; targets = emptyList()
        }
        DaoCueTrigger.new {
            cue = cue1; this.script = script1
            triggerType = TriggerType.ACTIVATION; sortOrder = 0
        }
        DaoCue.new {
            this.project = project; name = "build"; cueStack = stack1; sortOrder = 1
            palette = emptyList()
        }
        DaoCue.new {
            this.project = project; name = "finale"; cueStack = stack2; sortOrder = 0
            palette = emptyList()
        }

        // show entries (1 stack reference + 1 marker)
        val entry1 = DaoShowEntry.new {
            this.project = project; cueStack = stack1; entryType = "STACK"; sortOrder = 0
        }
        DaoShowEntry.new {
            this.project = project; entryType = "MARKER"; sortOrder = 1; label = "intermission"
        }

        // cue slot
        DaoCueSlot.new {
            this.project = project; page = 1; slotIndex = 1; cue = cue1
        }

        // prompt book with an anchor (FK-by-UUID to a cue) and two annotation kinds
        val promptBook = DaoPromptBook.new {
            this.project = project
            scriptHash = "a".repeat(64)
            scriptFileName = "act-one.pdf"
            pageCount = 12
        }
        DaoPromptBookAnchor.new {
            this.promptBook = promptBook; cue = cue1
            region = listOf(PromptBookRectDto(page = 0, x = 0.1, y = 0.2, w = 0.8, h = 0.05))
            label = "LX 1"
        }
        DaoPromptBookAnnotation.new {
            this.promptBook = promptBook; kind = "STRIKETHROUGH"
            region = listOf(PromptBookRectDto(page = 1, x = 0.1, y = 0.5, w = 0.8, h = 0.1))
        }
        DaoPromptBookAnnotation.new {
            this.promptBook = promptBook; kind = "NOTE"
            region = listOf(PromptBookRectDto(page = 2, x = 0.05, y = 0.9, w = 0.4, h = 0.03))
            text = "slow build, watch conductor"; color = "#ffb000"
        }

        // parked channels — two on universe 0, one on universe 1, exercises sort + UUID round-trip
        DaoParkedChannel.new {
            this.project = project; universe = 0; channel = 5; value = 128
        }
        DaoParkedChannel.new {
            this.project = project; universe = 0; channel = 12; value = 0
        }
        DaoParkedChannel.new {
            this.project = project; universe = 1; channel = 7; value = 255
        }

        // Wire up activeEntryId now that show entries exist.
        project.activeEntryId = entry1.id.value
        project.id.value
    }
}
