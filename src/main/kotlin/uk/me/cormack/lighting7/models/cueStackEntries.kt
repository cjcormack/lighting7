package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.dao.with
import org.jetbrains.exposed.v1.javatime.duration
import kotlin.math.ceil
import kotlin.math.floor

// ─── Entry kinds ────────────────────────────────────────────────────────

/**
 * What one row of a stack's list is (cue model plan §3.1). A `CUE` entry names a cue; a `MARKER`
 * entry is a labelled divider with no cue (D12) — never shared, pressed, listed or deleted as a
 * cue. On the wire a marker still reads as `cueType: "MARKER"`, the vocabulary the stack list and
 * today's sync archive both speak.
 */
enum class CueStackEntryKind { CUE, MARKER }

// ─── Cue stack entries table ────────────────────────────────────────────

/**
 * A stack's ordered list (cue model plan D1, D2, §3.2): one row per place in the list. An entry
 * holds the cue's **place** in that stack — its position, its number (and whether that number is
 * auto) and its follow — and the cue holds what it is.
 *
 * Until the cue model plan's session 4 every cue has exactly one entry (P4). Nothing here assumes
 * it, and the few callers that still have only a cue and need its stack go through [soleEntryOf],
 * which says so. The archive no longer does: from sync `formatVersion` 32 each entry is its own
 * record, `cueStackEntries/{uuid}.json` (`sync/ProjectExporter.kt`), written from these rows
 * directly — so a shared or loose cue (session 4) already travels.
 *
 * Numbers are unique per stack among cue entries through the raw-SQL partial index
 * `uq_entry_number_per_stack` (`State.initDatabase`), which Exposed cannot declare.
 */
object DaoCueStackEntries : IntIdTable("cue_stack_entries") {
    val stack = reference("stack_id", DaoCueStacks)
    /** The cue this entry places; null for a marker. */
    val cue = optReference("cue_id", DaoCues)
    /** [CueStackEntryKind] as a string. */
    val kind = varchar("kind", 20).default(CueStackEntryKind.CUE.name)
    /** A marker's text; null for a cue entry, which is shown by its cue's name. */
    val label = varchar("label", 255).nullable()
    /**
     * Position in the stack, ascending, ties broken by uuid ([ENTRY_ORDER], P6). A double so an
     * insert takes the midpoint of its neighbours ([writeEntryOrder]) and a drag rewrites one entry;
     * the startup migration renormalised every stack to `1.0, 2.0, …`. Two desks inserting into the
     * same gap give their entries the same ordinal, and the uuid orders them the same way on both.
     */
    val ordinal = double("ordinal").default(0.0)
    val cueNumber = varchar("cue_number", 20).nullable()
    /**
     * True when [cueNumber] was derived from the entry's position rather than typed by the
     * operator. An auto number stays put while its entry keeps its place in the stack, and is
     * derived again when a change to the stack's list moves or shifts it (venue-visit plan D11, see
     * `renumberAutoEntries`); explicit ones are never touched.
     */
    val cueNumberAuto = bool("cue_number_auto").default(false)
    val autoAdvance = bool("auto_advance").default(false)
    val autoAdvanceDelay = duration("auto_advance_delay").nullable()
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        index(false, stack, ordinal)
        index(false, cue)
    }
}

