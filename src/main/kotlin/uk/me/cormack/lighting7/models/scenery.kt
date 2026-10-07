package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.duration
import java.time.Duration
import java.util.UUID

// ─── Scenery on cues, stacks and Looks (stage-view plan session 8, D11–D13) ─────────────────────
//
// A **scenery change** is an element and the states it takes — `visible`, a drawn drape's `open`, a
// flown piece's `trimM` — attached to one of three owners: a cue (moved on GO, on its own clock),
// a cue stack (its *set*: held while the stack is live) or a Look (shown while the Look is live).
// Not a template (D11: a template names no targets of its own), and not a virtual DMX fixture (D12):
// nothing here reaches the composition pipeline. Record does not capture it yet (D13, which the
// scenery-programmer plan's D7 narrows in its session 3). Above all three sits the programmer's own
// scenery (`state/ProgrammerScenery.kt`, runtime only). How the changes resolve into what the stage
// shows is `show/SceneryResolver.kt`.
//
// One row per (owner, element): an owner says one thing about each element. The element is a
// foreign key here and a uuid on the wire and in sync; deleting an element sweeps its rows
// ([deleteSceneryForElement]).

/** A cue's scenery changes: moved on GO into the cue, each on its own [transition]. */
object DaoCueScenery : IntIdTable("cue_scenery") {
    val cue = reference("cue_id", DaoCues)
    val element = reference("element_id", DaoStageElements)
    /** The states this cue moves the element to, as canonical JSON ([encodeSceneryState]). */
    val state = text("state")
    /** How long the move takes; null follows the cue's own fade. Nanoseconds on disk. */
    val transition = duration("transition").nullable()
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(cue, element)
    }
}

class DaoCueSceneryRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueSceneryRow>(DaoCueScenery)

    var cue by DaoCue referencedOn DaoCueScenery.cue
    var element by DaoStageElement referencedOn DaoCueScenery.element
    /** The [DaoCueScenery.state] column. Not `state`, which every route's `state: State` would shadow. */
    var stateJson by DaoCueScenery.state
    var transition by DaoCueScenery.transition
    var sortOrder by DaoCueScenery.sortOrder
    var uuid by DaoCueScenery.uuid
}

/** A cue stack's *set*: the states its elements hold while the stack is live, under its cues. */
object DaoCueStackScenery : IntIdTable("cue_stack_scenery") {
    val stack = reference("cue_stack_id", DaoCueStacks)
    val element = reference("element_id", DaoStageElements)
    val state = text("state")
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(stack, element)
    }
}

class DaoCueStackSceneryRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueStackSceneryRow>(DaoCueStackScenery)

    var stack by DaoCueStack referencedOn DaoCueStackScenery.stack
    var element by DaoStageElement referencedOn DaoCueStackScenery.element
    var stateJson by DaoCueStackScenery.state
    var sortOrder by DaoCueStackScenery.sortOrder
    var uuid by DaoCueStackScenery.uuid
}

/** A Look's scenery: shown while the Look is live — layered in a live cue, or pressed. */
object DaoLookScenery : IntIdTable("look_scenery") {
    val look = reference("look_id", DaoLooks)
    val element = reference("element_id", DaoStageElements)
    val state = text("state")
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(look, element)
    }
}

class DaoLookSceneryRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoLookSceneryRow>(DaoLookScenery)

    var look by DaoLook referencedOn DaoLookScenery.look
    var element by DaoStageElement referencedOn DaoLookScenery.element
    var stateJson by DaoLookScenery.state
    var sortOrder by DaoLookScenery.sortOrder
    var uuid by DaoLookScenery.uuid
}

// ─── The state document ──────────────────────────────────────────────────────────────────────

/** The longest a cue's scenery move may take: ten minutes, well past any tab or fly. */
val MAX_SCENERY_TRANSITION: Duration = Duration.ofMinutes(10)

/** Which states an element of [kind] with [params] can take: `visible` always, the others by kind. */
fun sceneryKeysOf(kind: StageElementKind?, params: ElementParams?): Set<String> = buildSet {
    add("visible")
    if (kind == StageElementKind.DRAPE && (params as? DrapeParams)?.operation == DrapeOperation.DRAW) add("open")
    if (elementFlies(kind, params)) add("trimM")
}

/** Whether the element is a flown piece: an `OBJECT` with `flies`, or a `DRAPE` whose operation is `FLY`. */
fun elementFlies(kind: StageElementKind?, params: ElementParams?): Boolean = when (kind) {
    StageElementKind.OBJECT -> (params as? ObjectParams)?.flies == true
    StageElementKind.DRAPE -> (params as? DrapeParams)?.operation == DrapeOperation.FLY
    else -> false
}

