package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.RICH_PROJECT_NAME
import uk.me.cormack.lighting7.testsupport.RICH_PROJECT_SCRIPT_HASH
import uk.me.cormack.lighting7.testsupport.allUuidsIn
import uk.me.cormack.lighting7.testsupport.assertExportsEqual
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Holds [ProjectCloner] to its promise: a clone reproduces the *entire* project graph.
 *
 * The mechanism is deliberately not a hand-written list of things to check — that's exactly
 * what rotted in the old bespoke clone walker, which quietly copied 5 of 21 tables. Instead
 * the test exports the source and the clone, inverts the clone's UUID mapping, and demands
 * the two exports be byte-identical. Anything the exporter knows how to write is therefore
 * covered automatically, including tables added long after this test.
 */
class ProjectCloneTest {

    private lateinit var state: State
    private lateinit var exportDirA: Path
    private lateinit var exportDirB: Path

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        exportDirA = Files.createTempDirectory("clone-export-a-")
        exportDirB = Files.createTempDirectory("clone-export-b-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { exportDirA.toFile().deleteRecursively() }
        runCatching { exportDirB.toFile().deleteRecursively() }
    }

    @Test
    fun `clone reproduces the whole project graph`() {
        val sourceId = seedRichProject(state)
        val result = ProjectCloner(state).clone(sourceId, "cloned-rich", description = null)

        ProjectExporter(state).export(sourceId, exportDirA)
        ProjectExporter(state).export(result.projectId, exportDirB)

        // Undo the clone's freshly-minted UUIDs so the two graphs are directly comparable.
        // This is stronger than normalising UUIDs away: it pins every FK to the *corresponding*
        // record, so a clone that wired two group members to the same patch would still fail.
        val inverse = result.uuidMapping.entries.associate { (old, new) -> new to old }
        ExportUuidRemapper.applyMapping(exportDirB, inverse)

        assertExportsEqual(exportDirA, exportDirB, ignoreProjectIdentity = true)
    }

    @Test
    fun `clone mints fresh identities for the project and every record`() {
        val sourceId = seedRichProject(state)
        val result = ProjectCloner(state).clone(sourceId, "cloned-ids", description = null)

        val sourceUuid = transaction(state.database) {
            DaoProject.findById(sourceId)!!.uuid.toString()
        }
        assertNotEquals(sourceUuid, result.projectUuid, "clone must not reuse the project UUID")

        // Compare what actually landed on disk, not `result.uuidMapping` — every value in that
        // map came from UUID.randomUUID(), so asserting its keys and values are disjoint is a
        // tautology that holds even if the remapper skipped every record. Scanning both exports
        // for UUIDs is independent of the remapper's idea of what an identity is, which is the
        // only way this catches an identity it failed to mint (as it once did for the UUIDs of
        // records embedded in a parent document).
        ProjectExporter(state).export(sourceId, exportDirA)
        ProjectExporter(state).export(result.projectId, exportDirB)
        val sourceUuids = allUuidsIn(exportDirA)
        val cloneUuids = allUuidsIn(exportDirB)

        assertTrue(sourceUuids.size > 20, "expected the rich fixture to hold identities, got ${sourceUuids.size}")
        assertEquals(
            emptySet(), sourceUuids intersect cloneUuids,
            "clone shares identities with its source",
        )
        assertEquals(
            sourceUuids.size, cloneUuids.size,
            "clone holds a different number of identities than its source",
        )
        // Every identity in the export is a row the clone copied, plus the project row itself.
        assertEquals(cloneUuids.size - 1, result.recordsCloned, "recordsCloned does not match the graph")
    }

