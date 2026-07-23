package uk.me.cormack.lighting7.sync

import org.eclipse.jgit.lib.Repository
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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
 *
 * [isDeleted] is true when this snapshot represents a tombstone (file under
 * `tombstones/{tableName}/{uuid}.json`). The three-way diff uses the bit to distinguish
 * deletions from "absent" so deletions propagate without resurrecting records on peers.
 */
data class RecordSnapshot(
    val key: RecordKey,
    val files: Map<String, String>,
    val hash: String,
    val isDeleted: Boolean = false,
)

/**
 * Compact `(hash, isDeleted)` pair for the three-way diff input. The local and remote
 * sides project from [RecordSnapshot] via [toMeta]; the `lastSynced` side comes
 * straight from `sync_state` rows where we don't have the file bytes anyway.
 */
data class SnapshotMeta(val hash: String, val isDeleted: Boolean)

fun RecordSnapshot.toMeta(): SnapshotMeta = SnapshotMeta(hash, isDeleted)

/**
 * Walks a JGit ref's tree (or a flat path map) and groups blobs into per-record
 * snapshots. Lives in this package — the export/import layer doesn't need it, only
 * the sync engine does.
 *
 * Record-grouping rules:
 *  * Most tables: one blob → one record. The path looks like `cueStacks/{uuid}.json`
 *    and the file body is the canonical JSON for that record.
 *  * Scripts: each record is the *pair* `scripts/{uuid}.kts` + `scripts/{uuid}.meta.json`.
 *    Both files must exist for a record to be included; an orphaned half is silently
 *    skipped (mirrors the importer).
 *  * Tombstones: `tombstones/{tableName}/{uuid}.json` produces a snapshot keyed
 *    `RecordKey(tableName, uuid)` with `isDeleted = true`. If both a tombstone and a
 *    live record exist for the same key (a state the snapshot pipeline shouldn't
 *    produce) the live record wins and a WARN is logged.
 *  * Top-level metadata files (`formatVersion.json`, `project.json`, `installs.json`,
 *    `.gitignore`, `.gitattributes`) aren't records — they're filtered out.
 */
object RecordHasher {

    private val logger = LoggerFactory.getLogger(RecordHasher::class.java)

    /** Repo-relative subdirectory holding tombstones for deleted records. */
    const val TOMBSTONES_DIR = "tombstones"

    /**
     * Repo-relative subdirectory holding prompt-book script PDFs (`{sha256}.pdf`).
     * These are binary, content-addressed blobs — not records — so they're excluded
     * from the record scan and from [JGitClient.walkTree]'s UTF-8 read. See
     * [PromptScriptRepoSync].
     */
    const val PROMPT_SCRIPTS_DIR = "promptScripts"

    fun fromRef(repo: Repository, ref: String): Map<RecordKey, RecordSnapshot> =
        groupBlobs(JGitClient.walkTree(repo, ref))

    fun fromBlobs(blobs: Map<String, String>): Map<RecordKey, RecordSnapshot> = groupBlobs(blobs)

    private fun groupBlobs(blobs: Map<String, String>): Map<RecordKey, RecordSnapshot> {
        val live = mutableMapOf<RecordKey, RecordSnapshot>()
        val tombstones = mutableMapOf<RecordKey, RecordSnapshot>()
        val scriptMetas = mutableMapOf<UUID, Pair<String, String>>() // uuid -> (path, content)
        val scriptBodies = mutableMapOf<UUID, Pair<String, String>>()

        for ((path, content) in blobs) {
            when (val kind = classifyPath(path)) {
                is PathClass.Tombstone -> {
                    tombstones[kind.key] = RecordSnapshot(
                        key = kind.key,
                        files = mapOf(path to content),
                        hash = sha256Hex(content),
                        isDeleted = true,
                    )
                }
                is PathClass.LiveRecord -> {
                    live[kind.key] = RecordSnapshot(
                        key = kind.key,
                        files = mapOf(path to content),
                        hash = sha256Hex(content),
                    )
                }
                is PathClass.ScriptMeta -> scriptMetas[kind.uuid] = path to content
                is PathClass.ScriptBody -> scriptBodies[kind.uuid] = path to content
                PathClass.Skip -> Unit
            }
        }

        for (uuid in scriptMetas.keys intersect scriptBodies.keys) {
            val (metaPath, metaContent) = scriptMetas.getValue(uuid)
            val (bodyPath, bodyContent) = scriptBodies.getValue(uuid)
            // Hash deterministic concatenation; the file map preserves both halves verbatim.
            val combined = metaContent + "\n" + bodyContent
            val key = RecordKey("scripts", uuid)
            live[key] = RecordSnapshot(
                key = key,
                files = mapOf(metaPath to metaContent, bodyPath to bodyContent),
                hash = sha256Hex(combined),
            )
        }

        // Defensive: a key shouldn't have both a live record and a tombstone. If it does,
        // the live record wins and we log so the operator notices. The wipe-and-export
        // snapshot pipeline cannot produce this state under normal operation.
        val collisions = live.keys intersect tombstones.keys
        if (collisions.isNotEmpty()) {
            logger.warn(
                "Tombstone/live-record collision for {} key(s); live records win: {}",
                collisions.size, collisions,
            )
        }
        // Mutate `tombstones` in place to avoid one extra map allocation; live overrides
        // tombstones on collision keys.
        tombstones.putAll(live)
        return tombstones
    }

