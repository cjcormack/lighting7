package uk.me.cormack.lighting7.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Rewrites the record identities in an export folder, in place.
 *
 * Used by [ProjectCloner]: a clone must be a *distinct* record graph, not a second copy of
 * the same UUIDs (which would make two local projects indistinguishable to cloud sync, and
 * which [ProjectImporter.import] refuses outright).
 *
 * The rewrite is deliberately schema-agnostic, in both directions:
 *
 *  * **Which identities exist** comes from the documents themselves — the value of any field
 *    named `uuid`, at any nesting depth. That covers records addressed by filename
 *    (`cues/{uuid}.json`) *and* records embedded in a parent document (a fixture group's
 *    `members`, an FX preset's `propertyAssignments`), which is why this doesn't use
 *    [RecordHasher.scanRecordKeys]: that enumerates *records*, and an embedded child is not
 *    one. Every reference field is named `{table}Uuid` rather than `uuid`, so a reference can
 *    never mint an identity of its own — it always resolves to a record whose `uuid` field
 *    was collected.
 *  * **How they're replaced** is a blind UUID-for-UUID substitution across the JSON. Every
 *    cross-record reference in the export layout is a UUID string, so this preserves the
 *    graph without knowing a single field name. A table added to the exporter later — as a
 *    folder or as an embedded array — is remapped correctly with no change here.
 *
 * The substitution is not field-aware, so a UUID appearing in a free-text column (`cues.notes`,
 * an FX definition's `script`, a binding's `targetPayload`) is rewritten too. For a reference
 * that's correct; for prose it's a cosmetic edit to a string that names a record which no
 * longer exists in this project. Field-level exclusions would trade that for exactly the
 * per-table knowledge this class exists to avoid.
 *
 * Deliberately untouched:
 *  * `installs.json` — its UUIDs are *install* identities, not records. They must survive, so
 *    it is exempt from both collection and substitution.
 *  * `scripts/{uuid}.kts` bodies, and any other non-JSON sidecar — only `.json` documents are
 *    parsed and rewritten. Script bodies address fixtures by key, never by record UUID. Their
 *    *filenames* are still renamed.
 *  * the PDFs under `promptScripts` — binary, content-addressed by SHA-256, so they hold no
 *    UUIDs and need no rename (see [PromptScriptRepoSync]).
 */
object ExportUuidRemapper {

    private val UUID_PATTERN =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** The JSON field name that marks a record's own identity (as opposed to a reference). */
    private const val UUID_FIELD = "uuid"

    /** Files whose UUIDs are not record identities and must be preserved verbatim. */
    private val EXEMPT = setOf("installs.json")

    /**
     * Mint a fresh UUID for every identity in [exportDir] — every record, embedded child, and
     * the project row itself — apply the mapping, and return it. The returned map is old → new.
     */
    fun remapToFreshUuids(exportDir: Path): Map<UUID, UUID> {
        val mapping = collectIdentities(exportDir).associateWith { UUID.randomUUID() }
        applyMapping(exportDir, mapping)
        return mapping
    }

    /**
     * Apply an explicit old → new UUID [mapping] to [exportDir]: substitute inside every
     * JSON document, then rename the files whose stem is a remapped UUID.
     *
     * Exposed separately from [remapToFreshUuids] so tests can invert a clone's mapping and
     * compare the two exports byte-for-byte.
     */
    fun applyMapping(exportDir: Path, mapping: Map<UUID, UUID>) {
        if (mapping.isEmpty()) return
        val byString = mapping.entries.associate { (old, new) -> old.toString() to new.toString() }
        rewriteContents(exportDir, byString)
        renameFiles(exportDir, mapping)
    }

    /**
     * Every UUID the export uses as an identity, read out of the same document set
     * [rewriteContents] will rewrite. Collecting and substituting over one set keeps the two
     * in step: an identity is only minted where it can also be replaced.
     */
    private fun collectIdentities(exportDir: Path): Set<UUID> {
        val out = mutableSetOf<UUID>()
        for (file in jsonDocuments(exportDir)) {
            collectUuidFields(canonicalJson.parseToJsonElement(Files.readString(file)), out)
        }
        return out
    }

    private fun collectUuidFields(element: JsonElement, out: MutableSet<UUID>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key == UUID_FIELD) {
                    (value as? JsonPrimitive)?.contentOrNull?.toUuidOrNull()?.let(out::add)
                }
                collectUuidFields(value, out)
            }
            is JsonArray -> element.forEach { collectUuidFields(it, out) }
            else -> Unit
        }
    }

    /**
     * Single-pass substitution per document: match any UUID, replace it if it's in the mapping.
     * One pass regardless of record count, rather than one `replace` per mapping entry.
     */
    private fun rewriteContents(exportDir: Path, byString: Map<String, String>) {
        for (file in jsonDocuments(exportDir)) {
            val original = Files.readString(file)
            val rewritten = UUID_PATTERN.replace(original) { m ->
                byString[m.value.lowercase()] ?: m.value
            }
            if (rewritten != original) Files.writeString(file, rewritten)
        }
    }

    /**
     * Rename `{dir}/{uuid}.json`, `scripts/{uuid}.kts` and `scripts/{uuid}.meta.json` to
     * their new UUIDs. Collected up-front because renaming during the walk would be
     * visiting a directory we're mutating.
     */
    private fun renameFiles(exportDir: Path, mapping: Map<UUID, UUID>) {
        for (file in walkFiles(exportDir)) {
            val name = file.fileName.toString()
            val (stem, suffix) = splitRecordFileName(name) ?: continue
            val old = stem.toUuidOrNull() ?: continue
            val new = mapping[old] ?: continue
            Files.move(file, file.resolveSibling("$new$suffix"))
        }
    }

    /** Splits a record filename into (uuid stem, suffix), or null if it isn't one. */
    private fun splitRecordFileName(name: String): Pair<String, String>? = when {
        name.endsWith(".meta.json") -> name.removeSuffix(".meta.json") to ".meta.json"
        name.endsWith(".json") -> name.removeSuffix(".json") to ".json"
        name.endsWith(".kts") -> name.removeSuffix(".kts") to ".kts"
        else -> null
    }

    /** The `.json` documents whose identities are ours to mint and rewrite. */
    private fun jsonDocuments(exportDir: Path): List<Path> =
        walkFiles(exportDir).filter { p ->
            val rel = relativePath(exportDir, p)
            rel.endsWith(".json") && rel !in EXEMPT
        }

    /**
     * Every regular file under [exportDir] except `.git/` and `promptScripts/` (binary
     * blobs — reading them as UTF-8 would corrupt them, and their names are content
     * hashes rather than UUIDs).
     */
    private fun walkFiles(exportDir: Path): List<Path> =
        Files.walk(exportDir).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { p ->
                    val rel = relativePath(exportDir, p)
                    !rel.startsWith(".git/") && !rel.startsWith("${RecordHasher.PROMPT_SCRIPTS_DIR}/")
                }
                .toList()
        }

    private fun relativePath(root: Path, file: Path): String =
        root.relativize(file).toString().replace(File.separatorChar, '/')

    private fun String.toUuidOrNull(): UUID? =
        try { UUID.fromString(this) } catch (_: IllegalArgumentException) { null }
}