    /**
     * A named-palette reference lives inside an opaque `value` string, not in a `{table}Uuid`
     * field, so it is remapped only because [ExportUuidRemapper] substitutes uuids across the
     * whole JSON text. That is the property the `ref:{uuid}` form (rather than `ref:{intId}`)
     * depends on, and it is worth asserting head-on: the byte-comparison test above passes
     * whether or not the ref was rewritten (an un-rewritten ref inverts back to the source's
     * own value), and `clone mints fresh identities` catches it only as an opaque set
     * intersection. Neither names the failure.
     */
    @Test
    fun `clone rewires a palette reference to the clone's own palette`() {
        val sourceId = seedRichProject(state)
        val result = ProjectCloner(state).clone(sourceId, "cloned-refs", description = null)

        transaction(state.database) {
            val clone = DaoProject.findById(result.projectId)!!
            val source = DaoProject.findById(sourceId)!!

            val cloneLook = clone.looks.single { it.name == "Warm Amber" }
            val sourceLook = source.looks.single { it.name == "Warm Amber" }
            assertNotEquals(
                sourceLook.uuid, cloneLook.uuid,
                "clone must mint a fresh look identity",
            )

            // A cue's dependency on a Look is a `DaoCueLayer` FK, so the clone's layers must name
            // the clone's Look. Until session 4 this also checked a cue *row* whose opaque value
            // string held `ref:{uuid}`, rewired by ExportUuidRemapper's text substitution; the
            // `ref:` grammar retired and the FK is the only path left — which is the structural win
            // of the merge, since an FK cannot be half-rewritten.
            val clonedLayerLookIds = clone.cues.flatMap { it.layers }.mapNotNull { it.look?.id?.value }.toSet()
            assertTrue(
                clonedLayerLookIds.isNotEmpty(),
                "expected the fixture's cue layers in the clone",
            )
            assertTrue(
                clone.looks.map { it.id.value }.containsAll(clonedLayerLookIds),
                "every cloned layer must name a look in the clone, not in the source",
            )

            // Look rows hold literals only — `validateLookRows` rejects a `ref:`-shaped value at the
            // write boundary, and that rejection is what keeps looks from nesting.
            assertTrue(
                clone.looks.flatMap { it.rows.toList() }.none { it.value.startsWith("ref:") },
                "looks hold literals only",
            )
        }
    }

    /**
     * The speed-master analogue of the palette-ref test above. The preset-effect reference
     * lives inside the `fx_presets.effects` JSON blob (remapped only because
     * [ExportUuidRemapper] substitutes uuids across the whole export text); the ad-hoc and
     * preset-application references are real columns. All three must point at the *clone's*
     * master — an un-rewritten reference would silently run the clone's effects at the
     * original project's tempo, or (after the original is deleted) fall back to master 1.
     */
    @Test
    fun `clone rewires speed-master references to the clone's own master`() {
        val sourceId = seedRichProject(state)
        val result = ProjectCloner(state).clone(sourceId, "cloned-masters", description = null)

        transaction(state.database) {
            val clone = DaoProject.findById(result.projectId)!!
            val source = DaoProject.findById(sourceId)!!

            val cloneMaster = clone.speedMasters.single { it.name == "Slow Wash" }
            val sourceMaster = source.speedMasters.single { it.name == "Slow Wash" }
            assertNotEquals(
                sourceMaster.uuid, cloneMaster.uuid,
                "clone must mint a fresh master identity",
            )

            val lookEffect = clone.looks.flatMap { it.effects.toList() }.single()
            assertEquals(
                cloneMaster.uuid, lookEffect.speedMasterUuid,
                "the look effect's reference must point at the clone's master",
            )
            assertEquals(
                cloneMaster.uuid, lookEffect.rateSpeedMasterUuid,
                "and so must the rate role — parity matters, they are set independently",
            )

            val adHoc = clone.cues.flatMap { it.adHocEffects }.single()
            assertEquals(
                cloneMaster.uuid, adHoc.speedMasterUuid,
                "the ad-hoc effect's column must point at the clone's master",
            )

            val layer = clone.cues.flatMap { it.layers }.single { it.speedMasterUuid != null }
            assertEquals(
                cloneMaster.uuid, layer.speedMasterUuid,
                "the cue layer's override must point at the clone's master",
            )

            // The rate role is a second, independent reference to the same master — it has to be
            // rewritten everywhere too, or a cloned wall-clock effect would scale against the
            // source project's tempo.
            assertEquals(
                cloneMaster.uuid, adHoc.rateSpeedMasterUuid,
                "the ad-hoc effect's rate column must point at the clone's master",
            )
            assertEquals(
                cloneMaster.uuid, layer.rateSpeedMasterUuid,
                "the preset application's rate override must point at the clone's master",
            )
        }
    }