class DaoCueStackEntry(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueStackEntry>(DaoCueStackEntries)

    var stack by DaoCueStack referencedOn DaoCueStackEntries.stack
    var cue by DaoCue optionalReferencedOn DaoCueStackEntries.cue
    var kind by DaoCueStackEntries.kind
    var label by DaoCueStackEntries.label
    var ordinal by DaoCueStackEntries.ordinal
    var cueNumber by DaoCueStackEntries.cueNumber
    var cueNumberAuto by DaoCueStackEntries.cueNumberAuto
    var autoAdvance by DaoCueStackEntries.autoAdvance
    var autoAdvanceDelay by DaoCueStackEntries.autoAdvanceDelay
    var uuid by DaoCueStackEntries.uuid

    val isMarker: Boolean get() = kind == CueStackEntryKind.MARKER.name

    /** The FK values without loading the referenced entities. */
    val stackId: Int get() = readValues.getOrNull(DaoCueStackEntries.stack)?.value ?: stack.id.value
    val cueId: Int? get() = if (readValues.hasValue(DaoCueStackEntries.cue)) {
        readValues[DaoCueStackEntries.cue]?.value
    } else {
        cue?.id?.value
    }

    /** What this row is called in a list: a marker's label, else its cue's name. */
    val displayName: String get() = if (isMarker) label.orEmpty() else cue?.name.orEmpty()
}

/**
 * Playback order (cue model plan P6): ascending `ordinal`, then ascending uuid — the uuid's
 * canonical text, which is its 16 bytes compared unsigned. Never the int id: ids are minted per
 * desk in whatever order an import reads its files, so two desks holding the same entries (two
 * inserts into one gap, merged) would otherwise play them in different orders.
 */
val ENTRY_ORDER: Comparator<DaoCueStackEntry> = Comparator { a, b ->
    val byOrdinal = a.ordinal.compareTo(b.ordinal)
    if (byOrdinal != 0) byOrdinal else compareUuidsAsText(a.uuid, b.uuid)
}

/**
 * [a] against [b] as their canonical (lowercase, fixed-width hex) text compares — the 128 bits read
 * unsigned — without building either string. `UUID.compareTo` is not it: it compares the halves
 * signed. Every entry list is sorted through [ENTRY_ORDER], so a sort no longer allocates two
 * strings per comparison.
 */
internal fun compareUuidsAsText(a: java.util.UUID, b: java.util.UUID): Int {
    val high = java.lang.Long.compareUnsigned(a.mostSignificantBits, b.mostSignificantBits)
    return if (high != 0) high else java.lang.Long.compareUnsigned(a.leastSignificantBits, b.leastSignificantBits)
}

/** [stack]'s entries in playback order ([ENTRY_ORDER]), markers included. */
fun entriesOf(stack: DaoCueStack): List<DaoCueStackEntry> = entriesOfStack(stack.id)

/** [stackId]'s entries in playback order ([ENTRY_ORDER]), markers included. */
fun entriesOfStack(stackId: EntityID<Int>): List<DaoCueStackEntry> =
    DaoCueStackEntry.find { DaoCueStackEntries.stack eq stackId }.toList().sortedWith(ENTRY_ORDER)

/**
 * The first entry in stack [stackId] that places cue [cueId], in the stack's order — the caller names
 * the stack (cue model plan P7). Only for a caller that still names a cue where the runtime takes an
 * entry (P2): the MCP stack tools until the plan's session 7, a Record into a stack, and the include
 * indicator's number. A cue twice in one stack (D7) is told apart only by its entries.
 */
fun entryInStack(stackId: Int, cueId: Int): DaoCueStackEntry? =
    DaoCueStackEntry.find { (DaoCueStackEntries.stack eq stackId) and (DaoCueStackEntries.cue eq cueId) }
        .toList()
        .minWithOrNull(ENTRY_ORDER)

/** Every entry naming [cue], in no particular stack order. */
fun entriesOfCue(cue: DaoCue): List<DaoCueStackEntry> =
    DaoCueStackEntry.find { DaoCueStackEntries.cue eq cue.id }.orderBy(DaoCueStackEntries.id to SortOrder.ASC).toList()

