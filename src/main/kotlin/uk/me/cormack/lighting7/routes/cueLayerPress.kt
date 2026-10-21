package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.request.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.LayerPress
import uk.me.cormack.lighting7.fx.LayerPressRules
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.parseMaskGroups
import uk.me.cormack.lighting7.models.CueLayerDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/**
 * `POST /projects/{id}/cues/{cid}/layers/press` — a palette or a Look pressed into a **stored** cue
 * (apply-model plan §3.6, D7): the cue editor's bands and picker. It applies the programmer's press
 * to the cue's layer list — per target, the family's other palettes replaced on the pressed heads,
 * the record's newest layer widened when it is the top, a second press taking it off — and writes
 * the list, sending nothing to the stage. A live cue is marked edited since GO, as any layer write
 * to it is.
 *
 * The rule is [LayerPress] over the cue's layers, the replacement set and a per-fixture palette's
 * heads [LayerPressRules] — the programmer's own, so the two stacks cannot drift — and the mask is
 * tested on the on arm only ([LayerPress.wouldRelease]), as every programmer door tests it. A
 * generic palette pressed with no targets is refused (`PALETTE_NEEDS_SELECTION`), as a busk pad is.
 * A Look is pressed whole (absolute-looks plan P1): a layer naming no targets and no mask, on when
 * the cue has none of it and every layer of it off when it has, whatever the request's targets and
 * mask say.
 */
internal fun Route.routeApiRestCueLayerPress(state: State) {
    post<PressCueLayerResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot modify cues in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val request = call.receive<CueLayerPressRequest>()
            if ((request.templateId == null) == (request.lookId == null)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Name exactly one of templateId or lookId"))
                return@withCurrentProject
            }
            // A Look press ignores its targets and mask, so only a palette's are judged.
            val families = try {
                if (request.templateId != null) parseMaskGroups(request.families) else null
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
                return@withCurrentProject
            }
            val badTargets = request.targets.filter { TargetRef.ofOrNull(it.type, it.key) == null }
            if (request.templateId != null && badTargets.isNotEmpty()) {
                val named = badTargets.joinToString { "${it.type}:${it.key}" }
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("A layer's target must be a fixture or a group: $named"))
                return@withCurrentProject
            }
            when (val outcome = pressCueLayer(state, project, resource.cueId, request, families)) {
                is CueLayerPressOutcome.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse(outcome.message))
                is CueLayerPressOutcome.Refused ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = outcome.code))
                is CueLayerPressOutcome.Moved ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(outcome.message, code = CODE_CUE_LAYERS_MOVED))
                is CueLayerPressOutcome.Pressed -> {
                    if (outcome.stageChanged) state.show.cueStackManager.holds.markEdited(resource.cueId)
                    state.show.fixtures.cueListChanged()
                    state.show.fixtures.cueStackListChanged()
                    call.respond(outcome.response)
                }
            }
        }
    }
}

internal sealed interface CueLayerPressOutcome {
    data class Pressed(val response: CueLayerPressResponse, val stageChanged: Boolean) : CueLayerPressOutcome
    data class Refused(val message: String, val code: String) : CueLayerPressOutcome
    data class NotFound(val message: String) : CueLayerPressOutcome

    /** Other writes kept moving the cue's layers between every read and write of the press. */
    data class Moved(val message: String) : CueLayerPressOutcome
}

/**
 * The press: the cue and the record read, the press decided outside any transaction — a palette
 * snapshot opens its own, and must not meet rows this one has loaded — then the layers written in a
 * second, which re-reads them and decides again if another write moved them in between.
 */
internal fun pressCueLayer(
    state: State,
    project: DaoProject,
    cueId: Int,
    request: CueLayerPressRequest,
    families: Set<PropertyMaskGroup>?,
): CueLayerPressOutcome {
    val coverage = TargetCoverage { state.show.fixtures }
    val rules = LayerPressRules({ state.show.templateRegistry }, coverage)
    val requested = request.targets.map { CueTargetDto(it.type, it.key) }
    val read: PressedRecord = transaction(state.database) {
        if (DaoCue.findById(cueId)?.takeIf { it.project.id == project.id } == null) {
            return@transaction PressedRecord.Missing("Cue not found")
        }
        readPressedRecord(project, request.templateId, request.lookId)
    }
    val record = when (read) {
        is PressedRecord.Missing -> return CueLayerPressOutcome.NotFound(read.message)
        is PressedRecord.Found -> read
    }

    repeat(PRESS_ATTEMPTS) {
        val layers = transaction(state.database) { DaoCue.findById(cueId)?.layersInOrder()?.mapNotNull { it.toDto() } }
            ?: return CueLayerPressOutcome.NotFound("Cue not found")
        val decided = when (val decision = decideCueLayerPress(record, requested, families, layers, rules, coverage)) {
            is CueLayerPressDecision.Refused -> return CueLayerPressOutcome.Refused(decision.message, decision.code)
            is CueLayerPressDecision.Decided -> decision
        }
        val written: CueLayerPressOutcome? = transaction(state.database) {
            val cue = DaoCue.findById(cueId) ?: return@transaction CueLayerPressOutcome.NotFound("Cue not found")
            if (cue.layersInOrder().mapNotNull { it.toDto() } != layers) return@transaction null
            val writesStage = state.show.cueStackManager.holds.isHeld(cueId)
            val stageBefore = if (writesStage) cue.stageContent() else null
            writeCueChildren(cue, layers = decided.result.layers.mapIndexed { index, layer -> layer.copy(sortOrder = index) })
            val stageChanged = stageBefore != null && cue.stageContent() != stageBefore
            val applied = decided.result is LayerPress.Result.Applied
            CueLayerPressOutcome.Pressed(
                CueLayerPressResponse(
                    action = if (applied) "applied" else "removed",
                    released = (decided.result as? LayerPress.Result.Applied)?.released ?: 0,
                    propertyMask = if (applied) decided.arriving.propertyMask else null,
                    cue = cue.toCueDetails(isCurrentProject = true, state.show.fixtures),
                ),
                stageChanged,
            )
        }
        if (written != null) return written
    }
    return CueLayerPressOutcome.Moved("The cue's layers kept changing under the press — try again")
}

