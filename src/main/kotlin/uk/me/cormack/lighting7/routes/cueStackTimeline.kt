package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.FxRegistry
import uk.me.cormack.lighting7.fx.AttributeFamily
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.fx.attributeFamilyForFxFamily
import uk.me.cormack.lighting7.fx.perFixtureRowTargetsOf
import uk.me.cormack.lighting7.fx.familyForAttribute
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.fx.toMaskNames
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State

/**
 * A stack's timeline (stack-timeline plan §3.5): every entry of the stack in list order, markers
 * included, with each cue's layers, ad-hoc effects, scenery and tracked scenery — the stored rows the
 * client derives the grid's columns, cells and runs from (P6). Names, fade, follow, numbers and live
 * state are the stack list's and the run frames', so they are not repeated here.
 */
internal fun Route.routeApiRestCueStackTimeline(state: State) {
    get<CueStackTimelineResource> { resource ->
        withProject(state, resource.parent.parent.projectId) { project ->
            val fixtures = if (state.isCurrentProject(project)) state.show.fixtures else null
            val timeline = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.parent.stackId) ?: return@transaction null
                if (stack.project.id != project.id) return@transaction null
                cueStackTimeline(stack, project, fixtures, state.show.fxRegistry)
            }
            if (timeline == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            } else {
                call.respond(timeline)
            }
        }
    }
}

/**
 * [stack]'s timeline, from one read per child table across all of its cues. [fixtures] is the live
 * patch when [project] is current, else null: `heads` and the families read off a fixture are the
 * live rig's, so a project that is not running answers its targets unexpanded and its families from
 * effect categories alone. Must run inside a transaction.
 */
internal fun cueStackTimeline(
    stack: DaoCueStack,
    project: DaoProject,
    fixtures: Fixtures?,
    registry: FxRegistry,
): CueStackTimelineDto {
    val entries = entriesOf(stack)
    val cueIds = entries.mapNotNull { it.cueId }.distinct()
    val cueRefs = cueIds.map { EntityID(it, DaoCues) }
    val layersByCue = if (cueIds.isEmpty()) emptyMap() else {
        DaoCueLayer.find { DaoCueLayers.cue inList cueRefs }.groupBy { it.readValues[DaoCueLayers.cue].value }
    }
    val fxByCue = if (cueIds.isEmpty()) emptyMap() else {
        DaoCueAdHocFxEntity.find { DaoCueAdHocFx.cue inList cueRefs }.groupBy { it.readValues[DaoCueAdHocFx.cue].value }
    }
    val sceneryByCue = cueSceneryByCue(cueRefs)
    val tracked = trackedSceneryOfStack(stack)
    val reads = TimelineReads(fixtures, registry, speedMastersOf(project).firstOrNull()?.uuid)

    // Each cue's children once, however many places the cue has in the stack.
    val layerDtos = HashMap<Int, List<TimelineLayerDto>>()
    val fxDtos = HashMap<Int, List<TimelineFxDto>>()
    return CueStackTimelineDto(
        stackId = stack.id.value,
        entries = entries.map { entry ->
            val cueId = entry.cueId
            if (entry.isMarker || cueId == null) {
                return@map TimelineEntryDto(entryId = entry.id.value, kind = KIND_MARKER, label = entry.displayName)
            }
            TimelineEntryDto(
                entryId = entry.id.value,
                kind = KIND_CUE,
                cueId = cueId,
                layers = layerDtos.getOrPut(cueId) {
                    // Ranked over every stored layer, so one whose source is gone keeps its place
                    // and the layers above it their ranks.
                    layersByCue[cueId].orEmpty()
                        .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
                        .mapIndexedNotNull { index, layer -> reads.layer(layer, rank = index + 1) }
                },
                fx = fxDtos.getOrPut(cueId) {
                    fxByCue[cueId].orEmpty()
                        .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
                        .map { reads.effect(it) }
                },
                scenery = sceneryByCue[cueId].orEmpty().map {
                    TimelineSceneryDto(it.element.uuid.toString(), storedParamsObject(it.stateJson), it.transition?.toMillis())
                },
                trackedScenery = tracked[entry.id.value].orEmpty().map {
                    TimelineTrackedSceneryDto(it.elementUuid, it.state, it.fromCueId)
                },
            )
        },
    )
}

