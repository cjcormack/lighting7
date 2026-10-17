package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.entriesOf

/**
 * How a tool names one place in a stack (cue model plan §3.10): an `entryId`, or the stack's
 * `number` — the one an operator reads off the Show view, unique in its stack. A cue is named by its
 * id; a cue twice in one stack (D7) is told apart only by its entries.
 */
internal data class EntryRef(val entryId: Int?, val number: String?) {
    val isEmpty: Boolean get() = entryId == null && number == null

    companion object {
        /**
         * [obj]'s `entryId` and `number`, or null after [problem] for a malformed one. A number may
         * arrive as a JSON number (`14`); it is read as the text it would be typed as.
         */
        fun of(obj: JsonObject, where: String, problem: (String) -> Unit, idField: String = "entryId", numberField: String = "number"): EntryRef? {
            val rawId = obj[idField]?.takeIf { it !is JsonNull }
            val entryId = rawId?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull }
            if (rawId != null && entryId == null) {
                problem("$where: $idField must be an integer")
                return null
            }
            val number = obj[numberField]?.let(::cueNumberText)
            if (obj[numberField] != null && obj[numberField] !is JsonNull && number == null) {
                problem("$where: $numberField must be the cue number as the stack shows it, e.g. \"14\" or \"14A\"")
                return null
            }
            if (entryId != null && number != null) {
                problem("$where: name the entry by $idField or by $numberField, not both")
                return null
            }
            return EntryRef(entryId, number)
        }
    }
}

/** A cue number as a tool sends it — text, or a JSON number read as typed (`14`) — trimmed; null for anything else. */
internal fun cueNumberText(element: JsonElement): String? =
    (element as? JsonPrimitive)?.takeIf { it.isString || it.doubleOrNull != null }?.content?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The cue entry of [project] that [obj] names — `entryId`, or `stackId` and `number` — or null after
 * [problem]. A `stackId` beside an `entryId` must be the entry's stack. Inside a transaction.
 */
internal fun findProjectEntry(project: DaoProject, obj: JsonObject, where: String, problem: (String) -> Unit): DaoCueStackEntry? {
    val ref = EntryRef.of(obj, where, problem) ?: return null
    val rawStack = obj["stackId"]?.takeIf { it !is JsonNull }
    val stackId = rawStack?.let {
        (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull ?: run { problem("$where: stackId must be an integer"); return null }
    }
    if (ref.isEmpty) {
        problem("$where: name the entry by entryId, or by stackId and number")
        return null
    }
    if (ref.entryId != null) {
        val entry = DaoCueStackEntry.findById(ref.entryId)?.takeIf { it.stack.project.id == project.id }
        return when {
            entry == null -> { problem("$where: no entry ${ref.entryId} in this project"); null }
            entry.isMarker -> { problem("$where: entry ${ref.entryId} is a marker, not a cue"); null }
            stackId != null && entry.stackId != stackId -> {
                problem("$where: entry ${ref.entryId} is in stack ${entry.stackId}, not stack $stackId")
                null
            }
            else -> entry
        }
    }
    if (stackId == null) {
        problem("$where: number names an entry of a stack: give stackId too")
        return null
    }
    val stack = DaoCueStack.findById(stackId)?.takeIf { it.project.id == project.id }
        ?: run { problem("$where: no cue stack $stackId in this project"); return null }
    return findStackEntry(stack, ref, where, problem)
}

/**
 * The entry of [stack] [ref] names, or null after [problem]. A number names a cue entry; an id names
 * a cue entry, or a marker only when [markers] (a marker has no number, and is never gone to). Inside
 * a transaction.
 */
internal fun findStackEntry(
    stack: DaoCueStack,
    ref: EntryRef,
    where: String,
    problem: (String) -> Unit,
    markers: Boolean = false,
): DaoCueStackEntry? {
    val entries = entriesOf(stack)
    ref.entryId?.let { id ->
        val entry = entries.firstOrNull { it.id.value == id }
        return when {
            entry == null -> { problem("$where: entry $id is not in stack ${stack.id.value} ('${stack.name}')"); null }
            entry.isMarker && !markers -> { problem("$where: entry $id is a marker, not a cue"); null }
            else -> entry
        }
    }
    val number = ref.number ?: run { problem("$where: name the entry by entryId or by number"); return null }
    val cues = entries.filter { !it.isMarker }
    return cues.firstOrNull { it.cueNumber == number }
        ?: cues.singleOrNull { it.cueNumber.equals(number, ignoreCase = true) }
        ?: run { problem("$where: stack ${stack.id.value} ('${stack.name}') has no cue numbered '$number'"); null }
}

/** The `entryId` and `number` schema fields, with [what] saying which entry they name. */
internal fun JsonObjectBuilder.putEntryRefProps(what: String, idField: String = "entryId", numberField: String = "number") {
    put(idField, buildJsonObject {
        put("type", "integer")
        put("description", "$what, by entry id (each place a cue has in a stack is an entry; get_current_state's cues list them). Give this or $numberField.")
    })
    put(numberField, buildJsonObject {
        put("type", "string")
        put("description", "$what, by its cue number in the stack, as the Show view shows it ('14', '14A'). Give this or $idField.")
    })
}

/** An entry as the tools answer it: where it is, its number and its follow. */
internal fun JsonObjectBuilder.putEntry(entry: DaoCueStackEntry) {
    put("entryId", entry.id.value)
    put("stackId", entry.stackId)
    put("stackName", entry.stack.name)
    entry.cueId?.let { put("cueId", it) }
    entry.cueNumber?.let { put("number", it) }
    put("autoAdvance", entry.autoAdvance)
    entry.autoAdvanceDelay?.let { put("autoAdvanceDelayMs", it.toMillis()) }
}