    /**
     * Walk an export folder on disk and return the set of record keys that exist there as
     * **live** records — i.e. anything matching `{tableName}/{uuid}.json` (or the script
     * meta/body pair). Excludes `tombstones/`, `.git/`, and metadata files.
     *
     * Used by [SnapshotEngine] to derive the set of deletions = `sync_state \ scanRecordKeys`.
     */
    fun scanRecordKeys(workingTreePath: Path): Set<RecordKey> {
        if (!Files.isDirectory(workingTreePath)) return emptySet()
        val out = mutableSetOf<RecordKey>()
        val scriptMetas = mutableSetOf<UUID>()
        val scriptBodies = mutableSetOf<UUID>()
        Files.walkFileTree(workingTreePath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Skip subtrees that can never contain live records: `.git/` (huge, full of
                // pack files / loose objects) and `tombstones/` (deletions, not records).
                val rel = workingTreePath.relativize(dir).toString().replace(File.separatorChar, '/')
                return if (rel == ".git" || rel == TOMBSTONES_DIR || rel == PROMPT_SCRIPTS_DIR) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = workingTreePath.relativize(file).toString().replace(File.separatorChar, '/')
                when (val kind = classifyPath(rel)) {
                    is PathClass.LiveRecord -> out.add(kind.key)
                    is PathClass.ScriptMeta -> scriptMetas.add(kind.uuid)
                    is PathClass.ScriptBody -> scriptBodies.add(kind.uuid)
                    is PathClass.Tombstone, PathClass.Skip -> Unit
                }
                return FileVisitResult.CONTINUE
            }
        })
        // A script counts only when both META and BODY exist on disk — mirrors [groupBlobs].
        for (uuid in scriptMetas intersect scriptBodies) {
            out.add(RecordKey("scripts", uuid))
        }
        return out
    }

    /**
     * True if [tableName]'s records span more than one file on disk (e.g. scripts have
     * a `.kts` body + `.meta.json` sidecar). Single source of truth — callers like the
     * MANUAL-edit gate in the sync routes use this to decide whether a record can be
     * round-tripped through a single-textarea editor.
     */
    fun isMultiFileTable(tableName: String): Boolean = tableName == "scripts"

    /** Repo-relative path for a tombstone marker for [key]. */
    fun tombstonePathFor(key: RecordKey): String =
        "$TOMBSTONES_DIR/${key.tableName}/${key.uuid}.json"

    /** Hex-encoded SHA-256 of the UTF-8 bytes of [content]. Mirrors the precedent in
     *  [uk.me.cormack.lighting7.show.Show.cacheKey]. */
    fun sha256Hex(content: String): String = sha256Hex(content.toByteArray(Charsets.UTF_8))

    /** Hex-encoded SHA-256 of [bytes] (e.g. prompt-book script PDFs). */
    @OptIn(ExperimentalStdlibApi::class)
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    /**
     * Classify a repo-relative path into one of the record-grouping categories. Single
     * source of truth shared by [groupBlobs] (which reads bytes) and [scanRecordKeys]
     * (which only needs keys). Returns [PathClass.Skip] for metadata files, malformed
     * paths, and anything that doesn't follow the record layout.
     */
    private fun classifyPath(path: String): PathClass {
        if ('/' !in path) return PathClass.Skip

        if (path.startsWith("$TOMBSTONES_DIR/")) {
            val tail = path.substring(TOMBSTONES_DIR.length + 1)
            val slash = tail.indexOf('/')
            if (slash <= 0) return PathClass.Skip
            val tableName = tail.substring(0, slash)
            val rest = tail.substring(slash + 1)
            if (!rest.endsWith(".json") || '/' in rest) return PathClass.Skip
            val uuid = rest.removeSuffix(".json").toUuidOrNull() ?: return PathClass.Skip
            return PathClass.Tombstone(RecordKey(tableName, uuid))
        }

        val firstSlash = path.indexOf('/')
        val tableName = path.substring(0, firstSlash)
        val rest = path.substring(firstSlash + 1)

        if (isMultiFileTable(tableName)) {
            return parseScriptFilename(rest) ?: PathClass.Skip
        }

        if (!rest.endsWith(".json") || '/' in rest) return PathClass.Skip
        val uuid = rest.removeSuffix(".json").toUuidOrNull() ?: return PathClass.Skip
        return PathClass.LiveRecord(RecordKey(tableName, uuid))
    }

    private fun parseScriptFilename(tail: String): PathClass? {
        if (tail.endsWith(".meta.json")) {
            val uuid = tail.removeSuffix(".meta.json").toUuidOrNull() ?: return null
            return PathClass.ScriptMeta(uuid)
        }
        if (tail.endsWith(".kts")) {
            val uuid = tail.removeSuffix(".kts").toUuidOrNull() ?: return null
            return PathClass.ScriptBody(uuid)
        }
        return null
    }

    private sealed class PathClass {
        data class Tombstone(val key: RecordKey) : PathClass()
        data class LiveRecord(val key: RecordKey) : PathClass()
        data class ScriptMeta(val uuid: UUID) : PathClass()
        data class ScriptBody(val uuid: UUID) : PathClass()
        object Skip : PathClass()
    }
}

private fun String.toUuidOrNull(): UUID? = try { UUID.fromString(this) } catch (_: IllegalArgumentException) { null }
