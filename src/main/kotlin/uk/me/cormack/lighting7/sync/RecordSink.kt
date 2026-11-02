package uk.me.cormack.lighting7.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import uk.me.cormack.lighting7.sync.dto.ScriptMetaJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Where [ProjectExporter]'s record kinds write: a folder of files ([DirSink]) or a map in memory
 * ([MapSink]). Every record arrives as its folder, its uuid, the id of the row it was read from and
 * its document; `project.json` arrives as the record ([SyncDispositions.PROJECT_FOLDER], the project's
 * uuid), and a script as its body and its meta together.
 */
interface RecordSink {
    fun <T> record(folder: String, uuid: UUID, rowId: Int, serializer: KSerializer<T>, value: T)

    fun script(uuid: UUID, rowId: Int, meta: ScriptMetaJson, body: String)
}

/**
 * Writes the export layout under [root], creating each record folder with its first record. Counts
 * the files it writes and collects the [RecordKey] of every record but `project.json`, which is not
 * a sync record.
 */
class DirSink(private val root: Path) : RecordSink {
    var fileCount = 0
        private set
    val liveKeys = mutableSetOf<RecordKey>()

    override fun <T> record(folder: String, uuid: UUID, rowId: Int, serializer: KSerializer<T>, value: T) {
        if (folder == SyncDispositions.PROJECT_FOLDER) {
            write(root.resolve("project.json"), canonicalEncode(serializer, value))
            return
        }
        val dir = root.resolve(folder)
        Files.createDirectories(dir)
        write(dir.resolve("$uuid.json"), canonicalEncode(serializer, value))
        liveKeys.add(RecordKey(folder, uuid))
    }

    override fun script(uuid: UUID, rowId: Int, meta: ScriptMetaJson, body: String) {
        val dir = root.resolve(SCRIPTS)
        Files.createDirectories(dir)
        // The body as raw .kts, so git diffs read like normal Kotlin.
        write(dir.resolve("$uuid.kts"), body)
        write(dir.resolve("$uuid.meta.json"), canonicalEncode(ScriptMetaJson.serializer(), meta))
        liveKeys.add(RecordKey(SCRIPTS, uuid))
    }

    private fun write(path: Path, text: String) {
        Files.writeString(path, text)
        fileCount++
    }
}

/**
 * One record as [MapSink] holds it: its canonical JSON (a script's meta with its body as `body`),
 * the id of its row, and the SHA-256 of what [DirSink] would write for it (a script's meta, a
 * newline and its body) — [RecordHasher]'s hash of the same files for a record in today's names.
 */
data class RecordImage(val json: String, val rowId: Int, val hash: String)

/** Every record written to it, by [RecordKey] — `(folder, uuid)`, `project.json` included. */
class MapSink : RecordSink {
    val images = mutableMapOf<RecordKey, RecordImage>()

    override fun <T> record(folder: String, uuid: UUID, rowId: Int, serializer: KSerializer<T>, value: T) {
        val json = canonicalEncode(serializer, value)
        images[RecordKey(folder, uuid)] = RecordImage(json, rowId, RecordHasher.sha256Hex(json))
    }

    override fun script(uuid: UUID, rowId: Int, meta: ScriptMetaJson, body: String) {
        val metaText = canonicalEncode(ScriptMetaJson.serializer(), meta)
        val metaObject = canonicalJson.encodeToJsonElement(ScriptMetaJson.serializer(), meta) as JsonObject
        val json = canonicalEncode(JsonElement.serializer(), JsonObject(metaObject + ("body" to JsonPrimitive(body))))
        // As RecordHasher hashes a script's two files: the meta, a newline, the body.
        images[RecordKey(SCRIPTS, uuid)] = RecordImage(json, rowId, RecordHasher.sha256Hex(metaText + "\n" + body))
    }
}

private const val SCRIPTS = "scripts"
