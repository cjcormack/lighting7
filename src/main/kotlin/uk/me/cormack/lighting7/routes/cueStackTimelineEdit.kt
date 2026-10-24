package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.FxRegistry
import uk.me.cormack.lighting7.fx.FxSpecCoercion
import uk.me.cormack.lighting7.fx.LayerPress
import uk.me.cormack.lighting7.fx.LayerPressRules
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.fx.parseFamilies
import uk.me.cormack.lighting7.fx.toAttributeMask
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/**
 * `POST /projects/{id}/cue-stacks/{sid}/timeline/edit` `{ops, dryRun?}` (stack-timeline plan §3.6,
 * C3, P5): every gesture on the Timeline, as one request. The batch is **one transaction, all or
 * nothing**; its ops run in order, each over the state the one before it left, and every cue they
 * name must have an entry in this stack. **Nothing is sent to the stage**: a held cue whose
 * `stageContent` changed is marked edited since GO, which *Update stage* or its next GO lands.
 *
 * - `layerOn` / `layerOff` are the cue editor's press ([decideCueLayerPress], shared, not copied):
 *   per target, the family's other palettes taken off the pressed heads (D5), the record's top
 *   layer widened where it carries the arriving layer's settings. `layerOn` takes the settings of
 *   `settingsFrom` (D6) — a layer of the same record in this stack as the batch found it — else a
 *   fresh press's, and a new layer goes on top. A cell already on (or already off) is left alone.
 * - `layerSettings` writes one layer's fields, as the cue editor's Looks tab does.
 * - `replace` swaps one cell's record for another's in each cue that has it: in place, rank and
 *   settings kept, where the cue lacks the replacement; the old one off where it has it.
 * - `scenerySet` / `sceneryClear` write one element's change, checked as every scenery list is.
 * - `restore` is Undo (P5): a cue's layers, effects and scenery put back exactly as an answer's
 *   `before` holds them, uuids and run keys included.
 *
 * The answer names the cues that changed, what each press released, each changed cue as the batch
 * found it (`before`, what Undo sends back) and the held cues it marked edited. `dryRun` runs the
 * batch and rolls it back. Every problem is named at once: 400 `TIMELINE_EDIT_INVALID`. 409 off the
 * current project.
 */
internal fun Route.routeApiRestCueStackTimelineEdit(state: State) {
    post<CueStackTimelineEditResource> { resource ->
        val stackResource = resource.parent.parent
        withCurrentProject(
            state,
            stackResource.parent.projectId,
            { p -> "Cannot modify cues in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val request = try {
                call.receive<TimelineEditRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad request"))
                return@withCurrentProject
            }
            when (val outcome = editTimeline(state, project, stackResource.stackId, request)) {
                TimelineEditOutcome.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
                is TimelineEditOutcome.Invalid -> call.respond(
                    HttpStatusCode.BadRequest,
                    TimelineEditRefusal(
                        error = outcome.problems.joinToString("; ") { "ops[${it.op}]: ${it.message}" },
                        code = CODE_TIMELINE_EDIT_INVALID,
                        problems = outcome.problems,
                    ),
                )
                is TimelineEditOutcome.Edited -> {
                    val response = outcome.response
                    if (!request.dryRun) {
                        for (cueId in response.edited) state.show.cueStackManager.holds.markEdited(cueId)
                        if (response.changed.isNotEmpty()) {
                            state.show.fixtures.cueListChanged()
                            state.show.fixtures.cueStackListChanged()
                        }
                    }
                    call.respond(response)
                }
            }
        }
    }
}

/** 400 code: the batch named a problem; every one is in `problems`. */
internal const val CODE_TIMELINE_EDIT_INVALID = "TIMELINE_EDIT_INVALID"

internal sealed interface TimelineEditOutcome {
    data object NotFound : TimelineEditOutcome
    data class Invalid(val problems: List<TimelineEditProblem>) : TimelineEditOutcome
    data class Edited(val response: TimelineEditResponse) : TimelineEditOutcome
}

/**
 * The batch, in one transaction rolled back when any op names a problem or [request] is a dry run.
 * The palette snapshots the press reads join this transaction rather than opening their own, so the
 * whole batch — reads, decisions and writes — sees one state.
 */
