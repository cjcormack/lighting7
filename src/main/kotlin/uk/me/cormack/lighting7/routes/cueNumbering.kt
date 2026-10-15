package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCues

/**
 * Cue-number model shared by the ordering checks, the group-aware sort, and the auto-numbering
 * engine.
 *
 * A cue number is a free-form display label — `sort_order` remains the authoritative playback
 * order. What this file adds is *structure*: a number is read as a group prefix, a dotted
 * decimal run, and an optional letter suffix. Numbers are only ever compared against others in
 * the same group, so a stack holding `Pre-show 1`, `Pre-show 2`, `T2-1`, `S-1`, `S-2` is in
 * order even though the groups themselves aren't alphabetical.
 *
 * The frontend mirrors this model in `src/lib/cueNumber.ts` (lighting-react); keep the two in
 * step — the banner that offers "Fix Order" is driven by the TypeScript copy, and the fix
 * itself by this one.
 */

// ─── Structural parse ──────────────────────────────────────────────────

/** Longest cue number the `cues.cue_number` column will hold. */
internal const val MAX_CUE_NUMBER_LENGTH = 20

/**
 * A cue number split into its ordering components.
 *
 * @property prefix everything before the trailing decimal run — the group key ("S1-", "Pre-show ").
 * @property segments the dotted decimal run, most significant first ("3.1" → [3, 1]).
 * @property suffix trailing letters, if any ("14A" → "A").
 */
internal data class ParsedCueNumber(
    val prefix: String,
    val segments: List<Long>,
    val suffix: String,
)

/**
 * The prefix is lazy but still has to match the whole string, so it settles on the *last*
 * decimal run: "S1-3.1" → ("S1-", [3,1], ""), "T2-1" → ("T2-", [1], ""),
 * "Pre-show 2" → ("Pre-show ", [2], ""), "14A" → ("", [14], "A").
 */
private val CUE_NUMBER_RE = Regex("""^(.*?)(\d+(?:\.\d+)*)([A-Za-z]*)$""")

/** Parse [cueNumber], or null when it holds no decimal run to order by (e.g. "A", "Preshow"). */
internal fun parseCueNumber(cueNumber: String): ParsedCueNumber? {
    val match = CUE_NUMBER_RE.matchEntire(cueNumber) ?: return null
    val (prefix, digits, suffix) = match.destructured
    val segments = digits.split('.').map { it.toLongOrNull() ?: return null }
    return ParsedCueNumber(prefix = prefix, segments = segments, suffix = suffix)
}

/**
 * The group a cue number sorts within. Unparseable numbers get a private key so they form
 * singleton groups and therefore never move — this replaces the old "first character must be a
 * digit" pinning rule, which wrongly excluded every prefixed number like "S1-3".
 */
internal fun cueNumberGroupKey(cueNumber: String): String {
    val parsed = parseCueNumber(cueNumber) ?: return "\u0000unparsed:${cueNumber.lowercase()}"
    return parsed.prefix.lowercase()
}

/**
 * Order two numbers from the same group: decimal run element-wise (so `3` < `3.1` < `3.2` < `4`),
 * shorter run first on a shared stem, then the letter suffix ("14" < "14A" < "14B").
 */
internal fun compareWithinGroup(a: ParsedCueNumber, b: ParsedCueNumber): Int {
    for (i in 0 until minOf(a.segments.size, b.segments.size)) {
        val cmp = a.segments[i].compareTo(b.segments[i])
        if (cmp != 0) return cmp
    }
    val lengthCmp = a.segments.size.compareTo(b.segments.size)
    if (lengthCmp != 0) return lengthCmp
    return a.suffix.lowercase().compareTo(b.suffix.lowercase())
}

// ─── Ordering checks ───────────────────────────────────────────────────

/**
 * True when some group's members do not ascend in the order given. Interleaved groups are fine
 * — only a group descending against itself counts, so `["Pre-show 1", "T2-1", "S-1", "S-2"]`
 * passes while `["S-2", "S-1"]` does not.
 */
