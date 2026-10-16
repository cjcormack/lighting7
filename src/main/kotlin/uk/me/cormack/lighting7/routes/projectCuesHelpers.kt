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
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixturePropertyCatalogue
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State
import java.time.Duration
import uk.me.cormack.lighting7.models.asDuration

private val logger = LoggerFactory.getLogger("projectCues")

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
    val adHocEffects: List<CueAdHocEffectDto>,
    val propertyAssignments: List<CuePropertyAssignmentDto>,
)

/**
 * Capture active effects and Layer 4 property assignments from the FX engine.
 * Group-scoped assignments round-trip with `targetType="group"` intact when members share a
 * single composed value — see [captureCueAssignments] for the shape-preservation rules.
 */
internal fun captureCurrentState(state: State): CapturedState {
    val activeEffects = state.show.fxEngine.getActiveEffects()

    // Keyed by the **source record**, Look or template: an effect names where it came from and
    // nothing else, so one record applied twice to different targets collapses into one layer
    // covering both — the honest reading of a *snapshot*, since the stage cannot tell you it was
    // two gestures.
    //
    // Keyed by (kind, id) rather than by a bare int, and this is load-bearing rather than tidy.
    // A template layer spawns effects since fx-templates D5, so the old `effect.lookId != null`
    // fork sent every one of them down the ad-hoc arm below — Record would have written a
    // template's effect onto the cue as a loose child, silently severing the tracking that is the
    // entire point of layering a template. The kind has to be part of the key for the same reason
    // it is part of `CueLayerDto`: id 3 as a Look and id 3 as a template are different records.
    //
    // (kind, id) rather than the whole `LayerSource`: its `name` is part of its equality, and an
    // instance stamped before a rename holds the old label — so keying on the whole value would
    // split one record into two layers whenever a Look or template was renamed with its effects
    // still up, which is exactly the collapse this map exists to do.
    val layerTargets = mutableMapOf<Pair<LayerSourceKind, Int>, MutableList<CueTargetDto>>()
    val adHocEffects = mutableListOf<CueAdHocEffectDto>()

    for (effect in activeEffects) {
        val targetType = if (effect.isGroupEffect) "group" else "fixture"
        val targetKey = effect.target.targetKey

        val source = effect.source
        if (source != null) {
            val targets = layerTargets.getOrPut(source.kind to source.id) { mutableListOf() }
            val target = CueTargetDto(type = targetType, key = targetKey)
            if (target !in targets) {
                targets.add(target)
            }
        } else {
            // One dynamics snapshot per row — see the same pattern in programmerCapture.kt.
            val dyn = effect.dynamics
            adHocEffects.add(CueAdHocEffectDto(
                targetType = targetType,
                targetKey = targetKey,
                effectType = effect.effectTypeId,
                category = categoryFromPropertyName(effect.target.propertyName),
                propertyName = effect.target.propertyName,
                beatDivision = effect.timing.beatDivision,
                blendMode = effect.blendMode.name,
                distribution = dyn.distributionStrategy.javaClass.simpleName,
                phaseOffset = dyn.phaseOffset,
                elementMode = if (effect.isGroupEffect) dyn.elementMode.name else null,
                elementFilter = if (dyn.elementFilter != ElementFilter.ALL) dyn.elementFilter.name else null,
                stepTiming = if (dyn.stepTiming != effect.effect.defaultStepTiming) dyn.stepTiming else null,
                parameters = effect.effect.parameters,
                speedMasterUuid = effect.speedMasterUuid?.toString(),
                rateSpeedMasterUuid = effect.rateSpeedMasterUuid?.toString(),
                seconds = effect.seconds,
                size = dyn.size,
                direction = dyn.direction.name,
                nShot = dyn.nShot,
                spread = dyn.spread,
                wings = dyn.wings,
                blocks = dyn.blocks,
                groups = dyn.groups,
            ))
        }
    }

    val layerDtos = layerTargets.entries.mapIndexed { index, (key, targets) ->
        val (kind, sourceId) = key
        // Exactly one of the two, which is what `CueLayerDto` requires on write — see
        // `layerSourceShape`.
        CueLayerDto(
            lookId = sourceId.takeIf { kind == LayerSourceKind.LOOK },
            templateId = sourceId.takeIf { kind == LayerSourceKind.TEMPLATE },
            targets = targets,
            sortOrder = index,
        )
    }

    val propertyAssignments = captureCueAssignments(state)

    return CapturedState(
        layers = layerDtos,
        adHocEffects = adHocEffects,
        propertyAssignments = propertyAssignments,
    )
}

