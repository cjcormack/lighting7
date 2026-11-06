package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.entriesOf
import uk.me.cormack.lighting7.models.writeEntryOrder

/**
 * Where a cue goes when the number it is given reads out of order where it would sit.
 *
 * - [HERE] keeps the place it was given (an edit leaves it where it is), out of order or not.
 * - [BY_NUMBER] moves it to its number's place among the stack's numbered cues ([positionForNumber]).
 * - [ASK] writes nothing when the number reads out of order, answering 409
 *   `CUE_NUMBER_OUT_OF_ORDER` so the operator can choose one of the other two; in order, it is [HERE].
 */
@Serializable
enum class CueNumberPlacement { ASK, HERE, BY_NUMBER }

internal const val CUE_NUMBER_INVALID = "CUE_NUMBER_INVALID"
internal const val CUE_NUMBER_TAKEN = "CUE_NUMBER_TAKEN"
internal const val CUE_NUMBER_OUT_OF_ORDER = "CUE_NUMBER_OUT_OF_ORDER"

/** The 409 an [CueNumberPlacement.ASK] write answers: the number and the neighbours it reads out of order against. */
@Serializable
data class CueNumberOutOfOrderResponse(
    val error: String,
    val code: String = CUE_NUMBER_OUT_OF_ORDER,
    val number: String,
    /** The numbered cue before it that is not below it. */
    val previous: String? = null,
    /** The numbered cue after it that is not above it. */
    val next: String? = null,
)

/** Why a cue number was not written. */
internal sealed interface EntryNumberRefusal {
    val message: String

    data class Invalid(override val message: String) : EntryNumberRefusal
    data class Taken(override val message: String) : EntryNumberRefusal
    data class OutOfOrder(val number: String, val neighbours: OutOfOrderNeighbours) : EntryNumberRefusal {
        override val message: String
            get() = buildString {
                append("Q$number reads out of order here")
                val where = listOfNotNull(neighbours.previous?.let { "after Q$it" }, neighbours.next?.let { "before Q$it" })
                if (where.isNotEmpty()) append(", ${where.joinToString(" and ")}")
                append(" — move it to its place, or keep it here")
            }
    }
}

internal suspend fun ApplicationCall.respondNumberRefusal(refusal: EntryNumberRefusal) = when (refusal) {
    is EntryNumberRefusal.Invalid -> respond(HttpStatusCode.BadRequest, ErrorResponse(refusal.message, CUE_NUMBER_INVALID))
    is EntryNumberRefusal.Taken -> respond(HttpStatusCode.Conflict, ErrorResponse(refusal.message, CUE_NUMBER_TAKEN))
    is EntryNumberRefusal.OutOfOrder -> respond(
        HttpStatusCode.Conflict,
        CueNumberOutOfOrderResponse(
            error = refusal.message,
            number = refusal.number,
            previous = refusal.neighbours.previous,
            next = refusal.neighbours.next,
        ),
    )
}

/** What a new cue entry's number and place come to, or why it cannot have that number. */
internal sealed interface NewEntryNumberPlan {
    data class Place(val number: String?, val position: Int?) : NewEntryNumberPlan
    data class Refused(val refusal: EntryNumberRefusal) : NewEntryNumberPlan
}

/**
 * The number and place of a new cue entry of [stack] typed [raw] and asked for at [position] (an
 * index into the stack's list, markers counted; null appends), under [placement]. Writes nothing.
 * Inside a transaction.
 */
internal fun planNewEntryNumber(
    stack: DaoCueStack,
    raw: String?,
    position: Int?,
    placement: CueNumberPlacement,
): NewEntryNumberPlan {
    val number = parseTypedCueNumber(raw).getOrElse { return NewEntryNumberPlan.Refused(EntryNumberRefusal.Invalid(it.message!!)) }
        ?: return NewEntryNumberPlan.Place(null, position)
    explicitNumberClash(stack, number)?.let { return NewEntryNumberPlan.Refused(EntryNumberRefusal.Taken(it)) }
    val entries = entriesOf(stack)
    val at = position?.coerceIn(0, entries.size) ?: entries.size
    return when (placement) {
        CueNumberPlacement.HERE -> NewEntryNumberPlan.Place(number, position)
        CueNumberPlacement.BY_NUMBER -> NewEntryNumberPlan.Place(number, numberPosition(entries, number) ?: position)
        CueNumberPlacement.ASK -> numberOutOfOrder(entries, number, at, inserted = true)
            ?.let { NewEntryNumberPlan.Refused(EntryNumberRefusal.OutOfOrder(number, it)) }
            ?: NewEntryNumberPlan.Place(number, position)
    }
}