internal fun detectCueNumbersOutOfOrder(cueNumbers: List<String?>): Boolean {
    val lastByGroup = HashMap<String, ParsedCueNumber>()
    for (raw in cueNumbers) {
        if (raw.isNullOrEmpty()) continue
        val parsed = parseCueNumber(raw) ?: continue
        val key = parsed.prefix.lowercase()
        val previous = lastByGroup[key]
        if (previous != null && compareWithinGroup(parsed, previous) < 0) return true
        lastByGroup[key] = parsed
    }
    return false
}

/**
 * Sort each group's members among themselves and write them back into the positions that group
 * already occupied. Groups keep their relative placement, and anything without a parseable
 * number (markers, "A", blanks) stays exactly where it is.
 *
 * [entries] must be in current order. Returns the entries in their new order.
 */
internal fun <T> groupAwareCueOrder(entries: List<T>, numberOf: (T) -> String?): List<T> {
    val membersByGroup = LinkedHashMap<String, MutableList<Pair<Int, ParsedCueNumber>>>()
    entries.forEachIndexed { index, entry ->
        val raw = numberOf(entry)
        if (raw.isNullOrEmpty()) return@forEachIndexed
        val parsed = parseCueNumber(raw) ?: return@forEachIndexed
        membersByGroup.getOrPut(parsed.prefix.lowercase()) { mutableListOf() }.add(index to parsed)
    }

    val result = entries.toMutableList()
    for (members in membersByGroup.values) {
        if (members.size < 2) continue
        // `members` was built in index order, so it doubles as the slot list.
        val slots = members.map { it.first }
        val sorted = members.sortedWith { l, r -> compareWithinGroup(l.second, r.second) }
        sorted.forEachIndexed { i, (originalIndex, _) -> result[slots[i]] = entries[originalIndex] }
    }
    return result
}

// ─── Auto numbering ────────────────────────────────────────────────────

/**
 * One cue's current numbering state, as [computeAutoCueNumbers] needs to see it.
 *
 * @property keep this cue is where it was before the change being renumbered for, so an auto
 *   number it already holds stays put (D11). False — the default, and what a pure "derive this
 *   stack from scratch" means — relabels it from its position like any other auto cue.
 */
internal data class AutoNumberInput(val number: String?, val auto: Boolean, val keep: Boolean = false)

private fun AutoNumberInput.isExplicit(): Boolean = !auto && !number.isNullOrEmpty()

/**
 * Derive a display number for every cue that hasn't got an explicit one.
 *
 * [cues] must be the stack's STANDARD cues in `sortOrder`.
 *
 * **An auto number stays put while its cue does** (venue-visit plan D11). A cue flagged [keep] —
 * at the same place in the stack as before the change — keeps the auto number it holds, as long as
 * no explicit number and no earlier kept cue has claimed it. So an explicit renumber, which moves
 * nothing, never relabels its auto neighbours: in five auto cues, cue 2 set to `4.5` reads
 * 1, 4.5, 3, 4, 5, and the stack reports itself out of order until *Fix Order* moves it.
 *
 * Every other auto cue — new, moved, shifted by an insert or a delete, or one whose number an
 * explicit claim took — is labelled from its neighbours. Each maximal run of them is labelled from
 * the nearest preceding *fixed* number (an explicit one, or a kept auto one): normally by bumping
 * its trailing decimal (`S1-3` → `S1-4`, `S1-5`), but by inserting a decimal level beneath it
 * (`S1-3.1`, `S1-3.2`) whenever bumping would land on a number already used or would run past the
 * next fixed cue.
 *
 * **An explicit number that reads out of order against the auto numbers around it neither anchors
 * nor bounds a run** — the run passes through it, counting its place. It is the operator's renumber
 * still waiting for *Fix Order*, and anchoring on it would relabel the cues around it the moment
 * anything else in the stack changed, which is the relabelling D11 forbids made one gesture later.
 *
 * A run with nothing fixed before it counts *up to* the cue that follows — two cues before `S1-3`
 * are `S1-1` and `S1-2`, and a lone cue before `S1-1` is `S1-0`. With nothing either side the run
 * is starting the series, so a fresh stack numbers `1`, `2`, `3`.
 *
 * Returns the label for each auto cue, keyed by its index in [cues] — a kept cue's own number
 * included. An index is absent when no label could be derived that both fits the column and still
 * orders before the following cue — that cue stays blank rather than carrying a number that reads
 * as out of order.
 */
