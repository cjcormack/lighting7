@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fixture.TriggerIndex
import uk.me.cormack.lighting7.fixture.CommandIndex

import uk.me.cormack.lighting7.models.CueTargetDto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.dao.with
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.inList
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureAttributeCatalogue
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.reflect.KMutableProperty1
import uk.me.cormack.lighting7.models.asDuration


// ─── State capture ──────────────────────────────────────────────────────

internal data class CapturedState(
    /**
     * Layers reconstructed from the running effects, one per Look, with a de-duplicated target list.
     *
     * Blend mode, amount and mask **cannot** be recovered from an `FxInstance` — it never carried
     * them — so they take the DTO defaults, exactly as the retired `CuePresetApplicationDto` did. A
     * stage snapshot describes the stage, not the stack; when the operator wants the stack's own
     * amounts and masks preserved they Record `TOUCHED`, which reads
     * [uk.me.cormack.lighting7.fx.ProgrammerStore.layers] directly.
     */
    val layers: List<CueLayerDto>,
    val adHocFx: List<CueAdHocFxDto>,
    val attributeAssignments: List<CueAttributeAssignmentDto>,
    /** The id of the Local effect each of [adHocFx] was captured from; null for any other FX. */
    val adHocSourceIds: List<Long?> = emptyList(),
)

/**
 * Capture active effects and Layer 4 property assignments from the FX engine.
 * Group-scoped assignments round-trip with `targetType="group"` intact when members share a
 * single composed value — see [captureCueAssignments] for the shape-preservation rules.
 */
internal fun captureCurrentState(state: State): CapturedState {
    // In stored apply order, so the ad-hoc FX are numbered in the order they fold on stage.
    val activeFx = state.show.fxEngine.let { it.inStoredApplyOrder(it.getActiveFx()) }

    // Keyed by the **source record**, Look or palette: an effect names where it came from and
    // nothing else, so one palette applied twice to different targets collapses into one layer
    // covering both — the honest reading of a *snapshot*, since the stage cannot tell you it was
    // two gestures. A Look's layer names no targets: it lands whole.
    //
    // Keyed by (kind, id) rather than by a bare int, and this is load-bearing rather than tidy.
    // A palette layer spawns effects since fx-templates D5, so the old `effect.lookId != null`
    // fork sent every one of them down the ad-hoc arm below — Record would have written a
    // palette's effect onto the cue as a loose child, silently severing the tracking that is the
    // entire point of layering a palette. The kind has to be part of the key for the same reason
    // it is part of `CueLayerDto`: id 3 as a Look and id 3 as a palette are different records.
    //
    // (kind, id) rather than the whole `LayerSource`: its `name` is part of its equality, and an
    // instance stamped before a rename holds the old label — so keying on the whole value would
    // split one record into two layers whenever a Look or palette was renamed with its effects
    // still up, which is exactly the collapse this map exists to do.
    val layerTargets = mutableMapOf<Pair<LayerSourceKind, Int>, MutableList<CueTargetDto>>()
    val adHocFx = mutableListOf<CueAdHocFxDto>()
    val adHocSourceIds = mutableListOf<Long?>()

    for (effect in activeFx) {
        // A leaving instance is on its way off the stage: whatever held it has let it go.
        if (effect.leaving) continue
        val targetType = effect.target.targetRef.discriminator
        val targetKey = effect.target.targetKey

        val source = effect.source
        if (source != null) {
            val targets = layerTargets.getOrPut(source.kind to source.id) { mutableListOf() }
            // A layer's targets are fixtures and groups, so a bound selection effect names its items.
            val named = (effect.target.targetRef as? FxTargetRef.SelectionRef)?.items?.map { CueTargetDto(it) }
                ?: listOf(CueTargetDto(type = targetType, key = targetKey))
            for (target in named) {
                if (target !in targets) targets.add(target)
            }
        } else {
            // One dynamics snapshot per row — see the same pattern in programmerCapture.kt.
            val dyn = effect.dynamics
            adHocFx.add(CueAdHocFxDto(
                targetType = targetType,
                targetKey = targetKey,
                fxType = effect.fxTypeId,
                family = recordedFxFamily(effect),
                attribute = effect.target.attribute,
                cycle = effect.timing.cycle,
                blendMode = effect.blendMode.name,
                order = dyn.order.javaClass.simpleName,
                phaseOffset = effect.storedPhaseOffset(dyn),
                elementMode = ElementMode.stored(effect.spreadsOverMembers, dyn.elementMode),
                elementFilter = if (dyn.elementFilter != ElementFilter.ALL) dyn.elementFilter.name else null,
                timing = if (dyn.timing != effect.effect.defaultTiming) dyn.timing else null,
                parameters = effect.effect.parameters,
                sortOrder = adHocFx.size,
                speedMasterUuid = effect.speedMasterUuid?.toString(),
                rateSpeedMasterUuid = effect.rateSpeedMasterUuid?.toString(),
                seconds = effect.seconds,
                amount = dyn.amount,
                direction = dyn.direction.name,
                runs = dyn.runs,
                spread = dyn.spread,
                wings = dyn.wings,
                segments = dyn.segments,
                parts = dyn.parts,
                spreadUnit = SpreadUnit.toStored(dyn.spreadUnit),
            ))
            adHocSourceIds += effect.id.takeIf { effect.isLocalFx }
        }
    }

    val layerDtos = layerTargets.entries.mapIndexed { index, (key, targets) ->
        val (kind, sourceId) = key
        // Exactly one of the two, which is what `CueLayerDto` requires on write — see
        // `layerSourceShape`.
        CueLayerDto(
            lookId = sourceId.takeIf { kind == LayerSourceKind.LOOK },
            paletteId = sourceId.takeIf { kind == LayerSourceKind.PALETTE },
            targets = targets.takeUnless { kind == LayerSourceKind.LOOK }.orEmpty(),
            sortOrder = index,
        )
    }

    val attributeAssignments = captureCueAssignments(state)

    return CapturedState(
        layers = layerDtos,
        adHocFx = adHocFx,
        attributeAssignments = attributeAssignments,
        adHocSourceIds = adHocSourceIds,
    )
}