internal fun editTimeline(state: State, project: DaoProject, stackId: Int, request: TimelineEditRequest): TimelineEditOutcome {
    val fixtures = state.show.fixtures
    val coverage = TargetCoverage { fixtures }
    val rules = LayerPressRules({ state.show.paletteRegistry }, coverage)
    val holds = state.show.cueStackManager.holds
    return transaction(state.database) {
        val stack = DaoCueStack.findById(stackId)?.takeIf { it.project.id == project.id }
            ?: return@transaction TimelineEditOutcome.NotFound
        val batch = TimelineBatch(project, stack, rules, coverage, isHeld = holds::isHeld, registry = state.show.fxRegistry)
        request.ops.forEachIndexed { index, raw -> batch.run(index, raw) }
        val outcome = if (batch.problems.isNotEmpty()) TimelineEditOutcome.Invalid(batch.problems) else TimelineEditOutcome.Edited(batch.answer())
        if (outcome !is TimelineEditOutcome.Edited || request.dryRun) TransactionManager.current().rollback()
        outcome
    }
}

/** One batch's state: the cues it has touched, as it found them, and what it has said so far. Must be used inside the batch's transaction. */
private class TimelineBatch(
    private val project: DaoProject,
    stack: DaoCueStack,
    private val rules: LayerPressRules,
    private val coverage: TargetCoverage,
    private val isHeld: (Int) -> Boolean,
    private val registry: FxRegistry,
) {
    val problems = mutableListOf<TimelineEditProblem>()
    private val cueIds: Set<Int> = entriesOf(stack).mapNotNullTo(LinkedHashSet()) { it.cueId }

    /**
     * Every layer of the stack's cues as the batch found it, by row id: what `settingsFrom` names
     * (D6). Read before any op runs, so a layer an earlier op took off still lends its settings.
     */
    private val startLayers: Map<Int, CueLayerDto> =
        cueIds.flatMap { id -> DaoCue.findById(id)?.layersInOrder()?.mapNotNull { it.toDto() }.orEmpty() }
            .mapNotNull { layer -> layer.id?.let { it to layer } }
            .toMap()
    private val elements by lazy { sceneryElementsOf(project) }
    private val masters by lazy { speedMastersOf(project).mapTo(HashSet()) { it.uuid } }

    /** Each touched cue as the batch found it, and its stage content then when it is held. */
    private val before = LinkedHashMap<Int, TimelineCueSnapshotDto>()
    private val stageBefore = HashMap<Int, List<List<Any?>>>()
    private val released = mutableListOf<TimelineReleasedDto>()

    fun run(index: Int, raw: JsonObject) {
        val problem = { message: String -> problems += TimelineEditProblem(index, message) }
        val kind = (raw["op"] as? JsonPrimitive)?.contentOrNull
        val body = JsonObject(raw - "op")
        try {
            when (kind) {
                "layerOn" -> layerOn(OP_JSON.decodeFromJsonElement(LayerOnOp.serializer(), body), problem)
                "layerOff" -> layerOff(OP_JSON.decodeFromJsonElement(LayerOffOp.serializer(), body), problem)
                "layerSettings" -> layerSettings(OP_JSON.decodeFromJsonElement(LayerSettingsOp.serializer(), body), problem)
                "replace" -> replace(OP_JSON.decodeFromJsonElement(ReplaceOp.serializer(), body), problem)
                "scenerySet" -> scenerySet(index, OP_JSON.decodeFromJsonElement(ScenerySetOp.serializer(), body), problem)
                "sceneryClear" -> sceneryClear(index, OP_JSON.decodeFromJsonElement(SceneryClearOp.serializer(), body), problem)
                "restore" -> restore(index, OP_JSON.decodeFromJsonElement(RestoreOp.serializer(), body), problem)
                null -> problem("op is required")
                else -> problem("unknown op '$kind' — expected one of ${OP_KINDS.joinToString()}")
            }
        } catch (e: SerializationException) {
            problem("malformed $kind: ${e.message}")
        } catch (e: IllegalArgumentException) {
            problem(e.message ?: "refused")
        }
    }

    fun answer(): TimelineEditResponse {
        val changed = before.filter { (cueId, found) -> DaoCue.findById(cueId)?.let(::snapshotOf) != found }
        val edited = stageBefore.filter { (cueId, found) -> cueId in changed && DaoCue.findById(cueId)?.stageContent() != found }
        return TimelineEditResponse(
            changed = changed.keys.toList(),
            released = released.filter { it.cueId in changed },
            before = changed.values.toList(),
            edited = edited.keys.toList(),
        )
    }

    // ── The ops ───────────────────────────────────────────────────────────────────────────────────

    private fun layerOn(op: LayerOnOp, problem: (String) -> Unit) {
        val cue = cueOf(op.cueId, problem) ?: return
        val record = recordOf(op.source, problem) ?: return
        val target = cellTargetOf(record, op.target, problem) ?: if (record is PressedRecord.Palette && op.target != null) return else null
        val settings = op.settingsFrom?.let { id ->
            val from = startLayers[id] ?: return problem("settingsFrom names no layer of this stack")
            if (!from.isOf(record)) return problem("settingsFrom is a layer of another record")
            from
        }
        press(cue, record, listOfNotNull(target), on = true, settings, problem)
    }

    private fun layerOff(op: LayerOffOp, problem: (String) -> Unit) {
        val cue = cueOf(op.cueId, problem) ?: return
        val record = recordOf(op.source, problem) ?: return
        val target = cellTargetOf(record, op.target, problem) ?: if (record is PressedRecord.Palette && op.target != null) return else null
        press(cue, record, listOfNotNull(target), on = false, settings = null, problem)
    }

    private fun layerSettings(op: LayerSettingsOp, problem: (String) -> Unit) {
        val cue = cueOf(op.cueId, problem) ?: return
        val layers = layersOf(cue)
        val at = layers.indexOfFirst { it.id == op.layerId }
        if (at < 0) return problem("layer ${op.layerId} is not a layer of cue ${op.cueId}")
        val found = mutableListOf<String>()
        val layer = withFields(layers[at], op.fields, found)
        if (found.isNotEmpty()) return found.forEach(problem)
        if (layer == layers[at]) return
        write(cue, layers.toMutableList().also { it[at] = layer })
    }

    private fun replace(op: ReplaceOp, problem: (String) -> Unit) {
        if (op.cueIds.isEmpty()) return problem("cueIds is empty")
        val from = recordOf(op.from.source, problem)
        val to = recordOf(op.to.source, problem)
        if (from == null || to == null) return
        val fromTarget = cellTargetOf(from, op.from.target, problem)
        val toTarget = cellTargetOf(to, op.to.target, problem)
        if ((from is PressedRecord.Palette && op.from.target != null && fromTarget == null) ||
            (to is PressedRecord.Palette && op.to.target != null && toTarget == null)
        ) return
        if (from.source == to.source && fromTarget == toTarget) return problem("from and to are the same cell")
        val cues = op.cueIds.map { cueOf(it, problem) }
        if (cues.any { it == null }) return
        for (cue in cues.filterNotNull()) {
            val layers = layersOf(cue)
            val fromLayer = layers.lastOrNull { it.fills(from, fromTarget) } ?: continue
            when {
                layers.any { it.fills(to, toTarget) } ->
                    press(cue, from, listOfNotNull(fromTarget), on = false, settings = null, problem)
                op.keepSettings -> replaceInPlace(cue, layers, fromLayer, from, fromTarget, to, toTarget, problem)
                else -> {
                    press(cue, from, listOfNotNull(fromTarget), on = false, settings = null, problem)
                    press(cue, to, listOfNotNull(toTarget), on = true, settings = null, problem)
                }
            }
        }
    }

    /**
     * [from] off [fromTarget] and [to] on [toTarget] with [fromLayer]'s settings, the arriving layer
     * put where [fromLayer] was rather than on top: the cell keeps its rank. The press is still the
     * press, so a palette arriving takes its family's others off its heads (D5), and a cue the
     * replacement already reaches through a group only loses [from].
     */
    private fun replaceInPlace(
        cue: DaoCue,
        layers: List<CueLayerDto>,
        fromLayer: CueLayerDto,
        from: PressedRecord.Found,
        fromTarget: CueTargetDto?,
        to: PressedRecord.Found,
        toTarget: CueTargetDto?,
        problem: (String) -> Unit,
    ) {
        val below = layers.takeWhile { it !== fromLayer }.mapNotNullTo(HashSet()) { it.id }
        val off = decide(from, listOfNotNull(fromTarget), layers, settings = null, problem) ?: return
        val afterOff = (off.result as? LayerPress.Result.Removed)?.layers ?: layers
        // A palette's mask is its family; a Look keeps the cell's mask only where the cell was a Look's.
        val mask = when {
            to is PressedRecord.Palette -> to.family?.name
            from.source.isPalette -> null
            else -> fromLayer.attributeMask
        }
        val on = decide(to, listOfNotNull(toTarget), afterOff, fromLayer.copy(attributeMask = mask), problem) ?: return
        val next = when (val result = on.result) {
            is LayerPress.Result.Removed -> afterOff
            is LayerPress.Result.Applied -> {
                noteReleased(cue, afterOff, result.layers, to.source, on.arriving.targets)
                if (result.layers.lastOrNull() === on.arriving) {
                    val rest = result.layers.dropLast(1)
                    val at = rest.count { it.id in below }
                    rest.take(at) + on.arriving + rest.drop(at)
                } else {
                    result.layers
                }
            }
        }
        write(cue, next)
    }

    private fun scenerySet(index: Int, op: ScenerySetOp, problem: (String) -> Unit) {
        val cue = cueOf(op.cueId, problem) ?: return
        val uuid = elementUuidOf(op.elementUuid, problem) ?: return
        val current = cueSceneryOf(cue.id).map { it.toDto() }
        val item = JsonObject(
            buildMap {
                put("elementUuid", JsonPrimitive(op.elementUuid))
                put("state", op.state)
                if (op.transitionMs != null) put("transitionMs", JsonPrimitive(op.transitionMs))
            },
        )
        val at = current.indexOfFirst { it.elementUuid == uuid.toString() }
        val items = current.map(::sceneryItemOf).toMutableList<JsonElement>().also { if (at >= 0) it[at] = item else it += item }
        writeScenery(cue, index, items, uuids = emptyList(), problem)
    }

    private fun sceneryClear(index: Int, op: SceneryClearOp, problem: (String) -> Unit) {
        val cue = cueOf(op.cueId, problem) ?: return
        val uuid = elementUuidOf(op.elementUuid, problem) ?: return
        val current = cueSceneryOf(cue.id).map { it.toDto() }
        if (current.none { it.elementUuid == uuid.toString() }) return
        writeScenery(cue, index, current.filter { it.elementUuid != uuid.toString() }.map(::sceneryItemOf), uuids = emptyList(), problem)
    }

    private fun restore(index: Int, op: RestoreOp, problem: (String) -> Unit) {
        val cue = cueOf(op.cueId, problem) ?: return
        touch(cue)
        writeCueChildren(
            cue,
            adHocFx = inListOrder(op.adHocFx, { it.sortOrder }) { effect, i -> effect.copy(sortOrder = i) },
            layers = inListOrder(op.layers, { it.sortOrder }) { layer, i -> layer.copy(sortOrder = i) },
            restore = true,
            registry = registry,
        )
        // A `before` change carries its element's name and kind beside it, which a list does not take.
        val items = op.scenery.map { item -> JsonObject(item.filterKeys { it in RESTORED_SCENERY_KEYS }) }
        val uuids = op.scenery.map { (it["uuid"] as? JsonPrimitive)?.contentOrNull?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() } }
        writeScenery(cue, index, items, uuids, problem)
    }

    // ── The press ─────────────────────────────────────────────────────────────────────────────────

    /**
     * [record] pressed on [targets] in [cue], written only when the press lands on the arm the op
     * asked for: an on press over a cell already on, or an off press over one already off, leaves
     * the cue as it is.
     */
    private fun press(cue: DaoCue, record: PressedRecord.Found, targets: List<CueTargetDto>, on: Boolean, settings: CueLayerDto?, problem: (String) -> Unit) {
        val layers = layersOf(cue)
        val decided = decide(record, targets, layers, settings, problem) ?: return
        val result = decided.result
        if ((result is LayerPress.Result.Applied) != on) return
        if (result is LayerPress.Result.Applied) noteReleased(cue, layers, result.layers, record.source, decided.arriving.targets)
        write(cue, result.layers)
    }

    private fun decide(
        record: PressedRecord.Found,
        targets: List<CueTargetDto>,
        layers: List<CueLayerDto>,
        settings: CueLayerDto?,
        problem: (String) -> Unit,
    ): CueLayerPressDecision.Decided? =
        when (val decision = decideCueLayerPress(record, targets, null, layers, rules, coverage, settings)) {
            is CueLayerPressDecision.Refused -> null.also { problem(decision.message) }
            is CueLayerPressDecision.Decided -> decision
        }

    /** Every other record whose layers the press narrowed or dropped, named on the pressed [targets]. */
    private fun noteReleased(cue: DaoCue, before: List<CueLayerDto>, after: List<CueLayerDto>, pressed: LayerSource, targets: List<CueTargetDto>) {
        val kept = after.mapNotNull { layer -> layer.id?.let { it to layer.targets } }.toMap()
        val sources = before
            .filter { it.source?.uuid != pressed.uuid.toString() && it.id != null && kept[it.id] != it.targets }
            .mapNotNull { it.source }
            .distinctBy { it.uuid }
        for (source in sources) released += TimelineReleasedDto(cue.id.value, source, targets)
    }

    // ── Reads and writes ──────────────────────────────────────────────────────────────────────────

    private fun cueOf(cueId: Int, problem: (String) -> Unit): DaoCue? {
        if (cueId !in cueIds) return null.also { problem("cue $cueId has no entry in this stack") }
        return DaoCue.findById(cueId) ?: null.also { problem("cue $cueId not found") }
    }

    private fun recordOf(source: TimelineSourceRef, problem: (String) -> Unit): PressedRecord.Found? {
        val read = when (source.kind.uppercase()) {
            "LOOK" -> readPressedRecord(project, null, source.id)
            "PALETTE" -> readPressedRecord(project, source.id, null)
            else -> return null.also { problem("source.kind must be LOOK or PALETTE, not '${source.kind}'") }
        }
        return when (read) {
            is PressedRecord.Missing -> null.also { problem(read.message) }
            is PressedRecord.Found -> read
        }
    }

    /**
     * A cell's target: a palette's as named, checked; a Look's none, whatever an op names, since a
     * Look lands whole (absolute-looks plan D1).
     */
    private fun cellTargetOf(record: PressedRecord.Found, target: CueTargetDto?, problem: (String) -> Unit): CueTargetDto? {
        if (record is PressedRecord.Look || target == null) return null
        if (TargetRef.ofOrNull(target.type, target.key) == null) {
            return null.also { problem("A layer's target must be a fixture or a group: ${target.type}:${target.key}") }
        }
        return target
    }

    private fun elementUuidOf(raw: String, problem: (String) -> Unit): UUID? {
        val uuid = runCatching { UUID.fromString(raw) }.getOrNull()
        if (uuid == null || uuid !in elements) return null.also { problem("elementUuid names no stage element in this project") }
        return uuid
    }

    private fun layersOf(cue: DaoCue): List<CueLayerDto> = cue.layersInOrder().mapNotNull { it.toDto() }

    /** Remember [cue] as the batch found it, the first time an op writes it. */
    private fun touch(cue: DaoCue) {
        val id = cue.id.value
        if (id in before) return
        before[id] = snapshotOf(cue)
        if (isHeld(id)) stageBefore[id] = cue.stageContent()
    }

    private fun write(cue: DaoCue, layers: List<CueLayerDto>) {
        touch(cue)
        writeCueChildren(cue, layers = layers.mapIndexed { index, layer -> layer.copy(sortOrder = index) }, registry = registry)
    }

    private fun writeScenery(cue: DaoCue, index: Int, items: List<JsonElement>, uuids: List<UUID?>, problem: (String) -> Unit) {
        val found = mutableListOf<String>()
        val writes = parseSceneryList(items, elements, SceneryOwnerKind.CUE, "ops[$index].scenery", found)
        if (found.isNotEmpty()) return found.forEach(problem)
        touch(cue)
        replaceCueScenery(cue, writes, uuids)
    }

    // ── A layer's fields ──────────────────────────────────────────────────────────────────────────

    /** [layer] with [fields] applied — the cue editor's Looks-tab fields — every problem in [found]. */
    private fun withFields(layer: CueLayerDto, fields: JsonObject, found: MutableList<String>): CueLayerDto {
        var out = layer
        for ((key, value) in fields) {
            val primitive = value as? JsonPrimitive
            val isNull = value is JsonNull
            when (key) {
                "enabled", "stomp" -> {
                    val flag = primitive?.takeIf { !it.isString }?.booleanOrNull
                    if (flag == null) found += "fields.$key must be true or false"
                    else out = if (key == "enabled") out.copy(enabled = flag) else out.copy(stomp = flag)
                }
                "amount" -> {
                    val amount = primitive?.takeIf { !it.isString }?.doubleOrNull
                    if (amount == null || amount !in 0.0..1.0) found += "fields.amount must be between 0 and 1"
                    else out = out.copy(amount = amount)
                }
                "blendMode" -> {
                    val blend = primitive?.takeIf { it.isString }?.contentOrNull
                    val refusal = blend?.let { FxSpecCoercion.Strict.problem(blendMode = it) }
                    when {
                        blend == null -> found += "fields.blendMode must be a blend mode"
                        refusal != null -> found += "fields.blendMode: $refusal"
                        else -> out = out.copy(blendMode = blend)
                    }
                }
                "attributeMask" -> {
                    val names = primitive?.takeIf { it.isString }?.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    when {
                        isNull -> out = out.copy(attributeMask = null)
                        names == null -> found += "fields.attributeMask must be a mask or null"
                        else -> try {
                            out = out.copy(attributeMask = parseFamilies(names)?.toAttributeMask())
                        } catch (e: IllegalArgumentException) {
                            found += "fields.attributeMask: ${e.message}"
                        }
                    }
                }
                "delayMs", "intervalMs", "randomWindowMs" -> {
                    val ms = primitive?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
                    when {
                        !isNull && ms == null -> found += "fields.$key must be a whole number of milliseconds, 0 or more, or null"
                        key == "delayMs" -> out = out.copy(delayMs = ms)
                        key == "intervalMs" -> out = out.copy(intervalMs = ms)
                        else -> out = out.copy(randomWindowMs = ms)
                    }
                }
                "speedMasterUuid", "rateSpeedMasterUuid" -> {
                    val uuid = primitive?.takeIf { it.isString }?.contentOrNull?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    when {
                        !isNull && (uuid == null || uuid !in masters) -> found += "fields.$key names no speed master in this project"
                        key == "speedMasterUuid" -> out = out.copy(speedMasterUuid = uuid?.toString())
                        else -> out = out.copy(rateSpeedMasterUuid = uuid?.toString())
                    }
                }
                else -> found += "fields: unknown field '$key' (known: ${LAYER_FIELDS.joinToString()})"
            }
        }
        return out
    }

    private companion object {
        val RESTORED_SCENERY_KEYS = setOf("elementUuid", "state", "transitionMs")
        val OP_KINDS = listOf("layerOn", "layerOff", "layerSettings", "replace", "scenerySet", "sceneryClear", "restore")
        val LAYER_FIELDS = listOf(
            "enabled", "amount", "blendMode", "attributeMask", "stomp",
            "delayMs", "intervalMs", "randomWindowMs", "speedMasterUuid", "rateSpeedMasterUuid",
        )
    }
}