internal fun computeAutoCueNumbers(cues: List<AutoNumberInput>): Map<Int, String> {
    val taken = cues.filter { it.isExplicit() }.mapTo(HashSet()) { it.number!!.lowercase() }
    val labels = HashMap<Int, String>()

    // The kept auto numbers first: they are fixed points the runs are labelled around, and taken
    // like an explicit number, since the unique index sees every number in the stack.
    val fixed = BooleanArray(cues.size)
    cues.forEachIndexed { index, cue ->
        val number = cue.number
        if (cue.isExplicit() || !cue.keep || number.isNullOrEmpty()) return@forEachIndexed
        if (number.length > MAX_CUE_NUMBER_LENGTH || !taken.add(number.lowercase())) return@forEachIndexed
        fixed[index] = true
        labels[index] = number
    }
    cues.forEachIndexed { index, cue ->
        if (cue.isExplicit() && !readsOutOfPlace(cues, index)) fixed[index] = true
    }

    var index = 0
    while (index < cues.size) {
        if (fixed[index]) {
            index++
            continue
        }
        var end = index
        while (end < cues.size && !fixed[end]) end++

        // A run made only of out-of-place explicit numbers has nothing to label.
        if ((index until end).any { !cues[it].isExplicit() }) {
            val previous = (index - 1 downTo 0).firstOrNull { fixed[it] }?.let { cues[it].number }
            val next = (end until cues.size).firstOrNull { fixed[it] }?.let { cues[it].number }

            // Anchor the run on the preceding fixed number. With none — or one that has no decimal
            // run to bump, like "intro" — borrow the following cue's prefix so the labels still
            // read as part of the same series.
            val anchor = previous?.let { parseCueNumber(it) }
            val prefix = anchor?.prefix
                ?: next?.let { parseCueNumber(it) }?.prefix
                ?: ""

            runLabels(anchor, prefix, next, end - index, taken)
                .forEachIndexed { offset, label ->
                    // An out-of-place explicit cue takes its step in the run and keeps its number.
                    if (!cues[index + offset].isExplicit()) labels[index + offset] = label
                }
        }

        index = end
    }
    return labels
}

/**
 * Whether the explicit number at [index] descends against the auto numbers beside it in its own
 * group — the nearest one before it is not below it, or the nearest one after it is not above it.
 * Only *auto* neighbours are read: two explicit numbers out of order are the operator's to fix and
 * anchor as they always did, where an explicit number out of order against the auto sequence is a
 * renumber waiting for *Fix Order* (D11).
 */
private fun readsOutOfPlace(cues: List<AutoNumberInput>, index: Int): Boolean {
    val parsed = parseCueNumber(cues[index].number ?: return false) ?: return false
    val group = parsed.prefix.lowercase()
    fun autoNeighbour(range: IntProgression): ParsedCueNumber? = range.asSequence()
        .filter { !cues[it].isExplicit() }
        .mapNotNull { cues[it].number?.let(::parseCueNumber) }
        .firstOrNull { it.prefix.lowercase() == group }
    val before = autoNeighbour(index - 1 downTo 0)
    val after = autoNeighbour(index + 1 until cues.size)
    return (before != null && compareWithinGroup(before, parsed) >= 0) ||
        (after != null && compareWithinGroup(after, parsed) <= 0)
}

/**
 * Labels for one run of [count] auto cues in group [prefix], following [anchor] (null when nothing
 * explicit precedes the run) and preceding [next]. Falls back to decimal-inserting beneath the run's
 * base whenever the preferred integers are already [taken], too long for the column, or would order
 * at/after [next] within the same group.
 */