/**
 * Layer 4 snapshot for `snapshot-from-live` / `/current-state`. Values come from the resolver
 * ([uk.me.cormack.lighting7.fx.LayerResolver.currentCueLayerState]) so HTP / LTP / crossfade
 * composition is authoritative. Each active cue's DB rows contribute `(groupKey, attribute)`
 * hints: a hint collapses to a single group row iff every member's composed value matches;
 * otherwise members fall through to per-fixture emission. Preserves operator-authored group
 * shape after surface edits (Phase 6 group-scoped `DefaultSurfaceActions.writeGroupAttribute`).
 */
private fun captureCueAssignments(state: State): List<CueAttributeAssignmentDto> {
    val engine = state.show.fxEngine
    val programmerOverlay = programmerOverlayForSnapshot(state)
    // The cues alone: a head Move in dark has moved is captured where its cues put it (D1).
    val cuesOnly = engine.cueLayer.cuesOnly().state
    val cueLayerSnapshot = if (programmerOverlay.values.isEmpty()) {
        cuesOnly
    } else {
        // The programmer sits above the cue layer, so what's on stage is the cue snapshot with
        // the programmer's entries laid over it. Reading Layer 4 alone is precisely the
        // "Record is lossy" bug: everything busked through a fader, Locate, or the programmer
        // sheet was invisible to the capture.
        HashMap(cuesOnly).apply { putAll(programmerOverlay.values) }
    }
    if (cueLayerSnapshot.isEmpty()) return emptyList()

    val activeCueIds = engine.cueLayer.activeCueIds()

    val cueGroupHints: Set<Pair<String, String>> = if (activeCueIds.isEmpty()) {
        emptySet()
    } else transaction(state.database) {
        val hints = LinkedHashSet<Pair<String, String>>()
        val cues = DaoCue.find { DaoCues.id inList activeCueIds }
            .with(DaoCue::attributeAssignments)
            .toList()
        for (cue in cues) {
            for (row in cue.attributeAssignments) {
                if (row.targetType == TargetRef.Group.TYPE) {
                    hints.add(row.targetKey to canonicalAttributeName(row.attribute))
                }
            }
        }
        hints
    }

    // Group shape can come from either side: a cue's own group rows, or a group-scoped
    // programmer write sitting on top of them.
    val groupHints = if (programmerOverlay.groupHints.isEmpty()) {
        cueGroupHints
    } else {
        LinkedHashSet(cueGroupHints).apply { addAll(programmerOverlay.groupHints) }
    }

    return captureCueAssignmentsFromSnapshot(cueLayerSnapshot, groupHints, state.show.fixtures)
}

/** The programmer's contribution to a stage snapshot: winning values plus their group hints. */
private data class ProgrammerOverlay(
    val values: Map<CueAssignmentResolver.Key, CueAssignmentResolver.AttributeValue>,
    val groupHints: Set<Pair<String, String>>,
)

/**
 * The programmer's winning property entries, keyed for overlay onto a Layer 4 snapshot.
 *
 * Empty while blind: a *stage* snapshot must describe the stage, and blind means the
 * programmer is deliberately not reaching it.
 *
 * Built from [collectProgrammerEntries] rather than walking the store directly, so the stage
 * snapshot and `programmer.record`'s own sources agree on two things they must not disagree
 * about: sideband slots that a property covers (a raw pan/tilt drag is part of the stage and
 * has to be in a stage snapshot), and which of a property entry and a covering sideband slot
 * is the newer write.
 *
 * `ALL`, not `TOUCHED`: a stage snapshot describes the rig, so mechanical hand-downs count.
 * Unmasked, because the caller masks the collapsed rows afterwards.
 */
private fun programmerOverlayForSnapshot(state: State): ProgrammerOverlay {
    val store = state.show.programmerStore
    if (store.blind || (store.size == 0 && !store.hasSidebandEntries)) {
        return ProgrammerOverlay(emptyMap(), emptySet())
    }
    // `allowElements`: a head the programmer holds is on stage, and a cue can hold it.
    val (entries, _) = collectProgrammerEntries(state, RecordSource.ALL, mask = null, allowElements = true)
    val values = HashMap<CueAssignmentResolver.Key, CueAssignmentResolver.AttributeValue>(entries.size)
    val hints = LinkedHashSet<Pair<String, String>>()
    for (entry in entries) {
        values[CueAssignmentResolver.Key.fixture(entry.fixtureKey, entry.attribute)] = entry.value
        entry.sourceGroup?.let { hints.add(it to entry.attribute) }
    }
    return ProgrammerOverlay(values, hints)
}

/** Pure snapshot-collapse pass — extracted from [captureCueAssignments] for DB-less testing. */
internal fun captureCueAssignmentsFromSnapshot(
    cueLayerSnapshot: Map<CueAssignmentResolver.Key, CueAssignmentResolver.AttributeValue>,
    groupHints: Set<Pair<String, String>>,
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
): List<CueAttributeAssignmentDto> {
    if (cueLayerSnapshot.isEmpty()) return emptyList()

    val emitted = mutableListOf<CueAttributeAssignmentDto>()
    val covered = HashSet<Pair<String, String>>() // (fixtureKey, attribute)

    for ((groupKey, attribute) in groupHints) {
        val members = try {
            fixtures.untypedGroup(groupKey).fixtures.filterIsInstance<Fixture>()
        } catch (_: IllegalStateException) { emptyList() }
        if (members.isEmpty()) continue

        val firstMember = members.first()
        val firstMemberValue = cueLayerSnapshot[CueAssignmentResolver.Key.fixture(firstMember.key, attribute)]
            ?: continue
        val uniform = members.all { member ->
            member === firstMember ||
                cueLayerSnapshot[CueAssignmentResolver.Key.fixture(member.key, attribute)] == firstMemberValue
        }
        if (!uniform) continue

        emitted.add(
            CueAttributeAssignmentDto(
                targetType = TargetRef.Group.TYPE,
                targetKey = groupKey,
                attribute = attribute,
                value = firstMemberValue.serialize(),
                sortOrder = emitted.size,
            )
        )
        for (member in members) covered.add(member.key to attribute)
    }

    val fixtureRows = cueLayerSnapshot.entries
        .filter { (key, _) -> (key.targetKey to key.attribute) !in covered }
        .sortedWith(compareBy({ it.key.targetKey }, { it.key.attribute }))
    for ((key, value) in fixtureRows) {
        emitted.add(
            CueAttributeAssignmentDto(
                targetType = TargetRef.Fixture.TYPE,
                targetKey = key.targetKey,
                attribute = key.attribute,
                value = value.serialize(),
                sortOrder = emitted.size,
            )
        )
    }

    return emitted
}