/**
 * Layer 4 snapshot for `snapshot-from-live` / `/current-state`. Values come from the resolver
 * ([uk.me.cormack.lighting7.fx.LayerResolver.currentCueLayerState]) so HTP / LTP / crossfade
 * composition is authoritative. Each active cue's DB rows contribute `(groupKey, propertyName)`
 * hints: a hint collapses to a single group row iff every member's composed value matches;
 * otherwise members fall through to per-fixture emission. Preserves operator-authored group
 * shape after surface edits (Phase 6 group-scoped `DefaultSurfaceActions.writeGroupProperty`).
 */
private fun captureCueAssignments(state: State): List<CuePropertyAssignmentDto> {
    val engine = state.show.fxEngine
    val programmerOverlay = programmerOverlayForSnapshot(state)
    val cueLayerSnapshot = if (programmerOverlay.values.isEmpty()) {
        engine.layerResolver.currentCueLayerState
    } else {
        // The programmer sits above the cue layer, so what's on stage is the cue snapshot with
        // the programmer's entries laid over it. Reading Layer 4 alone is precisely the
        // "Record is lossy" bug: everything busked through a fader, Locate, or the programmer
        // sheet was invisible to the capture.
        HashMap(engine.layerResolver.currentCueLayerState).apply { putAll(programmerOverlay.values) }
    }
    if (cueLayerSnapshot.isEmpty()) return emptyList()

    val activeCueIds = engine.cueLayer.activeCueIds()

    val cueGroupHints: Set<Pair<String, String>> = if (activeCueIds.isEmpty()) {
        emptySet()
    } else transaction(state.database) {
        val hints = LinkedHashSet<Pair<String, String>>()
        val cues = DaoCue.find { DaoCues.id inList activeCueIds }
            .with(DaoCue::propertyAssignments)
            .toList()
        for (cue in cues) {
            for (row in cue.propertyAssignments) {
                if (row.targetType == TargetRef.Group.TYPE) {
                    hints.add(row.targetKey to canonicalPropertyName(row.propertyName))
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
    val values: Map<CueAssignmentResolver.Key, CueAssignmentResolver.PropertyValue>,
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
    val values = HashMap<CueAssignmentResolver.Key, CueAssignmentResolver.PropertyValue>(entries.size)
    val hints = LinkedHashSet<Pair<String, String>>()
    for (entry in entries) {
        values[CueAssignmentResolver.Key.fixture(entry.fixtureKey, entry.propertyName)] = entry.value
        entry.sourceGroup?.let { hints.add(it to entry.propertyName) }
    }
    return ProgrammerOverlay(values, hints)
}

/** Pure snapshot-collapse pass — extracted from [captureCueAssignments] for DB-less testing. */
internal fun captureCueAssignmentsFromSnapshot(
    cueLayerSnapshot: Map<CueAssignmentResolver.Key, CueAssignmentResolver.PropertyValue>,
    groupHints: Set<Pair<String, String>>,
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
): List<CuePropertyAssignmentDto> {
    if (cueLayerSnapshot.isEmpty()) return emptyList()

    val emitted = mutableListOf<CuePropertyAssignmentDto>()
    val covered = HashSet<Pair<String, String>>() // (fixtureKey, propertyName)

    for ((groupKey, propertyName) in groupHints) {
        val members = try {
            fixtures.untypedGroup(groupKey).fixtures.filterIsInstance<Fixture>()
        } catch (_: IllegalStateException) { emptyList() }
        if (members.isEmpty()) continue

        val firstMember = members.first()
        val firstMemberValue = cueLayerSnapshot[CueAssignmentResolver.Key.fixture(firstMember.key, propertyName)]
            ?: continue
        val uniform = members.all { member ->
            member === firstMember ||
                cueLayerSnapshot[CueAssignmentResolver.Key.fixture(member.key, propertyName)] == firstMemberValue
        }
        if (!uniform) continue

        emitted.add(
            CuePropertyAssignmentDto(
                targetType = TargetRef.Group.TYPE,
                targetKey = groupKey,
                propertyName = propertyName,
                value = firstMemberValue.serialize(),
                sortOrder = emitted.size,
            )
        )
        for (member in members) covered.add(member.key to propertyName)
    }

    val fixtureRows = cueLayerSnapshot.entries
        .filter { (key, _) -> (key.targetKey to key.propertyName) !in covered }
        .sortedWith(compareBy({ it.key.targetKey }, { it.key.propertyName }))
    for ((key, value) in fixtureRows) {
        emitted.add(
            CuePropertyAssignmentDto(
                targetType = TargetRef.Fixture.TYPE,
                targetKey = key.targetKey,
                propertyName = key.propertyName,
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
    templateId = template?.id?.value,
    sortOrder = sortOrder,
    enabled = enabled,
    targets = targets,
    propertyMask = propertyMask,
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
)


/**
 * DTO + health evaluated against the live patch [fixtures]. Used for REST responses where
 * dead-reference diagnostics are surfaced (Phase 6). Apply-path and snapshot callers keep
 * using [toDto] — they don't need health and shouldn't pay the lookup cost.
 */
private fun DaoCuePropertyAssignment.toDtoWithHealth(
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
): CuePropertyAssignmentDto {
    val base = toDto()
    // Only the *target* is checkable now. Until session 4 a row's value could be a `ref:{uuid}`,
    // and this also reported whether the referenced Look covered the target; with the grammar gone
    // a row's value is always a literal, so there is no second reference to validate.
    return base.copy(
        health = PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures, base.target, base.propertyName, allowElements = true,
        ),
    )
}


/**
 * Convert a DaoCue entity to CueDetails API response. Property-assignment rows are tagged
 * with [AssignmentHealth] by resolving each `(targetType, targetKey, propertyName)` against
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
    /** The cue's entry and position; a list passes each from one [soleEntrySlotsOf] for them all. */
    slot: EntrySlot? = soleEntrySlotsOf(listOf(id.value))[id.value],
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
    val assignmentDetails = this.propertyAssignments.sortedBy { it.sortOrder }
        .map { it.toDtoWithHealth(fixtures) }
    // A cue's place is its entry's. Every cue has one until sharing lands (cue model plan P4), and
    // these fields describe it; the plan's session 4 lists every entry instead.
    val entry = slot?.entry
    val stack = entry?.stack
    return CueDetails(
        id = this.id.value,
        name = this.name,
        layers = this.layers.sortedBy { it.sortOrder }.mapNotNull { it.toDto() },
        adHocEffects = this.adHocEffects.sortedBy { it.sortOrder }.map { it.toDto() },
        propertyAssignments = assignmentDetails,
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
    )
}

/**
 * A cue layer's referent, resolved for the write path: exactly one field is non-null.
 *
 * A tiny type rather than a `Pair<DaoLook?, DaoTemplate?>` so the exactly-one rule is stated once
 * where it is established, instead of at each of the two assignments that consume it.
 */
internal class ResolvedLayerSource private constructor(
    val look: DaoLook?,
    val template: DaoTemplate?,
) {
    companion object {
        fun ofLook(look: DaoLook) = ResolvedLayerSource(look, null)
        fun ofTemplate(template: DaoTemplate) = ResolvedLayerSource(null, template)
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
    val templateId = layer.templateId
    // A wire DTO has no id on the write path by design, so the warning names what it does have:
    // without it a malformed write logs a line no operator can trace back to a layer.
    val wellFormed = layerSourceShape(lookId, templateId).wellFormedOrWarn {
        "from the wire (sortOrder=${layer.sortOrder}, targets=${layer.targets.joinToString { "${it.type}:${it.key}" }})"
    }
    if (!wellFormed) return null
    return if (lookId != null) {
        DaoLook.findById(lookId)?.let { ResolvedLayerSource.ofLook(it) }
    } else {
        DaoTemplate.findById(templateId!!)?.let { ResolvedLayerSource.ofTemplate(it) }
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
    LayerSourceKind.TEMPLATE -> DaoTemplate.findById(source.id)?.let { ResolvedLayerSource.ofTemplate(it) }
}

/**
 * Does this stored layer apply the same record [dto] names?
 *
 * The identity half of Record's `(source, targets)` upsert key. Comparing **both** columns rather
 * than one id is what stops a Look and a template that happen to share an int PK from matching each
 * other — two tables, two id spaces, and Record would otherwise overwrite the wrong layer.
 *
 * Must run inside a transaction.
 */
internal fun DaoCueLayer.appliesSameSourceAs(dto: CueLayerDto): Boolean =
    look?.id?.value == dto.lookId && template?.id?.value == dto.templateId

/**
 * Reject an unrecognised `blendMode` / `distribution` / `elementMode` / `elementFilter` before it
 * reaches the `varchar(50)` that stores it, or null when every value parses.
 *
 * Message-returning rather than throwing, because [writeCueChildren] serves surfaces with three
 * different ways of saying "no" — a 400, a `ToolExecutionResult`, Record's outcome type — and only
 * the first can afford a stack trace.
 */
internal fun validateCueChildren(
    adHocEffects: List<CueAdHocEffectDto>,
    layers: List<CueLayerDto>,
): String? {
    for (layer in layers) {
        EffectSpecCoercion.Strict.problem(blendMode = layer.blendMode)?.let { return it }
    }
    for (effect in adHocEffects) {
        EffectSpecCoercion.Strict.problem(
            blendMode = effect.blendMode,
            distribution = effect.distribution,
            elementMode = effect.elementMode,
            elementFilter = effect.elementFilter,
            direction = effect.direction,
            size = effect.size,
            nShot = effect.nShot,
            spread = effect.spread,
            wings = effect.wings,
            blocks = effect.blocks,
            groups = effect.groups,
        )?.let { return it }
    }
    return null
}

/** A cue row's identity within its cue: what it is on, never what it holds (show-cue-editing plan §3.4). */
internal data class CueRowKey(val targetType: String, val targetKey: String, val propertyName: String)

internal fun cueRowKey(targetType: String, targetKey: String, propertyName: String) =
    CueRowKey(targetType, targetKey, canonicalPropertyName(propertyName))

internal val DaoCuePropertyAssignment.rowKey get() = cueRowKey(targetType, targetKey, propertyName)

internal val CuePropertyAssignmentDto.rowKey get() = cueRowKey(targetType, targetKey, propertyName)

/** A cue effect's identity within its cue: the effect, and what it runs on. */
internal data class CueEffectKey(val targetType: String, val targetKey: String, val propertyName: String?, val effectType: String)

internal fun cueEffectKey(targetType: String, targetKey: String, propertyName: String?, effectType: String) =
    CueEffectKey(targetType, targetKey, propertyName?.let(::canonicalPropertyName), effectType)

internal val DaoCueAdHocEffect.effectKey get() = cueEffectKey(targetType, targetKey, propertyName, effectType)

internal val CueAdHocEffectDto.effectKey get() = cueEffectKey(targetType, targetKey, propertyName, effectType)

/**
 * A cue layer's identity within its cue: the record it applies. Both ids, because a Look and a
 * template are two tables with two id spaces — the reason [appliesSameSourceAs] compares both.
 */
internal data class CueLayerKey(val lookId: Int?, val templateId: Int?)

internal val DaoCueLayer.layerKey get() = CueLayerKey(look?.id?.value, template?.id?.value)

internal val ResolvedLayerSource.layerKey get() = CueLayerKey(look?.id?.value, template?.id?.value)

/** A script hook's identity within its cue: when it fires, and what it runs. */
internal data class CueHookKey(val triggerType: TriggerType, val scriptId: Int)

internal val DaoCueTrigger.hookKey get() = CueHookKey(triggerType, script.id.value)

/** A cue's stored layers, in their order. Sorted in memory: Exposed refuses to order a loaded referrer collection. */
internal fun DaoCue.layersInOrder(): List<DaoCueLayer> = layers.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

internal fun DaoCue.adHocEffectsInOrder(): List<DaoCueAdHocEffect> = adHocEffects.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

internal fun DaoCue.propertyAssignmentsInOrder(): List<DaoCuePropertyAssignment> =
    propertyAssignments.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

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
 * Each kind is matched by its natural key — [CueRowKey], [CueLayerKey], [CueEffectKey],
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
 * Must be called inside a transaction.
 */
internal fun writeCueChildren(
    cue: DaoCue,
    adHocEffects: List<CueAdHocEffectDto>? = null,
    propertyAssignments: List<CuePropertyAssignmentDto>? = null,
    triggers: List<CueTriggerDto>? = null,
    layers: List<CueLayerDto>? = null,
    created: Boolean = false,
) {
    val newEffects = adHocEffects.orEmpty()
    val newRows = propertyAssignments.orEmpty()
    validateCueChildren(newEffects, layers.orEmpty())?.let { throw IllegalArgumentException(it) }
    // No cue row and no effect may name a one-shot trigger (stage-view plan session 9, D15) — every
    // problem at once, before anything is written, so the caller's transaction rolls back whole.
    // The index is queries; build it only when some name could be refused at all.
    // Nor a fixture command (fixture optics plan session 7), by the same rules and with its own code.
    val mayTrigger = newRows.any { TriggerIndex.mayRefuse(it.propertyName) } || newEffects.any { TriggerIndex.mayRefuse(it.propertyName) }
    // A row is also refused for a level inside a command's band on a property that shares its channel.
    val mayCommand = newRows.any { CommandIndex.mayRefuseRow(it.propertyName) } || newEffects.any { CommandIndex.mayRefuse(it.propertyName) }
    if (mayTrigger || mayCommand) {
        val refs = newRows.mapIndexed { i, a -> TriggerIndex.RowRef(a.targetType, a.targetKey, a.propertyName, "propertyAssignments[$i]", a.value) } +
            newEffects.mapIndexed { i, e -> TriggerIndex.RowRef(e.targetType, e.targetKey, e.propertyName, "adHocEffects[$i]") }
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
        val pairing = pairByKey(if (created) emptyList() else cue.layersInOrder(), resolved, { it.layerKey }, { it.second.layerKey })
        pairing.unmatched.forEach { it.delete() }
        for ((item, stored) in pairing.pairs) {
            val (layer, source) = item
            if (stored != null) {
                stored.take(layer)
            } else {
                DaoCueLayer.new {
                    this.cue = cue
                    this.look = source.look
                    this.template = source.template
                    take(layer)
                }
            }
        }
    }
    if (adHocEffects != null) {
        val pairing = pairByKey(if (created) emptyList() else cue.adHocEffectsInOrder(), adHocEffects, { it.effectKey }, { it.effectKey })
        pairing.unmatched.forEach { it.delete() }
        for ((effect, stored) in pairing.pairs) {
            if (stored != null) {
                stored.take(effect)
            } else {
                DaoCueAdHocEffect.new {
                    this.cue = cue
                    targetType = effect.targetType
                    targetKey = effect.targetKey
                    take(effect)
                }
            }
        }
    }
    if (propertyAssignments != null) {
        val pairing = pairByKey(if (created) emptyList() else cue.propertyAssignmentsInOrder(), propertyAssignments, { it.rowKey }, { it.rowKey })
        pairing.unmatched.forEach { it.delete() }
        for ((row, stored) in pairing.pairs) {
            if (stored != null) {
                stored.take(row)
            } else {
                DaoCuePropertyAssignment.new {
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
}

/** [layer] onto this layer, but for the record [writeCueChildren] matched it on. */
internal fun DaoCueLayer.take(layer: CueLayerDto) {
    sortOrder = layer.sortOrder
    enabled = layer.enabled
    targets = layer.targets
    propertyMask = layer.propertyMask
    blendMode = layer.blendMode
    amount = layer.amount
    stomp = layer.stomp
    speedMasterUuid = speedMasterUuidOrNull(layer.speedMasterUuid)
    rateSpeedMasterUuid = speedMasterUuidOrNull(layer.rateSpeedMasterUuid)
    delay = layer.delayMs.asDuration()
    interval = layer.intervalMs.asDuration()
    randomWindow = layer.randomWindowMs.asDuration()
}

/** [effect] onto this effect, but for the target [writeCueChildren] matched it on. */
internal fun DaoCueAdHocEffect.take(effect: CueAdHocEffectDto) {
    effectType = effect.effectType
    category = effect.category
    propertyName = effect.propertyName
    beatDivision = effect.beatDivision
    blendMode = effect.blendMode
    distribution = effect.distribution
    phaseOffset = effect.phaseOffset
    elementMode = effect.elementMode
    elementFilter = effect.elementFilter
    stepTiming = effect.stepTiming
    parameters = effect.parameters
    delay = effect.delayMs.asDuration()
    interval = effect.intervalMs.asDuration()
    randomWindow = effect.randomWindowMs.asDuration()
    sortOrder = effect.sortOrder
    // Seconds follows no master (D7), so a row on Seconds keeps none beside it.
    speedMasterUuid = if (effect.seconds) null else speedMasterUuidOrNull(effect.speedMasterUuid)
    rateSpeedMasterUuid = if (effect.seconds) null else speedMasterUuidOrNull(effect.rateSpeedMasterUuid)
    seconds = effect.seconds
    size = effect.size
    direction = EffectSpecCoercion.Names.canonicalDirection(effect.direction)
    nShot = effect.nShot
    this.spread = effect.spread
    this.wings = effect.wings
    this.blocks = effect.blocks
    this.groups = effect.groups
}

/** [row] onto this row, but for the target [writeCueChildren] matched it on. */
internal fun DaoCuePropertyAssignment.take(row: CuePropertyAssignmentDto) {
    propertyName = row.propertyName
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
    cue.adHocEffects.forEach { it.delete() }
    cue.propertyAssignments.forEach { it.delete() }
    cue.triggers.forEach { it.delete() }
}

/**
 * Delete every prompt-book anchor on an entry of [cue] — an anchor marks an entry (cue model plan
 * D4). Kept separate from [deleteCueChildren] because an anchor is not one of the cue's children:
 * it is the prompt book's, and dies only with the cue's entry, never with an edit of its contents.
 * Explicit rather than DB-cascade because SQLite doesn't enforce cascades without a
 * per-connection pragma. Returns the number removed so callers know whether to fire
 * promptBookChanged.
 */
internal fun deletePromptBookAnchorsForCue(cue: DaoCue): Int {
    val entryIds = entriesOfCue(cue).map { it.id }
    if (entryIds.isEmpty()) return 0
    return DaoPromptBookAnchors.deleteWhere { DaoPromptBookAnchors.entry inList entryIds }
}

/**
 * Delete [cue]'s entries — its places in every stack — and renumber each stack it left. For a cue
 * delete, after [deletePromptBookAnchorsForCue] (the anchors mark the entries). Returns the stacks
 * that lost an entry, for their priorities.
 */
internal fun deleteCueEntries(cue: DaoCue): List<DaoCueStack> {
    val entries = entriesOfCue(cue)
    val stacks = entries.map { it.stack }.distinctBy { it.id.value }
    val before = stacks.associate { it.id.value to cueEntryIds(it) }
    entries.forEach { it.delete() }
    stacks.forEach { renumberAutoEntries(it, before.getValue(it.id.value)) }
    return stacks
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
 * `memberFixtureKey → groupKey` for every group in [targets], so a writer that fans a
 * group-scoped gesture out to members can still stamp `sourceGroup` on each programmer slot.
 *
 * Without the hint, Record sees N unrelated fixture entries and emits N cue rows where the
 * operator wrote one group row. Groups are visited in sorted key order and the first group
 * claiming a member wins, so overlapping groups produce a stable (if arbitrary) hint rather
 * than one that flips between calls.
 */
internal fun groupHintsForTargets(
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
    targets: List<TargetRef>,
): Map<String, String> {
    val groupKeys = targets.filterIsInstance<TargetRef.Group>().map { it.key }.distinct().sorted()
    if (groupKeys.isEmpty()) return emptyMap()
    val out = HashMap<String, String>()
    for (groupKey in groupKeys) {
        val group = try {
            fixtures.untypedGroup(groupKey)
        } catch (_: IllegalStateException) {
            continue
        }
        for (member in group.fixtures) out.putIfAbsent(member.targetKey, groupKey)
    }
    return out
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
 * [Fixture.fixtureProperties] of their own; their `@FixtureProperty` metadata comes from the
 * shared [FixturePropertyCatalogue] keyed on the element class.
 */
private fun elementCategoryFor(
    element: FixtureElement<*>,
    propertyName: String,
): Pair<PropertyCategory, CompositionRule>? {
    val canonical = canonicalPropertyName(propertyName)
    if (canonical.equals("position", ignoreCase = true)) {
        val pan = findElementProperty(element, "pan") ?: return null
        findElementProperty(element, "tilt") ?: return null
        return pan.category to pan.composition
    }
    val property = findElementProperty(element, canonical) ?: return null
    return property.category to property.composition
}

private fun findElementProperty(element: FixtureElement<*>, name: String): Fixture.Property? =
    FixturePropertyCatalogue.of(element::class).byName[name]

/**
 * Infer effect category from property name for from-state capture.
 */
internal fun categoryFromPropertyName(propertyName: String): String {
    return when (propertyName.lowercase()) {
        "dimmer" -> "dimmer"
        "colour", "color", "rgbcolour" -> "colour"
        "position" -> "position"
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
 * Every path that makes a cue goes through this, so one entry per cue (P4) holds by construction.
 * A caller placing several cues in one gesture passes [renumber] false and renumbers once, from
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
    writeEntryOrder(existing.take(at) + entry + existing.drop(at))
    if (renumber) renumberAutoEntries(stack, before)
    return entry
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
 * Move [entry] to [position] in [stack]'s list (0 first, markers counted; null or past the end
 * appends) — within its own stack, or into another, which is what `add-cue` does while every cue
 * has one entry (cue model plan P4). Renumbers the stack it joined, and the stack it left as well
 * (venue-visit R1). An explicit number that another cue entry of the target already holds refuses
 * the move, as the unique index would; an auto one is dropped and derived again where it lands.
 * Answers an error, or null when moved. Must run inside a transaction.
 */
internal fun moveEntryTo(stack: DaoCueStack, entry: DaoCueStackEntry, position: Int?): String? {
    val left = entry.stack.takeIf { it.id != stack.id }
    val leftBefore = left?.let { cueEntryIds(it) }
    val before = cueEntryIds(stack)
    val existing = entriesOf(stack).filter { it.id != entry.id }
    if (left != null) {
        val number = entry.cueNumber
        if (!entry.cueNumberAuto && number != null &&
            existing.any { !it.isMarker && !it.cueNumberAuto && number.equals(it.cueNumber, ignoreCase = true) }
        ) {
            return "Cue number $number is already used in ${stack.name}"
        }
        if (entry.cueNumberAuto) entry.cueNumber = null
        releaseAutoNumber(stack, entry.cueNumber, exceptEntryId = entry.id.value)
        entry.stack = stack
    }
    val at = position?.coerceIn(0, existing.size) ?: existing.size
    writeEntryOrder(existing.take(at) + entry + existing.drop(at))
    renumberAutoEntries(stack, before)
    if (left != null) renumberAutoEntries(left, leftBefore!!)
    return null
}