private fun runLabels(
    anchor: ParsedCueNumber?,
    prefix: String,
    next: String?,
    count: Int,
    taken: Set<String>,
): List<String> {
    // Only a `next` in this run's own group constrains us; a different group sorts independently,
    // so running past it is meaningless.
    val boundary = next
        ?.let { parseCueNumber(it) }
        ?.takeIf { it.prefix.equals(prefix, ignoreCase = true) }

    fun usable(labels: List<String>): Boolean =
        labels.all { it.lowercase() !in taken && it.length <= MAX_CUE_NUMBER_LENGTH } &&
            (boundary == null || compareWithinGroup(parseCueNumber(labels.last())!!, boundary) < 0)

    // Decimal-insert fallback: a level beneath [base]. Usually this lands neatly between the run's
    // surroundings, but it can't when the follower is itself a decimal under the same stem —
    // between "S1-3" and "S1-3.1" there is nothing this scheme can express, since "S1-3.1" is taken
    // and "S1-3.2" would sort *after* the follower. Rather than hand out a number that reads as out
    // of order (and trip the "cue numbers are out of order" banner on a stack the operator never
    // broke), leave those cues blank for the operator to number themselves.
    fun beneath(base: String): List<String> {
        val labels = decimalLabels(base, count, taken)
        return if (labels.isEmpty() || usable(labels)) labels else emptyList()
    }

    if (anchor != null) {
        // Increment mode — bump the anchor's trailing segment. Any letter suffix on the anchor is
        // dropped: the successor to "14A" is "15", not "14B".
        val incremental = (1..count).map { step ->
            val segments = anchor.segments.toMutableList()
            segments[segments.lastIndex] = segments.last() + step
            prefix + segments.joinToString(".")
        }
        if (usable(incremental)) return incremental
        return beneath(prefix + anchor.segments.joinToString("."))
    }

    // Nothing explicit precedes the run. With nothing following either we're starting the series,
    // so count from 1.
    if (boundary == null) {
        val fromOne = (1..count).map { "$prefix$it" }
        if (usable(fromOne)) return fromOne
        return beneath("${prefix}0")
    }

    // Otherwise we're slotting in beneath an existing series, so count *up to* the cue that
    // follows: two cues before "S1-3" are "S1-1" and "S1-2", and a lone cue before "S1-1" is
    // "S1-0" — not a decimal insert, since 0 itself is free.
    //
    // Highest integer still ordering below the boundary: "S1-3" leaves "S1-2", but a boundary
    // that is itself a decimal insert ("S1-3.2") leaves the whole "S1-3" above it usable.
    val highest = if (boundary.segments.size > 1) boundary.segments[0] else boundary.segments[0] - 1
    // Highest block of integers first; a lower one when a number in it is already taken — a lone
    // cue before "2" whose "1" an explicit renumber claimed reads "0" (D11's third case), not "0.1".
    for (first in (highest - count + 1) downTo 0) {
        val upToBoundary = (first until first + count).map { "$prefix$it" }
        if (usable(upToBoundary)) return upToBoundary
    }
    return beneath("${prefix}0")
}

/** `base.1`, `base.2`, … skipping anything [taken] and stopping when the column can't hold more. */
private fun decimalLabels(base: String, count: Int, taken: Set<String>): List<String> {
    val out = ArrayList<String>(count)
    var suffix = 1L
    while (out.size < count) {
        val label = "$base.$suffix"
        suffix++
        // Labels only ever get longer from here, so a length failure ends the run.
        if (label.length > MAX_CUE_NUMBER_LENGTH) break
        if (label.lowercase() in taken) continue
        out.add(label)
    }
    return out
}

// ─── Persistence ───────────────────────────────────────────────────────

private fun DaoCue.hasExplicitNumber(): Boolean = !cueNumberAuto && !cueNumber.isNullOrEmpty()

/**
 * Push queued entity writes to the database. Needed twice over in this file: so a following
 * `ORDER BY sort_order` sees sort orders written moments earlier, and so the two-pass renumber's
 * clears land before its assignments (otherwise `uq_cue_number_per_stack` sees both at once).
 */