// ─── Entity helpers ─────────────────────────────────────────────────────


/**
 * Wire form of a stored cue layer, or null when the row names neither record or both.
 *
 * Nullable for the same reason [DaoCueLayer.source] is: a layer with no resolvable referent is
 * absent as far as every consumer is concerned, and the read path is not where that gets reported.
 *
 * Must run inside a transaction.
 */
internal fun DaoCueLayer.toDto(): CueLayerDto? = CueLayerDto(
    lookId = look?.id?.value,
    paletteId = palette?.id?.value,
    sortOrder = sortOrder,
    enabled = enabled,
    targets = targets,
    attributeMask = attributeMask,
    blendMode = blendMode,
    amount = amount,
    stomp = stomp,
    speedMasterUuid = speedMasterUuid?.toString(),
    rateSpeedMasterUuid = rateSpeedMasterUuid?.toString(),
    delayMs = delay?.toMillis(),
    intervalMs = interval?.toMillis(),
    randomWindowMs = randomWindow?.toMillis(),
    source = (source ?: return null).toDto(),
    id = id.value,
    uuid = uuid.toString(),
)


/**
 * DTO + health evaluated against the live patch [fixtures]. Used for REST responses where
 * dead-reference diagnostics are surfaced (Phase 6). Apply-path and snapshot callers keep
 * using [toDto] — they don't need health and shouldn't pay the lookup cost.
 */
private fun DaoCueAttributeAssignment.toDtoWithHealth(
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
): CueAttributeAssignmentDto {
    val base = toDto()
    // Only the *target* is checkable now. Until session 4 a row's value could be a `ref:{uuid}`,
    // and this also reported whether the referenced Look covered the target; with the grammar gone
    // a row's value is always a literal, so there is no second reference to validate.
    return base.copy(
        health = PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures, base.target, base.attribute, allowElements = true,
        ),
    )
}


/**
 * Convert a DaoCue entity to CueDetails API response. Property-assignment rows are tagged
 * with [AssignmentHealth] by resolving each `(targetType, targetKey, attribute)` against
 * [fixtures] — dead references surface in the UI with markers (Phase 6) rather than
 * silently dropping at apply time.
 *
 * There was a `lookRegistry` parameter beside [fixtures], for the second check a row's *value* used
 * to need: a `ref:{uuid}` was validated against the Look it named. The `ref:` grammar retired in
 * session 4, so a row's value is always a literal and its target is the only thing left to check.
 */
internal fun DaoCue.toCueDetails(
    isCurrentProject: Boolean,
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
    /** The cue's entries in show order; a list passes each from one [entrySlotsOf] for them all. */
    slots: List<EntrySlot> = entrySlotsOf(listOf(id.value))[id.value].orEmpty(),
    /** The entry the place fields describe; null describes the cue's only entry, if it has one. */
    contextEntryId: Int? = null,
): CueDetails {
    val triggerDetails = this.triggers.sortedBy { it.sortOrder }.map { trigger ->
        CueTriggerDetailDto(
            triggerType = trigger.triggerType.name,
            delayMs = trigger.delay?.toMillis(),
            intervalMs = trigger.interval?.toMillis(),
            randomWindowMs = trigger.randomWindow?.toMillis(),
            scriptId = trigger.script.id.value,
            scriptName = trigger.script.name,
            sortOrder = trigger.sortOrder,
        )
    }
    val assignmentDetails = this.attributeAssignments.sortedBy { it.sortOrder }
        .map { it.toDtoWithHealth(fixtures) }
    val slot = if (contextEntryId != null) slots.firstOrNull { it.entry.id.value == contextEntryId } else slots.singleOrNull()
    val entry = slot?.entry
    val stack = entry?.stack
    return CueDetails(
        id = this.id.value,
        name = this.name,
        layers = this.layers.sortedBy { it.sortOrder }.mapNotNull { it.toDto() },
        adHocFx = adHocFxInOrder().map { it.toDto() },
        attributeAssignments = assignmentDetails,
        triggers = triggerDetails,
        cueStackId = stack?.id?.value,
        cueStackName = stack?.name,
        entryId = entry?.id?.value,
        sortOrder = slot?.position ?: 0,
        autoAdvance = entry?.autoAdvance ?: false,
        autoAdvanceDelayMs = entry?.autoAdvanceDelay?.toMillis(),
        fadeDurationMs = this.fadeDuration?.toMillis(),
        fadeCurve = this.fadeCurve,
        cueNumber = entry?.cueNumber,
        cueNumberAuto = entry?.cueNumberAuto ?: false,
        notes = this.notes,
        cueType = CueType.STANDARD.name,
        stomp = this.stomp,
        canEdit = isCurrentProject,
        canDelete = isCurrentProject,
        scenery = cueSceneryOf(this.id).map { it.toDto() },
        trackedScenery = entry?.let(::trackedSceneryAt).orEmpty(),
        events = cueEventsOf(this.id).map { it.toDto() },
        speedMasters = this.speedMasters,
        entries = slots.map { it.toPlaceDto() },
    )
}

internal fun EntrySlot.toPlaceDto() = CueEntryPlaceDto(
    entryId = entry.id.value,
    stackId = entry.stackId,
    stackName = entry.stack.name,
    sortOrder = position,
    cueNumber = entry.cueNumber,
    cueNumberAuto = entry.cueNumberAuto,
    autoAdvance = entry.autoAdvance,
    autoAdvanceDelayMs = entry.autoAdvanceDelay?.toMillis(),
)

/**
 * A cue layer's referent, resolved for the write path: exactly one field is non-null.
 *
 * A tiny type rather than a `Pair<DaoLook?, DaoPalette?>` so the exactly-one rule is stated once
 * where it is established, instead of at each of the two assignments that consume it.
 */
internal class ResolvedLayerSource private constructor(
    val look: DaoLook?,
    val palette: DaoPalette?,
) {
    companion object {
        fun ofLook(look: DaoLook) = ResolvedLayerSource(look, null)
        fun ofPalette(palette: DaoPalette) = ResolvedLayerSource(null, palette)
    }
}

