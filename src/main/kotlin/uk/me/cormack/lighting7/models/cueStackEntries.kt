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
 * Until the cue model plan's session 4 every cue has exactly one entry (P4), so today's archive
 * format can still describe the desk; nothing here assumes it, and the few callers that still
 * have only a cue and need its stack go through [soleEntryOf], which says so.
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
     * Position in the stack, ascending, ties broken by id (P6). A double so an insert can take the
     * midpoint of its neighbours; the startup migration renormalised every stack to `1.0, 2.0, …`.
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

/** [stack]'s entries in playback order — `(ordinal, id)`, markers included. */
fun entriesOf(stack: DaoCueStack): List<DaoCueStackEntry> = entriesOfStack(stack.id)

/** [stackId]'s entries in playback order — `(ordinal, id)`, markers included. */
fun entriesOfStack(stackId: EntityID<Int>): List<DaoCueStackEntry> =
    DaoCueStackEntry.find { DaoCueStackEntries.stack eq stackId }
        .orderBy(DaoCueStackEntries.ordinal to SortOrder.ASC, DaoCueStackEntries.id to SortOrder.ASC)
        .toList()

/**
 * The first entry in stack [stackId] that places cue [cueId], in the stack's order — the caller names
 * the stack (cue model plan P7). Only for a caller that still names a cue where the runtime takes an
 * entry (P2): the MCP stack tools until the plan's session 7, a Record into a stack, and the include
 * indicator's number. A cue twice in one stack (D7) is told apart only by its entries.
 */
fun entryInStack(stackId: Int, cueId: Int): DaoCueStackEntry? =
    DaoCueStackEntry.find { (DaoCueStackEntries.stack eq stackId) and (DaoCueStackEntries.cue eq cueId) }
        .orderBy(DaoCueStackEntries.ordinal to SortOrder.ASC, DaoCueStackEntries.id to SortOrder.ASC)
        .limit(1)
        .firstOrNull()

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
        .orderBy(DaoCueStackEntries.ordinal to SortOrder.ASC, DaoCueStackEntries.id to SortOrder.ASC)
        .toList()
        .groupBy { it.stackId }
        .values
        .flatMap { list -> list.mapIndexed { index, entry -> entry.id.value to index } }
        .toMap()
    return sole.associate { (cueId, entry) -> cueId to EntrySlot(entry, positions.getValue(entry.id.value)) }
}

/** [soleEntryOf] by id, for a caller holding only the cue's id. */
fun soleEntryOfCueId(cueId: Int): DaoCueStackEntry? =
    DaoCueStackEntry.find { DaoCueStackEntries.cue eq cueId }.limit(2).toList().singleOrNull()

/** The ordinal one past [stack]'s last entry — where an appended entry goes. */
fun nextOrdinal(stack: DaoCueStack): Double = (entriesOf(stack).maxOfOrNull { it.ordinal } ?: 0.0) + 1.0

/**
 * Write [ordered] — a stack's entries in the order they should play — back as ordinals `1.0, 2.0,
 * …`, touching only the entries whose ordinal changes. Every handler that changes a list builds the
 * new order in memory and hands it here, so a tie never survives a write and the order is exactly
 * the list given.
 *
 * Renormalising rather than taking a midpoint (P6) is session 1's choice: the archive still carries
 * each cue's position as an integer `sortOrder`, so nothing yet benefits from a sparse ordinal. The
 * cue model plan's session 3, which gives entries their own files, is where an insert should start
 * taking the midpoint of its neighbours.
 */
fun writeEntryOrder(ordered: List<DaoCueStackEntry>) {
    ordered.forEachIndexed { index, entry ->
        val ordinal = (index + 1).toDouble()
        if (entry.ordinal != ordinal) entry.ordinal = ordinal
    }
}
