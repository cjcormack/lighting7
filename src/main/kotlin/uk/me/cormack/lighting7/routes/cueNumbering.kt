package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntries
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.entriesOf
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Cue-number model shared by the ordering checks, the sort, and the auto-numbering engine.
 *
 * A cue number is a number — `0` to `9999.9999`, at most four decimal places — sorted by value, so
 * `2.1` and `2.10` are one number, `2.15` sits between `2.1` and `2.2`, and `10` comes after `9`. It
 * is stored in one canonical spelling ([canonicalCueNumber]: `2.10` is `2.1`, `007` is `7`), which is
 * what lets `uq_entry_number_per_stack` refuse two spellings of one number. An entry's `ordinal`
 * remains the authoritative playback order; the number is what the operator reads and types.
 *
 * The frontend mirrors this model in `frontend/src/lib/cueNumber.ts`; keep the two in step.
 */

// ─── Parse ─────────────────────────────────────────────────────────────

/** Most decimal places a cue number may have. */
internal const val CUE_NUMBER_DECIMALS = 4

/** The highest cue number. */
internal val MAX_CUE_NUMBER: BigDecimal = BigDecimal("9999.9999")

/** The lowest number above every cue number: [MAX_CUE_NUMBER] plus one step of its last place. */
private val CUE_NUMBER_CEILING: BigDecimal = MAX_CUE_NUMBER.add(BigDecimal.ONE.movePointLeft(CUE_NUMBER_DECIMALS))

/** What a refused cue number is told, everywhere one is typed. */
internal const val CUE_NUMBER_RULE = "A cue number is a number from 0 to 9999.9999, with at most 4 decimal places"

/** Digits with an optional fraction; a leading `Q` (as the desk displays a number) is allowed. */
private val CUE_NUMBER_RE = Regex("""^[Qq]?(\d*)(?:\.(\d*))?$""")

/**
 * [raw]'s value, or null when it is not a cue number. Trailing zeros of the fraction do not count
 * towards its four places, so `2.10000` is `2.1`.
 */
internal fun cueNumberValue(raw: String): BigDecimal? {
    val match = CUE_NUMBER_RE.matchEntire(raw.trim()) ?: return null
    val (whole, fraction) = match.destructured
    if (whole.isEmpty() && fraction.isEmpty()) return null
    val value = BigDecimal("${whole.ifEmpty { "0" }}.${fraction.ifEmpty { "0" }}").stripTrailingZeros()
    if (value.scale() > CUE_NUMBER_DECIMALS || value > MAX_CUE_NUMBER) return null
    return value
}

/** [value] in the one spelling the desk stores: no leading zeros, no trailing fraction zeros. */
internal fun formatCueNumber(value: BigDecimal): String {
    val stripped = value.stripTrailingZeros()
    return (if (stripped.scale() < 0) stripped.setScale(0) else stripped).toPlainString()
}

/** [raw] in its canonical spelling, or null when it is not a cue number. */
internal fun canonicalCueNumber(raw: String): String? = cueNumberValue(raw)?.let(::formatCueNumber)

/**
 * A typed number for a write boundary: blank is null (the entry is auto-numbered), a cue number its
 * canonical spelling, and anything else a failure carrying [CUE_NUMBER_RULE].
 */
internal fun parseTypedCueNumber(raw: String?): Result<String?> {
    val trimmed = raw?.trim()
    if (trimmed.isNullOrEmpty()) return Result.success(null)
    return canonicalCueNumber(trimmed)?.let { Result.success(it) }
        ?: Result.failure(IllegalArgumentException("$CUE_NUMBER_RULE — '$trimmed' is not one"))
}

// ─── Ordering checks ───────────────────────────────────────────────────

/** True when some numbered cue reads lower than one before it. Blanks and non-numbers are ignored. */
internal fun detectCueNumbersOutOfOrder(cueNumbers: List<String?>): Boolean {
    var last: BigDecimal? = null
    for (raw in cueNumbers) {
        val value = raw?.let(::cueNumberValue) ?: continue
        if (last != null && value < last) return true
        last = value
    }
    return false
}

/**
 * Sort the numbered entries among the places numbered entries already occupy. Anything without a
 * number (markers, blanks) stays exactly where it is. Ties keep their order.
 *
 * [entries] must be in current order. Returns the entries in their new order.
 */