/**
 * Which record a [CueLayerDto] names, or null when it names none, both, or something deleted.
 *
 * The none/both cases go through [layerSourceShape] so this path and [DaoCueLayer.source] share one
 * verdict and one warning; the deleted case stays silent, because a layer naming a record the
 * operator has since deleted is expected rather than malformed.
 *
 * Must run inside a transaction.
 */
internal fun resolveCueLayerSource(layer: CueLayerDto): ResolvedLayerSource? {
    val lookId = layer.lookId
    val paletteId = layer.paletteId
    // A wire DTO has no id on the write path by design, so the warning names what it does have:
    // without it a malformed write logs a line no operator can trace back to a layer.
    val wellFormed = layerSourceShape(lookId, paletteId).wellFormedOrWarn {
        "from the wire (sortOrder=${layer.sortOrder}, targets=${layer.targets.joinToString { "${it.type}:${it.key}" }})"
    }
    if (!wellFormed) return null
    return if (lookId != null) {
        DaoLook.findById(lookId)?.let { ResolvedLayerSource.ofLook(it) }
    } else {
        DaoPalette.findById(paletteId!!)?.let { ResolvedLayerSource.ofPalette(it) }
    }
}

/**
 * The same resolution the other way round: a [LayerSource] already in hand back to the rows a cue
 * layer stores. Used where the layer being written came from the *programmer* rather than off the
 * wire, so its source is resolved already and only the entities are missing.
 *
 * Must run inside a transaction.
 */
internal fun resolveLayerSourceRecords(source: LayerSource): ResolvedLayerSource? = when (source.kind) {
    LayerSourceKind.LOOK -> DaoLook.findById(source.id)?.let { ResolvedLayerSource.ofLook(it) }
    LayerSourceKind.PALETTE -> DaoPalette.findById(source.id)?.let { ResolvedLayerSource.ofPalette(it) }
}

/**
 * Does this stored layer apply the same record [dto] names?
 *
 * The identity half of Record's `(source, targets)` upsert key. Comparing **both** columns rather
 * than one id is what stops a Look and a palette that happen to share an int PK from matching each
 * other — two tables, two id spaces, and Record would otherwise overwrite the wrong layer.
 *
 * Must run inside a transaction.
 */
internal fun DaoCueLayer.appliesSameSourceAs(dto: CueLayerDto): Boolean =
    look?.id?.value == dto.lookId && palette?.id?.value == dto.paletteId

/**
 * Is this stored layer the one [dto] describes — Record's whole `(source, targets)` key? A Look's
 * layer is matched on its source alone, since it names no targets (absolute-looks plan P5).
 *
 * Must run inside a transaction.
 */
internal fun DaoCueLayer.isSameLayerAs(dto: CueLayerDto): Boolean =
    appliesSameSourceAs(dto) && (dto.lookId != null || sameTargets(targets, dto.targets))

/**
 * A cue write's speed-master list, checked against [project]'s masters and the ones cue [cueId]
 * already names (null for a new cue) — every problem in one failure, its message the 400's.
 */
internal fun checkCueSpeedMasters(
    state: State,
    project: DaoProject,
    cueId: Int?,
    items: List<JsonElement>,
): Result<List<CueSpeedMasterDto>> {
    if (items.isEmpty()) return Result.success(emptyList())
    val problems = mutableListOf<String>()
    val parsed = transaction(state.database) {
        val cue = cueId?.let { DaoCue.findById(it) }?.takeIf { it.project.id == project.id }
        // No such cue: the write answers 404, not a 400 about its list.
        if (cueId != null && cue == null) return@transaction emptyList()
        parseCueSpeedMasters(items, speedMasterRefsOf(project), cue?.speedMasters.orEmpty().map { it.speedMasterUuid }, "speedMasters", problems)
    }
    return if (problems.isEmpty()) Result.success(parsed) else Result.failure(IllegalArgumentException(problems.joinToString("; ")))
}

/**
 * Reject an unrecognised `blendMode` / `distribution` / `elementMode` / `elementFilter` before it
 * reaches the `varchar(50)` that stores it, or null when every value parses.
 *
 * Message-returning rather than throwing, because [writeCueChildren] serves surfaces with three
 * different ways of saying "no" — a 400, a `ToolExecutionResult`, Record's outcome type — and only
 * the first can afford a stack trace. The one throw is a Look layer naming targets, refused by name
 * ([LookLayerTargetedException]).
 */
internal fun validateCueChildren(
    adHocFx: List<CueAdHocFxDto>,
    layers: List<CueLayerDto>,
    attributeAssignments: List<CueAttributeAssignmentDto> = emptyList(),
    registry: FxRegistry,
): String? {
    // A selection is an effect's target only (effects plan D3): a value row and a layer name a
    // fixture or a group.
    attributeAssignments.withIndex().firstOrNull { (_, row) -> TargetRef.ofOrNull(row.targetType, row.targetKey) == null }
        ?.let { (i, row) -> return "attributeAssignments[$i]: unknown target type '${row.targetType}' — expected 'fixture' or 'group'" }
    checkLookLayersUntargeted(layers)
    for (layer in layers) {
        layer.targets.firstOrNull { TargetRef.ofOrNull(it.type, it.key) == null }
            ?.let { return "A layer's target must be a fixture or a group, not '${it.type}'" }
        FxSpecCoercion.Strict.problem(blendMode = layer.blendMode)?.let { return it }
    }
    adHocFx.withIndex().firstOrNull { (_, e) -> FxTargetsRef.ofOrNull(e.targetType, e.targetKey) == null }
        ?.let { (i, e) ->
            return "adHocFx[$i]: unknown target '${e.targetType}' — expected 'fixture', 'group' or " +
                "'${FxTargetsRef.Selection.TYPE}' with a list of fixtures and groups"
        }
    for (effect in adHocFx) {
        FxSpecCoercion.Strict.problem(
            blendMode = effect.blendMode,
            order = effect.order,
            elementMode = effect.elementMode,
            elementFilter = effect.elementFilter,
            direction = effect.direction,
            amount = effect.amount,
            runs = effect.runs,
            spread = effect.spread,
            wings = effect.wings,
            segments = effect.segments,
            parts = effect.parts,
            spreadUnit = effect.spreadUnit,
            fxType = effect.fxType,
            fxSteps = registry.steps(effect.fxType),
        )?.let { return it }
        registry.fxWriteProblem(effect.fxType, effect.parameters, effect.family)?.let { return it }
    }
    return null
}