    @Test
    fun `clone carries machine-local controller addresses`() {
        // Addresses are excluded from the export by design (per-rig), but a clone lands on the
        // same machine, so it should be able to output DMX without re-entering every IP.
        val sourceId = seedRichProject(state)
        val result = ProjectCloner(state).clone(sourceId, "cloned-addresses", description = null)

        transaction(state.database) {
            val cloned = DaoUniverseConfig
                .find { DaoUniverseConfigs.project eq result.projectId }
                .associateBy { it.universe }
            assertEquals(
                "10.0.0.1",
                Overrides.resolveUniverseAddress(result.projectId, cloned.getValue(0).uuid),
                "universe 0's controller IP did not survive the clone",
            )
            assertNull(
                Overrides.resolveUniverseAddress(result.projectId, cloned.getValue(1).uuid),
                "universe 1 had no address on the source and must not gain one",
            )
        }
    }

    @Test
    fun `clone copies the prompt book PDF into the new project's store`() {
        val sourceId = seedRichProject(state)
        val sourceUuid = transaction(state.database) { DaoProject.findById(sourceId)!!.uuid.toString() }
        val pdfBytes = "%PDF-1.4 fixture".toByteArray()
        val sourcePdf = state.promptScriptPath(sourceUuid, RICH_PROJECT_SCRIPT_HASH)
        Files.createDirectories(sourcePdf.parent)
        Files.write(sourcePdf, pdfBytes)

        val result = ProjectCloner(state).clone(sourceId, "cloned-book", description = null)

        val clonedPdf = state.promptScriptPath(result.projectUuid, RICH_PROJECT_SCRIPT_HASH)
        assertTrue(Files.exists(clonedPdf), "prompt-book PDF was not copied to $clonedPdf")
        assertContentEquals(pdfBytes, Files.readAllBytes(clonedPdf))
    }

    @Test
    fun `clone does not become current or inherit the playhead`() {
        val sourceId = seedRichProject(state)
        val result = ProjectCloner(state).clone(sourceId, "cloned-inert", description = null)

        transaction(state.database) {
            val cloned = DaoProject.findById(result.projectId)!!
            assertEquals(false, cloned.isCurrent, "cloning must not switch the current project")
            assertNull(cloned.activeStackId, "cloning must not inherit the show playhead")
        }
    }

    @Test
    fun `clone applies a description override and defaults to the source description`() {
        val sourceId = seedRichProject(state)
        val sourceDescription = transaction(state.database) {
            DaoProject.findById(sourceId)!!.description
        }

        val overridden = ProjectCloner(state).clone(sourceId, "cloned-described", description = "new words")
        val inherited = ProjectCloner(state).clone(sourceId, "cloned-inherited", description = null)

        transaction(state.database) {
            assertEquals("new words", DaoProject.findById(overridden.projectId)!!.description)
            assertEquals(sourceDescription, DaoProject.findById(inherited.projectId)!!.description)
        }
    }

    @Test
    fun `clone refuses a name that is already taken`() {
        val sourceId = seedRichProject(state)
        val ex = assertFailsWith<ImportError> {
            ProjectCloner(state).clone(sourceId, RICH_PROJECT_NAME, description = null)
        }
        assertEquals(io.ktor.http.HttpStatusCode.Conflict, ex.status)
        // Nothing half-created.
        transaction(state.database) {
            assertEquals(1, DaoProject.all().count())
        }
    }

    @Test
    fun `clone rejects an unknown source project`() {
        val ex = assertFailsWith<ImportError> {
            ProjectCloner(state).clone(99999, "nope", description = null)
        }
        assertEquals(io.ktor.http.HttpStatusCode.NotFound, ex.status)
    }
}