/** A cue's layers, effects and scenery as one value — what `before` carries and `restore` puts back. Must run inside a transaction. */
private fun snapshotOf(cue: DaoCue) = TimelineCueSnapshotDto(
    cueId = cue.id.value,
    layers = cue.layersInOrder().mapNotNull { it.toDto() },
    adHocFx = cue.adHocFxInOrder().map { it.toDto() },
    scenery = cueSceneryOf(cue.id).map { it.toDto() },
)

/** A stored change back in a scenery list's item shape, its uuid beside it. */
private fun sceneryItemOf(change: SceneryChangeDto): JsonObject = JsonObject(
    buildMap {
        put("uuid", JsonPrimitive(change.uuid))
        put("elementUuid", JsonPrimitive(change.elementUuid))
        put("state", change.state)
        change.transitionMs?.let { put("transitionMs", JsonPrimitive(it)) }
    },
)

/** Whether this layer applies [record]. */
private fun CueLayerDto.isOf(record: PressedRecord.Found): Boolean =
    source?.kind == record.source.kind.name && source.id == record.source.id

/**
 * Whether this layer fills [record]'s cell of [target]: any layer of a Look, which lands whole; a
 * palette's naming [target], or naming none for its own heads (§3.2).
 */
private fun CueLayerDto.fills(record: PressedRecord.Found, target: CueTargetDto?): Boolean =
    isOf(record) && (record is PressedRecord.Look || if (target == null) targets.isEmpty() else target in targets)

