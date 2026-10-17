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
 * generic palette pressed with no targets is refused (`PALETTE_NEEDS_SELECTION`), as a busk pad is;
 * a Look pressed with none goes on as a layer of its own fixtures — a cue layer naming no targets —
 * unless its effects take the targets (`LOOK_NEEDS_SELECTION`), decided arm first by
 * [resolveLookPress] as `/looks/{id}/toggle` and the busk pad decide it.
 */
internal fun Route.routeApiRestCueLayerPress(state: State) {
    post<PressCueLayerResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot modify cues in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val request = call.receive<CueLayerPressRequest>()
            val families = try {
                parseMaskGroups(request.families)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
                return@withCurrentProject
            }
            if ((request.templateId == null) == (request.lookId == null)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Name exactly one of templateId or lookId"))
                return@withCurrentProject
            }
            val badTargets = request.targets.filter { TargetRef.ofOrNull(it.type, it.key) == null }
            if (badTargets.isNotEmpty()) {
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
        val templateId = request.templateId
        if (templateId != null) {
            val template = DaoTemplate.findById(templateId)?.takeIf { it.project.id == project.id }
                ?: return@transaction PressedRecord.Missing("Palette not found")
            PressedRecord.Palette(
                LayerSource.template(template.id.value, template.uuid, template.name),
                template.familyOf(),
                template.isGenericTemplate(),
            )
        } else {
            val look = DaoLook.findById(request.lookId!!)?.takeIf { it.project.id == project.id }
                ?: return@transaction PressedRecord.Missing("Look not found")
            PressedRecord.Look(look.id.value, look.toggleSource(state.show.fixtures))
        }
    }
    val record = when (read) {
        is PressedRecord.Missing -> return CueLayerPressOutcome.NotFound(read.message)
        is PressedRecord.Found -> read
    }

    repeat(PRESS_ATTEMPTS) {
        val layers = transaction(state.database) { DaoCue.findById(cueId)?.layersInOrder()?.mapNotNull { it.toDto() } }
            ?: return CueLayerPressOutcome.NotFound("Cue not found")
        val decided = when (val decision = decide(record, requested, families, layers, rules, coverage)) {
            is Decision.Refused -> return decision.outcome
            is Decision.Decided -> decision
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
                    skippedFamilies = if (applied) decided.skippedFamilies else emptyList(),
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
private sealed interface PressedRecord {
    data class Missing(val message: String) : PressedRecord

    sealed interface Found : PressedRecord {
        val source: LayerSource
    }

    data class Palette(override val source: LayerSource, val family: PropertyMaskGroup?, val generic: Boolean) : Found

    data class Look(val lookId: Int, val toggle: LookToggleSource) : Found {
        override val source get() = toggle.source
    }
}

private sealed interface Decision {
    class Refused(val outcome: CueLayerPressOutcome.Refused) : Decision

    class Decided(val arriving: CueLayerDto, val result: LayerPress.Result<CueLayerDto>, val skippedFamilies: List<String>) : Decision
}

/** The press over [layers]: a refusal, or what the list becomes. */
private fun decide(
    record: PressedRecord.Found,
    requested: List<CueTargetDto>,
    families: Set<PropertyMaskGroup>?,
    layers: List<CueLayerDto>,
    rules: LayerPressRules,
    coverage: TargetCoverage,
): Decision {
    val source = record.source
    val ops = CueLayerOps(rules, layers, (record as? PressedRecord.Look)?.let { it.source.uuid to it.toggle.ownTargets })
    val arriving: CueLayerDto
    var skippedFamilies = emptyList<String>()
    when (record) {
        is PressedRecord.Palette -> {
            // A generic palette's rows take their targets from the press, so with none the layer
            // would assert nothing — the busk pad's refusal. A per-fixture one names its own heads.
            if (requested.isEmpty() && record.generic) {
                return Decision.Refused(CueLayerPressOutcome.Refused("This palette needs a selection to press onto", CODE_TEMPLATE_NEEDS_SELECTION))
            }
            val targets = rules.pressTargets(source.uuid, requested)
            val releasing = LayerPress.wouldRelease(layers, source.uuid, targets, ops, coverage)
            if (!releasing && families != null && !maskAllows(families, record.family)) {
                return Decision.Refused(
                    CueLayerPressOutcome.Refused(templateOutsideMaskMessage(source.name, record.family, families), CODE_TEMPLATE_OUTSIDE_MASK),
                )
            }
            arriving = CueLayerDto(templateId = source.id, targets = targets, propertyMask = record.family?.name, source = source.toDto())
        }
        is PressedRecord.Look -> {
            // With no layer of it naming none, a press naming no targets over layers already on
            // every one of its own fixtures takes them off, as the programmer's press would.
            val own = record.toggle.ownTargets
            if (requested.isEmpty() && own.isNotEmpty() &&
                !LayerPress.wouldRelease(layers, source.uuid, emptyList(), ops, coverage) &&
                LayerPress.wouldRelease(layers, source.uuid, own, ops, coverage)
            ) {
                val arriving = CueLayerDto(lookId = record.lookId, targets = own, source = source.toDto())
                return Decision.Decided(arriving, LayerPress.press(layers, arriving, ops, coverage), emptyList())
            }
            // A cue keeps a press naming no targets naming none — the Look's own fixtures, which
            // follow the Look as it is edited.
            val resolved = resolveLookPress(requested, record.toggle, families, keepUntargeted = true) {
                LayerPress.wouldRelease(layers, source.uuid, it, ops, coverage)
            }
            val ready = when (resolved) {
                is LookPressResolution.Refused -> return Decision.Refused(CueLayerPressOutcome.Refused(resolved.message, resolved.code))
                is LookPressResolution.Ready -> resolved
            }
            skippedFamilies = ready.skippedFamilies
            arriving = CueLayerDto(lookId = record.lookId, targets = ready.targets, propertyMask = ready.propertyMask, source = source.toDto())
        }
    }
    val replaces = rules.familyReplacements(
        source.isTemplate,
        source.uuid,
        layers.mapNotNullTo(HashSet()) { layer -> layer.source?.takeIf { it.kind == "TEMPLATE" }?.let { UUID.fromString(it.uuid) } },
    )
    val replacesOn = if (replaces.isEmpty()) null else rules.paletteHeads(source.uuid, arriving.targets)
    return Decision.Decided(arriving, LayerPress.press(layers, arriving, ops, coverage, replaces = replaces, replacesOn = replacesOn), skippedFamilies)
}

/**
 * How [LayerPress] reads a stored cue layer. A fresh press is untimed, unmastered, unstomped and at
 * full amount, so a layer the operator has since retuned — its timing, a master, its blend — is
 * theirs, and is not widened onto new heads.
 *
 * A layer naming no targets lands on a per-fixture palette's heads, or on the pressed Look's own
 * fixtures ([pressedLook]), so a press on some of them reads as covered and narrows it.
 */
private class CueLayerOps(
    rules: LayerPressRules,
    layers: List<CueLayerDto>,
    pressedLook: Pair<UUID, List<CueTargetDto>>?,
) : LayerPress.Ops<CueLayerDto> {
    private val boundHeads = rules.boundHeads(
        layers.filter { it.templateId != null && it.targets.isEmpty() }.mapNotNull { it.sourceUuidOrNull() },
    ) + listOfNotNull(pressedLook)

    override fun sourceUuid(layer: CueLayerDto): UUID = layer.sourceUuidOrNull() ?: NO_SOURCE
    override fun targets(layer: CueLayerDto) = layer.targets
    override fun withTargets(layer: CueLayerDto, targets: List<CueTargetDto>) = layer.copy(targets = targets)
    override fun boundHeads(layer: CueLayerDto) = layer.sourceUuidOrNull()?.let { boundHeads[it] }?.takeIf { it.isNotEmpty() }

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
    /** The heads the press lands on; none is a per-fixture palette's own, or a Look's own fixtures. */
    val targets: List<CueTargetDto> = emptyList(),
    /** The selection's attribute mask (`PropertyMaskGroup` names); absent is every attribute. */
    val families: List<String>? = null,
)

@Serializable
internal data class CueLayerPressResponse(
    /** `"applied"` or `"removed"`. */
    val action: String,
    /** The other palettes' layers the press narrowed or dropped on its heads. */
    val released: Int = 0,
    /** The arriving layer's mask — a palette's family, a Look's `mask ∩ its families`. */
    val propertyMask: String? = null,
    /** The Look's families the mask left out, as the Look toggle answers them. */
    val skippedFamilies: List<String> = emptyList(),
    /** The cue as stored after the press. */
    val cue: CueDetails,
)

@Resource("/{cueId}/layers/press")
internal data class PressCueLayerResource(val parent: ProjectCuesResource, val cueId: Int)