private const val PRESS_ATTEMPTS = 3

/** 409 code: another write moved the cue's layers between every read and write of the press. */
internal const val CODE_CUE_LAYERS_MOVED = "CUE_LAYERS_MOVED"

/** What was pressed, read once — or what was not there. */
internal sealed interface PressedRecord {
    data class Missing(val message: String) : PressedRecord

    sealed interface Found : PressedRecord {
        val source: LayerSource
    }

    data class Palette(override val source: LayerSource, val family: PropertyMaskGroup?, val generic: Boolean) : Found

    data class Look(val lookId: Int, override val source: LayerSource) : Found
}

/**
 * The record a press names — a palette by [templateId] or a Look by [lookId], exactly one — read as
 * the press needs it, or [PressedRecord.Missing] for one [project] lacks. Must run inside a
 * transaction.
 */
internal fun readPressedRecord(project: DaoProject, templateId: Int?, lookId: Int?): PressedRecord {
    if (templateId != null) {
        val template = DaoTemplate.findById(templateId)?.takeIf { it.project.id == project.id }
            ?: return PressedRecord.Missing("Palette not found")
        return PressedRecord.Palette(
            LayerSource.template(template.id.value, template.uuid, template.name),
            template.familyOf(),
            template.isGenericTemplate(),
        )
    }
    val look = lookId?.let { DaoLook.findById(it) }?.takeIf { it.project.id == project.id }
        ?: return PressedRecord.Missing("Look not found")
    return PressedRecord.Look(look.id.value, look.layerSource())
}

internal sealed interface CueLayerPressDecision {
    class Refused(val message: String, val code: String) : CueLayerPressDecision

    class Decided(val arriving: CueLayerDto, val result: LayerPress.Result<CueLayerDto>) : CueLayerPressDecision
}

/**
 * The press of [record] on [requested] over a cue's [layers]: a refusal, or what the list becomes —
 * the decide step the cue editor's press and the Timeline's batch (`timeline/edit`) share, so the
 * two cannot drift. Pure but for the palette snapshots [rules] reads, which open a transaction of
 * their own or join the caller's. A Look's [requested] targets and [families] are ignored.
 *
 * [settings] is a layer whose settings the arriving one takes (stack-timeline plan D6) — its
 * enabled, mask, blend, amount, stomp, masters and timing; absent, a fresh press's defaults. The
 * widening test reads them, so a layer that already carries them is widened and keeps its own.
 */
internal fun decideCueLayerPress(
    record: PressedRecord.Found,
    requested: List<CueTargetDto>,
    families: Set<PropertyMaskGroup>?,
    layers: List<CueLayerDto>,
    rules: LayerPressRules,
    coverage: TargetCoverage,
    settings: CueLayerDto? = null,
): CueLayerPressDecision {
    val source = record.source
    val ops = CueLayerOps(rules, layers)
    val arriving: CueLayerDto
    when (record) {
        is PressedRecord.Palette -> {
            // A generic palette's rows take their targets from the press, so with none the layer
            // would assert nothing — the busk pad's refusal. A per-fixture one names its own heads.
            if (requested.isEmpty() && record.generic) {
                return CueLayerPressDecision.Refused("This palette needs a selection to press onto", CODE_TEMPLATE_NEEDS_SELECTION)
            }
            val targets = rules.pressTargets(source.uuid, requested)
            val releasing = LayerPress.wouldRelease(layers, source.uuid, targets, ops, coverage)
            if (!releasing && families != null && !maskAllows(families, record.family)) {
                return CueLayerPressDecision.Refused(templateOutsideMaskMessage(source.name, record.family, families), CODE_TEMPLATE_OUTSIDE_MASK)
            }
            arriving = CueLayerDto(templateId = source.id, targets = targets, propertyMask = record.family?.name, source = source.toDto())
        }
        // A Look lands whole: no targets and no mask, whatever the press carries (D1, D3).
        is PressedRecord.Look -> arriving = CueLayerDto(lookId = record.lookId, source = source.toDto())
    }
    val replaces = rules.familyReplacements(
        source.isTemplate,
        source.uuid,
        layers.mapNotNullTo(HashSet()) { layer -> layer.source?.takeIf { it.kind == "TEMPLATE" }?.let { UUID.fromString(it.uuid) } },
    )
    val replacesOn = if (replaces.isEmpty()) null else rules.paletteHeads(source.uuid, arriving.targets)
    val pressed = settings?.let { arriving.withSettingsOf(it) } ?: arriving
    return CueLayerPressDecision.Decided(pressed, LayerPress.press(layers, pressed, ops, coverage, replaces = replaces, replacesOn = replacesOn))
}