/** The op bodies are decoded strictly, so a misspelled field is named rather than ignored. */
private val OP_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

@Resource("/edit")
internal data class CueStackTimelineEditResource(val parent: CueStackTimelineResource)

@Serializable
internal data class TimelineEditRequest(val ops: List<JsonObject>, val dryRun: Boolean = false)

/** A Look or a palette, as the Timeline names one: `LOOK` or `PALETTE`, and its id. */
@Serializable
internal data class TimelineSourceRef(val kind: String, val id: Int)

/** A Look or palette column's cell: the record, and the one target it is on — none for its own heads. */
@Serializable
internal data class TimelineCellRef(val source: TimelineSourceRef, val target: CueTargetDto? = null)

@Serializable
internal data class LayerOnOp(val cueId: Int, val source: TimelineSourceRef, val target: CueTargetDto? = null, val settingsFrom: Int? = null)

@Serializable
internal data class LayerOffOp(val cueId: Int, val source: TimelineSourceRef, val target: CueTargetDto? = null)

@Serializable
internal data class LayerSettingsOp(val cueId: Int, val layerId: Int, val fields: JsonObject)

@Serializable
internal data class ReplaceOp(val cueIds: List<Int>, val from: TimelineCellRef, val to: TimelineCellRef, val keepSettings: Boolean = true)

