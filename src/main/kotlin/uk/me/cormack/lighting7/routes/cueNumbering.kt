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
 * [cues] must be the stack's cue entries in playback order.
 *
 * **An auto number stays put while its cue does** (venue-visit plan D11). A cue flagged [keep] —
 * at the same place in the stack as before the change — keeps the auto number it holds, as long as
 * no explicit number and no earlier kept cue has claimed it. So an explicit renumber, which moves
 * nothing, never relabels its auto neighbours: in five auto cues, cue 2 set to `4.5` reads
 * 1, 4.5, 3, 4, 5, and the stack reports itself out of order until *Fix Order* moves it.
 *
 * Every other auto cue — new, moved, shifted by an insert or a delete, or one whose number an
 * explicit claim took — is labelled from its neighbours. Each maximal run of them is labelled from
 * the nearest preceding *fixed* number (an explicit one, or a kept auto one) with the **whole
 * numbers** after it that are free and below the next fixed number: `3` → `4`, `5`. Only the cues
 * there are not enough of those for go as decimals, after the last whole number and before the next
 * fixed one, evenly spaced in the fewest places that fit — one cue between `3` and `4` is `3.5`, one
 * between `3` and `3.5` is `3.25`, two between `3` and `4` are `3.3` and `3.7`, and three between `1`
 * and `4` are `2`, `3` and `3.5` — as a point cue is on other desks.
 *
 * **An explicit number that reads out of order against the auto numbers around it neither anchors
 * nor bounds a run** — the run passes through it, counting its place. It is the operator's renumber
 * still waiting for *Fix Order*, and anchoring on it would relabel the cues around it the moment
 * anything else in the stack changed, which is the relabelling D11 forbids made one gesture later.
 *
 * A run with nothing fixed before it takes the highest free whole numbers below the cue that
 * follows — two cues before `3` are `1` and `2`, and a lone cue before `1` is `0`. With nothing
 * either side the run is starting the series, so a fresh stack numbers `1`, `2`, `3`.
 *
 * Returns the label for each auto cue, keyed by its index in [cues] — a kept cue's own number
 * included. An index is absent when no number fits between its neighbours in four places: that cue
 * stays blank rather than carrying a number that reads as out of order.
 */
internal fun computeAutoCueNumbers(cues: List<AutoNumberInput>): Map<Int, String> {
    val taken = cues.filter { it.isExplicit() }.mapTo(HashSet()) { canonicalCueNumber(it.number!!) ?: it.number.lowercase() }
    val labels = HashMap<Int, String>()

    // The kept auto numbers first: they are fixed points the runs are labelled around, and taken
    // like an explicit number, since the unique index sees every number in the stack.
    val fixed = BooleanArray(cues.size)
    cues.forEachIndexed { index, cue ->
        val number = cue.number
        if (cue.isExplicit() || !cue.keep || number.isNullOrEmpty()) return@forEachIndexed
        val canonical = canonicalCueNumber(number) ?: return@forEachIndexed
        if (canonical != number || !taken.add(canonical)) return@forEachIndexed
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
            val anchor = (index - 1 downTo 0).firstOrNull { fixed[it] }?.let { cues[it].number }?.let(::cueNumberValue)
            val boundary = (end until cues.size).firstOrNull { fixed[it] }?.let { cues[it].number }?.let(::cueNumberValue)
            runLabels(anchor, boundary, end - index, taken).forEachIndexed { offset, label ->
                // An out-of-place explicit cue takes its step in the run and keeps its number.
                if (!cues[index + offset].isExplicit()) labels[index + offset] = label
            }
        }

        index = end
    }
    return labels
}

/**
 * Whether the explicit number at [index] descends against the auto numbers beside it — the nearest
 * one before it is not below it, or the nearest one after it is not above it. Only *auto* neighbours
 * are read: two explicit numbers out of order are the operator's to fix and anchor as they always
 * did, where an explicit number out of order against the auto sequence is a renumber waiting for
 * *Fix Order* (D11).
 */
