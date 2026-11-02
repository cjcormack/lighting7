package uk.me.cormack.lighting7.history

import uk.me.cormack.lighting7.sync.RecordImage
import uk.me.cormack.lighting7.sync.RecordKey

/** One record's change within an entry: its image before the entry's first write and after its last. */
class Change(val key: RecordKey, var rowId: Int, var before: RecordImage?, var after: RecordImage?) {
    val kind: Kind
        get() = when {
            before == null -> Kind.ADDED
            after == null -> Kind.REMOVED
            else -> Kind.CHANGED
        }

    /** True when the record ends as it began: an entry drops such a change. */
    val isNoOp: Boolean get() = before?.hash == after?.hash

    enum class Kind { ADDED, REMOVED, CHANGED }
}

/**
 * One change to a project's records (undo plan §3.1): every record one request, frame, tool call or
 * surface input changed, with its images before and after. [changes] is keyed by record and holds the
 * first `before` and the last `after` of every write the entry folded in (§3.4).
 */
class HistoryEntry internal constructor(
    val id: String,
    val projectId: Int,
    val source: HistorySource,
    val window: String?,
    requestId: String,
    val at: Long,
    internal val changes: LinkedHashMap<RecordKey, Change>,
) {
    /**
     * Every request whose writes the entry holds: its first, and each folded into it since (§3.4, D8).
     * A REST answer's `X-History-Entry` is one of these.
     */
    internal val requestIds: MutableSet<String> = linkedSetOf(requestId)

    /** When the entry last took a write: D8's merge window runs from here. */
    @Volatile
    var lastAt: Long = at
        internal set

    /** The door's or the route's name for the entry, when either gave one. */
    @Volatile
    var givenLabel: String? = null
        internal set

    /** The name the history shows, the door's or else [RecordLabeler]'s. */
    @Volatile
    var label: String = ""
        internal set

    @Volatile
    var undone: Boolean = false
        internal set

    val records: Collection<Change> get() = changes.values

    /** What the entry's images cost, counted against the 32 MB cap (D5). */
    internal fun bytes(): Long = changes.values.sumOf { (it.before?.json?.length ?: 0).toLong() + (it.after?.json?.length ?: 0) }

    fun counts(): Counts = Counts(
        added = changes.values.count { it.kind == Change.Kind.ADDED },
        removed = changes.values.count { it.kind == Change.Kind.REMOVED },
        changed = changes.values.count { it.kind == Change.Kind.CHANGED },
    )

    data class Counts(val added: Int, val removed: Int, val changed: Int)

    /** A copy no later commit changes, for a reader outside the history's lock. */
    internal fun detached(): HistoryEntry = HistoryEntry(
        id, projectId, source, window, requestIds.first(), at,
        changes.mapValuesTo(LinkedHashMap()) { (_, c) -> Change(c.key, c.rowId, c.before, c.after) },
    ).also { copy ->
        copy.requestIds.addAll(requestIds)
        copy.lastAt = lastAt
        copy.givenLabel = givenLabel
        copy.label = label
        copy.undone = undone
    }
}

/** A moment a project's show history was dropped (D5), drawn as a rule in the History sheet. */
data class ClearPoint(val id: String, val at: Long, val reason: String)
