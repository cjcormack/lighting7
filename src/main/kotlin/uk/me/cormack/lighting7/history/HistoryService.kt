package uk.me.cormack.lighting7.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.sceneImageHashesOf
import uk.me.cormack.lighting7.sync.MapSink
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.RESTORABLE_FOLDERS
import uk.me.cormack.lighting7.sync.RecordFolders
import uk.me.cormack.lighting7.sync.RecordHasher
import uk.me.cormack.lighting7.sync.RecordImage
import uk.me.cormack.lighting7.sync.RecordKey
import uk.me.cormack.lighting7.sync.canonicalEncode
import uk.me.cormack.lighting7.sync.jsonObjectOrNull
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
    private var undoSeq = 0L
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
     * is rebased into the journal (no origin, D14), or marks the entries an undo or redo put back.
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
                    else -> {
                        moved = true
                        markLocked(history, origin.entryIds, origin.kind == WriteOrigin.Kind.UNDO)
                    }
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
            val from = change.before!!.jsonObjectOrNull() ?: continue
            val to = change.after!!.jsonObjectOrNull() ?: continue
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
        val held = image.jsonObjectOrNull() ?: return image
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
        projects[projectId]?.entries?.filter { it.undone && it.source.key == sourceKey }?.maxByOrNull { it.undoneSeq }
    }

    /** What an undo or a redo of a project's show history would do, read against its record cache. */
    internal sealed interface Plan {
        /** Nothing of the caller's to take, or the entry named is already where it was asked to go. */
        data class Empty(val already: Boolean = false) : Plan

        /** The entry named is not in the history. */
        data object Unknown : Plan

        /**
         * The entry's records have moved since (D3): [later] are the entries that moved them, which
         * back-to-here would take too. Empty when something the history does not hold moved them.
         */
        data class Conflict(val entry: HistoryEntry, val later: List<HistoryEntry>) : Plan

        /** It would take away [record] while [by], which no entry it takes holds, still names it. */
        data class InUse(val record: String, val by: String) : Plan

        /** [entries], newest first, and the image each record goes back to — null for one that goes. */
        data class Ready(val entries: List<HistoryEntry>, val targets: Map<RecordKey, RecordImage?>) : Plan
    }

    /**
     * Plan an undo (§3.5): [entryId], or else [caller]'s newest entry not undone. Every record it holds
     * must stand as it left it, and nothing may come to name a record it takes away or be gone from under
     * one it puts back; otherwise the later entries that did so answer a [Plan.Conflict] — unless
     * [backToHere], which takes them too, newest first: on the caller's own entry every later one of the
     * caller's, and on anyone's the ones that collide, each with the entries that collide with it.
     * Call inside the transaction that applies it, so no commit lands between the two.
     */
    internal fun planUndo(projectId: Int, entryId: String?, caller: String, backToHere: Boolean): Plan = synchronized(lock) {
        val history = projects[projectId]
        val records = history?.records ?: return if (entryId == null) Plan.Empty() else Plan.Unknown
        val entries = history.entries.toList()
        val entry = when (entryId) {
            null -> entries.lastOrNull { !it.undone && it.source.key == caller } ?: return Plan.Empty()
            else -> entries.firstOrNull { it.id == entryId } ?: return Plan.Unknown
        }
        if (entry.undone) return Plan.Empty(already = true)
        val from = entries.indexOf(entry)
        val later = entries.subList(from + 1, entries.size).filter { !it.undone }
        val taken = LinkedHashSet<HistoryEntry>().apply { add(entry) }
        if (backToHere && entry.source.key == caller) later.filterTo(taken) { it.source.key == caller }
        // Every later entry that collides with what is taken, until none does.
        do {
            val targets = undoTargets(taken, entries)
            val grown = later.filter { it !in taken && collides(it, targets) }
            taken += grown
        } while (grown.isNotEmpty())
        val colliding = later.filter { it in taken }
        if (colliding.isNotEmpty() && !backToHere) return Plan.Conflict(entry, colliding.asReversed())
        val newestFirst = taken.sortedByDescending { entries.indexOf(it) }
        val targets = undoTargets(taken, entries)
        // Each record must stand as the newest taken entry left it.
        val standing = HashMap<RecordKey, RecordImage?>()
        for (e in newestFirst.asReversed()) for (change in e.changes.values) standing[change.key] = change.after
        if (standing.any { (key, image) -> records[key]?.hash != image?.hash }) return Plan.Conflict(entry, colliding.asReversed())
        inUse(records, targets)?.let { return it }
        Plan.Ready(newestFirst, targets)
    }

    /** Where [taken]'s records go back to: each to the oldest taken entry's `before`. */
    private fun undoTargets(taken: Set<HistoryEntry>, entries: List<HistoryEntry>): LinkedHashMap<RecordKey, RecordImage?> {
        val targets = LinkedHashMap<RecordKey, RecordImage?>()
        for (e in taken.sortedBy { entries.indexOf(it) }) for (change in e.changes.values) targets.putIfAbsent(change.key, change.before)
        return targets
    }

    /**
     * Whether [later] collides with an undo putting [targets] back: it touches one of their records, a
     * record it wrote links to one the undo takes away, or it took away a record one the undo puts back
     * links to ([links]).
     */
    private fun collides(later: HistoryEntry, targets: Map<RecordKey, RecordImage?>): Boolean {
        if (later.changes.keys.any(targets::containsKey)) return true
        val leaving = targets.filterValues { it == null }.keys.map(::Linked)
        val back = targets.mapNotNull { (key, image) -> image?.let { key to it } }
        return later.changes.values.any { change ->
            val after = change.after
            when {
                after != null -> leaving.any { links(change.key, after.json, it) }
                else -> Linked(change.key).let { gone -> back.any { (key, image) -> links(key, image.json, gone) } }
            }
        }
    }

    /**
     * A record [targets] take away that a record they leave standing still links to, by uuid — one
     * written outside the history, or before it began — or null when there is none.
     */
    private fun inUse(records: Map<RecordKey, RecordImage>, targets: Map<RecordKey, RecordImage?>): Plan.InUse? {
        val leaving = targets.filterValues { it == null }.keys.map(::Linked)
        if (leaving.isEmpty()) return null
        for ((key, image) in records) {
            if (targets.containsKey(key) && targets[key] == null) continue
            val json = (if (key in targets) targets[key] else image)?.json ?: continue
            val named = leaving.firstOrNull { links(key, json, it) }?.key ?: continue
            return Plan.InUse(RecordLabeler.name(named, records[named]), RecordLabeler.name(key, targets[key] ?: image))
        }
        for ((key, image) in targets) {
            if (key in records || image == null) continue
            val named = leaving.firstOrNull { links(key, image.json, it) }?.key ?: continue
            return Plan.InUse(RecordLabeler.name(named, records[named]), RecordLabeler.name(key, image))
        }
        return null
    }

    /**
     * Plan a redo (§3.5, D10): [entryId], or else [caller]'s most recently undone entry. The mirror of
     * [planUndo]: every record it holds must stand as it found it, or the entries that moved it since
     * answer a [Plan.Conflict] — the ones after it still standing, and the ones before it still undone.
     */
    internal fun planRedo(projectId: Int, entryId: String?, caller: String): Plan = synchronized(lock) {
        val history = projects[projectId]
        val records = history?.records ?: return if (entryId == null) Plan.Empty() else Plan.Unknown
        val entries = history.entries.toList()
        val entry = when (entryId) {
            null -> entries.filter { it.undone && it.source.key == caller }.maxByOrNull { it.undoneSeq } ?: return Plan.Empty()
            else -> entries.firstOrNull { it.id == entryId } ?: return Plan.Unknown
        }
        if (!entry.undone) return Plan.Empty(already = true)
        val moved = entry.changes.values.any { records[it.key]?.hash != it.before?.hash }
        if (moved) {
            val at = entries.indexOf(entry)
            val keys = entry.changes.keys
            val colliding = entries.withIndex().filter { (i, e) ->
                e !== entry && e.changes.keys.any(keys::contains) && (if (i > at) !e.undone else e.undone)
            }.map { it.value }
            return Plan.Conflict(entry, colliding.asReversed())
        }
        val targets = entry.changes.values.associateTo(LinkedHashMap()) { it.key to it.after }
        inUse(records, targets)?.let { return it }
        Plan.Ready(listOf(entry), targets)
    }

    /**
     * Mark [ids] of [projectId] undone or standing, as an undo's or redo's commit does; idempotent, for one
     * whose records already stood where it put them, so its commit wrote nothing.
     */
    internal fun mark(projectId: Int, ids: List<String>, undone: Boolean) {
        synchronized(lock) {
            val history = projects[projectId] ?: return
            if (markLocked(history, ids, undone)) bump()
        }
    }

    private fun markLocked(history: ProjectHistory, ids: List<String>, undone: Boolean): Boolean {
        var moved = false
        // Newest first, so redo's latest-undone is the oldest of them: it goes back first.
        for (id in ids) {
            val entry = history.entries.firstOrNull { it.id == id } ?: continue
            if (entry.undone == undone) continue
            entry.undone = undone
            if (undone) {
                entry.undoneSeq = ++undoSeq
                entry.undoneAt = clock()
            }
            moved = true
        }
        return moved
    }

    /** One record's change named for the History sheet, its owner read from [projectId]'s cache. */
    fun describe(projectId: Int, change: Change): String = RecordLabeler.label(listOf(change)) { recordOf(projectId, it) }

    /**
     * Every scene image a stage element's image in [projectId]'s journal paints with, before or after
     * a change: an undo may put one back, so the store keeps them ([uk.me.cormack.lighting7.state.SceneImageStore.prune]).
     */
    fun sceneImageHashes(projectId: Int): Set<String> = synchronized(lock) {
        val entries = projects[projectId]?.entries ?: return emptySet()
        entries.flatMap { it.changes.values }
            .filter { it.key.tableName == RecordFolders.STAGE_ELEMENTS }
            .flatMap { listOfNotNull(it.before, it.after) }
            .flatMapTo(HashSet()) { image -> (image.jsonObjectOrNull()?.get("params") as? JsonObject)?.let(::sceneImageHashesOf).orEmpty() }
    }

    /** The record cache's image of [key] in [projectId], for a label or a test. */
    fun recordOf(projectId: Int, key: RecordKey): RecordImage? = synchronized(lock) { projects[projectId]?.records?.get(key) }

    /** The entries of any project with [ids], as copies. */
    internal fun entriesById(ids: Collection<String>): List<HistoryEntry> = synchronized(lock) {
        projects.values.flatMap { history -> history.entries.filter { it.id in ids } }.map { it.detached() }
    }

    /** The record cache's images of [folders] in [projectId], as a copy. */
    internal fun recordsIn(projectId: Int, folders: Set<String>): Map<RecordKey, RecordImage> = synchronized(lock) {
        projects[projectId]?.records?.filterKeys { it.tableName in folders }.orEmpty()
    }

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

        /**
         * The folders whose records link to others by uuid, as foreign keys do: the restorable ones but a
         * binding and a surface job, whose payloads are tolerant of a record gone (sync v11).
         */
        private val LINKING_FOLDERS: Set<String> by lazy {
            RESTORABLE_FOLDERS - RecordFolders.CONTROL_SURFACE_BINDINGS - RecordFolders.SURFACE_JOBS
        }

        /** A record another may link to, its uuid as the text a link carries. */
        private class Linked(val key: RecordKey) {
            val uuid = key.uuid.toString()
        }

        /**
         * Whether record [from], as [json], links to [to]: it carries [to]'s uuid, or it is an anchor or
         * a note of the project's one prompt book, which names no book.
         */
        private fun links(from: RecordKey, json: String, to: Linked): Boolean =
            from.tableName in LINKING_FOLDERS &&
                (json.contains(to.uuid) || (to.key.tableName == RecordFolders.PROMPT_BOOKS && from.tableName in RecordFolders.PROMPT_BOOK_CHILDREN))
    }
}

/**
 * This image with [fields] set to their values in [to] (absent there, removed here), its hash taken
 * again as [MapSink] would: a script's over its meta, a newline and its body.
 */
private fun RecordImage.patched(folder: String, to: JsonObject, fields: Set<String>): RecordImage {
    val held = jsonObjectOrNull() ?: return this
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