/** A cue row's identity within its cue: what it is on, never what it holds (show-cue-editing plan §3.4). */
internal data class CueRowKey(val targetType: String, val targetKey: String, val attribute: String)

internal fun cueRowKey(targetType: String, targetKey: String, attribute: String) =
    CueRowKey(targetType, targetKey, canonicalAttributeName(attribute))

internal val DaoCueAttributeAssignment.rowKey get() = cueRowKey(targetType, targetKey, attribute)

internal val CueAttributeAssignmentDto.rowKey get() = cueRowKey(targetType, targetKey, attribute)

/** A cue effect's identity within its cue: the effect, and what it runs on. */
internal data class CueFxKey(val targetType: String, val targetKey: String, val attribute: String?, val fxType: String)

internal fun cueFxKey(targetType: String, targetKey: String, attribute: String?, fxType: String) =
    CueFxKey(targetType, targetKey, attribute?.let(::canonicalAttributeName), fxType)

internal val DaoCueAdHocFxEntity.fxKey get() = cueFxKey(targetType, targetKey, attribute, fxType)

internal val CueAdHocFxDto.fxKey get() = cueFxKey(targetType, targetKey, attribute, fxType)

/**
 * A cue layer's identity within its cue: the record it applies. Both ids, because a Look and a
 * palette are two tables with two id spaces — the reason [appliesSameSourceAs] compares both.
 */
internal data class CueLayerKey(val lookId: Int?, val paletteId: Int?)

internal val DaoCueLayer.layerKey get() = CueLayerKey(look?.id?.value, palette?.id?.value)

internal val ResolvedLayerSource.layerKey get() = CueLayerKey(look?.id?.value, palette?.id?.value)

/** A script hook's identity within its cue: when it fires, and what it runs. */
internal data class CueHookKey(val triggerType: TriggerType, val scriptId: Int)

internal val DaoCueTrigger.hookKey get() = CueHookKey(triggerType, script.id.value)

/**
 * What *Update stage* sends of a cue — its own rows' values, its untimed layers and its untimed
 * effects, in their order — compared across a write to tell whether it leaves a held cue *Edited
 * since GO* (show-cue-editing plan §3.5). A row's own fade and move-in-dark, a timed layer or effect,
 * scenery and events are not in it: Update stage sends none of them. Nor is an effect's run key
 * (stack-timeline plan P4): breaking or joining a run changes nothing on stage now.
 */
internal fun DaoCue.stageContent(): List<List<Any?>> = listOf(
    attributeAssignmentsInOrder().map { it.toDto() }.map { listOf(it.targetType, it.targetKey, it.attribute, it.value) },
    layersInOrder().mapNotNull { it.toDto() }
        .filter { it.delayMs == null && it.intervalMs == null }
        .map { it.copy(sortOrder = 0, randomWindowMs = null) },
    adHocFxInOrder().map { it.toDto() }
        .filter { it.delayMs == null && it.intervalMs == null }
        .map { it.copy(sortOrder = 0, randomWindowMs = null, runUuid = null) },
)

/** A cue's stored layers, in their order. Sorted in memory: Exposed refuses to order a loaded referrer collection. */
internal fun DaoCue.layersInOrder(): List<DaoCueLayer> = layers.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

internal fun DaoCue.adHocFxInOrder(): List<DaoCueAdHocFxEntity> = adHocFx.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

/**
 * A home's FX after a write from the programmer, bottom first (FX editor plan §3.5): [stored] in its
 * stored order, the rows the write wrote ([written], in apply order) taking the places of the stored
 * ones among them, and the rest of them after everything. A row the write did not touch keeps its
 * place. Each row's [sortOrder] becomes its index, unless the order is the stored one and nothing was
 * added, when the stored numbers stand; answers the rows whose place moved.
 */
internal fun <T : Any> renumberInApplyOrder(
    stored: List<T>,
    written: List<T>,
    sortOrder: KMutableProperty1<T, Int>,
): Set<T> {
    val writtenRows = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    // A row written twice (two of the write's FX matched it) takes the place of its first.
    val queue = ArrayDeque(written.filter { writtenRows.add(it) })
    val ordered = stored.map { if (it in writtenRows) queue.removeFirst() else it } + queue
    val moved = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    for ((index, row) in ordered.withIndex()) if (stored.getOrNull(index) !== row) moved += row
    if (moved.isEmpty()) return moved
    for ((index, row) in ordered.withIndex()) if (sortOrder.get(row) != index) sortOrder.set(row, index)
    return moved
}

/**
 * [items] in their list's order: as they are when their sort orders already rise along the list,
 * else renumbered by position — so an explicit list, whatever sort orders it sends, is stored in the
 * order it was written.
 */
internal fun <T> inListOrder(items: List<T>, order: (T) -> Int, renumbered: (T, Int) -> T): List<T> =
    if (items.zipWithNext().all { (a, b) -> order(a) < order(b) }) items else items.mapIndexed { i, item -> renumbered(item, i) }

internal fun DaoCue.attributeAssignmentsInOrder(): List<DaoCueAttributeAssignment> =
    attributeAssignments.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

internal fun DaoCue.triggersInOrder(): List<DaoCueTrigger> = triggers.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

/**
 * A [CueTriggerDto]'s `triggerType` read as stored. The legacy `DELAYED` / `RECURRING` are
 * `ACTIVATION` with timing fields; anything else unknown is refused, naming the valid types.
 */
private fun normalisedTriggerType(raw: String): TriggerType = when (raw) {
    "DELAYED", "RECURRING" -> TriggerType.ACTIVATION
    else -> try {
        TriggerType.valueOf(raw)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Unknown trigger type: '$raw'. Valid types: ${TriggerType.entries.joinToString()}")
    }
}

