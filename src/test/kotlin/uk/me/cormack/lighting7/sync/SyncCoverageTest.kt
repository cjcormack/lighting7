package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.core.Table
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.ALL_TABLES
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Forces a sync decision for every table in the schema, and forces the exporter to honour it.
 *
 * The pre-existing round-trip test compares two *exports*, so it can only catch the importer
 * dropping something the exporter wrote — a column missing from both sides is missing
 * symmetrically and passes. That gap is what let the clone path (and, in principle, the export
 * path) fall behind the schema. This test closes it from the other end:
 *
 *  1. every table in [ALL_TABLES] must have a [SyncDisposition] recorded in [SyncDispositions] —
 *     adding a table without answering "portable, machine-local, or transient?" fails the build;
 *  2. every table declared [SyncDisposition.Portable] must actually produce output when the rich
 *     fixture is exported, so declaring a table portable without wiring it into
 *     `ProjectExporter` fails too;
 *  3. the fixture itself must populate every portable table, which is what makes
 *     `ProjectRoundTripTest` and `ProjectCloneTest` meaningful.
 *
 * Its granularity is the table, not the column: a portable table whose *fields* are partly
 * missing from the exporter still satisfies (2). Field-level fidelity is the fixture's job —
 * see the note in `RichProjectFixture`.
 */
class SyncCoverageTest {

    private val dispositions: Map<Table, SyncDisposition> = SyncDispositions.byTable

    private lateinit var state: State
    private lateinit var exportDir: Path

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        exportDir = Files.createTempDirectory("sync-coverage-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { exportDir.toFile().deleteRecursively() }
    }

    @Test
    fun `every table has a recorded sync disposition`() {
        val undeclared = ALL_TABLES.filter { it !in dispositions }.map { it.tableName }
        assertEquals(
            emptyList(), undeclared,
            "New table(s) with no sync disposition. Work through the decision tree in " +
                "CLAUDE.md §\"Database changes and cloud sync\", then record the answer in " +
                "SyncDispositions.byTable. If portable: wire it through ProjectExporter " +
                "and ProjectImporter, add rows to RichProjectFixture, and it is cloned for free.",
        )
    }

    @Test
    fun `disposition map has no entries for tables that no longer exist`() {
        val stale = dispositions.keys.filter { it !in ALL_TABLES }.map { it.tableName }
        assertEquals(emptyList(), stale, "disposition recorded for table(s) not in the schema")
    }

    @Test
    fun `every portable table is written by the exporter`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDir)
        // A show on a venue (venue plan sessions 4 and 5) holds the two tables a standalone show cannot —
        // its hides and its facet overrides — so it is exported too, from a fresh database, and a table passes when either export
        // carries it.
        runCatching { state.shutdown() }
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        val venueShowDir = exportDir.resolve("venue-show")
        ProjectExporter(state).export(seedShowOnVenue(state).showId, venueShowDir)

        val missing = mutableListOf<String>()
        val emptyEmbeds = mutableListOf<String>()
        for ((table, disposition) in dispositions) {
            if (disposition !is SyncDisposition.Portable) continue
            val files = listOf(exportDir, venueShowDir).flatMap { root ->
                val dir = root.resolve(disposition.folder)
                if (Files.isDirectory(dir)) Files.list(dir).use { it.toList() } else emptyList()
            }
            if (files.isEmpty()) {
                missing += "${table.tableName} -> ${disposition.folder}/"
                continue
            }
            val field = disposition.embeddedField ?: continue
            // Embedded child tables have no folder of their own; assert the parent documents
            // actually carry the array, so a forgotten inline child is caught too.
            val carried = files.any { Files.readString(it).contains("\"$field\"") }
            if (!carried) emptyEmbeds += "${table.tableName} -> ${disposition.folder}/*.json[$field]"
        }

        assertEquals(
            emptyList(), missing,
            "portable table(s) produced no export output. Either ProjectExporter does not " +
                "write them, or RichProjectFixture does not seed any rows — both must be fixed.",
        )
        assertEquals(
            emptyList(), emptyEmbeds,
            "embedded portable table(s) missing from their parent's JSON",
        )
    }

    @Test
    fun `machine-local and excluded tables have a documented reason`() {
        val undocumented = dispositions.entries.filter { (_, d) ->
            when (d) {
                is SyncDisposition.MachineLocal -> d.why.isBlank()
                is SyncDisposition.Excluded -> d.why.isBlank()
                else -> false
            }
        }.map { it.key.tableName }
        assertTrue(undocumented.isEmpty(), "non-portable table(s) with no stated reason: $undocumented")
    }
}