/** What a timeline reads off the rig and the effect library, each Look and palette once. */
private class TimelineReads(
    private val fixtures: Fixtures?,
    private val registry: FxRegistry,
    private val master1Uuid: java.util.UUID?,
) {
    private val coverage = TargetCoverage { fixtures }
    private val lookFamilies = HashMap<Int, List<String>>()
    private val lookOwn = HashMap<Int, List<String>>()
    private val paletteFamilies = HashMap<Int, List<String>>()
    private val paletteRows = HashMap<Int, Set<CueTargetDto>?>()

    fun layer(layer: DaoCueLayer, rank: Int): TimelineLayerDto? {
        val source = layer.source ?: return null
        val look = layer.look
        val template = layer.palette
        val families = when {
            look != null -> lookFamilies.getOrPut(look.id.value) { lookFamiliesOf(look) }
            template != null -> paletteFamilies.getOrPut(template.id.value) { listOfNotNull(template.familyOf()?.name) }
            else -> emptyList()
        }
        val heads = when {
            look != null -> lookHeads(look)
            template != null -> paletteHeads(template, layer.targets)
            else -> emptyList()
        }
        return TimelineLayerDto(
            id = layer.id.value,
            source = TimelineSourceDto(source.kind.name, source.id, source.uuid.toString(), source.name, families),
            targets = layerTargets(source, layer.targets),
            heads = heads,
            sortOrder = layer.sortOrder,
            rank = rank,
            enabled = layer.enabled,
            amount = layer.amount,
            blendMode = layer.blendMode,
            attributeMask = layer.attributeMask,
            stomp = layer.stomp,
            timed = layer.delay != null || layer.interval != null || layer.randomWindow != null,
            delayMs = layer.delay?.toMillis(),
            intervalMs = layer.interval?.toMillis(),
            randomWindowMs = layer.randomWindow?.toMillis(),
            speedMasterUuid = layer.speedMasterUuid?.toString(),
            rateSpeedMasterUuid = layer.rateSpeedMasterUuid?.toString(),
        )
    }

    fun effect(row: DaoCueAdHocFxEntity): TimelineFxDto {
        val dto = row.toDto().canonicalTarget()
        val target = FxTargetsRef.ofOrNull(dto.targetType, dto.targetKey)
        val heads = headsOf(target?.items.orEmpty().map(::CueTargetDto))
        val registration = registry.getRegistration(dto.fxType)
        return TimelineFxDto(
            id = row.id.value,
            uuid = row.uuid.toString(),
            runUuid = row.runUuid?.toString(),
            fxType = dto.fxType,
            attribute = dto.attribute,
            targetType = dto.targetType,
            targetKey = dto.targetKey,
            family = fxFamilyOf(dto, heads)?.name,
            heads = heads,
            settings = comparableSettingsOf(dto, target, registration),
        )
    }

    private fun headsOf(targets: List<CueTargetDto>): List<String> = coverage.expand(targets).map { it.key }.distinct()

    /** The heads a Look layer lands on: the Look's own fixtures, since it lands whole (absolute-looks plan D1). */
    private fun lookHeads(look: DaoLook): List<String> =
        lookOwn.getOrPut(look.id.value) { headsOf(look.ownTargets(fixtures)) }

    /**
     * The heads a palette layer lands on: a generic palette's targets; a per-fixture palette's own
     * row heads, narrowed to the pressed targets where the layer names any
     * ([uk.me.cormack.lighting7.fx.LayerPressRules.paletteHeads]'s rule — a row head a target
     * covers, or the row's cells within a pressed parent).
     */
    private fun paletteHeads(template: DaoPalette, targets: List<CueTargetDto>): List<String> {
        val rows = paletteRows.getOrPut(template.id.value) {
            perFixtureRowTargetsOf(template.effect != null, template.rows.map { it.target })?.let { coverage.expand(it).toSet() }
        } ?: return headsOf(targets)
        if (targets.isEmpty()) return rows.map { it.key }
        return coverage.expand(targets)
            .flatMap { head -> if (coverage.covers(rows, head)) listOf(head) else coverage.cells(head).filter { it in rows } }
            .map { it.key }
            .distinct()
    }

    private fun lookFamiliesOf(look: DaoLook): List<String> =
        if (fixtures != null) {
            look.derivedFamilyGroups(fixtures).toMaskNames()
        } else {
            look.effects.mapNotNullTo(LinkedHashSet()) { attributeFamilyForFxFamily(it.family) }.toMaskNames()
        }

    /**
     * The family of the property an effect drives, as Record reads it (`fxFamily`): its first
     * head's property's group, else its library category's.
     */
    private fun fxFamilyOf(effect: CueAdHocFxDto, heads: List<String>): AttributeFamily? {
        val byCategory = attributeFamilyForFxFamily(effect.family)
        val attribute = effect.attribute ?: return byCategory
        val fixture = heads.firstOrNull()?.let { key -> runCatching { fixtures?.untypedGroupableFixture(key) }.getOrNull() }
            ?: return byCategory
        return familyForAttribute(fixture, attribute) ?: byCategory
    }

    /**
     * What [uk.me.cormack.lighting7.fx.comparableFxSettings] compares, read off the stored row
     * rather than a running instance: the canonical effect type, the parameters over the library's
     * defaults, an element mode only over members, the filter and step timing only where they differ
     * from the default, and only the master the effect's timing reads, master 1 spelled by its uuid.
     */
    private fun comparableSettingsOf(
        effect: CueAdHocFxDto,
        target: FxTargetsRef?,
        registration: uk.me.cormack.lighting7.fx.FxRegistration?,
    ): TimelineFxSettingsDto {
        val beat = (registration?.timingSource ?: TimingSource.BEAT) == TimingSource.BEAT
        val overMembers = target is FxTargetsRef.Selection ||
            (target is FxTargetsRef.Single && target.target is TargetRef.Group)
        val defaults = registration?.parameters.orEmpty().associate { it.name to it.defaultValue }
        return TimelineFxSettingsDto(
            fxType = registration?.id ?: effect.fxType,
            cycle = effect.cycle,
            blendMode = effect.blendMode.uppercase(),
            order = effect.order.uppercase(),
            phaseOffset = effect.phaseOffset,
            elementMode = effect.elementMode?.uppercase()?.takeIf { overMembers },
            elementFilter = effect.elementFilter?.takeUnless { it.equals("ALL", ignoreCase = true) },
            timing = effect.timing?.takeUnless { it == (registration?.defaultTiming ?: Timing.CYCLE) },
            parameters = (defaults + effect.parameters).toSortedMap(),
            speedMasterUuid = if (beat) (speedMasterUuidOrNull(effect.speedMasterUuid) ?: master1Uuid)?.toString() else null,
            rateSpeedMasterUuid = if (beat) null else speedMasterUuidOrNull(effect.rateSpeedMasterUuid)?.toString(),
            seconds = effect.seconds,
            amount = effect.amount,
            direction = effect.direction.uppercase(),
            runs = effect.runs,
            spread = effect.spread,
            wings = effect.wings,
            segments = effect.segments,
            parts = effect.parts,
        )
    }
}