/**
 * Make a cue's layers, ad-hoc effects, property assignments and script hooks the given lists; null
 * leaves that list alone. [created] says the cue has no children yet, so there is nothing to read.
 *
 * A child that survives the write is updated in place rather than recreated, so it keeps its `uuid`
 * and a one-value edit reads as one changed record in a sync diff (show-cue-editing plan P3, §3.4).
 * Each kind is matched by its natural key — [CueRowKey], [CueLayerKey], [CueFxKey],
 * [CueHookKey] — and [pairByKey] pairs the n-th incoming item of a key with the n-th stored one in
 * `sortOrder` order, so a key held twice pairs deterministically. Everything else about a survivor
 * is the incoming item's; the rest are inserted or deleted. `writeLookChildren` is the Look's twin.
 *
 * A layer naming a record that no longer exists, or a malformed one (both ids, or neither), is
 * dropped rather than failing the write — the rule a preset application used for a deleted preset,
 * kept when that table went: [resolveCueLayerSource] warns and returns null. A hook naming a script
 * that no longer exists is dropped the same way.
 *
 * **Throws [IllegalArgumentException] on an unrecognised effect enum or hook type** — see
 * [validateCueChildren] for why a bad `blendMode` must not be allowed to reach the column. The check
 * is here as well as in the three cue routes, which call [validateCueChildren] themselves to answer
 * a 400, so that a new write path cannot skip it: `AiTools.executeTool` catches the throw at its
 * dispatcher and turns it into a failed tool result, and Record cannot produce an unrecognised
 * value — its ad-hoc effects are serialized from typed enums, and its layers' blends were
 * canonicalised when Include put them in the programmer (`ProgrammerLayerStack.installFromCue`).
 * Record has no catch, so if that ever stops being true it surfaces as a 500, not a stored bad
 * blend. Every refusal is raised before anything is written.
 *
 * [restore] is the Timeline's Undo (stack-timeline plan P5): layers and effects are paired by their
 * `uuid` instead of their key, a new one takes the uuid it names, and an effect takes its run key —
 * so a cue is put back exactly as an edit found it.
 *
 * Answers the ad-hoc rows written, one per [adHocFx] item and in its order.
 *
 * Must be called inside a transaction.
 */
internal fun writeCueChildren(
    cue: DaoCue,
    adHocFx: List<CueAdHocFxDto>? = null,
    attributeAssignments: List<CueAttributeAssignmentDto>? = null,
    triggers: List<CueTriggerDto>? = null,
    layers: List<CueLayerDto>? = null,
    created: Boolean = false,
    restore: Boolean = false,
    registry: FxRegistry,
): List<DaoCueAdHocFxEntity> {
    val newFx = adHocFx.orEmpty()
    val newRows = attributeAssignments.orEmpty()
    validateCueChildren(newFx, layers.orEmpty(), newRows, registry)?.let { throw IllegalArgumentException(it) }
    // No cue row and no effect may name a one-shot trigger (stage-view plan session 9, D15) — every
    // problem at once, before anything is written, so the caller's transaction rolls back whole.
    // The index is queries; build it only when some name could be refused at all.
    // Nor a fixture command (fixture optics plan session 7), by the same rules and with its own code.
    val mayTrigger = newRows.any { TriggerIndex.mayRefuse(it.attribute) } || newFx.any { TriggerIndex.mayRefuse(it.attribute) }
    // A row is also refused for a level inside a command's band on a property that shares its channel.
    val mayCommand = newRows.any { CommandIndex.mayRefuseRow(it.attribute) } || newFx.any { CommandIndex.mayRefuse(it.attribute) }
    if (mayTrigger || mayCommand) {
        val refs = newRows.mapIndexed { i, a -> TriggerIndex.RowRef(a.targetType, a.targetKey, a.attribute, "attributeAssignments[$i]", a.value) } +
            newFx.mapIndexed { i, e -> TriggerIndex.RowRef(e.targetType, e.targetKey, e.attribute, "adHocFx[$i]") }
        if (mayTrigger) TriggerIndex.of(cue.project).check(refs)
        if (mayCommand) CommandIndex.of(cue.project).check(refs)
    }
    // Resolved up front, so an unknown hook type is refused before any child is touched.
    val newHooks = triggers?.mapNotNull { trigger ->
        val type = normalisedTriggerType(trigger.triggerType)
        val script = DaoScript.findById(trigger.scriptId) ?: return@mapNotNull null
        Triple(trigger, type, script)
    }

    if (layers != null) {
        val resolved = layers.mapNotNull { layer -> resolveCueLayerSource(layer)?.let { layer to it } }
        val existing = if (created) emptyList() else cue.layersInOrder()
        val pairing = if (restore) {
            pairByKey(existing, resolved, { it.uuid to it.layerKey }, { (layer, source) -> restoredUuid(layer.uuid) to source.layerKey })
        } else {
            pairByKey(existing, resolved, { it.layerKey }, { it.second.layerKey })
        }
        pairing.unmatched.forEach { it.delete() }
        for ((item, stored) in pairing.pairs) {
            val (layer, source) = item
            if (stored != null) {
                stored.take(layer)
            } else {
                DaoCueLayer.new {
                    this.cue = cue
                    this.look = source.look
                    this.palette = source.palette
                    take(layer)
                    if (restore) layer.uuid?.let { uuid = UUID.fromString(it) }
                }
            }
        }
    }
    val writtenFx = ArrayList<DaoCueAdHocFxEntity>()
    if (adHocFx != null) {
        val existing = if (created) emptyList() else cue.adHocFxInOrder()
        val incoming = inListOrder(adHocFx, { it.sortOrder }) { effect, i -> effect.copy(sortOrder = i) }
            .map { it.canonicalTarget() }
        val pairing = if (restore) {
            pairByKey(existing, incoming, { it.uuid to it.fxKey }, { restoredUuid(it.uuid) to it.fxKey })
        } else {
            pairByKey(existing, incoming, { it.fxKey }, { it.fxKey })
        }
        pairing.unmatched.forEach { it.delete() }
        for ((effect, stored) in pairing.pairs) {
            val row = stored?.apply { take(effect) } ?: DaoCueAdHocFxEntity.new {
                this.cue = cue
                targetType = effect.targetType
                targetKey = effect.targetKey
                take(effect)
                if (restore) effect.uuid?.let { uuid = UUID.fromString(it) }
            }
            if (restore) row.runUuid = effect.runUuid?.let(UUID::fromString)
            writtenFx += row
        }
    }
    if (attributeAssignments != null) {
        val pairing = pairByKey(if (created) emptyList() else cue.attributeAssignmentsInOrder(), attributeAssignments, { it.rowKey }, { it.rowKey })
        pairing.unmatched.forEach { it.delete() }
        for ((row, stored) in pairing.pairs) {
            if (stored != null) {
                stored.take(row)
            } else {
                DaoCueAttributeAssignment.new {
                    this.cue = cue
                    targetType = row.targetType
                    targetKey = row.targetKey
                    take(row)
                }
            }
        }
    }
    if (newHooks != null) {
        val pairing = pairByKey(
            if (created) emptyList() else cue.triggersInOrder(), newHooks,
            { it.hookKey }, { (_, type, script) -> CueHookKey(type, script.id.value) },
        )
        pairing.unmatched.forEach { it.delete() }
        for ((item, stored) in pairing.pairs) {
            val (trigger, type, script) = item
            if (stored != null) {
                stored.take(trigger)
            } else {
                DaoCueTrigger.new {
                    this.cue = cue
                    this.triggerType = type
                    this.script = script
                    take(trigger)
                }
            }
        }
    }
    return writtenFx
}

