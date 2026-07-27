package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.sql.transactions.transaction
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
