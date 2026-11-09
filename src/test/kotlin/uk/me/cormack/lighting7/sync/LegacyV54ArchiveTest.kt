package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.midi.Binding
import uk.me.cormack.lighting7.midi.Does
import uk.me.cormack.lighting7.midi.JobItem
import uk.me.cormack.lighting7.midi.JobKind
import uk.me.cormack.lighting7.midi.Subject
import uk.me.cormack.lighting7.midi.SurfaceStripJobs
import uk.me.cormack.lighting7.midi.decodeBinding
import uk.me.cormack.lighting7.midi.decodeJobItems
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoSurfaceJob
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.ControlSurfaceBindingJson
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.assertExportsEqual
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `src/test/resources/sync/legacy-v54/` is `RichProjectFixture` as a desk on main exported it at
 * `formatVersion` 54, with five channel strips added on an X-Touch — groups on strips 1 and 2 and the
 * master, a fixture on strip 4, a fixture on Layer A's strip 1. Frozen: never regenerate it from a
 * later commit. Its strips import as jobs through the translate database step 17 shares, and
 * re-exported it is the same archive with only those rows turned into jobs.
 */
class LegacyV54ArchiveTest {

    private lateinit var state: State
    private lateinit var scratch: Path

    private val frozen: Path = Path.of("src/test/resources/sync/legacy-v54")
    private val device = "x-touch-compact-standard"

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        scratch = Files.createTempDirectory("legacy-v54-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { scratch.toFile().deleteRecursively() }
    }

    private fun bindingRecords(): Map<Path, ControlSurfaceBindingJson> =
        Files.list(frozen.resolve("controlSurfaceBindings")).use { files -> files.toList() }
            .associateWith { canonicalDecode(ControlSurfaceBindingJson.serializer(), Files.readString(it)) }

    private fun strips() = bindingRecords().filterValues { SurfaceStripJobs.isStrip(it.deviceTypeKey, it.controlId) }

    @Test
    fun `the frozen archive is v54 and holds its strips as bindings`() {
        assertEquals(54, canonicalDecode(FormatVersionJson.serializer(), Files.readString(frozen.resolve("formatVersion.json"))).formatVersion)
        assertEquals(5, strips().size)
        assertTrue(!Files.exists(frozen.resolve("surfaceJobs")))
    }

    @Test
    fun `a v54 archive's strips import as jobs, a fixture among groups as its controls' own`() {
        val imported = ProjectImporter(state).import(frozen, nameOverride = null).projectId

        transaction(state.database) {
            val jobs = DaoSurfaceJob.all().filter { it.project.id.value == imported }
                .associate { (it.region to it.bank) to (it.kind to decodeJobItems(it.items)) }
            assertEquals(
                mapOf(
                    ("strips" to null) to (JobKind.GROUPS.id to listOf(JobItem.Group("front-wash"), JobItem.Group("back-wash"))),
                    ("strips" to "layer-a") to (JobKind.FIXTURES.id to listOf(JobItem.Fixture("hex-1"))),
                    ("master" to null) to (JobKind.GROUPS.id to listOf(JobItem.Group("front-wash"))),
                ),
                jobs,
            )
            val rows = DaoControlSurfaceBinding.all().filter { it.project.id.value == imported }
            assertTrue(rows.none { SurfaceStripJobs.isStrip(it.deviceTypeKey, it.controlId) })
            val hex2 = Subject.Fixture("hex-2")
            assertEquals(
                mapOf(
                    "fader-4" to Binding(Does.Level("dimmer"), hex2),
                    "btn-28" to Binding(Does.Select, hex2),
                    "enc-4" to Binding(Does.PageLevel, hex2),
                    "btn-4" to Binding(Does.Flash("dimmer"), hex2),
                ),
                rows.filter { it.deviceTypeKey == device }.associate { it.controlId to decodeBinding(it.targetPayload, it.targetType) },
            )
            // The other profile's strip names no profile this desk has: kept as it was, read as unknown.
            val mini = rows.single { it.deviceTypeKey == "xtouch-mini" && it.controlId == "strip-1" }
            assertTrue(decodeBinding(mini.targetPayload, mini.targetType).does is Does.Unknown)
        }
    }

    @Test
    fun `an archive's translate and a database's are one function, with the same records on any desk`() {
        val imported = ProjectImporter(state).import(frozen, nameOverride = null).projectId
        val plan = SurfaceStripJobs.plan(
            strips().values.map { SurfaceStripJobs.StripRow(UUID.fromString(it.uuid), it.deviceTypeKey, it.controlId, it.bank, it.targetPayload) },
            own = emptySet(),
        )
        transaction(state.database) {
            assertEquals(
                plan.jobs.map { it.uuid }.toSet(),
                DaoSurfaceJob.all().filter { it.project.id.value == imported }.map { it.uuid }.toSet(),
            )
            assertEquals(
                plan.bindings.map { it.uuid }.toSet(),
                DaoControlSurfaceBinding.all().filter { it.project.id.value == imported && it.deviceTypeKey == device }.map { it.uuid }.toSet(),
            )
        }
    }

    @Test
    fun `re-exported, it is the frozen archive with only its strips turned into jobs`() {
        val imported = ProjectImporter(state).import(frozen, nameOverride = null).projectId
        val exported = scratch.resolve("exported")
        ProjectExporter(state).export(imported, exported)

        val expected = scratch.resolve("expected")
        val stripFiles = strips().keys.map { frozen.relativize(it).toString() }.toSet()
        Files.walk(frozen).use { files -> files.toList() }.filter(Files::isRegularFile).forEach { file ->
            val rel = frozen.relativize(file).toString()
            if (rel in stripFiles) return@forEach
            val target = expected.resolve(rel)
            Files.createDirectories(target.parent)
            if (rel == "formatVersion.json") Files.writeString(target, canonicalEncode(FormatVersionJson.serializer(), FormatVersionJson()))
            else Files.copy(file, target)
        }
        // What the strips became, checked record by record above: copied across as exported.
        val made = Files.walk(exported).use { files -> files.toList() }.filter(Files::isRegularFile).filter { file ->
            val rel = exported.relativize(file).toString()
            rel.startsWith("surfaceJobs/") || (rel.startsWith("controlSurfaceBindings/") && !Files.exists(frozen.resolve(rel)))
        }
        assertEquals(3 + 4, made.size, "three jobs and four bindings of the strip's own")
        for (file in made) {
            val target = expected.resolve(exported.relativize(file).toString())
            Files.createDirectories(target.parent)
            Files.copy(file, target)
        }
        assertExportsEqual(expected, exported, installsShapeOnly = true)
    }
}