/** A restored child's uuid, or a fresh one — which no stored row holds — for a child that names none. */
private fun restoredUuid(uuid: String?): UUID = uuid?.let(UUID::fromString) ?: UUID.randomUUID()

/** [layer] onto this layer, but for the record [writeCueChildren] matched it on. */
internal fun DaoCueLayer.take(layer: CueLayerDto) {
    sortOrder = layer.sortOrder
    enabled = layer.enabled
    targets = layer.targets
    attributeMask = layer.attributeMask
    blendMode = layer.blendMode
    amount = layer.amount
    stomp = layer.stomp
    speedMasterUuid = speedMasterUuidOrNull(layer.speedMasterUuid)
    rateSpeedMasterUuid = speedMasterUuidOrNull(layer.rateSpeedMasterUuid)
    delay = layer.delayMs.asDuration()
    interval = layer.intervalMs.asDuration()
    randomWindow = layer.randomWindowMs.asDuration()
}

/**
 * [effect] onto this effect, but for the target [writeCueChildren] matched it on. Its `uuid` and run
 * key are the stored row's, never the write's (stack-timeline plan P2).
 */
internal fun DaoCueAdHocFxEntity.take(effect: CueAdHocFxDto) {
    fxType = effect.fxType
    family = effect.family
    attribute = effect.attribute
    cycle = effect.cycle
    blendMode = effect.blendMode
    order = effect.order
    phaseOffset = effect.phaseOffset
    elementMode = effect.elementMode
    elementFilter = effect.elementFilter
    timing = storedTiming(effect.timing)
    parameters = effect.parameters
    delay = effect.delayMs.asDuration()
    interval = effect.intervalMs.asDuration()
    randomWindow = effect.randomWindowMs.asDuration()
    sortOrder = effect.sortOrder
    // Seconds follows no master (D7), so a row on Seconds keeps none beside it.
    speedMasterUuid = if (effect.seconds) null else speedMasterUuidOrNull(effect.speedMasterUuid)
    rateSpeedMasterUuid = if (effect.seconds) null else speedMasterUuidOrNull(effect.rateSpeedMasterUuid)
    seconds = effect.seconds
    amount = effect.amount
    direction = FxSpecCoercion.Names.canonicalDirection(effect.direction)
    runs = effect.runs
    this.spread = effect.spread
    this.wings = effect.wings
    this.segments = effect.segments
    this.parts = effect.parts
    this.spreadUnit = uk.me.cormack.lighting7.fx.SpreadUnit.canonical(effect.spreadUnit)
}

/** [row] onto this row, but for the target [writeCueChildren] matched it on. */
internal fun DaoCueAttributeAssignment.take(row: CueAttributeAssignmentDto) {
    attribute = row.attribute
    value = row.value
    fadeDuration = row.fadeDurationMs.asDuration()
    sortOrder = row.sortOrder
    moveInDark = row.moveInDark
}

/** [trigger]'s timing onto this hook; its type and script are what [writeCueChildren] matched it on. */
private fun DaoCueTrigger.take(trigger: CueTriggerDto) {
    delay = trigger.delayMs.asDuration()
    interval = trigger.intervalMs.asDuration()
    randomWindow = trigger.randomWindowMs.asDuration()
    sortOrder = trigger.sortOrder
}

/**
 * Delete all child entities (layers, ad-hoc effects, property assignments, and triggers) for a cue
 * that is being deleted, with its project or on its own. A cue *edit* goes through
 * [writeCueChildren], which keeps the children that survive it.
 */
internal fun deleteCueChildren(cue: DaoCue) {
    cue.layers.forEach { it.delete() }
    cue.adHocFx.forEach { it.delete() }
    cue.attributeAssignments.forEach { it.delete() }
    cue.triggers.forEach { it.delete() }
}

/**
 * Delete everything that *points at* [cue] from outside it — its busk pads and its cue slots —
 * and return the ids of the busk pages that lost a pad, for `buskLayoutChanged`.
 *
 * Kept out of [deleteCueChildren] for the same reason the anchors are: a pad or a slot is not one
 * of the cue's children, and dies only with the cue, never with an edit of its contents. Both are
 * hand-rolled cascades — `cue_slots.cue_id` declares `CASCADE`, but SQLite does
 * not enforce it without a per-connection pragma, and a slot whose cue is gone used to 500 the
 * slot list. Must be called inside a transaction.
 */
internal fun deleteCueReferences(cue: DaoCue): Set<Int> {
    val pageIds = deleteBuskPadsReferencing(cueId = cue.id.value)
    DaoCueSlots.deleteWhere { DaoCueSlots.cue eq cue.id }
    return pageIds
}

/**
 * Resolve `elementKey` — either a full element key (`"bar-1.head-0"`) or a suffix
 * (`"head-0"`) — against [fixture]. Returns null if [fixture] isn't multi-element or
 * doesn't contain a matching element.
 */
private fun findElement(fixture: Fixture, elementKey: String): FixtureElement<*>? {
    val multi = fixture as? MultiElementFixture<*> ?: return null
    val fullKey = if (elementKey.startsWith("${fixture.key}.")) elementKey else "${fixture.key}.$elementKey"
    return multi.elements.firstOrNull { it.elementKey == fullKey }
}