/**
 * [cue]'s one entry, while the cue model plan's P4 holds — every cue in exactly one stack.
 *
 * **This is the P4 bridge, not a home stack.** Nothing that makes a cue live goes through it: a
 * busk cue pad, a cue slot, a MIDI `FireCue`, the hand, `/cues/{id}/apply` and MCP `apply_cue` hold
 * the cue loose (the plan's session 2, `fx/CueHolds.kt`) and need no entry. What is left are the
 * cue-shaped reads and writes that report or move a cue's stack and number (`CueDetails`, the
 * briefing, the prompt book's cue-keyed anchors, the scenery tracked into a cue, `add-cue`), which
 * take an entry from their caller as the later sessions reach them. It never chooses: a cue with no
 * entry or several answers null, which every caller treats as "this cue is in no stack" — so the day
 * session 4 lets a cue be shared, a caller still on it fails visibly rather than picking one.
 */
fun soleEntryOf(cue: DaoCue): DaoCueStackEntry? = entriesOfCue(cue).singleOrNull()

/** A cue's one entry ([soleEntryOf]) and that entry's index in its stack's list, markers counted. */
data class EntrySlot(val entry: DaoCueStackEntry, val position: Int)

/**
 * [soleEntryOf] and the entry's position for every cue in [cueIds], **batched**: two queries for
 * the whole set (the cues' entries with their stacks, then those stacks' lists), where asking per
 * cue costs two queries and a whole stack's list each — which a cue list or a republish of every
 * live cue would pay once per cue. A cue with no entry or several is absent, as [soleEntryOf]
 * answers null for it. Must run inside a transaction.
 */
fun soleEntrySlotsOf(cueIds: Collection<Int>): Map<Int, EntrySlot> {
    if (cueIds.isEmpty()) return emptyMap()
    val sole = DaoCueStackEntry.find { DaoCueStackEntries.cue inList cueIds.distinct() }
        .with(DaoCueStackEntry::stack)
        .toList()
        .groupBy { it.cueId!! }
        .mapNotNull { (cueId, entries) -> entries.singleOrNull()?.let { cueId to it } }
    if (sole.isEmpty()) return emptyMap()
    val positions = DaoCueStackEntry.find { DaoCueStackEntries.stack inList sole.map { it.second.stackId }.distinct() }
        .toList()
        .groupBy { it.stackId }
        .values
        .flatMap { list -> list.sortedWith(ENTRY_ORDER).mapIndexed { index, entry -> entry.id.value to index } }
        .toMap()
    return sole.associate { (cueId, entry) -> cueId to EntrySlot(entry, positions.getValue(entry.id.value)) }
}

/** [soleEntryOf] by id, for a caller holding only the cue's id. */
fun soleEntryOfCueId(cueId: Int): DaoCueStackEntry? =
    DaoCueStackEntry.find { DaoCueStackEntries.cue eq cueId }.limit(2).toList().singleOrNull()

/** The ordinal one past [stack]'s last entry — where an appended entry goes. */
fun nextOrdinal(stack: DaoCueStack): Double = (entriesOf(stack).maxOfOrNull { it.ordinal } ?: 0.0) + 1.0

/**
 * Write [ordered] — a stack's entries in the order they should play — back as ordinals, so that
 * [ENTRY_ORDER] reads exactly that list, **changing as few entries as it can** (cue model plan P6).
 *
 * Every handler that changes a list builds the new order in memory and hands it here. The entries
 * whose ordinals already ascend in it — the longest run of them, each strictly above the last —
 * keep their ordinals; each gap of the others between two kept neighbours takes evenly spaced
 * ordinals strictly between them (one moved entry takes the midpoint), one after the last kept entry
 * counts on in whole numbers, and one before the first counts down. So a drag rewrites one entry and
 * an insert writes one, and since the archive writes one file per entry (`cueStackEntries/`, sync
 * `formatVersion` 32) a drag is one changed file, where renumbering the stack `1.0, 2.0, …` rewrote
 * every entry after it.
 *
 * The caller names the entries in [ordered] that are **fresh** — just made, or moved in from another
 * stack — so their placeholder or old-stack ordinal never counts as in order; each takes the room its
 * neighbours leave.
 *
 * It never writes two entries one ordinal: a tie is ordered by uuid, which a copy or a clone re-mints.
 * A tie arrives only from a merge — two desks that inserted into one gap — and the next write to the
 * stack that keeps one of the pair moves the other off it.
 *
 * When a gap has no room left — doubles too close to split — the whole stack is renormalised to
 * `1.0, 2.0, …` instead: still exactly the order given, just more files.
 */