internal fun <T> numericCueOrder(entries: List<T>, numberOf: (T) -> String?): List<T> {
    val numbered = entries.withIndex().mapNotNull { (index, entry) ->
        numberOf(entry)?.let(::cueNumberValue)?.let { index to it }
    }
    val result = entries.toMutableList()
    val slots = numbered.map { it.first }
    numbered.sortedBy { it.second }.forEachIndexed { i, (originalIndex, _) -> result[slots[i]] = entries[originalIndex] }
    return result
}

/**
 * Where a cue numbered [value] belongs in [entries] (in order, not including the cue itself): just
 * after the last entry numbered below it, else just before the first numbered entry. Null when no
 * entry is numbered, so there is nothing to place it by.
 */
internal fun <T> positionForNumber(entries: List<T>, value: BigDecimal, numberOf: (T) -> String?): Int? {
    val numbered = entries.withIndex().mapNotNull { (index, entry) -> numberOf(entry)?.let(::cueNumberValue)?.let { index to it } }
    if (numbered.isEmpty()) return null
    return numbered.lastOrNull { it.second < value }?.let { it.first + 1 } ?: numbered.first().first
}

/** The numbered neighbours a number reads out of order against, by their numbers. */
internal data class OutOfOrderNeighbours(val previous: String?, val next: String?)

/**
 * Whether [value], at [index] of [cueNumbers] (the stack's cue entries in order, its own place
 * included and ignored), reads out of order against the nearest numbered cue either side — the one
 * before not below it, or the one after not above it. Null when it reads in order; otherwise the
 * neighbours it reads out of order against. [index] may be [cueNumbers]' size, for a cue appended.
 */
internal fun outOfOrderAt(cueNumbers: List<String?>, index: Int, value: BigDecimal): OutOfOrderNeighbours? {
    fun nearest(range: IntProgression): String? = range.asSequence()
        .mapNotNull { i -> cueNumbers.getOrNull(i)?.takeIf { cueNumberValue(it) != null } }
        .firstOrNull()
    val previous = nearest(index - 1 downTo 0)
    val next = nearest(index + 1 until cueNumbers.size)
    val before = previous?.let(::cueNumberValue)
    val after = next?.let(::cueNumberValue)
    val previousWrong = before != null && before >= value
    val nextWrong = after != null && after <= value
    if (!previousWrong && !nextWrong) return null
    return OutOfOrderNeighbours(previous = previous.takeIf { previousWrong }, next = next.takeIf { nextWrong })
}

// ─── Auto numbering ────────────────────────────────────────────────────

/**
 * Derive a display number for every cue that hasn't got a typed one, from position and the typed
 * numbers alone. [typed] is the stack's cue entries in playback order, each its typed number or null.
 *
 * A typed number is **in place** when it is above the previous one in place; the first is in place
 * when the cues before it fit the free whole numbers below it, from 1 or else from 0. Each run of
 * other cues takes the free whole numbers after the typed number in place before it and below the
 * one after, and only the cues those do not cover go as evenly spaced decimals, in the fewest places
 * that fit. A leading run counts from 1 where that fits, else takes the highest free whole numbers
 * below the cue that follows. A typed number out of place takes a step of its run but anchors
 * nothing: 38 cues before a typed `30` read 1–29 and 31–39, and a cue after it 41.
 *
 * Returns the label for each auto cue by its index in [typed]; an index is absent when no number fits
 * in four places, so that cue stays blank.
 */