/** What an entry's number edit comes to, or why it cannot be made. */
internal sealed interface EntryNumberEditPlan {
    /** The number is the one the entry holds already: nothing to write, the auto flag left alone. */
    data object Unchanged : EntryNumberEditPlan
    data class Write(val number: String?, val byNumber: Boolean) : EntryNumberEditPlan
    data class Refused(val refusal: EntryNumberRefusal) : EntryNumberEditPlan
}

/** [entry]'s number typed as [raw] under [placement]. Writes nothing. Inside a transaction. */
internal fun planEntryNumberEdit(entry: DaoCueStackEntry, raw: String?, placement: CueNumberPlacement): EntryNumberEditPlan {
    val number = parseTypedCueNumber(raw).getOrElse { return EntryNumberEditPlan.Refused(EntryNumberRefusal.Invalid(it.message!!)) }
    if (number == entry.cueNumber) return EntryNumberEditPlan.Unchanged
    if (number == null) return EntryNumberEditPlan.Write(null, byNumber = false)
    val stack = entry.stack
    explicitNumberClash(stack, number, entry.id.value)?.let { return EntryNumberEditPlan.Refused(EntryNumberRefusal.Taken(it)) }
    return when (placement) {
        CueNumberPlacement.HERE -> EntryNumberEditPlan.Write(number, byNumber = false)
        CueNumberPlacement.BY_NUMBER -> EntryNumberEditPlan.Write(number, byNumber = true)
        CueNumberPlacement.ASK -> {
            val all = entriesOf(stack)
            val others = all.filter { it.id != entry.id }
            numberOutOfOrder(others, number, all.indexOfFirst { it.id == entry.id }, inserted = false)
                ?.let { EntryNumberEditPlan.Refused(EntryNumberRefusal.OutOfOrder(number, it)) }
                ?: EntryNumberEditPlan.Write(number, byNumber = false)
        }
    }
}

/**
 * The numbers [entries] show for the purpose of placing a cue numbered [number]: a marker's none,
 * and an auto number equal to it none, since the write takes that one over ([releaseAutoNumber]).
 */
private fun placingNumbers(entries: List<DaoCueStackEntry>, number: String): List<String?> =
    entries.map { e -> e.cueNumber.takeIf { !e.isMarker && !(e.cueNumberAuto && number.equals(it, ignoreCase = true)) } }

/**
 * [outOfOrderAt] for a cue numbered [number] going in at [position] of [others] (the list without
 * it, markers included), read against the numbers the stack would then show: an auto number equal
 * to it released, and — when the cue is [inserted] — the auto entries after it relabelled, as
 * [renumberAutoEntries] does after the write.
 */
private fun numberOutOfOrder(
    others: List<DaoCueStackEntry>,
    number: String,
    position: Int,
    inserted: Boolean,
): OutOfOrderNeighbours? {
    val value = cueNumberValue(number) ?: return null
    val cues = others.withIndex().filter { !it.value.isMarker }
    val at = cues.count { it.index < position }
    val inputs = cues.mapIndexed { i, (_, e) ->
        val released = e.cueNumberAuto && number.equals(e.cueNumber, ignoreCase = true)
        AutoNumberInput(e.cueNumber.takeUnless { released }, e.cueNumberAuto, keep = !inserted || i < at)
    }.toMutableList()
    inputs.add(at, AutoNumberInput(number, auto = false))
    val labels = computeAutoCueNumbers(inputs)
    val reads = inputs.mapIndexed { i, c -> if (!c.auto && !c.number.isNullOrEmpty()) c.number else labels[i] }
    return outOfOrderAt(reads, at, value)
}