private const val KIND_CUE = "CUE"
private const val KIND_MARKER = "MARKER"

@Resource("/timeline")
internal data class CueStackTimelineResource(val parent: ProjectCueStackResource)

@Serializable
internal data class CueStackTimelineDto(val stackId: Int, val entries: List<TimelineEntryDto>)

/** One row of the stack: a cue's place (`CUE`) or a marker (`MARKER`, its [label] and nothing else). */
@Serializable
internal data class TimelineEntryDto(
    val entryId: Int,
    val kind: String,
    val label: String? = null,
    val cueId: Int? = null,
    /** In layer order, bottom first; each layer carries its rank, which counts a layer whose source is gone. */
    val layers: List<TimelineLayerDto> = emptyList(),
    val fx: List<TimelineFxDto> = emptyList(),
    val scenery: List<TimelineSceneryDto> = emptyList(),
    val trackedScenery: List<TimelineTrackedSceneryDto> = emptyList(),
)

@Serializable
internal data class TimelineSourceDto(
    /** `LOOK` or `PALETTE`. */
    val kind: String,
    val id: Int,
    val uuid: String,
    val name: String,
    /** `AttributeFamily` names: a Look's from its rows and effects, a palette's its one family. */
    val families: List<String>,
)

@Serializable
internal data class TimelineLayerDto(
    val id: Int,
    val source: TimelineSourceDto,
    /** A palette layer's targets; a Look's none, since it lands whole. */
    val targets: List<CueTargetDto>,
    /** The fixture keys the layer reaches: a Look's own, a palette's targets with groups expanded, else its own rows'. */
    val heads: List<String>,
    val sortOrder: Int,
    /** Its place in the cue's stored layer order, from 1 at the bottom — a layer whose source is gone still counted. */
    val rank: Int,
    val enabled: Boolean,
    val amount: Double,
    val blendMode: String,
    val attributeMask: String?,
    val stomp: Boolean,
    /** A delay, interval or random window: the layer fires on a timer rather than at the GO. */
    val timed: Boolean,
    val delayMs: Long?,
    val intervalMs: Long?,
    val randomWindowMs: Long?,
    val speedMasterUuid: String?,
    val rateSpeedMasterUuid: String?,
)

@Serializable
internal data class TimelineFxDto(
    val id: Int,
    val uuid: String,
    val runUuid: String?,
    val fxType: String,
    val attribute: String?,
    /** The target as the desk stores it (`CueAdHocFxDto.canonicalTarget`), so a column keys on one spelling. */
    val targetType: String,
    val targetKey: String,
    /** The `AttributeFamily` of the property it drives, or null for none. */
    val family: String?,
    val heads: List<String>,
    val settings: TimelineFxSettingsDto,
)

/** `LookFxSettings` on the wire, uuids as strings: equal for two rows a GO would run alike. */
@Serializable
internal data class TimelineFxSettingsDto(
    val fxType: String,
    val cycle: Double,
    val blendMode: String,
    val order: String,
    val phaseOffset: Double,
    val elementMode: String?,
    val elementFilter: String?,
    val timing: Timing?,
    val parameters: Map<String, String>,
    val speedMasterUuid: String?,
    val rateSpeedMasterUuid: String?,
    val seconds: Boolean,
    val amount: Double,
    val direction: String,
    val runs: Int?,
    val spread: Double,
    val wings: Int,
    val segments: Int,
    val parts: Int,
)

@Serializable
internal data class TimelineSceneryDto(val elementUuid: String, val state: JsonObject, val transitionMs: Long?)

/** A state the cue shows without setting it; [fromCueId] the cue it tracks from, null for the stack's set. */
@Serializable
internal data class TimelineTrackedSceneryDto(val elementUuid: String, val state: JsonObject, val fromCueId: Int?)