private fun flushPendingCueWrites() {
    TransactionManager.current().entityCache.flush(listOf(DaoCues))
}

/**
 * Release [number] from any *auto*-numbered sibling in [stack] (other than [exceptCueId]) so an
 * operator typing an explicit number can always claim it.
 *
 * Without this, typing "2" onto the third of three auto-numbered cues would collide with the
 * auto "2" the position-derived scheme had already handed to its neighbour, and
 * `uq_cue_number_per_stack` would reject the edit. [renumberAutoCues] relabels the loser
 * afterwards. Explicit numbers are never released — a genuine duplicate still gets its 409.
 */
internal fun releaseAutoNumber(stack: DaoCueStack, number: String?, exceptCueId: Int) {
    if (number.isNullOrEmpty()) return
    val clashes = DaoCue.find { DaoCues.cueStack eq stack.id }.filter {
        it.id.value != exceptCueId && it.cueNumberAuto && number.equals(it.cueNumber, ignoreCase = true)
    }
    if (clashes.isEmpty()) return
    clashes.forEach { it.cueNumber = null }
    flushPendingCueWrites()
}

/**
 * [stack]'s STANDARD cues in playback order. `id` breaks a `sort_order` tie, so two reads of an
 * unchanged stack always agree — which [renumberAutoCues]'s comparison against a [standardCueIds]
 * snapshot depends on.
 */
private fun standardCuesInOrder(stack: DaoCueStack): List<DaoCue> =
    DaoCue.find { DaoCues.cueStack eq stack.id }
        .orderBy(DaoCues.sortOrder to SortOrder.ASC, DaoCues.id to SortOrder.ASC)
        .filter { it.cueType == CueType.STANDARD.name }

/**
 * The ids of [stack]'s STANDARD cues in playback order: the snapshot a handler takes **before**
 * it changes the stack's list, to hand [renumberAutoCues] afterwards.
 */
internal fun standardCueIds(stack: DaoCueStack): List<Int> {
    flushPendingCueWrites()
    return standardCuesInOrder(stack).map { it.id.value }
}

/**
 * Recompute [stack]'s auto cue numbers in place. Explicit numbers are never touched. Call this
 * from anything that changes the stack's membership, order or numbers.
 *
 * [before] is [standardCueIds] as it stood before the change (venue-visit plan D11). A cue still
 * at the same place in it keeps the auto number it holds; every other auto cue is labelled again
 * from its neighbours, so the cues after an insert, a delete or a move follow their new positions
 * while the ones before it stay put. A handler that moves nothing — a number edit — passes the
 * order as it stands, so no auto cue moves but the one whose number was taken or cleared. An empty
 * list relabels every auto cue from scratch.
 *
 * Writes in two passes with a flush between them: `uq_cue_number_per_stack` would otherwise
 * reject the transient duplicate you get when two auto numbers swap places.
 */
internal fun renumberAutoCues(stack: DaoCueStack, before: List<Int>) {
    // Callers have almost always just written sort orders; the ORDER BY below has to see them.
    flushPendingCueWrites()

    val standard = standardCuesInOrder(stack)
    if (standard.isEmpty()) return

    val labels = computeAutoCueNumbers(
        standard.mapIndexed { index, cue ->
            AutoNumberInput(cue.cueNumber, cue.cueNumberAuto, keep = before.getOrNull(index) == cue.id.value)
        },
    )
    val targets = standard.withIndex().filterNot { (_, cue) -> cue.hasExplicitNumber() }
    if (targets.none { (index, cue) -> cue.cueNumber != labels[index] }) return

    // Pass 1: release every number that is about to change, so pass 2 can reuse any of them.
    for ((index, cue) in targets) {
        if (cue.cueNumber != labels[index]) cue.cueNumber = null
    }
    flushPendingCueWrites()

    // Pass 2: assign.
    for ((index, cue) in targets) {
        val label = labels[index]
        cue.cueNumber = label
        cue.cueNumberAuto = label != null
    }
}
