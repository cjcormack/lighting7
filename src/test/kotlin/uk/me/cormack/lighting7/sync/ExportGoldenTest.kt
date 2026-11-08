package uk.me.cormack.lighting7.sync

import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.assertEquals

/**
 * The exporter's bytes, pinned. Each export below is hashed with every uuid replaced by a token
 * derived from where it appears ([normalisedHash]; the fixtures mint random uuids), so a change to
 * what the exporter writes — a field, a folder, a file, an order — changes the hash, and a refactor
 * that keeps every byte does not. If a deliberate format change moves one, record the new hash with it.
 */
class ExportGoldenTest {

    private lateinit var state: State
    private lateinit var dir: Path

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        dir = Files.createTempDirectory("export-golden-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `the rich project exports the bytes it always has`() {
        val result = ProjectExporter(state).export(seedRichProject(state), dir)
        assertEquals(RICH_PROJECT_HASH, normalisedHash(dir))
        assertEquals(RICH_PROJECT_FILES, result.fileCount)
        assertEquals(RecordHasher.scanRecordKeys(dir), result.liveKeys)
    }

    @Test
    fun `a show on a venue exports the bytes it always has`() {
        val result = ProjectExporter(state).export(seedShowOnVenue(state).showId, dir)
        assertEquals(SHOW_ON_VENUE_HASH, normalisedHash(dir))
        assertEquals(SHOW_ON_VENUE_FILES, result.fileCount)
        assertEquals(RecordHasher.scanRecordKeys(dir), result.liveKeys)
    }

    private companion object {
        const val RICH_PROJECT_HASH = "3c1077eff2dbb6eb70565bf07206cc12cd2dc0c72323ce514d9b00f6485e7942"
        const val RICH_PROJECT_FILES = 107
        const val SHOW_ON_VENUE_HASH = "40d2a48309ea2e96224b91156b4251f01282b492ea08fab535e6c2da2fd72076"
        const val SHOW_ON_VENUE_FILES = 96

        const val REFINEMENT_ROUNDS = 4

        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /**
         * Every file under [root] but `installs.json` (this machine's install and its name), each a
         * path and its bytes, hashed in one stream with every uuid replaced by a token. A uuid's
         * token comes from where it appears — which files, at which place in each — refined over a
         * few rounds as colour refinement does, so the tokens follow the graph rather than the uuids'
         * values or the order the folder lists in.
         */
        fun normalisedHash(root: Path): String {
            val files = Files.walk(root).use { s -> s.filter { it.isRegularFile() }.toList() }
                .map { root.relativize(it).toString().replace('\\', '/') to it }
                .filter { (rel, _) -> rel != "installs.json" }
                .map { (rel, path) ->
                    val binary = rel.substringBefore('/') in RecordHasher.BINARY_DIRS
                    "$rel\n" + if (binary) RecordHasher.sha256Hex(Files.readAllBytes(path)) else Files.readString(path)
                }
            val occurrences = files.map { text -> UUID.findAll(text).map { it.value }.toList() }
            var label: Map<String, String> = occurrences.flatten().associateWith { "" }
            repeat(REFINEMENT_ROUNDS) {
                val fileLabels = files.map { text -> UUID.replace(text) { m -> label.getValue(m.value) } }
                val contexts = mutableMapOf<String, MutableList<String>>()
                occurrences.forEachIndexed { file, uuids ->
                    uuids.forEachIndexed { at, uuid -> contexts.getOrPut(uuid) { mutableListOf() } += "${fileLabels[file]}@$at" }
                }
                label = label.mapValues { (uuid, previous) ->
                    RecordHasher.sha256Hex(previous + "\u0000" + contexts.getValue(uuid).sorted().joinToString("\u0000"))
                }
            }
            val token = label.entries.sortedBy { it.value }.withIndex().associate { (i, e) -> e.key to "u$i" }
            return RecordHasher.sha256Hex(
                files.map { text -> UUID.replace(text) { m -> token.getValue(m.value) } }.sorted().joinToString("\n\u0000\n"),
            )
        }
    }
}