fun writeEntryOrder(ordered: List<DaoCueStackEntry>, fresh: Collection<DaoCueStackEntry> = emptyList()) {
    if (ordered.isEmpty()) return
    // A fresh entry — one just made, or one just moved in from another stack — holds a placeholder or
    // another list's ordinal, which must never count as "already in order": it would be kept and an
    // existing neighbour moved instead (an insert writing two entries, a new stack never starting at 1).
    // By identity, not id: reading a new entity's id would flush its placeholder row early.
    val ordinals = planEntryOrdinals(ordered.map { entry -> if (fresh.any { it === entry }) Double.NaN else entry.ordinal })
    ordered.forEachIndexed { index, entry ->
        val ordinal = ordinals[index]
        if (entry.ordinal != ordinal) entry.ordinal = ordinal
    }
}

/**
 * The ordinals [writeEntryOrder] gives a list whose current ordinals are [current], in the order
 * given: the longest strictly ascending run keeps its own, every other entry takes room between its
 * kept neighbours, and when a gap has no room, the list is renormalised to `1.0, 2.0, …`. A non-finite
 * value — how a fresh entry is passed — is never kept. The answer is always strictly ascending. Pure,
 * so it is tested on its own.
 */
internal fun planEntryOrdinals(current: List<Double>): List<Double> {
    val n = current.size
    if (n == 0) return emptyList()
    val kept = longestAscendingRun(current)
    val out = current.toDoubleArray()
    var i = 0
    while (i < n) {
        if (kept[i]) { i++; continue }
        var j = i
        while (j < n && !kept[j]) j++
        val spread = spreadBetween(if (i > 0) out[i - 1] else null, if (j < n) out[j] else null, j - i)
            ?: return List(n) { (it + 1).toDouble() }
        spread.forEachIndexed { k, ordinal -> out[i + k] = ordinal }
        i = j
    }
    return out.toList()
}

/**
 * Which of [values] form one longest strictly ascending subsequence (patience sorting, O(n log n)).
 * A non-finite value is never kept, so it is always given a place.
 */
private fun longestAscendingRun(values: List<Double>): BooleanArray {
    val tails = ArrayList<Int>()          // tails[len] = index of the smallest tail of a run of length len + 1
    val previous = IntArray(values.size) { -1 }
    for (index in values.indices) {
        if (!values[index].isFinite()) continue
        var lo = 0
        var hi = tails.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[tails[mid]] < values[index]) lo = mid + 1 else hi = mid
        }
        if (lo > 0) previous[index] = tails[lo - 1]
        if (lo == tails.size) tails.add(index) else tails[lo] = index
    }
    val kept = BooleanArray(values.size)
    var at = tails.lastOrNull() ?: -1
    while (at >= 0) {
        kept[at] = true
        at = previous[at]
    }
    return kept
}

/**
 * [count] ordinals strictly between [lo] and [hi] and strictly ascending, or null when doubles
 * cannot hold them. A missing bound counts in whole numbers from the other; neither counts from 1.
 */
private fun spreadBetween(lo: Double?, hi: Double?, count: Int): List<Double>? {
    val values = when {
        lo != null && hi != null -> {
            val step = (hi - lo) / (count + 1)
            List(count) { lo + step * (it + 1) }
        }
        lo != null -> List(count) { floor(lo) + it + 1 }
        hi != null -> List(count) { ceil(hi) - (count - it) }
        else -> List(count) { (it + 1).toDouble() }
    }
    var previous = lo
    for (value in values) {
        if (!value.isFinite() || (previous != null && value <= previous)) return null
        previous = value
    }
    if (hi != null && previous != null && previous >= hi) return null
    return values
}