/** [states] as the column and the wire hold it: only the states set, keys sorted. */
fun sceneryStateObject(states: ElementStates): JsonObject = buildJsonObject {
    states.open?.let { put("open", it) }
    states.trimM?.let { put("trimM", it) }
    states.visible?.let { put("visible", it) }
}

fun encodeSceneryState(states: ElementStates): String = storedParamsText(sceneryStateObject(states))

/**
 * A stored or synced state document read back, keeping only well-typed states; an unreadable one
 * reads as no states. The importer stores what an archive holds without reparsing it, so every
 * reader goes through here and tolerates what it cannot read.
 */
fun decodeSceneryState(text: String): ElementStates = sceneryStateOf(storedParamsObject(text))

fun sceneryStateOf(obj: JsonObject): ElementStates {
    fun prim(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }
    return ElementStates(
        visible = prim("visible")?.booleanOrNull,
        open = prim("open")?.doubleOrNull?.takeIf { it.isFinite() },
        trimM = prim("trimM")?.doubleOrNull?.takeIf { it.isFinite() },
    )
}

// ─── Writing a list ──────────────────────────────────────────────────────────────────────────

/** Who owns a scenery list — what the write may carry and how its rows are stored. */
enum class SceneryOwnerKind(val noun: String) {
    CUE("cue"),
    STACK("cue stack"),
    LOOK("Look"),
}

/** One element as a scenery write checks it. */
class SceneryElementInfo(
    val id: Int,
    val uuid: UUID,
    val name: String,
    val kind: StageElementKind?,
    val params: ElementParams?,
)

/** A scenery change that has passed [parseSceneryList]. */
data class SceneryWrite(
    val element: SceneryElementInfo,
    val state: ElementStates,
    val transition: Duration?,
)

/**
 * A whole scenery list (`[{elementUuid, state, transitionMs?}]`) checked against the project's
 * [elements], every problem at once — the scene document's rule (`set_scene`, the stage routes).
 * A state is checked against its element's **kind**: `open` only on a drawn drape, `trimM` only on
 * a flown piece, `visible` on anything. [owner] decides whether a `transitionMs` may ride along (a
 * cue's own clock; a stack's set and a Look's scenery have none). An element named twice is a
 * problem, since an owner says one thing about each element.
 *
 * [where] prefixes every message (`scenery`, `cues[2].scenery`).
 */
