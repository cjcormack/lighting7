package uk.me.cormack.lighting7.history

import kotlinx.coroutines.runBlocking
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
import uk.me.cormack.lighting7.sync.RecordFolders
import uk.me.cormack.lighting7.sync.RecordHasher
import uk.me.cormack.lighting7.sync.RecordImage
import uk.me.cormack.lighting7.sync.RecordKey
import uk.me.cormack.lighting7.sync.SyncDispositions
import uk.me.cormack.lighting7.sync.canonicalEncode
import uk.me.cormack.lighting7.sync.canonicalJson
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Putting records back (undo plan sessions 4 and 5): over every portable table's fixture, and a show on
 * a venue for the folders only such a show holds, for each folder a write and its undo leave the export
 * as it was — every record's bytes and row id — and a redo leaves it as the write did.
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
        val outcome = runBlocking { RecordRestorer(state).undo(project, null, HistorySource.DESK, null, backToHere = false, confirm = false) }
        assertIs<RecordRestorer.Outcome.Done>(outcome, "$outcome")
    }

    private fun redo(project: Int) {
        val outcome = runBlocking { RecordRestorer(state).redo(project, null, HistorySource.DESK, null, confirm = false) }
        assertIs<RecordRestorer.Outcome.Done>(outcome, "$outcome")
    }

    /**
     * [key] and every record of the restorable folders that names it, and so on, as a delete takes them:
     * a prompt book's anchors and notes too, which name no book.
     */
    private fun withDependents(all: Map<RecordKey, RecordImage>, key: RecordKey): Set<RecordKey> {
        val taken = linkedSetOf(key)
        do {
            val names = taken.map { it.uuid.toString() }
            val book = taken.any { it.tableName == RecordFolders.PROMPT_BOOKS }
            val grown = all.filter { (k, image) ->
                k !in taken && k.tableName in RESTORABLE_FOLDERS &&
                    (names.any { image.json.contains(it) } || (book && k.tableName in RecordFolders.PROMPT_BOOK_CHILDREN))
            }.keys
            taken += grown
        } while (grown.isNotEmpty())
        return taken
    }

    /** Embedded children a record may lose without becoming one the importer refuses. */
    private val EMBEDDED = setOf("rows", "fx", "scenery", "events", "columns", "members", "extraPlacements")

    /** A hide is taken or given back, never edited: no route changes one in place. */
    private val NEVER_EDITED = setOf(RecordFolders.SHOW_HIDES)

    /**
     * [image] of [folder] with one of its own fields moved — a name, a label, an order, or the field a
     * record of its kind is edited by — and its first embedded list emptied.
     */
    private fun edited(folder: String, image: RecordImage): RecordImage {
        val obj = canonicalJson.parseToJsonElement(image.json).jsonObject
        val next = LinkedHashMap<String, JsonElement>(obj)
        val text = listOf("name", "displayName", "label", "notes", "description", "scriptFileName", "text")
            .firstOrNull { obj[it] is JsonPrimitive && (obj[it] as JsonPrimitive).isString }
        fun number(field: String) = (obj[field] as? JsonPrimitive)?.longOrNull ?: 0
        when {
            folder == RecordFolders.UNIVERSE_CONFIGS -> next["subnet"] = JsonPrimitive(number("subnet") + 9)
            folder == RecordFolders.PARKED_CHANNELS -> next["value"] = JsonPrimitive((number("value") + 1) % 256)
            folder == RecordFolders.SHOW_PARK_RELEASES -> next["channel"] = JsonPrimitive(500)
            // *Keep mine*: the venue's value taken as the override's base.
            folder == RecordFolders.SHOW_OVERRIDES -> next["base"] = obj.getValue("value")
            folder == RecordFolders.SHOW_COLOURS -> next["colour"] = JsonPrimitive(if ((obj["colour"] as? JsonPrimitive)?.content == "red") "blue" else "red")
            folder == RecordFolders.PROMPT_BOOK_ANNOTATIONS -> next["text"] = JsonPrimitive(((obj["text"] as? JsonPrimitive)?.content ?: "") + " (edited)")
            folder == RecordFolders.SHOW_GROUP_ORDERS -> next["members"] = JsonArray((obj.getValue("members") as JsonArray).reversed())
            folder == RecordFolders.SURFACE_JOBS -> next["items"] = JsonPrimitive("[]")
            text != null -> next[text] = JsonPrimitive((obj[text] as JsonPrimitive).content + " (edited)")
            obj["ordinal"] is JsonPrimitive -> next["ordinal"] = JsonPrimitive(((obj["ordinal"] as JsonPrimitive).content.toDouble()) + 0.25)
            // Canonical JSON leaves a default out, so an order of 0 is no field at all.
            else -> (if (obj["slotIndex"] is JsonPrimitive) "slotIndex" else "sortOrder").let { field ->
                next[field] = JsonPrimitive(((obj[field] as? JsonPrimitive)?.longOrNull ?: 0) + 70)
            }
        }
        if (folder != RecordFolders.SHOW_GROUP_ORDERS) {
            obj.entries.firstOrNull { it.key in EMBEDDED && (it.value as? JsonArray)?.isNotEmpty() == true }?.let { next[it.key] = JsonArray(emptyList()) }
        }
        val json = canonicalEncode(JsonElement.serializer(), JsonObject(next))
        return RecordImage(json, image.rowId, RecordHasher.sha256Hex(json))
    }

    @Test
    fun `every portable folder is exported, restorable, labelled and has a row in the effects table`() {
        val portable = SyncDispositions.folders + SyncDispositions.PROJECT_FOLDER
        assertEquals(portable, RESTORABLE_FOLDERS, "every portable folder is restorable, and nothing else")
        assertEquals(portable, ProjectExporter.FOLDERS, "the exporter writes every one")
        assertEquals(portable, KindEffects.folders, "each sets off what its write would")
        assertTrue(portable.all { it in RecordLabeler.namedFolders }, "each is named in the History sheet")
        assertTrue(RecordFolders.SHOW_GROUP_ORDERS in RESTORABLE_FOLDERS)
    }

    /** The folders only a show on a venue holds records of. */
    private val SHOW_ON_VENUE = with(RecordFolders) { setOf(SHOW_HIDES, SHOW_OVERRIDES, SHOW_COLOURS, SHOW_GROUP_ORDERS, SHOW_PARK_RELEASES) }

    @Test
    fun `every folder comes back exactly after a delete and after an edit, ids kept, and redo returns the write`() {
        roundTripAll(seedRichProject(state), RESTORABLE_FOLDERS - SHOW_ON_VENUE)
    }

    @Test
    fun `a show on a venue's own folders come back exactly too`() {
        roundTripAll(seedShowOnVenue(state).showId, SHOW_ON_VENUE)
    }

    private fun roundTripAll(project: Int, folders: Set<String>) {
        state.history.ensure(project)
        val all = images(project)
        // Every record, not one: the fixtures' uuids are random, so a choice by uuid differs run to run.
        for (folder in folders.sorted()) {
            val keys = all.keys.filter { it.tableName == folder }.sortedBy { it.uuid.toString() }
            assertTrue(keys.isNotEmpty(), "the fixture holds a $folder record")
            for (key in keys) roundTrip(project, folder, key, all)
        }
    }

    private fun roundTrip(project: Int, folder: String, key: RecordKey, all: Map<RecordKey, RecordImage>) {
        val original = snapshot(project)

        if (folder != SyncDispositions.PROJECT_FOLDER) {
            val taken = withDependents(all, key)
            write(project, taken.associateWith { null })
            val deleted = snapshot(project)
            assertTrue(taken.none { it in deleted }, "$folder: the delete took ${taken.size} records")
            undo(project)
            assertEquals(original, snapshot(project), "$folder: undoing a delete of $taken")
            redo(project)
            assertEquals(deleted, snapshot(project), "$folder: redoing the delete")
            undo(project)
            assertEquals(original, snapshot(project), "$folder: undoing it again")
        }

        if (folder in NEVER_EDITED) return
        write(project, mapOf(key to edited(folder, all.getValue(key))))
        val changed = snapshot(project)
        assertTrue(changed != original, "$folder: the edit changed something")
        undo(project)
        assertEquals(original, snapshot(project), "$folder: undoing an edit")
        redo(project)
        assertEquals(changed, snapshot(project), "$folder: redoing the edit")
        undo(project)
        assertEquals(original, snapshot(project), "$folder: undoing it again")
    }
}