/** This layer carrying [from]'s settings — every field a cue layer has but its record and targets. */
internal fun CueLayerDto.withSettingsOf(from: CueLayerDto): CueLayerDto = copy(
    enabled = from.enabled,
    propertyMask = from.propertyMask,
    blendMode = from.blendMode,
    amount = from.amount,
    stomp = from.stomp,
    speedMasterUuid = from.speedMasterUuid,
    rateSpeedMasterUuid = from.rateSpeedMasterUuid,
    delayMs = from.delayMs,
    intervalMs = from.intervalMs,
    randomWindowMs = from.randomWindowMs,
)

/**
 * How [LayerPress] reads a stored cue layer. A fresh press is untimed, unmastered, unstomped and at
 * full amount, so a layer the operator has since retuned — its timing, a master, its blend — is
 * theirs, and is not widened onto new heads.
 *
 * A palette layer naming no targets lands on a per-fixture palette's heads, so a press on some of
 * them reads as covered and narrows it. A Look's layer is pressed whole ([whole]).
 */
private class CueLayerOps(
    rules: LayerPressRules,
    layers: List<CueLayerDto>,
) : LayerPress.Ops<CueLayerDto> {
    private val boundHeads = rules.boundHeads(
        layers.filter { it.templateId != null && it.targets.isEmpty() }.mapNotNull { it.sourceUuidOrNull() },
    )

    override fun sourceUuid(layer: CueLayerDto): UUID = layer.sourceUuidOrNull() ?: NO_SOURCE
    override fun targets(layer: CueLayerDto) = layer.targets
    override fun withTargets(layer: CueLayerDto, targets: List<CueTargetDto>) = layer.copy(targets = targets)
    override fun boundHeads(layer: CueLayerDto) = layer.sourceUuidOrNull()?.let { boundHeads[it] }?.takeIf { it.isNotEmpty() }
    override fun whole(layer: CueLayerDto) = layer.lookId != null

    override fun sameSettings(layer: CueLayerDto, arriving: CueLayerDto) =
        layer.enabled == arriving.enabled &&
            layer.propertyMask == arriving.propertyMask &&
            layer.blendMode == arriving.blendMode &&
            layer.amount == arriving.amount &&
            layer.stomp == arriving.stomp &&
            layer.speedMasterUuid == arriving.speedMasterUuid &&
            layer.rateSpeedMasterUuid == arriving.rateSpeedMasterUuid &&
            layer.delayMs == arriving.delayMs &&
            layer.intervalMs == arriving.intervalMs &&
            layer.randomWindowMs == arriving.randomWindowMs

    private companion object {
        /** A row read back always carries its source; this only keeps a malformed one from matching anything. */
        val NO_SOURCE: UUID = UUID(0, 0)
    }
}

private fun CueLayerDto.sourceUuidOrNull(): UUID? = source?.uuid?.let(UUID::fromString)

@Serializable
internal data class CueLayerPressRequest(
    val templateId: Int? = null,
    val lookId: Int? = null,
    /** The heads a palette press lands on; none is a per-fixture palette's own. Ignored for a Look. */
    val targets: List<CueTargetDto> = emptyList(),
    /** The selection's attribute mask (`PropertyMaskGroup` names); absent is every attribute. Ignored for a Look. */
    val families: List<String>? = null,
)

@Serializable
internal data class CueLayerPressResponse(
    /** `"applied"` or `"removed"`. */
    val action: String,
    /** The other palettes' layers the press narrowed or dropped on its heads. */
    val released: Int = 0,
    /** The arriving layer's mask — a palette's family; none for a Look. */
    val propertyMask: String? = null,
    /** Always empty: a Look press takes no mask (D3). Kept on the wire until the client stops reading it. */
    val skippedFamilies: List<String> = emptyList(),
    /** The cue as stored after the press. */
    val cue: CueDetails,
)

@Resource("/{cueId}/layers/press")
internal data class PressCueLayerResource(val parent: ProjectCuesResource, val cueId: Int)
