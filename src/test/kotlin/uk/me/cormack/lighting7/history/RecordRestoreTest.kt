package uk.me.cormack.lighting7.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.ProjectImporter
import uk.me.cormack.lighting7.sync.RESTORABLE_FOLDERS
import uk.me.cormack.lighting7.sync.RecordHasher
import uk.me.cormack.lighting7.sync.RecordImage
import uk.me.cormack.lighting7.sync.RecordKey
import uk.me.cormack.lighting7.sync.SyncDispositions
import uk.me.cormack.lighting7.sync.canonicalEncode
import uk.me.cormack.lighting7.sync.canonicalJson
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.seedRichProject
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Putting records back (undo plan session 4): over every portable table's fixture, for each folder the
 * cue side restores, a write and its undo leave the export as it was — every record's bytes and row id
 * — and a redo leaves it as the write did.
 */
class RecordRestoreTest : RouteIntegrationTest() {

    private val exporter get() = ProjectExporter(state)

    /** Every record of [project]: its hash and row id. */
    private fun snapshot(project: Int): Map<RecordKey, Pair<String, Int>> =
        exporter.exportRecords(project).images.mapValues { (_, image) -> image.hash to image.rowId }

    private fun images(project: Int) = exporter.exportRecords(project).images

    /** [targets] written as a tracked change of the desk's: the entry an undo then takes back. */
    private fun write(project: Int, targets: Map<RecordKey, RecordImage?>) {
        WriteOrigin.with(WriteOrigin(HistorySource.DESK)) {
            transaction(state.database) { ProjectImporter(state).restore(DaoProject[project], targets) }
        }
    }

    private fun undo(project: Int) {
        val outcome = RecordRestorer(state).undo(project, null, HistorySource.DESK, null, backToHere = false, confirm = false)
        assertIs<RecordRestorer.Outcome.Done>(outcome, "$outcome")
    }

    private fun redo(project: Int) {
        val outcome = RecordRestorer(state).redo(project, null, HistorySource.DESK, null, confirm = false)
        assertIs<RecordRestorer.Outcome.Done>(outcome, "$outcome")
    }

    /** [key] and every record of the restorable folders that names it, and so on, as a delete takes them. */
    private fun withDependents(all: Map<RecordKey, RecordImage>, key: RecordKey): Set<RecordKey> {
        val taken = linkedSetOf(key)
        do {
            val names = taken.map { it.uuid.toString() }
            val grown = all.filter { (k, image) ->
                k !in taken && k.tableName in RESTORABLE_FOLDERS && names.any { image.json.contains(it) }
            }.keys
            taken += grown
        } while (grown.isNotEmpty())
        return taken
    }

    /** Embedded children a record may lose without becoming one the importer refuses. */
    private val EMBEDDED = setOf("rows", "fx", "scenery", "events", "columns")

    /** [image] with one of its own fields moved — a name, a label, an order — and its first embedded list emptied. */
    private fun edited(image: RecordImage): RecordImage {
        val obj = canonicalJson.parseToJsonElement(image.json).jsonObject
        val next = LinkedHashMap<String, JsonElement>(obj)
        val text = listOf("name", "label", "notes", "description").firstOrNull { obj[it] is JsonPrimitive && (obj[it] as JsonPrimitive).isString }
        when {
            text != null -> next[text] = JsonPrimitive((obj[text] as JsonPrimitive).content + " (edited)")
            obj["ordinal"] is JsonPrimitive -> next["ordinal"] = JsonPrimitive(((obj["ordinal"] as JsonPrimitive).content.toDouble()) + 0.25)
            // Canonical JSON leaves a default out, so an order of 0 is no field at all.
            else -> (if (obj["slotIndex"] is JsonPrimitive) "slotIndex" else "sortOrder").let { field ->
                next[field] = JsonPrimitive(((obj[field] as? JsonPrimitive)?.longOrNull ?: 0) + 70)
            }
        }
        obj.entries.firstOrNull { it.key in EMBEDDED && (it.value as? JsonArray)?.isNotEmpty() == true }?.let { next[it.key] = JsonArray(emptyList()) }
        val json = canonicalEncode(JsonElement.serializer(), JsonObject(next))
        return RecordImage(json, image.rowId, RecordHasher.sha256Hex(json))
    }

    @Test
    fun `every restorable folder has a row in the effects table, and every one is a portable folder`() {
        assertEquals(RESTORABLE_FOLDERS, KindEffects.folders)
        assertTrue((RESTORABLE_FOLDERS - SyncDispositions.PROJECT_FOLDER).all { it in SyncDispositions.folders })
    }

    @Test
    fun `every restorable folder comes back exactly after a delete and after an edit, ids kept, and redo returns the write`() {
        val rich = seedRichProject(state)
        state.history.ensure(rich)
        val original = snapshot(rich)
        val all = images(rich)
        for (folder in RESTORABLE_FOLDERS.sorted()) {
            val key = all.keys.filter { it.tableName == folder }.minByOrNull { it.uuid.toString() }
            assertTrue(key != null, "the fixture holds a $folder record")

            if (folder != SyncDispositions.PROJECT_FOLDER) {
                val taken = withDependents(all, key)
                write(rich, taken.associateWith { null })
                val deleted = snapshot(rich)
                assertTrue(taken.none { it in deleted }, "$folder: the delete took ${taken.size} records")
                undo(rich)
                assertEquals(original, snapshot(rich), "$folder: undoing a delete of $taken")
                redo(rich)
                assertEquals(deleted, snapshot(rich), "$folder: redoing the delete")
                undo(rich)
                assertEquals(original, snapshot(rich), "$folder: undoing it again")
            }

            write(rich, mapOf(key to edited(all.getValue(key))))
            val changed = snapshot(rich)
            assertTrue(changed != original, "$folder: the edit changed something")
            undo(rich)
            assertEquals(original, snapshot(rich), "$folder: undoing an edit")
            redo(rich)
            assertEquals(changed, snapshot(rich), "$folder: redoing the edit")
            undo(rich)
            assertEquals(original, snapshot(rich), "$folder: undoing it again")
        }
    }
}
