package uk.me.cormack.lighting7.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.isRegularFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The exporter's record kinds and its two sinks: a [MapSink] holds exactly the records a [DirSink]
 * writes, each kind alone writes exactly its folder of a whole export, and every folder
 * [SyncDispositions] names has a kind.
 */
class RecordKindsTest {

    private lateinit var state: State
    private lateinit var dir: Path

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        dir = Files.createTempDirectory("record-kinds-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `every record folder has one kind, and every kind a folder`() {
        assertEquals(SyncDispositions.folders, ProjectExporter.FOLDERS)
        assertEquals(ProjectExporter.KINDS.size, ProjectExporter.FOLDERS.size, "a folder written by two kinds")
        ProjectExporter.KINDS.forEach { assertTrue(it.tables.isNotEmpty(), "${it.folder} names no table") }
    }

    @Test
    fun `the rich project in memory is the folder read back`() = assertMapSinkIsTheFolder(seedRichProject(state))

    @Test
    fun `a show on a venue in memory is the folder read back`() = assertMapSinkIsTheFolder(seedShowOnVenue(state).showId)

    @Test
    fun `each kind alone writes its folder of the whole export`() {
        val projectId = seedRichProject(state)
        val whole = ProjectExporter(state).exportRecords(projectId).images
        for (folder in ProjectExporter.FOLDERS) {
            val sink = MapSink()
            ProjectExporter(state).exportKinds(projectId, setOf(folder), sink)
            assertEquals(whole.filterKeys { it.tableName == folder }, sink.images, folder)
        }
    }

    @Test
    fun `exportKinds runs inside the caller's transaction`() {
        val projectId = seedRichProject(state)
        val sink = MapSink()
        transaction(state.database) {
            DaoProject.findById(projectId)!!.name = "Renamed, not committed"
            ProjectExporter(state).exportKinds(projectId, setOf(SyncDispositions.PROJECT_FOLDER), sink)
            rollback()
        }
        val project = sink.images.values.single()
        assertTrue("Renamed, not committed" in project.json, project.json)
    }

    @Test
    fun `exportKinds refuses a folder no kind writes`() {
        val projectId = seedRichProject(state)
        assertFailsWith<IllegalArgumentException> { ProjectExporter(state).exportKinds(projectId, setOf("nope"), MapSink()) }
    }

    private fun assertMapSinkIsTheFolder(projectId: Int) {
        val result = ProjectExporter(state).export(projectId, dir, blobs = false)
        val images = ProjectExporter(state).exportRecords(projectId).images
        val projectUuid = transaction(state.database) { DaoProject.findById(projectId)!!.uuid }

        // The documents: every record file in the folder, parsed, a script's body folded into its meta.
        assertEquals(readFolder(dir, projectUuid), images.mapValues { canonicalJson.parseToJsonElement(it.value.json) })

        // The hashes: sync's, over every file in the folder (the fixtures hold no legacy names).
        val blobs = Files.walk(dir).use { s -> s.filter { it.isRegularFile() }.toList() }
            .associate { dir.relativize(it).toString().replace('\\', '/') to Files.readString(it) }
        val hashes = RecordHasher.fromBlobs(blobs).mapValues { it.value.hash }
        assertEquals(hashes, images.filterKeys { it.tableName != SyncDispositions.PROJECT_FOLDER }.mapValues { it.value.hash })
        assertEquals(result.liveKeys, hashes.keys)

        // project.json is the record (project, its uuid), its bytes the file's.
        val project = images.getValue(RecordKey(SyncDispositions.PROJECT_FOLDER, projectUuid))
        assertEquals(Files.readString(dir.resolve("project.json")), project.json)
        assertEquals(projectId, project.rowId)
        val cueIds = transaction(state.database) { DaoCue.all().associate { it.uuid to it.id.value } }
        assertEquals(cueIds, images.filterKeys { it.tableName == "cues" }.mapKeys { it.key.uuid }.mapValues { it.value.rowId })
    }

    /** Every record under the export folder [root], by [RecordKey]; `project.json` is `(project, [projectUuid])`. */
    private fun readFolder(root: Path, projectUuid: UUID): Map<RecordKey, JsonElement> {
        val out = mutableMapOf<RecordKey, JsonElement>()
        out[RecordKey(SyncDispositions.PROJECT_FOLDER, projectUuid)] = canonicalJson.parseToJsonElement(Files.readString(root.resolve("project.json")))
        Files.list(root).use { it.toList() }.filter { it.isDirectory() }.forEach { sub ->
            Files.list(sub).use { it.toList() }.forEach { file ->
                when {
                    sub.name == "scripts" && file.name.endsWith(".meta.json") -> {
                        val uuid = file.name.removeSuffix(".meta.json")
                        val meta = canonicalJson.parseToJsonElement(Files.readString(file)) as JsonObject
                        val body = Files.readString(sub.resolve("$uuid.kts"))
                        out[RecordKey(sub.name, UUID.fromString(uuid))] = JsonObject(meta + ("body" to JsonPrimitive(body)))
                    }
                    sub.name != "scripts" ->
                        out[RecordKey(sub.name, UUID.fromString(file.name.removeSuffix(".json")))] =
                            canonicalJson.parseToJsonElement(Files.readString(file))
                }
            }
        }
        return out
    }
}