/** [positionForNumber] over [others] (the list without the cue being placed). */
private fun numberPosition(others: List<DaoCueStackEntry>, number: String): Int? {
    val value = cueNumberValue(number) ?: return null
    val numbers = placingNumbers(others, number)
    return positionForNumber(numbers.indices.toList(), value) { numbers[it] }
}

/**
 * Set [entry]'s number as an operator edit: typed, it becomes explicit, released first from any
 * auto-numbered sibling that holds it; blank hands the entry back to the auto scheme, which fills it
 * in again from position. [byNumber] also moves the entry to its number's place. [number] must be
 * canonical (or null). Answers whether the entry moved. Must run inside a transaction.
 *
 * A number edit that moves nothing leaves every other auto entry's number alone (D11); one that
 * moves the entry relabels the auto entries it passed, as *Fix Order* does.
 */
internal fun setEntryNumber(entry: DaoCueStackEntry, number: String?, byNumber: Boolean = false): Boolean {
    val stack = entry.stack
    val before = cueEntryIds(stack)
    releaseAutoNumber(stack, number, entry.id.value)
    entry.cueNumber = number
    entry.cueNumberAuto = false
    var moved = false
    if (byNumber && number != null) {
        val all = entriesOf(stack)
        val others = all.filter { it.id != entry.id }
        val at = numberPosition(others, number)
        if (at != null && at != all.indexOfFirst { it.id == entry.id }) {
            writeEntryOrder(others.take(at) + entry + others.drop(at))
            moved = true
        }
    }
    renumberAutoEntries(stack, before)
    return moved
}

/** Why [stack]'s cue entries [entryIds] cannot be numbered [start], [start] + [step], …, or null. */
internal fun renumberProblem(stack: DaoCueStack, entryIds: List<Int>, start: Long, step: Long): EntryNumberRefusal? {
    if (entryIds.isEmpty()) return EntryNumberRefusal.Invalid("Name at least one cue to renumber")
    if (entryIds.toSet().size != entryIds.size) return EntryNumberRefusal.Invalid("A cue is named twice")
    if (start < 0) return EntryNumberRefusal.Invalid("The first number must be a whole number, 0 or more")
    if (step < 1) return EntryNumberRefusal.Invalid("The step must be a whole number, 1 or more")
    val cues = cueEntriesInOrder(stack)
    val named = entryIds.toSet()
    val unknown = named - cues.map { it.id.value }.toSet()
    if (unknown.isNotEmpty()) return EntryNumberRefusal.Invalid("Not a cue of ${stack.name}: ${unknown.sorted().joinToString()}")
    val last = start.toBigInteger() + step.toBigInteger() * (entryIds.size - 1).toBigInteger()
    if (last > MAX_CUE_NUMBER.toBigInteger()) {
        return EntryNumberRefusal.Invalid("$CUE_NUMBER_RULE — the last would be $last")
    }
    val numbers = (0 until entryIds.size).map { (start + step * it).toString() }.toSet()
    val clashes = cues.filter { it.id.value !in named && !it.cueNumberAuto && it.cueNumber in numbers }
    if (clashes.isNotEmpty()) {
        val held = clashes.map { "Q${it.cueNumber}" }
        return EntryNumberRefusal.Taken("${held.joinToString()} ${if (held.size == 1) "is" else "are"} already used in ${stack.name}")
    }
    return null
}

/**
 * Number [stack]'s cue entries [entryIds], in their order in the stack, [start], [start] + [step],
 * … as explicit numbers. Nothing moves: a number that then reads out of order waits for *Fix Order*.
 * Check [renumberProblem] first. Must run inside a transaction.
 */
internal fun renumberEntries(stack: DaoCueStack, entryIds: List<Int>, start: Long, step: Long) {
    val before = cueEntryIds(stack)
    val named = entryIds.toSet()
    val ordered = cueEntriesInOrder(stack).filter { it.id.value in named }
    // Released first, so two of them trading numbers is never a transient duplicate.
    ordered.forEach { it.cueNumber = null }
    flushPendingEntryWrites()
    ordered.forEachIndexed { i, entry ->
        val number = (start + step * i).toString()
        releaseAutoNumber(stack, number, entry.id.value)
        entry.cueNumber = number
        entry.cueNumberAuto = false
    }
    renumberAutoEntries(stack, before)
}
