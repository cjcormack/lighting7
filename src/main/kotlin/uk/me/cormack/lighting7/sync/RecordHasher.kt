package uk.me.cormack.lighting7.sync

import org.eclipse.jgit.lib.Repository
import java.security.MessageDigest
import java.util.UUID

/**
 * Identity of a synced record across both sides of a sync. `tableName` matches the
 * export folder name (e.g. `"cues"`, `"cueStacks"`, `"scripts"`). Combined with
 * `uuid` it points at exactly one logical record on disk and one DAO row in the DB.
 */
data class RecordKey(val tableName: String, val uuid: UUID) {
    override fun toString(): String = "$tableName/$uuid"
}

/**
 * One synced record's bytes-on-disk view. Most records consist of a single canonical-JSON
 * file ([files] has one entry); scripts have two ([files] holds the meta and body sidecars).
 *
 * The keys of [files] are the **repo-relative paths** the records would be written at —
 * sufficient information for the apply step to overlay them onto a working tree without
 * re-deriving the layout. The [hash] covers a stable concatenation of the file bytes in
 * sorted-path order, so a body-only edit on a script bumps the hash.
 */
data class RecordSnapshot(
    val key: RecordKey,
    val files: Map<String, String>,
    val hash: String,
)

/**
 * Walks a JGit ref's tree (or a flat path map) and groups blobs into per-record
 * snapshots. Lives in this package — the export/import layer doesn't need it, only
 * the sync engine does.
 *
 * Phase 5 record-grouping rules:
 *  * Most tables: one blob → one record. The path looks like `cueStacks/{uuid}.json`
 *    and the file body is the canonical JSON for that record.
 *  * Scripts: each record is the *pair* `scripts/{uuid}.kts` + `scripts/{uuid}.meta.json`.
 *    Both files must exist for a record to be included; an orphaned half is silently
 *    skipped (mirrors the importer).
 *  * Top-level metadata files (`formatVersion.json`, `project.json`, `installs.json`,
 *    `.gitignore`, `.gitattributes`) aren't records — they're filtered out.
 */
object RecordHasher {

    fun fromRef(repo: Repository, ref: String): Map<RecordKey, RecordSnapshot> =
        groupBlobs(JGitClient.walkTree(repo, ref))

    fun fromBlobs(blobs: Map<String, String>): Map<RecordKey, RecordSnapshot> = groupBlobs(blobs)

    private fun groupBlobs(blobs: Map<String, String>): Map<RecordKey, RecordSnapshot> {
        val out = mutableMapOf<RecordKey, RecordSnapshot>()
        val scriptMetas = mutableMapOf<UUID, Pair<String, String>>() // uuid -> (path, content)
        val scriptBodies = mutableMapOf<UUID, Pair<String, String>>()

        for ((path, content) in blobs) {
            if ('/' !in path) continue
            val firstSlash = path.indexOf('/')
            val tableName = path.substring(0, firstSlash)
            val rest = path.substring(firstSlash + 1)

            if (tableName == "scripts") {
                val parsed = parseScriptFilename(rest) ?: continue
                when (parsed.second) {
                    ScriptKind.META -> scriptMetas[parsed.first] = path to content
                    ScriptKind.BODY -> scriptBodies[parsed.first] = path to content
                }
                continue
            }

            if (!rest.endsWith(".json") || '/' in rest) continue
            val uuid = rest.removeSuffix(".json").toUuidOrNull() ?: continue
            val key = RecordKey(tableName, uuid)
            out[key] = RecordSnapshot(
                key = key,
                files = mapOf(path to content),
                hash = sha256Hex(content),
            )
        }

        val pairedUuids = scriptMetas.keys intersect scriptBodies.keys
        for (uuid in pairedUuids) {
            val (metaPath, metaContent) = scriptMetas.getValue(uuid)
            val (bodyPath, bodyContent) = scriptBodies.getValue(uuid)
            // Hash deterministic concatenation; the file map preserves both halves verbatim.
            val combined = metaContent + "\n" + bodyContent
            val key = RecordKey("scripts", uuid)
            out[key] = RecordSnapshot(
                key = key,
                files = mapOf(metaPath to metaContent, bodyPath to bodyContent),
                hash = sha256Hex(combined),
            )
        }
        return out
    }

    /** Hex-encoded SHA-256 of the UTF-8 bytes of [content]. Mirrors the precedent in
     *  [uk.me.cormack.lighting7.show.Show.cacheKey]. */
    @OptIn(ExperimentalStdlibApi::class)
    fun sha256Hex(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).toHexString()

    private enum class ScriptKind { META, BODY }

    private fun parseScriptFilename(tail: String): Pair<UUID, ScriptKind>? {
        if (tail.endsWith(".meta.json")) {
            val uuid = tail.removeSuffix(".meta.json").toUuidOrNull() ?: return null
            return uuid to ScriptKind.META
        }
        if (tail.endsWith(".kts")) {
            val uuid = tail.removeSuffix(".kts").toUuidOrNull() ?: return null
            return uuid to ScriptKind.BODY
        }
        return null
    }

}

private fun String.toUuidOrNull(): UUID? = try { UUID.fromString(this) } catch (_: IllegalArgumentException) { null }
