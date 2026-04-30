package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoMachineOverride
import uk.me.cormack.lighting7.models.DaoMachineOverrides
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [ProjectImporter.replaceFromWorkingTree] — the entry point cloud-sync uses
 * after a fast-forward pull. Properties under test:
 *  - the project row's id and uuid are preserved (so non-synced FKs survive)
 *  - child rows are dropped + re-imported from JSON
 *  - machine_overrides survive (they're keyed by uuid, not row id)
 *  - UUID mismatch between JSON and DB is refused
 */
class ProjectImporterReplaceTest {

    private lateinit var workingRoot: Path
    private lateinit var state: State
    private lateinit var exporter: ProjectExporter
    private lateinit var importer: ProjectImporter

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        workingRoot = Files.createTempDirectory("lighting7-importer-replace-")
        state = State(testAppConfig("sync.workingTreeRoot" to workingRoot.toString()))
        exporter = ProjectExporter(state)
        importer = ProjectImporter(state)
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { workingRoot.toFile().deleteRecursively() }
    }

    @Test
    fun `replace round-trips through working tree without changing project id or uuid`() {
        val projectId = seedMinimalProject(state)
        val (origUuid, origName) = transaction(state.database) {
            val p = DaoProject.findById(projectId)!!
            p.uuid to p.name
        }

        val exportDir = workingRoot.resolve("export")
        Files.createDirectories(exportDir)
        exporter.export(projectId, exportDir)

        importer.replaceFromWorkingTree(projectId, exportDir)

        transaction(state.database) {
            val p = DaoProject.findById(projectId)!!
            assertEquals(origUuid, p.uuid)
            assertEquals(origName, p.name)
        }
    }

    @Test
    fun `replace drops removed cue stacks and re-imports the JSON-only set`() {
        val projectId = seedMinimalProject(state)

        // Add a cue stack we'll later remove from JSON.
        val orphanStackId = transaction(state.database) {
            val p = DaoProject.findById(projectId)!!
            DaoCueStack.new {
                this.project = p
                this.name = "Doomed"
                this.palette = emptyList()
            }.id.value
        }

        val exportDir = workingRoot.resolve("export")
        Files.createDirectories(exportDir)
        exporter.export(projectId, exportDir)

        // Delete the JSON for the doomed stack so it disappears from the working tree.
        val cueStacksDir = exportDir.resolve("cueStacks")
        Files.list(cueStacksDir).use { stream ->
            stream.forEach { Files.deleteIfExists(it) }
        }
        // Add a fresh stack JSON with a different UUID so the post-import set is non-empty.
        val newUuid = UUID.randomUUID().toString()
        Files.writeString(
            cueStacksDir.resolve("$newUuid.json"),
            """{"loop":false,"name":"Fresh","palette":[],"uuid":"$newUuid"}""" + "\n",
        )

        importer.replaceFromWorkingTree(projectId, exportDir)

        transaction(state.database) {
            val p = DaoProject.findById(projectId)!!
            val names = p.cueStacks.map { it.name }
            assertTrue("Doomed" !in names, "removed stack must be gone after replace; got $names")
            assertTrue("Fresh" in names, "new stack must appear after replace; got $names")
            // Row id of the doomed stack is gone too (cascade-delete worked).
            assertEquals(null, DaoCueStack.findById(orphanStackId))
        }
    }

    @Test
    fun `replace preserves machine_overrides keyed by record uuid`() {
        val projectId = seedMinimalProject(state)
        val (universeUuid, projectUuid) = transaction(state.database) {
            val p = DaoProject.findById(projectId)!!
            val u = p.universeConfigs.first()
            u.uuid to p.uuid
        }

        // Set a machine-local override on the universe.
        transaction(state.database) { Overrides.setUniverseAddress(projectId, universeUuid, "10.0.0.5") }

        val exportDir = workingRoot.resolve("export")
        Files.createDirectories(exportDir)
        exporter.export(projectId, exportDir)
        importer.replaceFromWorkingTree(projectId, exportDir)

        // The universe row's int id changed (cascade-delete + re-insert) but the uuid is
        // stable, so the override still resolves.
        val resolved = transaction(state.database) { Overrides.resolveUniverseAddress(projectId, universeUuid) }
        assertEquals("10.0.0.5", resolved, "override should survive replace because it's keyed by uuid")
        // Row count sanity check — exactly one override row.
        val rowCount = transaction(state.database) {
            DaoMachineOverride.find {
                (DaoMachineOverrides.project eq projectId) and (DaoMachineOverrides.recordUuid eq universeUuid)
            }.count()
        }
        assertEquals(1L, rowCount)

        // Belt-and-braces: project uuid is still what we expect.
        transaction(state.database) {
            assertEquals(projectUuid, DaoProject.findById(projectId)!!.uuid)
        }
    }

    @Test
    fun `replace refuses when JSON project uuid mismatches DB uuid`() {
        val projectId = seedMinimalProject(state)
        val exportDir = workingRoot.resolve("export")
        Files.createDirectories(exportDir)
        exporter.export(projectId, exportDir)

        // Hand-edit project.json to a different uuid.
        val projectFile = exportDir.resolve("project.json")
        val text = Files.readString(projectFile)
        val foreignUuid = UUID.randomUUID().toString()
        val mutated = text.replace(Regex(""""uuid":\s*"[^"]+""""), """"uuid": "$foreignUuid"""")
        assertNotEquals(text, mutated, "test sanity: regex must have matched. Actual: $text")
        Files.writeString(projectFile, mutated)

        try {
            importer.replaceFromWorkingTree(projectId, exportDir)
            fail("expected ImportError on uuid mismatch")
        } catch (e: ImportError) {
            assertTrue(e.message!!.contains("Refusing to clobber"), "message should explain the refusal: ${e.message}")
        }
    }

    @Test
    fun `replace refuses when format is too new`() {
        val projectId = seedMinimalProject(state)
        val exportDir = workingRoot.resolve("export")
        Files.createDirectories(exportDir)
        exporter.export(projectId, exportDir)

        Files.writeString(
            exportDir.resolve("formatVersion.json"),
            """{"formatVersion":99,"minReader":99}""" + "\n",
        )

        try {
            importer.replaceFromWorkingTree(projectId, exportDir)
            fail("expected ImportError on too-new format")
        } catch (e: ImportError) {
            assertTrue(e.message!!.contains("newer"), "message should mention version mismatch: ${e.message}")
        }
    }
}

private infix fun org.jetbrains.exposed.sql.Op<Boolean>.and(other: org.jetbrains.exposed.sql.Op<Boolean>): org.jetbrains.exposed.sql.Op<Boolean> =
    org.jetbrains.exposed.sql.AndOp(listOf(this, other))
