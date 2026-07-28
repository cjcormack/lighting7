package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.TransactionManager
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

/** One cue's current numbering state, as [computeAutoCueNumbers] needs to see it. */
internal data class AutoNumberInput(val number: String?, val auto: Boolean)

private fun AutoNumberInput.isExplicit(): Boolean = !auto && !number.isNullOrEmpty()

/**
 * Derive a display number for every cue that hasn't got an explicit one.
 *
 * [cues] must be the stack's STANDARD cues in `sortOrder`. Each maximal run of auto /
 * unnumbered cues is labelled from the nearest preceding explicit number: normally by bumping
 * its trailing decimal (`S1-3` → `S1-4`, `S1-5`), but by inserting a decimal level beneath it
 * (`S1-3.1`, `S1-3.2`) whenever bumping would land on a number the operator has already used
 * elsewhere or would run past the next explicit cue.
 *
 * A run with nothing explicit before it counts *up to* the cue that follows — two cues before
 * `S1-3` are `S1-1` and `S1-2`, and a lone cue before `S1-1` is `S1-0`. With nothing either side
 * the run is starting the series, so a fresh stack numbers `1`, `2`, `3`.
 *
 * Returns the label for each auto cue, keyed by its index in [cues]. An index is absent when no
 * label could be derived that both fits the column and still orders before the following cue —
 * that cue stays blank rather than carrying a number that reads as out of order.
 */
internal fun computeAutoCueNumbers(cues: List<AutoNumberInput>): Map<Int, String> {
    val taken = cues.filter { it.isExplicit() }.mapTo(HashSet()) { it.number!!.lowercase() }
    val labels = HashMap<Int, String>()

    var index = 0
    while (index < cues.size) {
        if (cues[index].isExplicit()) {
            index++
            continue
        }
        var end = index
        while (end < cues.size && !cues[end].isExplicit()) end++

        val previous = cues.subList(0, index).lastOrNull { it.isExplicit() }?.number
        val next = cues.subList(end, cues.size).firstOrNull { it.isExplicit() }?.number

        // Anchor the run on the preceding explicit number. With none — or one that has no decimal
        // run to bump, like "intro" — borrow the following cue's prefix so the labels still read
        // as part of the same series.
        val anchor = previous?.let { parseCueNumber(it) }
        val prefix = anchor?.prefix
            ?: next?.let { parseCueNumber(it) }?.prefix
            ?: ""

        runLabels(anchor, prefix, next, end - index, taken)
            .forEachIndexed { offset, label -> labels[index + offset] = label }

        index = end
    }
    return labels
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
    val first = highest - count + 1
    if (first >= 0) {
        val upToBoundary = (first..highest).map { "$prefix$it" }
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
 * Recompute [stack]'s auto cue numbers in place. Explicit numbers are never touched. Call this
 * from anything that changes the stack's membership or order — the labels are derived from
 * position, so they have to move when positions do.
 *
 * Writes in two passes with a flush between them: `uq_cue_number_per_stack` would otherwise
 * reject the transient duplicate you get when two auto numbers swap places.
 */
internal fun renumberAutoCues(stack: DaoCueStack) {
    // Callers have almost always just written sort orders; the ORDER BY below has to see them.
    flushPendingCueWrites()

    val standard = DaoCue.find { DaoCues.cueStack eq stack.id }
        .orderBy(DaoCues.sortOrder to SortOrder.ASC)
        .filter { it.cueType == CueType.STANDARD.name }
    if (standard.isEmpty()) return

    val labels = computeAutoCueNumbers(
        standard.map { AutoNumberInput(it.cueNumber, it.cueNumberAuto) },
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
