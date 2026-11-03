package uk.me.cormack.lighting7.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Where the importer reads a folder's records from (undo plan §3.5): an archive on disk
 * ([DirSource]) or the images an undo puts back ([ImageSource]). Each record is its JSON, in today's
 * words, in uuid order — the order the files of a folder sort in. A script's is its meta with its
 * body as `body`, as [MapSink] holds one.
 */
interface RecordSource {
    fun records(folder: String): List<String>
}

/**
 * An archive's folders under [root], and each folder's old names ([LegacyNames.oldFolders]), every
 * file read in today's words ([LegacyNames.record]). A merged tree can hold one record in both, so a
 * file in today's folder is read and its namesake in an old one is not.
 */
class DirSource(private val root: Path) : RecordSource {
    override fun records(folder: String): List<String> {
        val dir = root.resolve(folder)
        if (folder == SCRIPTS) return scripts(dir)
        val files = LinkedHashMap<String, Path>()
        for (source in listOf(dir) + LegacyNames.oldFolders(folder).map(dir::resolveSibling)) {
            if (!source.exists() || !source.isDirectory()) continue
            Files.list(source).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json") && !it.fileName.toString().endsWith(".meta.json") }
                    .sorted()
                    .forEach { files.putIfAbsent(it.fileName.toString(), it) }
            }
        }
        return files.values.map { LegacyNames.record(folder, Files.readString(it)) }
    }

    /** Each `{uuid}.meta.json` with its `{uuid}.kts` beside it as `body` (empty when there is none). */
    private fun scripts(dir: Path): List<String> {
        if (!dir.isDirectory()) return emptyList()
        val metas = Files.list(dir).use { stream -> stream.filter { it.fileName.toString().endsWith(".meta.json") }.sorted().toList() }
        return metas.map { metaFile ->
            val meta = canonicalJson.parseToJsonElement(Files.readString(metaFile)).jsonObject
            val uuid = (meta["uuid"] as? JsonPrimitive)?.content ?: metaFile.fileName.toString().removeSuffix(".meta.json")
            val bodyFile = dir.resolve("$uuid.kts")
            val body = if (bodyFile.exists()) Files.readString(bodyFile) else ""
            canonicalEncode(JsonElement.serializer(), JsonObject(meta + ("body" to JsonPrimitive(body))))
        }
    }
}

private const val SCRIPTS = "scripts"

/** Record images as the exporter wrote them, already in today's words. */
class ImageSource(images: Map<RecordKey, RecordImage>) : RecordSource {
    private val byFolder: Map<String, List<String>> = images.entries
        .groupBy({ it.key.tableName }, { it.key.uuid.toString() to it.value.json })
        .mapValues { (_, records) -> records.sortedBy { it.first }.map { it.second } }

    override fun records(folder: String): List<String> = byFolder[folder].orEmpty()
}