internal fun computeAutoCueNumbers(typed: List<String?>): Map<Int, String> {
    val explicit = typed.map { !it.isNullOrEmpty() }
    val taken = typed.filterNotNull().filter { it.isNotEmpty() }.mapTo(HashSet()) { canonicalCueNumber(it) ?: it.lowercase() }
    val inPlace = BooleanArray(typed.size)
    var leadsFromOne = true
    var last: BigDecimal? = null
    typed.forEachIndexed { index, number ->
        val value = number?.takeIf { it.isNotEmpty() }?.let(::cueNumberValue) ?: return@forEachIndexed
        if (last != null) {
            inPlace[index] = value > last
        } else {
            val fromOne = fitsBelow(value, from = 1, count = index, taken)
            inPlace[index] = fromOne || fitsBelow(value, from = 0, count = index, taken)
            leadsFromOne = fromOne
        }
        if (inPlace[index]) last = value
    }

    val labels = HashMap<Int, String>()
    var index = 0
    while (index < typed.size) {
        if (inPlace[index]) {
            index++
            continue
        }
        var end = index
        while (end < typed.size && !inPlace[end]) end++

        // A run made only of typed numbers out of place has nothing to label.
        if ((index until end).any { !explicit[it] }) {
            val anchor = (index - 1 downTo 0).firstOrNull { inPlace[it] }?.let { typed[it] }?.let(::cueNumberValue)
            val boundary = (end until typed.size).firstOrNull { inPlace[it] }?.let { typed[it] }?.let(::cueNumberValue)
            runLabels(anchor, boundary, end - index, taken, countUp = anchor != null || leadsFromOne).forEachIndexed { offset, label ->
                if (!explicit[index + offset]) labels[index + offset] = label
            }
        }

        index = end
    }
    return labels
}

/** Whether [count] free whole numbers lie between [from] and [value] (exclusive). */
private fun fitsBelow(value: BigDecimal, from: Long, count: Int, taken: Set<String>): Boolean =
    freeWholes(from, value, taken).take(count).count() == count

/**
 * The whole numbers below [limit] that [taken] does not hold, from [from] upwards, or with [down]
 * from [from] down to 0.
 */
private fun freeWholes(from: Long, limit: BigDecimal, taken: Set<String>, down: Boolean = false): Sequence<Long> =
    generateSequence(from) { if (down) it - 1 else it + 1 }
        .takeWhile { if (down) it >= 0 else BigDecimal.valueOf(it) < limit }
        .filter { BigDecimal.valueOf(it) < limit && it.toString() !in taken }

/**
 * Labels for one run of [count] cues following [anchor] (null when no typed number in place precedes
 * the run) and preceding [boundary] (null when none follows it): whole numbers when enough are free
 * between the two, else [decimalLabels] between them, else none. With no [anchor], [countUp] counts
 * from 1; otherwise the run takes the highest free whole numbers below [boundary].
 */
private fun runLabels(anchor: BigDecimal?, boundary: BigDecimal?, count: Int, taken: Set<String>, countUp: Boolean): List<String> {
    // Exclusive: every number below it, to four places, is a cue number.
    val limit = (boundary ?: CUE_NUMBER_CEILING).min(CUE_NUMBER_CEILING)

    val wholes: List<Long> = when {
        // After the anchor, or from 1 when the run starts the series.
        countUp || boundary == null ->
            freeWholes(anchor?.let { it.setScale(0, RoundingMode.FLOOR).toLong() + 1 } ?: 1L, limit, taken).take(count).toList()
        // Slotting in beneath a series: the highest free whole numbers below the cue that follows.
        else -> freeWholes(boundary.setScale(0, RoundingMode.CEILING).toLong() - 1, limit, taken, down = true)
            .take(count).toList().reversed()
    }
    if (wholes.size == count) return wholes.map(Long::toString)
    // Too few whole numbers: the ones there are, and the rest between the last of them and the next
    // typed number — else the whole run between its two typed numbers.
    val low = anchor ?: BigDecimal.ZERO
    if (wholes.isNotEmpty()) {
        val rest = decimalLabels(BigDecimal.valueOf(wholes.last()), limit, count - wholes.size, taken)
        if (rest.isNotEmpty()) return wholes.map(Long::toString) + rest
    }
    return decimalLabels(low, limit, count, taken)
}

/**
 * [count] numbers strictly between [low] and [high], evenly spaced, in the fewest decimal places
 * that hold the spacing exactly (one cue between 3 and 3.5 is 3.25), else the fewest that keep them
 * distinct, ascending and free (two between 3 and 4 are 3.3 and 3.7). Empty when four places cannot.
 */