fun parseSceneryList(
    items: List<JsonElement>,
    elements: Map<UUID, SceneryElementInfo>,
    owner: SceneryOwnerKind,
    where: String,
    problems: MutableList<String>,
): List<SceneryWrite> {
    val out = mutableListOf<SceneryWrite>()
    val seen = mutableSetOf<UUID>()
    items.forEachIndexed { i, raw ->
        val at = "$where[$i]"
        val obj = raw as? JsonObject ?: run { problems += "$at must be an object"; return@forEachIndexed }
        for (key in obj.keys) {
            if (key !in SCENERY_ITEM_KEYS) problems += "$at: unknown field '$key' (known: ${SCENERY_ITEM_KEYS.sorted().joinToString()})"
        }
        val before = problems.size
        val uuidText = (obj["elementUuid"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        val element = when {
            uuidText == null -> { problems += "$at.elementUuid is required"; null }
            else -> runCatching { UUID.fromString(uuidText) }.getOrNull()?.let { elements[it] }
                ?: run { problems += "$at.elementUuid names no stage element in this project"; null }
        }
        val label = element?.let { "$at ('${it.name}')" } ?: at
        if (element != null && !seen.add(element.uuid)) problems += "$label names an element this list already names"

        val stateObj = obj["state"]?.takeIf { it !is JsonNull }
        val state = when (stateObj) {
            null -> { problems += "$label.state is required"; null }
            !is JsonObject -> { problems += "$label.state must be an object"; null }
            else -> parseSceneryState(stateObj, element, "$label.state", problems)
        }

        val transitionRaw = obj["transitionMs"]?.takeIf { it !is JsonNull }
        val transition = transitionRaw?.let { t ->
            if (owner != SceneryOwnerKind.CUE) {
                problems += "$label.transitionMs is a cue's: a ${owner.noun}'s scenery has no clock of its own"
                return@let null
            }
            val ms = (t as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            when {
                ms == null -> { problems += "$label.transitionMs must be a whole number of milliseconds"; null }
                ms < 0 || ms > MAX_SCENERY_TRANSITION.toMillis() ->
                    { problems += "$label.transitionMs must be between 0 and ${MAX_SCENERY_TRANSITION.toMillis()}"; null }
                else -> Duration.ofMillis(ms)
            }
        }
        if (problems.size == before && element != null && state != null) out += SceneryWrite(element, state, transition)
    }
    return out
}

private val SCENERY_ITEM_KEYS = setOf("elementUuid", "state", "transitionMs", "uuid", "sortOrder")

/**
 * A scenery list in the MCP tools' shape — `[{element, visible?, open?, trimM?,
 * transitionSeconds?}]`, the element by **name** (or uuid) and the states beside it — checked
 * exactly as [parseSceneryList] checks the REST shape, every problem at once.
 */
fun parseToolSceneryList(
    items: List<JsonElement>,
    elements: Map<UUID, SceneryElementInfo>,
    owner: SceneryOwnerKind,
    where: String,
    problems: MutableList<String>,
): List<SceneryWrite> {
    val byName = elements.values.associateBy { it.name.lowercase() }
    val out = mutableListOf<SceneryWrite>()
    val seen = mutableSetOf<UUID>()
    items.forEachIndexed { i, raw ->
        val at = "$where[$i]"
        val obj = raw as? JsonObject ?: run { problems += "$at must be an object"; return@forEachIndexed }
        for (key in obj.keys) {
            if (key !in TOOL_ITEM_KEYS) problems += "$at: unknown field '$key' (known: ${TOOL_ITEM_KEYS.sorted().joinToString()})"
        }
        val before = problems.size
        val ref = (obj["element"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        val element = when {
            ref.isNullOrEmpty() -> { problems += "$at.element is required: an element's name (get_scene lists them) or uuid"; null }
            else -> (runCatching { UUID.fromString(ref) }.getOrNull()?.let { elements[it] } ?: byName[ref.lowercase()])
                ?: run {
                    problems += "$at: no stage element named '$ref' (elements: ${elements.values.map { it.name }.sorted().joinToString().ifEmpty { "none" }})"
                    null
                }
        }
        val label = element?.let { "$at ('${it.name}')" } ?: at
        if (element != null && !seen.add(element.uuid)) problems += "$label names an element this list already names"
        val stateObj = JsonObject(obj.filterKeys { it in SCENERY_STATE_KEYS })
        val state = parseSceneryState(stateObj, element, label, problems)
        val transition = obj["transitionSeconds"]?.takeIf { it !is JsonNull }?.let { t ->
            if (owner != SceneryOwnerKind.CUE) {
                problems += "$label.transitionSeconds is a cue's: a ${owner.noun}'s scenery has no clock of its own"
                return@let null
            }
            val seconds = (t as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
            when {
                seconds == null -> { problems += "$label.transitionSeconds must be a number"; null }
                seconds < 0 || seconds * 1000 > MAX_SCENERY_TRANSITION.toMillis() ->
                    { problems += "$label.transitionSeconds must be between 0 and ${MAX_SCENERY_TRANSITION.seconds}"; null }
                else -> Duration.ofMillis(Math.round(seconds * 1000))
            }
        }
        if (problems.size == before && element != null && state != null) out += SceneryWrite(element, state, transition)
    }
    return out
}

private val TOOL_ITEM_KEYS = setOf("element", "visible", "open", "trimM", "transitionSeconds")

/**
 * One element's state document ([obj]) checked against [element]'s kind, every problem at once:
 * `open` only on a drawn drape, `trimM` only on a flown piece, `visible` on anything, and at least
 * one of them. Null with [problems] grown when refused. The owners' lists and the programmer's
 * scenery ([uk.me.cormack.lighting7.state.ProgrammerScenery]) all write through here.
 */
fun parseSceneryState(
    obj: JsonObject,
    element: SceneryElementInfo?,
    where: String,
    problems: MutableList<String>,
): ElementStates? {
    val before = problems.size
    for (key in obj.keys) {
        if (key !in SCENERY_STATE_KEYS) problems += "$where: unknown state '$key' (one of visible, open, trimM)"
    }
    fun prim(name: String): JsonPrimitive? {
        val e = obj[name]?.takeIf { it !is JsonNull } ?: return null
        return (e as? JsonPrimitive)?.takeIf { !it.isString } ?: run { problems += "$where.$name has the wrong type"; null }
    }
    val visible = prim("visible")?.let { p -> p.booleanOrNull ?: run { problems += "$where.visible must be true or false"; null } }
    val open = prim("open")?.let { p ->
        val v = p.doubleOrNull?.takeIf { it.isFinite() }
        when {
            v == null -> { problems += "$where.open must be a number"; null }
            v < 0.0 || v > 1.0 -> { problems += "$where.open must be between 0.0 and 1.0 (0 closed, 1 drawn)"; null }
            else -> v
        }
    }
    val trimM = prim("trimM")?.let { p ->
        val v = p.doubleOrNull?.takeIf { it.isFinite() }
        when {
            v == null -> { problems += "$where.trimM must be a number"; null }
            v < 0.0 || v > 100.0 -> { problems += "$where.trimM must be between 0.0 and 100.0 metres"; null }
            else -> v
        }
    }
    if (problems.size == before && visible == null && open == null && trimM == null) {
        problems += "$where names no state: give visible, open or trimM"
    }
    if (element != null) {
        val accepts = sceneryKeysOf(element.kind, element.params)
        if (open != null && "open" !in accepts) problems += "$where.open is a drawn drape's (a DRAPE with operation DRAW); '${element.name}' is not one"
        if (trimM != null && "trimM" !in accepts) problems += "$where.trimM is a flown piece's (an OBJECT with flies, or a DRAPE with operation FLY); '${element.name}' is not one"
    }
    return ElementStates(visible, open, trimM).takeIf { problems.size == before }
}

private val SCENERY_STATE_KEYS = setOf("visible", "open", "trimM")

/** The project's elements as a scenery write checks them, by uuid. Must run inside a transaction. */
fun sceneryElementsOf(project: DaoProject): Map<UUID, SceneryElementInfo> =
    DaoStageElement.find { DaoStageElements.project eq project.id }.associate { it.uuid to it.sceneryInfo() }

fun DaoStageElement.sceneryInfo(): SceneryElementInfo {
    val kind = enumOrNull<StageElementKind>(kind)
    return SceneryElementInfo(id.value, uuid, name, kind, kind?.let { readElementParams(it, params) })
}

// ─── The wire ────────────────────────────────────────────────────────────────────────────────

/**
 * One scenery change on the wire, as an owner's DTO carries it and a whole-list `PUT` answers it.
 * [state] holds only the states the change sets; [elementName] and [elementKind] are for display
 * (the element is [elementUuid]).
 */
@Serializable
data class SceneryChangeDto(
    val uuid: String,
    val elementUuid: String,
    val elementName: String,
    val elementKind: String,
    val state: JsonObject,
    /** A cue's own clock for the move; null follows the cue's fade. Always null off a cue. */
    val transitionMs: Long? = null,
    val sortOrder: Int,
)

/**
 * What a cue's scenery looks like *at* the cue for an element the cue itself does not move: tracked
 * from an earlier cue of its stack ([fromCueId]), or held by the stack's set ([fromSet]). The cue
 * card draws these hatched under the cue's own changes (stage-view plan session 8).
 */
@Serializable
data class TrackedSceneryDto(
    val elementUuid: String,
    val elementName: String,
    val state: JsonObject,
    val fromCueId: Int? = null,
    /** The cue's number where it has one, else its name. */
    val fromCueLabel: String? = null,
    val fromSet: Boolean = false,
)

@Serializable
data class SceneryListRequest(val scenery: List<JsonElement> = emptyList())

internal fun DaoCueSceneryRow.toDto() = SceneryChangeDto(
    uuid = uuid.toString(),
    elementUuid = element.uuid.toString(),
    elementName = element.name,
    elementKind = element.kind,
    state = storedParamsObject(stateJson),
    transitionMs = transition?.toMillis(),
    sortOrder = sortOrder,
)

internal fun DaoCueStackSceneryRow.toDto() = SceneryChangeDto(
    uuid = uuid.toString(),
    elementUuid = element.uuid.toString(),
    elementName = element.name,
    elementKind = element.kind,
    state = storedParamsObject(stateJson),
    sortOrder = sortOrder,
)

internal fun DaoLookSceneryRow.toDto() = SceneryChangeDto(
    uuid = uuid.toString(),
    elementUuid = element.uuid.toString(),
    elementName = element.name,
    elementKind = element.kind,
    state = storedParamsObject(stateJson),
    sortOrder = sortOrder,
)

fun cueSceneryOf(cueId: EntityID<Int>): List<DaoCueSceneryRow> =
    DaoCueSceneryRow.find { DaoCueScenery.cue eq cueId }.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

fun stackSceneryOf(stackId: EntityID<Int>): List<DaoCueStackSceneryRow> =
    DaoCueStackSceneryRow.find { DaoCueStackScenery.stack eq stackId }.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

fun lookSceneryOf(lookId: EntityID<Int>): List<DaoLookSceneryRow> =
    DaoLookSceneryRow.find { DaoLookScenery.look eq lookId }.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

// ─── Storing a list ──────────────────────────────────────────────────────────────────────────
//
// A write replaces the owner's whole list, but keeps the row — and so the uuid — of an element the
// list names again: re-saving an unchanged list must export byte-for-byte as before, or every save
// would be a sync change. Must run inside a transaction.

fun replaceCueScenery(cue: DaoCue, writes: List<SceneryWrite>) {
    val existing = DaoCueSceneryRow.find { DaoCueScenery.cue eq cue.id }.associateBy { it.element.id.value }
    val kept = writes.map { it.element.id }.toSet()
    existing.filterKeys { it !in kept }.values.forEach { it.delete() }
    writes.forEachIndexed { i, w ->
        val row = existing[w.element.id] ?: DaoCueSceneryRow.new {
            this.cue = cue
            this.element = DaoStageElement[w.element.id]
            this.stateJson = ""
        }
        row.stateJson = encodeSceneryState(w.state)
        row.transition = w.transition
        row.sortOrder = i
    }
}

fun replaceStackScenery(stack: DaoCueStack, writes: List<SceneryWrite>) {
    val existing = DaoCueStackSceneryRow.find { DaoCueStackScenery.stack eq stack.id }.associateBy { it.element.id.value }
    val kept = writes.map { it.element.id }.toSet()
    existing.filterKeys { it !in kept }.values.forEach { it.delete() }
    writes.forEachIndexed { i, w ->
        val row = existing[w.element.id] ?: DaoCueStackSceneryRow.new {
            this.stack = stack
            this.element = DaoStageElement[w.element.id]
            this.stateJson = ""
        }
        row.stateJson = encodeSceneryState(w.state)
        row.sortOrder = i
    }
}

fun replaceLookScenery(look: DaoLook, writes: List<SceneryWrite>) {
    val existing = DaoLookSceneryRow.find { DaoLookScenery.look eq look.id }.associateBy { it.element.id.value }
    val kept = writes.map { it.element.id }.toSet()
    existing.filterKeys { it !in kept }.values.forEach { it.delete() }
    writes.forEachIndexed { i, w ->
        val row = existing[w.element.id] ?: DaoLookSceneryRow.new {
            this.look = look
            this.element = DaoStageElement[w.element.id]
            this.stateJson = ""
        }
        row.stateJson = encodeSceneryState(w.state)
        row.sortOrder = i
    }
}

/**
 * Every scenery change naming [elementIds], swept with the element — as a group delete sweeps its
 * busk-rig tiles. A plain FK with no cascade (SQLite enforces none without a pragma), so by hand,
 * and before the element row goes. Returns how many rows went.
 */
fun deleteSceneryForElements(elementIds: Collection<EntityID<Int>>): Int {
    if (elementIds.isEmpty()) return 0
    return DaoCueScenery.deleteWhere { DaoCueScenery.element inList elementIds } +
        DaoCueStackScenery.deleteWhere { DaoCueStackScenery.element inList elementIds } +
        DaoLookScenery.deleteWhere { DaoLookScenery.element inList elementIds }
}

/** A cue's scenery, swept with the cue. Not part of `deleteCueChildren`, which also runs on a cue *edit*. */
fun deleteCueScenery(cueId: EntityID<Int>): Int = DaoCueScenery.deleteWhere { DaoCueScenery.cue eq cueId }

fun deleteStackScenery(stackId: EntityID<Int>): Int = DaoCueStackScenery.deleteWhere { DaoCueStackScenery.stack eq stackId }

fun deleteLookScenery(lookId: EntityID<Int>): Int = DaoLookScenery.deleteWhere { DaoLookScenery.look eq lookId }

/** [from]'s scenery written onto [to] — a copied cue moves the set exactly as its original does. */
fun copyCueScenery(from: DaoCue, to: DaoCue) {
    for (row in cueSceneryOf(from.id)) {
        DaoCueSceneryRow.new {
            cue = to
            element = row.element
            stateJson = row.stateJson
            transition = row.transition
            sortOrder = row.sortOrder
        }
    }
}