private fun readsOutOfPlace(cues: List<AutoNumberInput>, index: Int): Boolean {
    val value = cueNumberValue(cues[index].number ?: return false) ?: return false
    fun autoNeighbour(range: IntProgression): BigDecimal? = range.asSequence()
        .filter { !cues[it].isExplicit() }
        .mapNotNull { cues[it].number?.let(::cueNumberValue) }
        .firstOrNull()
    val before = autoNeighbour(index - 1 downTo 0)
    val after = autoNeighbour(index + 1 until cues.size)
    return (before != null && before >= value) || (after != null && after <= value)
}

/**
 * Labels for one run of [count] auto cues following [anchor] (null when nothing fixed precedes the
 * run) and preceding [boundary] (null when nothing fixed follows it): whole numbers when enough are
 * free between the two, else [decimalLabels] between them, else none.
 */
private fun runLabels(anchor: BigDecimal?, boundary: BigDecimal?, count: Int, taken: Set<String>): List<String> {
    // Exclusive: every number below it, to four places, is a cue number.
    val limit = (boundary ?: CUE_NUMBER_CEILING).min(CUE_NUMBER_CEILING)
    fun free(whole: Long): Boolean = BigDecimal.valueOf(whole) < limit && whole.toString() !in taken

    val wholes: List<Long> = when {
        // After the anchor, or from 1 when the run starts the series.
        anchor != null || boundary == null ->
            generateSequence(anchor?.let { it.setScale(0, RoundingMode.FLOOR).toLong() + 1 } ?: 1L) { it + 1 }
                .takeWhile { BigDecimal.valueOf(it) < limit }
                .filter(::free)
                .take(count)
                .toList()
        // Slotting in beneath a series: the highest free whole numbers below the cue that follows.
        else -> generateSequence(boundary.setScale(0, RoundingMode.CEILING).toLong() - 1) { it - 1 }
            .takeWhile { it >= 0 }
            .filter(::free)
            .take(count)
            .toList()
            .reversed()
    }
    if (wholes.size == count) return wholes.map(Long::toString)
    // Too few whole numbers: the ones there are, and the rest between the last of them and the next
    // fixed number — else the whole run between its two fixed numbers.
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

private fun DaoCueStackEntry.hasExplicitNumber(): Boolean = !cueNumberAuto && !cueNumber.isNullOrEmpty()

/**
 * Push queued entity writes to the database. Needed twice over in this file: so a following
 * `ORDER BY ordinal` sees ordinals written moments earlier, and so the two-pass renumber's clears
 * land before its assignments (otherwise `uq_entry_number_per_stack` sees both at once).
 */
internal fun flushPendingEntryWrites() {
    TransactionManager.current().entityCache.flush(listOf(DaoCueStackEntries))
}

/**
 * The auto numbers in [stack] a reader could not derive from position, by entry id: what the archive
 * writes beside the explicit ones (cue model plan P5, as amended 2026-10-16).
 *
 * A reader derives every number the archive leaves out from scratch — [renumberAutoEntries] with no
 * `before` — so an auto number venue-visit D11 held in place would move on the next pull or clone:
 * an explicit renumber still waiting for *Fix Order* holds its neighbours (1, 4.5, 3, 4, 5), where
 * a reader with no numbers would anchor on the 4.5 (4, 4.5, 4.6, 4.7, 4.8). Each such number is
 * written, and so read back as explicit, so every desk shows the labels this one does. Writing one
 * can change what the reader derives for the rest, so the set grows until that derivation gives
 * every auto number left out. A stack nothing holds writes none; a blank auto number (no label
 * fitted) is never written, since the archive cannot say blank.
 */
internal fun heldAutoNumbers(stack: DaoCueStack): Map<Int, String> {
    val entries = cueEntriesInOrder(stack)
    val held = HashMap<Int, String>()
    while (true) {
        // The inputs exactly as the importer's renumber sees them: the numbers the archive carries.
        val derived = computeAutoCueNumbers(
            entries.mapIndexed { index, entry ->
                val written = if (entry.hasExplicitNumber()) entry.cueNumber else held[index]
                AutoNumberInput(written, auto = written == null)
            },
        )
        val more = entries.withIndex().filter { (index, entry) ->
            !entry.hasExplicitNumber() && index !in held && !entry.cueNumber.isNullOrEmpty() &&
                derived[index] != entry.cueNumber
        }
        if (more.isEmpty()) break
        more.forEach { (index, entry) -> held[index] = entry.cueNumber!! }
    }
    return held.entries.associate { (index, number) -> entries[index].id.value to number }
}

/**
 * Relabel [stack]'s auto numbers as a reader of its archive would: every explicit number and each of
 * [held] (auto numbers by entry id that the archive carries, [heldAutoNumbers]) fixed, every other
 * auto entry derived from scratch. An undo's restore ends with this, so the stack exports as the
 * history says it did — an auto number the export leaves out is one a reader derives, wherever the
 * entries around it have since moved.
 */
internal fun renumberAsArchived(stack: DaoCueStack, held: Map<Int, String>) {
    flushPendingEntryWrites()
    val entries = cueEntriesInOrder(stack)
    if (entries.isEmpty()) return
    val labels = computeAutoCueNumbers(
        entries.map { entry ->
            val written = if (entry.hasExplicitNumber()) entry.cueNumber else held[entry.id.value]
            AutoNumberInput(written, auto = written == null)
        },
    )
    // A held entry takes its held number back too, whatever an earlier pass of the restore made of it.
    val wanted = entries.mapIndexed { index, entry -> held[entry.id.value] ?: labels[index] }
    val targets = entries.withIndex().filter { (_, entry) -> !entry.hasExplicitNumber() }
    if (targets.none { (index, entry) -> entry.cueNumber != wanted[index] }) return
    for ((index, entry) in targets) {
        if (entry.cueNumber != wanted[index]) entry.cueNumber = null
    }
    flushPendingEntryWrites()
    for ((index, entry) in targets) {
        val label = wanted[index]
        entry.cueNumber = label
        entry.cueNumberAuto = label != null
    }
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
 * `ordinal` tie, so two reads of an unchanged stack always agree — which [renumberAutoEntries]'s
 * comparison against a [cueEntryIds] snapshot depends on — and so do two desks.
 */
internal fun cueEntriesInOrder(stack: DaoCueStack): List<DaoCueStackEntry> = entriesOf(stack).filter { !it.isMarker }

/**
 * The ids of [stack]'s cue entries in playback order: the snapshot a handler takes **before** it
 * changes the stack's list, to hand [renumberAutoEntries] afterwards.
 */
internal fun cueEntryIds(stack: DaoCueStack): List<Int> {
    flushPendingEntryWrites()
    return cueEntriesInOrder(stack).map { it.id.value }
}

/**
 * Recompute [stack]'s auto cue numbers in place, on its entries. Explicit numbers are never
 * touched. Call this from anything that changes the stack's membership, order or numbers — and,
 * where an entry leaves a stack for another, on the stack it left as well as the one it joined.
 *
 * [before] is [cueEntryIds] as it stood before the change (venue-visit plan D11). An entry still
 * at the same place in it keeps the auto number it holds; every other auto entry is labelled again
 * from its neighbours, so the entries after an insert, a delete or a move follow their new
 * positions while the ones before it stay put. A handler that moves nothing — a number edit —
 * passes the order as it stands, so no auto entry moves but the one whose number was taken or
 * cleared. An empty list relabels every auto entry from scratch.
 *
 * Writes in two passes with a flush between them: `uq_entry_number_per_stack` would otherwise
 * reject the transient duplicate you get when two auto numbers swap places.
 */
internal fun renumberAutoEntries(stack: DaoCueStack, before: List<Int>) {
    // Callers have almost always just written ordinals; the ORDER BY below has to see them.
    flushPendingEntryWrites()

    val entries = cueEntriesInOrder(stack)
    if (entries.isEmpty()) return

    val labels = computeAutoCueNumbers(
        entries.mapIndexed { index, entry ->
            AutoNumberInput(entry.cueNumber, entry.cueNumberAuto, keep = before.getOrNull(index) == entry.id.value)
        },
    )
    val targets = entries.withIndex().filterNot { (_, entry) -> entry.hasExplicitNumber() }
    if (targets.none { (index, entry) -> entry.cueNumber != labels[index] }) return

    // Pass 1: release every number that is about to change, so pass 2 can reuse any of them.
    for ((index, entry) in targets) {
        if (entry.cueNumber != labels[index]) entry.cueNumber = null
    }
    flushPendingEntryWrites()

    // Pass 2: assign.
    for ((index, entry) in targets) {
        val label = labels[index]
        entry.cueNumber = label
        entry.cueNumberAuto = label != null
    }
}