private fun decimalLabels(low: BigDecimal, high: BigDecimal, count: Int, taken: Set<String>): List<String> {
    if (high <= low) return emptyList()
    val step = high.subtract(low).divide(BigDecimal(count + 1), MathContext.DECIMAL64)
    val ideal = (1..count).map { low.add(step.multiply(BigDecimal(it))) }
    for (exact in listOf(true, false)) {
        for (places in 1..CUE_NUMBER_DECIMALS) {
            val rounded = ideal.map { it.setScale(places, RoundingMode.HALF_UP) }
            if (exact && rounded.indices.any { rounded[it].compareTo(ideal[it]) != 0 }) continue
            val usable = rounded.first() > low && rounded.last() < high &&
                rounded.zipWithNext().all { (a, b) -> a < b } &&
                rounded.none { formatCueNumber(it) in taken }
            if (usable) return rounded.map(::formatCueNumber)
        }
    }
    return emptyList()
}

// ─── Persistence ───────────────────────────────────────────────────────

/** The number the operator typed for this entry, or null when it is auto-numbered or blank. */
internal val DaoCueStackEntry.typedNumber: String?
    get() = cueNumber.takeIf { !cueNumberAuto && !it.isNullOrEmpty() }

/**
 * Push queued entity writes to the database. Needed twice over in this file: so a following
 * `ORDER BY ordinal` sees ordinals written moments earlier, and so the two-pass renumber's clears
 * land before its assignments (otherwise `uq_entry_number_per_stack` sees both at once).
 */
internal fun flushPendingEntryWrites() {
    TransactionManager.current().entityCache.flush(listOf(DaoCueStackEntries))
}

/**
 * Release [number] from any *auto*-numbered cue entry in [stack] (other than [exceptEntryId]) so an
 * operator typing an explicit number can always claim it.
 *
 * Without this, typing "2" onto the third of three auto-numbered cues would collide with the
 * auto "2" the position-derived scheme had already handed to its neighbour, and
 * `uq_entry_number_per_stack` would reject the edit. [renumberAutoEntries] relabels the loser
 * afterwards. Explicit numbers are never released — a genuine duplicate still gets its 409.
 */
internal fun releaseAutoNumber(stack: DaoCueStack, number: String?, exceptEntryId: Int) {
    if (number.isNullOrEmpty()) return
    val clashes = cueEntriesInOrder(stack).filter {
        it.id.value != exceptEntryId && it.cueNumberAuto && number.equals(it.cueNumber, ignoreCase = true)
    }
    if (clashes.isEmpty()) return
    clashes.forEach { it.cueNumber = null }
    flushPendingEntryWrites()
}

/**
 * [stack]'s **cue** entries in playback order ([ENTRY_ORDER]), markers left out. The uuid breaks an
 * `ordinal` tie, so two reads of an unchanged stack always agree, and so do two desks.
 */
internal fun cueEntriesInOrder(stack: DaoCueStack): List<DaoCueStackEntry> = entriesOf(stack).filter { !it.isMarker }

/**
 * Recompute [stack]'s auto cue numbers in place, on its entries, from position and the typed numbers
 * ([computeAutoCueNumbers]). Typed numbers are never touched. Call this from anything that changes
 * the stack's membership, order or numbers — and, where an entry leaves a stack for another, on the
 * stack it left as well as the one it joined.
 *
 * Writes in two passes with a flush between them: `uq_entry_number_per_stack` would otherwise
 * reject the transient duplicate you get when two auto numbers swap places.
 */
internal fun renumberAutoEntries(stack: DaoCueStack) {
    // Callers have almost always just written ordinals; the ORDER BY below has to see them.
    flushPendingEntryWrites()

    val changed = autoNumberChanges(stack)
    if (changed.isEmpty()) return

    // Pass 1: release every number that is about to change, so pass 2 can reuse any of them.
    for ((entry, _) in changed) {
        entry.cueNumber = null
    }
    flushPendingEntryWrites()

    // Pass 2: assign.
    for ((entry, label) in changed) {
        entry.cueNumber = label
        entry.cueNumberAuto = label != null
    }
}

/** Each of [stack]'s auto entries whose number [renumberAutoEntries] would change, with its new one (null a blank). */
internal fun autoNumberChanges(stack: DaoCueStack): List<Pair<DaoCueStackEntry, String?>> {
    val entries = cueEntriesInOrder(stack)
    val labels = computeAutoCueNumbers(entries.map { it.typedNumber })
    return entries.withIndex()
        .filter { (index, entry) -> entry.typedNumber == null && entry.cueNumber != labels[index] }
        .map { (index, entry) -> entry to labels[index] }
}