@Serializable
internal data class ScenerySetOp(val cueId: Int, val elementUuid: String, val state: JsonObject, val transitionMs: Long? = null)

@Serializable
internal data class SceneryClearOp(val cueId: Int, val elementUuid: String)

@Serializable
internal data class RestoreOp(
    val cueId: Int,
    val layers: List<CueLayerDto>,
    val adHocFx: List<CueAdHocFxDto>,
    val scenery: List<JsonObject>,
)

@Serializable
internal data class TimelineEditProblem(
    /** The op's index in the batch. */
    val op: Int,
    val message: String,
)

@Serializable
internal data class TimelineEditRefusal(val error: String, val code: String, val problems: List<TimelineEditProblem>)

@Serializable
internal data class TimelineEditResponse(
    /** The cues the batch changed, in the order it first wrote them. */
    val changed: List<Int>,
    /** Each press's knock-on: another record taken off the pressed targets in that cue (D5). */
    val released: List<TimelineReleasedDto>,
    /** Each changed cue as the batch found it — what Undo sends back as a `restore`. */
    val before: List<TimelineCueSnapshotDto>,
    /** The held cues whose stage content changed: marked edited since GO. */
    val edited: List<Int>,
)

@Serializable
internal data class TimelineReleasedDto(val cueId: Int, val source: LayerSourceDto, val targets: List<CueTargetDto>)

@Serializable
internal data class TimelineCueSnapshotDto(
    val cueId: Int,
    val layers: List<CueLayerDto>,
    val adHocFx: List<CueAdHocFxDto>,
    val scenery: List<SceneryChangeDto>,
)