/**
 * Element counterpart to [fixtureCategoryFor]. Elements aren't `Fixture`s and so have no
 * [Fixture.fixtureAttributes] of their own; their `@FixtureAttribute` metadata comes from the
 * shared [FixtureAttributeCatalogue] keyed on the element class.
 */
private fun elementCategoryFor(
    element: FixtureElement<*>,
    attribute: String,
): Pair<AttributeCategory, CompositionRule>? {
    val canonical = canonicalAttributeName(attribute)
    if (canonical.equals("position", ignoreCase = true)) {
        val pan = findElementAttribute(element, "pan") ?: return null
        findElementAttribute(element, "tilt") ?: return null
        return pan.category to pan.composition
    }
    val declared = findElementAttribute(element, canonical) ?: return null
    return declared.category to declared.composition
}

private fun findElementAttribute(element: FixtureElement<*>, name: String): Fixture.Attribute? =
    FixtureAttributeCatalogue.of(element::class).byName[name]

/**
 * The family Record files a running effect under: the step effects' own (effects plan §3.6) —
 * Slot Steps is a beam effect whatever setting it steps, Palette Steps is its palettes' family,
 * read off the property it drives (a strobe in an Intensity palette is `intensity`, not `controls`) —
 * Wave's library family on every attribute, as every other door stores it (fx-settings plan D5:
 * `fxAttributeFamily` reads the family a beam attribute gives it over that) — else
 * [categoryFromAttributeName].
 */
internal fun recordedFxFamily(effect: FxInstance): String {
    val attribute = effect.target.attribute
    return when {
        effect.fxTypeId.equals(WAVE_ID, ignoreCase = true) -> WAVE_FAMILY
        effect.fxTypeId.equals(SLOT_STEPS_ID, ignoreCase = true) -> "beam"
        effect.fxTypeId.equals(PALETTE_STEPS_ID, ignoreCase = true) ->
            PaletteAttribute.ofOrNull(attribute)?.family?.let(::fxFamilyForAttributeFamily)
                ?: categoryFromAttributeName(attribute)
        else -> categoryFromAttributeName(attribute)
    }
}

/**
 * Infer effect category from property name for from-state capture.
 */
internal fun categoryFromAttributeName(attribute: String): String {
    return when (attribute.lowercase()) {
        "dimmer" -> "intensity"
        "colour", "color", "rgbcolour" -> "colour"
        "position" -> "position"
        "zoom", "iris", "frost", "focus" -> "beam"
        else -> "controls"
    }
}


// ─── Entries ────────────────────────────────────────────────────────────

/**
 * Place [cue] in [stack] as a new cue entry (cue model plan session 1): at [position] in the
 * stack's list (0 first, markers counted; null or past the end appends), with [cueNumber] as an
 * explicit number when given and the entry's follow. Renumbers the stack's auto numbers, so an
 * unnumbered cue takes one from where it landed. Must run inside a transaction.
 *
 * Every path that adds a cue to a stack goes through this — a new cue, or one shared into another
 * stack (`add-cue`, cue model plan D1). A caller placing several cues in one gesture passes [renumber] false and renumbers once, from
 * the [cueEntryIds] it took before the first, so the new cues are labelled together — as one run
 * between their numbered neighbours — rather than each from the ones placed before it.
 */
internal fun placeCueInStack(
    stack: DaoCueStack,
    cue: DaoCue,
    cueNumber: String? = null,
    position: Int? = null,
    autoAdvance: Boolean = false,
    autoAdvanceDelay: java.time.Duration? = null,
    renumber: Boolean = true,
): DaoCueStackEntry {
    val before = if (renumber) cueEntryIds(stack) else emptyList()
    cueNumber?.takeIf { it.isNotEmpty() }?.let { releaseAutoNumber(stack, it, exceptEntryId = -1) }
    val existing = entriesOf(stack)
    val entry = DaoCueStackEntry.new {
        this.stack = stack
        this.cue = cue
        kind = CueStackEntryKind.CUE.name
        this.cueNumber = cueNumber?.takeIf { it.isNotEmpty() }
        cueNumberAuto = false
        this.autoAdvance = autoAdvance
        this.autoAdvanceDelay = autoAdvanceDelay
        ordinal = 0.0
    }
    val at = position?.coerceIn(0, existing.size) ?: existing.size
    writeEntryOrder(existing.take(at) + entry + existing.drop(at), fresh = listOf(entry))
    if (renumber) renumberAutoEntries(stack, before)
    return entry
}

/**
 * Why explicit [number] cannot go on an entry of [stack] other than [exceptEntryId] — another cue
 * entry there already holds it explicitly — or null. An auto number is no clash: [setEntryNumber]
 * and [placeCueInStack] take it over. Must run inside a transaction.
 */
internal fun explicitNumberClash(stack: DaoCueStack, number: String, exceptEntryId: Int? = null): String? =
    "Cue number $number is already used in ${stack.name}".takeIf {
        entriesOf(stack).any { e ->
            e.id.value != exceptEntryId && !e.isMarker && !e.cueNumberAuto && number.equals(e.cueNumber, ignoreCase = true)
        }
    }

/**
 * Set [entry]'s number as an operator edit: typed, it becomes explicit, released first from any
 * auto-numbered sibling that holds it; blank hands the entry back to the auto scheme, which fills
 * it in again from position. A number edit moves nothing, so every other auto entry keeps its
 * number (D11). `""` is normalised to null so the unique index and the "is it numbered?" checks
 * agree on what blank means. Must run inside a transaction.
 */
internal fun setEntryNumber(entry: DaoCueStackEntry, incoming: String?) {
    val next = incoming?.takeIf { it.isNotEmpty() }
    val stack = entry.stack
    releaseAutoNumber(stack, next, entry.id.value)
    entry.cueNumber = next
    entry.cueNumberAuto = false
    renumberAutoEntries(stack, cueEntryIds(stack))
}

/**
 * Why a write read as a raw [JsonObject] names a field it does not take, or null. A partial write
 * reads only the keys it knows, so without this an old field name (terminology plan P5) would be a
 * silent 200 that changed nothing.
 */
internal fun unknownFieldsProblem(body: JsonObject, known: Set<String>): String? =
    (body.keys - known).takeIf { it.isNotEmpty() }?.let { "Unknown field(s): ${it.joinToString()}" }
