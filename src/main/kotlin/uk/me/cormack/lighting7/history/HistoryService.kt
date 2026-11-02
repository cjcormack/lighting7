package uk.me.cormack.lighting7.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.sync.MapSink
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.RecordHasher
import uk.me.cormack.lighting7.sync.RecordImage
import uk.me.cormack.lighting7.sync.RecordKey
import uk.me.cormack.lighting7.sync.canonicalEncode
import uk.me.cormack.lighting7.sync.canonicalJson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The show history (undo plan §3.3, §3.4): per project, the cache of its records as they stand
 * ([ProjectRecords]), and the journal of entries the [JournalInterceptor] hands it after every commit
 * that changed one. Runtime state only (D15): nothing here is stored or synced.
 *
 * A project has history once a door has called [ensure] for it — the current project, or one a route
 * or a tool names — and loses it at a clear point ([clear], D5) until the next [ensure]. Every commit
 * runs on the pool's one connection, so [stage] and [commit] for one transaction never interleave with
 * another's; [lock] guards the maps against the readers ([entries], [topFor]) on other threads.
 */
class HistoryService(
    private val database: Database,
    private val exporter: ProjectExporter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Long = MAX_BYTES,
) {
    /** One project's history: its record cache while it has one, its entries and its clear points. */
    private class ProjectHistory {
        /** The project's records as they stand: `(folder, uuid)` → image. Null until [ensure]. */
        var records: MutableMap<RecordKey, RecordImage>? = null
        val entries = ArrayDeque<HistoryEntry>()
        val clears = ArrayDeque<ClearPoint>()

        /** When building the cache last failed: the doors do not try again until [ENSURE_RETRY_MS] has passed. */
        var failedAt: Long? = null
    }

    private val projects = ConcurrentHashMap<Int, ProjectHistory>()
    private val lock = Any()
    private val ids = AtomicLong()
    private val _revision = MutableStateFlow(0L)

    /** Moves with every change to any project's history; `history.state` and the sheet refetch on it. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Whether any project has a record cache, so a commit has something to diff against. */
    internal val isJournaling: Boolean get() = projects.values.any { it.records != null }

    /** The projects with a record cache, which every journaled commit re-exports its folders for. */
    internal fun journaledProjects(): List<Int> = projects.entries.filter { it.value.records != null }.map { it.key }

    /**
     * Build [projectId]'s record cache if it has none, so the next write to it is journaled. Inside its
     * own transaction, which holds the pool's one connection: no commit can land between the export and
     * the cache going in. A venue version is never journaled (D12), and a project the desk lacks has
     * nothing to journal.
     */
    fun ensure(projectId: Int) {
        val known = projects[projectId]
        if (known?.records != null) return
        // Every frame and input calls this; a project whose export fails is not exported again on each.
        known?.failedAt?.let { if (clock() - it < ENSURE_RETRY_MS) return }
        try {
            transaction(database) {
                val project = DaoProject.findById(projectId) ?: return@transaction
                if (project.kind == ProjectKind.VENUE_VERSION) return@transaction
                val sink = MapSink()
                exporter.exportKinds(projectId, ProjectExporter.FOLDERS, sink)
                synchronized(lock) {
                    val history = projects.getOrPut(projectId) { ProjectHistory() }
                    history.failedAt = null
                    if (history.records == null) history.records = sink.images.toMutableMap()
                }
            }
        } catch (e: Exception) {
            synchronized(lock) { projects.getOrPut(projectId) { ProjectHistory() }.failedAt = clock() }
            log.warn("History could not read project {}'s records; its changes go unjournaled", projectId, e)
        }
    }

    /**
     * The diff a commit that touched [folders] made to each journaled project, read inside that commit
     * by the [JournalInterceptor]. Null when it changed no record.
     */
    internal fun stage(folders: Set<String>, origin: WriteOrigin?): Staged? {
        val diffs = mutableMapOf<Int, List<Change>>()
        for (projectId in journaledProjects()) {
            // Read without a copy: only [commit] changes a cache, and it runs after this on the same connection.
            val cached = synchronized(lock) { projects[projectId]?.records } ?: continue
            val sink = MapSink()
            val exists = transaction(database) { DaoProject.findById(projectId) != null }
            if (!exists) {
                diffs[projectId] = emptyList()
                continue
            }
            exporter.exportKinds(projectId, folders, sink)
            val changes = mutableListOf<Change>()
            val keys = LinkedHashSet<RecordKey>()
            cached.keys.filterTo(keys) { it.tableName in folders }
            keys.addAll(sink.images.keys)
            for (key in keys) {
                val before = cached[key]
                val after = sink.images[key]
                if (before?.hash == after?.hash) continue
                changes += Change(key, (after ?: before)!!.rowId, before, after)
            }
            if (changes.isNotEmpty()) diffs[projectId] = changes
        }
        if (diffs.isEmpty()) return null
        return Staged(origin, diffs)
    }

    /** What one commit changed, per project, staged before the commit and applied after it. */
    internal class Staged(val origin: WriteOrigin?, val diffs: Map<Int, List<Change>>)

    /**
     * Apply a committed [staged] diff: the cache takes it, and it becomes an entry (a tracked origin),
     * is rebased into the journal (no origin, D14), or only moves the cache (an undo or redo's own
     * write, whose entries session 4 marks).
     */
    internal fun commit(staged: Staged) {
        val origin = staged.origin
        synchronized(lock) {
            // Only a change a reader could see moves the revision: an untracked write to a project
            // with no entries — the tempo flush, mostly — sends no frame.
            var moved = false
            for ((projectId, changes) in staged.diffs) {
                val history = projects[projectId] ?: continue
                val records = history.records ?: continue
                if (changes.isEmpty()) {
                    // The project was deleted in this transaction.
                    forgetLocked(projectId)
                    moved = true
                    continue
                }
                for (change in changes) {
                    val after = change.after
                    if (after == null) records.remove(change.key) else records[change.key] = after
                }
                when {
                    origin == null || origin.kind == WriteOrigin.Kind.UNTRACKED -> {
                        if (history.entries.isNotEmpty()) moved = true
                        rebase(projectId, history, changes)
                    }
                    origin.kind == WriteOrigin.Kind.TRACKED -> {
                        moved = true
                        append(projectId, history, origin, changes)
                    }
                    else -> Unit
                }
            }
            if (moved) bump()
        }
    }

    private fun append(projectId: Int, history: ProjectHistory, origin: WriteOrigin, changes: List<Change>) {
        val now = clock()
        val keys = changes.mapTo(HashSet()) { it.key }
        val top = history.entries.lastOrNull()
        val entry = history.entries.lastOrNull { origin.requestId in it.requestIds }
            ?: top?.takeIf {
                !it.undone && it.source.key == origin.source.key && it.changes.keys == keys && now - it.lastAt < MERGE_MS
            }
        val target = entry ?: run {
            // A new entry from a source drops that source's undone entries (D10).
            history.entries.removeAll { it.undone && it.source.key == origin.source.key }
            HistoryEntry(
                id = "e-" + ids.incrementAndGet().toString(36),
                projectId = projectId,
                source = origin.source,
                window = origin.window,
                requestId = origin.requestId,
                at = now,
                changes = LinkedHashMap(),
            ).also { history.entries.addLast(it) }
        }
        target.requestIds.add(origin.requestId)
        for (change in changes) {
            val held = target.changes[change.key]
            if (held == null) {
                target.changes[change.key] = Change(change.key, change.rowId, change.before, change.after)
            } else {
                held.after = change.after
                held.rowId = change.rowId
            }
        }
        target.changes.values.removeAll { it.isNoOp }
        if (target.changes.isEmpty()) {
            history.entries.remove(target)
            return
        }
        target.lastAt = now
        origin.entryLabel?.let { target.givenLabel = it }
        val records = history.records.orEmpty()
        target.label = target.givenLabel ?: RecordLabeler.label(target.records) { records[it] }
        cap(history)
    }

    /**
     * An untracked write (D14): the fields it changed are patched into every entry's images of the
     * records it touched, so the journal never reads it as a conflict and an undo would not put it back.
     * A scalar field is set in both images. An array — a Look's rows, a group's members — is set only in
     * an image that held the value the write replaced; one the entry itself changed cannot be merged, and
     * neither can a write that adds or removes a record, so either clears the project's show history.
     * A change the patch leaves as it began drops out of its entry.
     */
    private fun rebase(projectId: Int, history: ProjectHistory, changes: List<Change>) {
        if (history.entries.isEmpty()) return
        if (changes.any { it.before == null || it.after == null }) {
            log.info(
                "History of project {} cleared: an untracked write added or removed {}",
                projectId, changes.filter { it.before == null || it.after == null }.joinToString { it.key.toString() },
            )
            clearLocked(projectId, history, BACKGROUND_REASON)
            return
        }
        for (change in changes) {
            val from = change.before!!.jsonObject() ?: continue
            val to = change.after!!.jsonObject() ?: continue
            val fields = (from.keys + to.keys).filterTo(mutableSetOf()) { from[it] != to[it] }
            if (fields.isEmpty()) continue
            for (entry in history.entries) {
                val held = entry.changes[change.key] ?: continue
                val before = held.before?.let { patch(it, change.key.tableName, from, to, fields) ?: return unmergeable(projectId, history, change) }
                val after = held.after?.let { patch(it, change.key.tableName, from, to, fields) ?: return unmergeable(projectId, history, change) }
                held.before = before
                held.after = after
            }
        }
        for (entry in history.entries) entry.changes.values.removeAll { it.isNoOp }
        history.entries.removeAll { it.changes.isEmpty() }
    }

    private fun unmergeable(projectId: Int, history: ProjectHistory, change: Change) {
        log.info("History of project {} cleared: an untracked write changed {} where an entry had changed it too", projectId, change.key)
        clearLocked(projectId, history, BACKGROUND_REASON)
    }

    /** [image] with [fields] taken from [to], or null when an array field held something other than [from]'s. */
    private fun patch(image: RecordImage, folder: String, from: JsonObject, to: JsonObject, fields: Set<String>): RecordImage? {
        val held = image.jsonObject() ?: return image
        for (field in fields) {
            val mine = held[field]
            if ((mine is JsonArray || from[field] is JsonArray || to[field] is JsonArray) && mine != from[field]) return null
        }
        return image.patched(folder, to, fields)
    }

    private fun cap(history: ProjectHistory) {
        while (history.entries.size > maxEntries) history.entries.removeFirst()
        var bytes = history.entries.sumOf { it.bytes() }
        while (bytes > maxBytes && history.entries.size > 1) {
            bytes -= history.entries.removeFirst().bytes()
        }
    }

    /**
     * A clear point (D5): [projectId]'s show history and its record cache dropped, the moment recorded
     * as [reason]. The next door to name the project builds its cache again.
     */
    fun clear(projectId: Int, reason: String) {
        synchronized(lock) {
            val history = projects[projectId] ?: return
            clearLocked(projectId, history, reason)
            bump()
        }
    }

    private fun clearLocked(projectId: Int, history: ProjectHistory, reason: String) {
        val now = clock()
        val last = history.clears.lastOrNull()
        val newest = history.entries.lastOrNull()?.lastAt ?: Long.MIN_VALUE
        // One gesture that is two clear points — a pull's untracked rewrite, then the pull itself — is one rule.
        if (last != null && last.at >= newest && now - last.at < CLEAR_MERGE_MS) {
            history.clears.removeLast()
        }
        history.clears.addLast(ClearPoint("c-" + ids.incrementAndGet().toString(36), now, reason))
        while (history.clears.size > MAX_CLEARS) history.clears.removeFirst()
        history.entries.clear()
        history.records = null
        log.debug("History of project {} cleared: {}", projectId, reason)
    }

    /** A deleted project's history, gone. */
    fun forget(projectId: Int) {
        synchronized(lock) {
            forgetLocked(projectId)
            bump()
        }
    }

    private fun forgetLocked(projectId: Int) {
        projects.remove(projectId)
    }

    /** [projectId]'s entries, newest first, as copies no later commit changes. */
    fun entries(projectId: Int): List<HistoryEntry> = synchronized(lock) {
        projects[projectId]?.entries?.asReversed()?.map { it.detached() }.orEmpty()
    }

    /** [projectId]'s clear points, newest first. */
    fun clearPoints(projectId: Int): List<ClearPoint> = synchronized(lock) {
        projects[projectId]?.clears?.reversed().orEmpty()
    }

    /** What [sourceKey]'s ⌘Z would take of [projectId]'s show history: its newest entry not undone. */
    fun topFor(projectId: Int, sourceKey: String): HistoryEntry? = synchronized(lock) {
        projects[projectId]?.entries?.lastOrNull { !it.undone && it.source.key == sourceKey }
    }

    /** What [sourceKey]'s ⇧⌘Z would take: its most recently undone entry. */
    fun redoFor(projectId: Int, sourceKey: String): HistoryEntry? = synchronized(lock) {
        projects[projectId]?.entries?.lastOrNull { it.undone && it.source.key == sourceKey }
    }

    /** One record's change named for the History sheet, its owner read from [projectId]'s cache. */
    fun describe(projectId: Int, change: Change): String = RecordLabeler.label(listOf(change)) { recordOf(projectId, it) }

    /** The record cache's image of [key] in [projectId], for a label or a test. */
    fun recordOf(projectId: Int, key: RecordKey): RecordImage? = synchronized(lock) { projects[projectId]?.records?.get(key) }

    /** Whether [projectId] has a record cache. */
    fun isJournaled(projectId: Int): Boolean = projects[projectId]?.records != null

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    companion object {
        private val log = LoggerFactory.getLogger(HistoryService::class.java)

        /** D5's depth: entries per project. */
        const val MAX_ENTRIES = 500

        /** D5's other cap: the images a project's entries hold. */
        const val MAX_BYTES = 32L * 1024 * 1024

        /** D8: a source's writes to the same records within this of its last fold into one entry. */
        const val MERGE_MS = 1_500L

        private const val CLEAR_MERGE_MS = 5_000L
        private const val ENSURE_RETRY_MS = 60_000L
        private const val MAX_CLEARS = 20

        const val BACKGROUND_REASON = "Changed outside the history"
    }
}

private fun RecordImage.jsonObject(): JsonObject? =
    runCatching { canonicalJson.parseToJsonElement(json).jsonObject }.getOrNull()

/**
 * This image with [fields] set to their values in [to] (absent there, removed here), its hash taken
 * again as [MapSink] would: a script's over its meta, a newline and its body.
 */
private fun RecordImage.patched(folder: String, to: JsonObject, fields: Set<String>): RecordImage {
    val held = jsonObject() ?: return this
    val next = LinkedHashMap<String, JsonElement>(held)
    for (field in fields) {
        val value = to[field]
        if (value == null) next.remove(field) else next[field] = value
    }
    val obj = JsonObject(next)
    val json = canonicalEncode(JsonElement.serializer(), obj)
    val hash = if (folder == SCRIPTS_FOLDER) {
        val body = (obj["body"] as? JsonPrimitive)?.content.orEmpty()
        val meta = canonicalEncode(JsonElement.serializer(), JsonObject(obj - "body"))
        RecordHasher.sha256Hex(meta + "\n" + body)
    } else {
        RecordHasher.sha256Hex(json)
    }
    return RecordImage(json, rowId, hash)
}

private const val SCRIPTS_FOLDER = "scripts"
