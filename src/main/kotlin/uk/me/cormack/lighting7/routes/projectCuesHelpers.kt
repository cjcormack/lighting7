@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.models.CueTargetDto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.dao.with
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.inList
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.resolveComposition
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State
import kotlin.reflect.full.memberProperties

private val logger = LoggerFactory.getLogger("projectCues")

/**
 * Dead-assignment warn throttle. Keeps the log quiet when the same cue is fired repeatedly
 * with the same dead-reference shape (e.g. a stack advancing the same dead cue on every GO).
 * Capped at [DEAD_WARN_STATE_MAX] entries so a long-running process can't accumulate one
 * entry per ever-applied cue — on overflow the oldest entry is evicted. Not a cache of
 * correctness — just a log-rate gate, so imprecise eviction is fine.
 */
private const val DEAD_WARN_THROTTLE_MS = 30_000L
private const val DEAD_WARN_STATE_MAX = 1024
private val deadWarnState = java.util.Collections.synchronizedMap(
    object : java.util.LinkedHashMap<Int, Pair<Long, String>>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<Long, String>>?): Boolean =
            size > DEAD_WARN_STATE_MAX
    },
)

private fun maybeLogDeadAssignments(
    cueId: Int,
    cueName: String,
    deadRows: List<CuePropertyAssignmentDto>,
) {
    val signature = deadRows.joinToString(";") { "${it.targetType}:${it.targetKey}.${it.propertyName}" }
    val now = System.currentTimeMillis()
    val previous = deadWarnState[cueId]
    if (previous != null && previous.second == signature && (now - previous.first) < DEAD_WARN_THROTTLE_MS) {
        return
    }
    deadWarnState[cueId] = now to signature
    logger.warn(
        "applyCue '{}' ({}): {} dead assignment(s): {}",
        cueName, cueId, deadRows.size, signature,
    )
}

// Internal data class for apply logic
internal data class CueApplyData(
    val cueId: Int,
    val cueName: String,
    val palette: List<String>,
    val updateGlobalPalette: Boolean,
    val adHocEffects: List<CueAdHocEffectDto>,
    /**
     * The cue's ordered Look composition, resolved far enough for [CueComposer.cook] — the Look's
     * uuid is carried so the uuid-keyed [LookRegistry] needs no second DB hit at apply time.
     */
    val layers: List<CookLayer> = emptyList(),
    val propertyAssignments: List<CuePropertyAssignmentDto> = emptyList(),
    val triggers: List<CueTriggerDto> = emptyList(),
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
    val stomp: Boolean = false,
    val cueStackId: Int? = null,
    val sortOrder: Int = 0,
)

// ─── State capture ──────────────────────────────────────────────────────

internal data class CapturedState(
    val palette: List<String>,
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
 * Capture live palette, active effects, and Layer 4 property assignments from the FX engine.
 * Group-scoped assignments round-trip with `targetType="group"` intact when members share a
 * single composed value — see [captureCueAssignments] for the shape-preservation rules.
 */
internal fun captureCurrentState(state: State): CapturedState {
    val currentPalette = state.show.fxEngine.getPalette().map { it.toSerializedString() }
    val activeEffects = state.show.fxEngine.getActiveEffects()

    // Keyed by Look, not by preset: nothing stamps `presetId` any more. A Look applied twice to
    // different targets therefore collapses into one layer covering both — which is the honest
    // reading of a *snapshot*, since the stage cannot tell you it was two gestures.
    val layerTargets = mutableMapOf<Int, MutableList<CueTargetDto>>()
    val adHocEffects = mutableListOf<CueAdHocEffectDto>()

    for (effect in activeEffects) {
        val targetType = if (effect.isGroupEffect) "group" else "fixture"
        val targetKey = effect.target.targetKey

        val lookId = effect.lookId
        if (lookId != null) {
            val targets = layerTargets.getOrPut(lookId) { mutableListOf() }
            val target = CueTargetDto(type = targetType, key = targetKey)
            if (target !in targets) {
                targets.add(target)
            }
        } else {
            adHocEffects.add(CueAdHocEffectDto(
                targetType = targetType,
                targetKey = targetKey,
                effectType = effect.effect.name.replace(" ", ""),
                category = categoryFromPropertyName(effect.target.propertyName),
                propertyName = effect.target.propertyName,
                beatDivision = effect.timing.beatDivision,
                blendMode = effect.blendMode.name,
                distribution = effect.distributionStrategy.javaClass.simpleName,
                phaseOffset = effect.phaseOffset,
                elementMode = if (effect.isGroupEffect) effect.elementMode.name else null,
                elementFilter = if (effect.elementFilter != ElementFilter.ALL) effect.elementFilter.name else null,
                stepTiming = if (effect.stepTiming != effect.effect.defaultStepTiming) effect.stepTiming else null,
                parameters = effect.effect.parameters,
                speedMasterUuid = effect.speedMasterUuid?.toString(),
                rateSpeedMasterUuid = effect.rateSpeedMasterUuid?.toString(),
            ))
        }
    }

    val layerDtos = layerTargets.entries.mapIndexed { index, (lookId, targets) ->
        CueLayerDto(lookId = lookId, targets = targets, sortOrder = index)
    }

    val propertyAssignments = captureCueAssignments(state)

    return CapturedState(
        palette = currentPalette,
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

    val activeCueIds = engine.activeCueAssignmentIds()

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
    val (entries, _) = collectProgrammerEntries(state, RecordSource.ALL, mask = null)
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
 * Build a [CueApplyData] snapshot from a [DaoCue] entity. Must be called inside an Exposed
 * transaction — dereferences the cue's child collections eagerly.
 */
internal fun buildCueApplyData(cue: DaoCue): CueApplyData = CueApplyData(
    cueId = cue.id.value,
    cueName = cue.name,
    palette = cue.palette,
    updateGlobalPalette = cue.updateGlobalPalette,
    adHocEffects = cue.adHocEffects.sortedBy { it.sortOrder }.map { it.toDto() },
    layers = cue.layers.sortedBy { it.sortOrder }.map { it.toCookLayer() },
    propertyAssignments = cue.propertyAssignments.sortedBy { it.sortOrder }.map { it.toDto() },
    triggers = cue.triggers.sortedBy { it.sortOrder }.map { trigger ->
        CueTriggerDto(
            triggerType = trigger.triggerType.name,
            delayMs = trigger.delayMs,
            intervalMs = trigger.intervalMs,
            randomWindowMs = trigger.randomWindowMs,
            scriptId = trigger.script.id.value,
            sortOrder = trigger.sortOrder,
        )
    },
    stomp = cue.stomp,
    cueStackId = cue.cueStack?.id?.value,
    sortOrder = cue.sortOrder,
)

/**
 * Resolve a stored cue layer into the composer's input shape. Must run inside a transaction — it
 * dereferences the layer's Look for its uuid and name.
 */
internal fun DaoCueLayer.toCookLayer() = CookLayer(
    layerId = id.value,
    lookId = look.id.value,
    lookUuid = look.uuid,
    lookName = look.name,
    sortOrder = sortOrder,
    enabled = enabled,
    targets = targets,
    propertyMask = propertyMask,
    blendMode = blendMode,
    amount = amount,
    stomp = stomp,
    speedMasterUuid = speedMasterUuid,
    rateSpeedMasterUuid = rateSpeedMasterUuid,
    delayMs = delayMs,
    intervalMs = intervalMs,
    randomWindowMs = randomWindowMs,
)

/** Wire form of a stored cue layer. Must run inside a transaction. */
internal fun DaoCueLayer.toDto() = CueLayerDto(
    lookId = look.id.value,
    sortOrder = sortOrder,
    enabled = enabled,
    targets = targets,
    propertyMask = propertyMask,
    blendMode = blendMode,
    amount = amount,
    stomp = stomp,
    speedMasterUuid = speedMasterUuid?.toString(),
    rateSpeedMasterUuid = rateSpeedMasterUuid?.toString(),
    delayMs = delayMs,
    intervalMs = intervalMs,
    randomWindowMs = randomWindowMs,
    lookName = look.name,
    id = id.value,
)

/** Convert a DaoCuePropertyAssignment entity to its DTO form. Health defaults to [AssignmentHealth.Ok]. */
internal fun DaoCuePropertyAssignment.toDto() = CuePropertyAssignmentDto(
    targetType = targetType,
    targetKey = targetKey,
    propertyName = propertyName,
    value = value,
    fadeDurationMs = fadeDurationMs,
    sortOrder = sortOrder,
    moveInDark = moveInDark,
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
            fixtures, base.target, base.propertyName,
        ),
    )
}

/** Convert a DaoCueAdHocEffect entity to its DTO form. */
internal fun DaoCueAdHocEffect.toDto() = CueAdHocEffectDto(
    targetType = targetType,
    targetKey = targetKey,
    effectType = effectType,
    category = category,
    propertyName = propertyName,
    beatDivision = beatDivision,
    blendMode = blendMode,
    distribution = distribution,
    phaseOffset = phaseOffset,
    elementMode = elementMode,
    elementFilter = elementFilter,
    stepTiming = stepTiming,
    parameters = parameters,
    delayMs = delayMs,
    intervalMs = intervalMs,
    randomWindowMs = randomWindowMs,
    sortOrder = sortOrder,
    speedMasterUuid = speedMasterUuid?.toString(),
    rateSpeedMasterUuid = rateSpeedMasterUuid?.toString(),
)

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
): CueDetails {
    val triggerDetails = this.triggers.sortedBy { it.sortOrder }.map { trigger ->
        CueTriggerDetailDto(
            triggerType = trigger.triggerType.name,
            delayMs = trigger.delayMs,
            intervalMs = trigger.intervalMs,
            randomWindowMs = trigger.randomWindowMs,
            scriptId = trigger.script.id.value,
            scriptName = trigger.script.name,
            sortOrder = trigger.sortOrder,
        )
    }
    val assignmentDetails = this.propertyAssignments.sortedBy { it.sortOrder }
        .map { it.toDtoWithHealth(fixtures) }
    return CueDetails(
        id = this.id.value,
        name = this.name,
        palette = this.palette,
        layers = this.layers.sortedBy { it.sortOrder }.map { it.toDto() },
        adHocEffects = this.adHocEffects.sortedBy { it.sortOrder }.map { it.toDto() },
        propertyAssignments = assignmentDetails,
        triggers = triggerDetails,
        updateGlobalPalette = this.updateGlobalPalette,
        cueStackId = this.cueStack?.id?.value,
        cueStackName = this.cueStack?.name,
        sortOrder = this.sortOrder,
        autoAdvance = this.autoAdvance,
        autoAdvanceDelayMs = this.autoAdvanceDelayMs,
        fadeDurationMs = this.fadeDurationMs,
        fadeCurve = this.fadeCurve,
        cueNumber = this.cueNumber,
        cueNumberAuto = this.cueNumberAuto,
        notes = this.notes,
        cueType = this.cueType,
        stomp = this.stomp,
        canEdit = isCurrentProject,
        canDelete = isCurrentProject,
    )
}

/** Create child layer, ad-hoc effect, property assignment, and trigger entities for a cue. */
internal fun createCueChildren(
    cue: DaoCue,
    adHocEffects: List<CueAdHocEffectDto>,
    propertyAssignments: List<CuePropertyAssignmentDto> = emptyList(),
    triggers: List<CueTriggerDto> = emptyList(),
    layers: List<CueLayerDto> = emptyList(),
) {
    for (layer in layers) {
        // A layer naming a Look that no longer exists is dropped rather than failing the write —
        // the rule a preset application used for a deleted preset, kept when that table went.
        val look = DaoLook.findById(layer.lookId) ?: continue
        DaoCueLayer.new {
            this.cue = cue
            this.look = look
            this.sortOrder = layer.sortOrder
            this.enabled = layer.enabled
            this.targets = layer.targets
            this.propertyMask = layer.propertyMask
            this.blendMode = layer.blendMode
            this.amount = layer.amount
            this.stomp = layer.stomp
            this.speedMasterUuid = speedMasterUuidOrNull(layer.speedMasterUuid)
            this.rateSpeedMasterUuid = speedMasterUuidOrNull(layer.rateSpeedMasterUuid)
            this.delayMs = layer.delayMs
            this.intervalMs = layer.intervalMs
            this.randomWindowMs = layer.randomWindowMs
        }
    }
    for (effect in adHocEffects) {
        DaoCueAdHocEffect.new {
            this.cue = cue
            targetType = effect.targetType
            targetKey = effect.targetKey
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
            delayMs = effect.delayMs
            intervalMs = effect.intervalMs
            randomWindowMs = effect.randomWindowMs
            sortOrder = effect.sortOrder
            speedMasterUuid = speedMasterUuidOrNull(effect.speedMasterUuid)
            rateSpeedMasterUuid = speedMasterUuidOrNull(effect.rateSpeedMasterUuid)
        }
    }
    for (assignment in propertyAssignments) {
        DaoCuePropertyAssignment.new {
            this.cue = cue
            targetType = assignment.targetType
            targetKey = assignment.targetKey
            propertyName = assignment.propertyName
            value = assignment.value
            fadeDurationMs = assignment.fadeDurationMs
            sortOrder = assignment.sortOrder
            moveInDark = assignment.moveInDark
        }
    }
    for (trigger in triggers) {
        val script = DaoScript.findById(trigger.scriptId) ?: continue
        // Normalize legacy trigger types: DELAYED/RECURRING → ACTIVATION with timing fields
        val normalizedType = when (trigger.triggerType) {
            "DELAYED" -> TriggerType.ACTIVATION
            "RECURRING" -> TriggerType.ACTIVATION
            else -> try {
                TriggerType.valueOf(trigger.triggerType)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Unknown trigger type: '${trigger.triggerType}'. Valid types: ${TriggerType.entries.joinToString()}")
            }
        }
        DaoCueTrigger.new {
            this.cue = cue
            this.triggerType = normalizedType
            this.delayMs = trigger.delayMs
            this.intervalMs = trigger.intervalMs
            this.randomWindowMs = trigger.randomWindowMs
            this.script = script
            this.sortOrder = trigger.sortOrder
        }
    }
}

/**
 * Delete all child entities (layers, ad-hoc effects, property assignments, and triggers) for a
 * cue. Also used by the cue PUT route to *replace* children on update —
 * anything that must survive a cue edit (e.g. prompt-book anchors) must NOT be deleted here.
 */
internal fun deleteCueChildren(cue: DaoCue) {
    cue.layers.forEach { it.delete() }
    cue.adHocEffects.forEach { it.delete() }
    cue.propertyAssignments.forEach { it.delete() }
    cue.triggers.forEach { it.delete() }
}

/**
 * Delete every prompt-book anchor bound to [cue]. Kept separate from [deleteCueChildren]
 * because anchors must survive cue edits and die only with the cue itself. Explicit rather
 * than DB-cascade because SQLite doesn't enforce cascades without a per-connection pragma.
 * Returns the number removed so callers know whether to fire promptBookChanged.
 */
internal fun deletePromptBookAnchorsForCue(cue: DaoCue): Int =
    DaoPromptBookAnchors.deleteWhere { DaoPromptBookAnchors.cue eq cue.id }

// ─── Apply logic ────────────────────────────────────────────────────────

/**
 * Apply a cue: remove previous effects, set palette, apply preset effects and ad-hoc effects.
 *
 * @param replaceAll If true, remove ALL running cue effects (from any cue). If false, only
 *                   remove effects from this same cue (allowing multiple cues to run concurrently).
 */
internal fun applyCue(state: State, cueData: CueApplyData, replaceAll: Boolean = false): ApplyCueResponse {
    val engine = state.show.fxEngine
    var effectCount = 0

    // Pre-apply validation: warn once per cue-apply when any assignment targets a
    // removed/renamed fixture / group / property. The per-row warns inside the build helpers
    // stay (they're the detailed diagnostic trail); this summary is the rate-limited
    // operator-facing signal. Same-shape warns within `DEAD_WARN_THROTTLE_MS` are dropped
    // so a stack GO'ing the same dead cue on every beat doesn't flood the logs.
    val deadRows = cueData.propertyAssignments.filter {
        PersistedFixtureReferenceValidator.validateTargetedReference(
            state.show.fixtures, it.target, it.propertyName,
        ) != AssignmentHealth.Ok
    }
    if (deadRows.isNotEmpty()) {
        maybeLogDeadAssignments(cueData.cueId, cueData.cueName, deadRows)
    }

    // 1. Remove effects — either all cue effects or just this cue's effects
    if (replaceAll) {
        val toRemove = engine.getActiveEffects().filter { it.cueId != null }
        val removedCueIds = toRemove.mapNotNull { it.cueId }.toSet()
        for (effect in toRemove) {
            engine.removeEffect(effect.id)
        }
        for (removedCueId in removedCueIds) {
            engine.removeCuePalette(removedCueId)
        }
    } else {
        val toRemove = engine.getActiveEffects().filter { it.cueId == cueData.cueId }
        for (effect in toRemove) {
            engine.removeEffect(effect.id)
        }
        engine.removeCuePalette(cueData.cueId)
    }

    val priority = cueDerivedPriority(cueData)

    val cascade = PaletteCascade(
        cue = cueData.palette.toPaletteColours(),
        global = engine.getPalette(),
    )

    // Publish Layer 4 before applying effects so the effect reset pass sees the cue's baseline
    // instead of Layer 5 zero.
    //
    // Timed layers (delayMs/intervalMs) contribute nothing here — they are handled entirely by
    // CueTriggerManager, which at fire time re-cooks this cue with the fired layer included. It
    // re-cooks rather than appending because appending would put two contributors on one
    // (fixture, property) key, which is precisely the ambiguity cooking removes.
    val localRows = buildCueAssignmentsForCue(state.show.fixtures, cueData, cascade)
    val cooked = CueComposer.cook(
        fixtures = state.show.fixtures,
        cueId = cueData.cueId,
        priority = priority,
        layers = cueData.layers,
        localRows = localRows,
        cascade = cascade,
        lookRegistry = state.show.lookRegistry,
    )
    if (cooked.rows.isNotEmpty()) {
        engine.setCueAssignments(
            cueData.cueId, cooked.rows,
            cueStackId = cueData.cueStackId,
            stompSuppression = cooked.stompSuppression,
        )
    } else {
        // Re-applying a cue that lost its assignments must clear any stale state.
        engine.removeCueAssignments(cueData.cueId)
    }

    if (cueData.stomp) {
        engine.stompForCue(cueData.cueId, buildStompOverlap(state.show.fixtures, cueData, cooked))
    }

    // 2. Set per-cue palette (isolated from global palette)
    if (cueData.palette.isNotEmpty()) {
        val colours = cueData.palette.map { parseExtendedColour(it) }
        engine.setCuePalette(cueData.cueId, colours)
        if (cueData.updateGlobalPalette) {
            engine.setPalette(colours)
        }
    }

    // 3. Spawn the layers' effects, **in layer order**.
    //
    // No priority arithmetic is needed for that order to hold: `sortedEffectsComparator` is
    // `compareBy(priority, id)` with `id` a monotonic creation counter, and per-tick composition is
    // a sequential fold through `FxTarget.applyValue`. So same-priority effects already resolve
    // last-created-wins, and spawn order becomes composition order for free.
    for ((layer, lookEffect, target) in CueComposer.cookEffects(
        state.show.fixtures, cueData.cueId, cueData.layers, state.show.lookRegistry,
    )) {
        val effectSpec = lookEffect.toEffectSpec()
        val fxTarget = try {
            resolveTargetForCue(state, CueTargetDto(target), effectSpec)
        } catch (_: Exception) { null } ?: continue

        val instance = createInstanceFromPresetForCue(
            effectSpec, fxTarget, presetId = null, state = state, cueId = cueData.cueId,
            overrideSpeedMasterUuid = layer.speedMasterUuid,
            overrideRateSpeedMasterUuid = layer.rateSpeedMasterUuid,
        )
        instance.lookId = layer.lookId
        instance.cueLayerId = layer.layerId
        instance.cueId = cueData.cueId
        instance.priority = priority
        engine.addEffect(instance)
        effectCount++
    }

    // 4. Apply immediate ad-hoc effects
    // (Timed ad-hoc effects with delayMs/intervalMs are handled by CueTriggerManager)
    for (adHoc in cueData.adHocEffects.filter { it.delayMs == null && it.intervalMs == null }) {
        val target = CueTargetDto(adHoc.target)
        val presetEffectDto = LookEffectSpec(
            effectType = adHoc.effectType,
            category = adHoc.category,
            propertyName = adHoc.propertyName,
            beatDivision = adHoc.beatDivision,
            blendMode = adHoc.blendMode,
            distribution = adHoc.distribution,
            phaseOffset = adHoc.phaseOffset,
            elementMode = adHoc.elementMode,
            elementFilter = adHoc.elementFilter,
            stepTiming = adHoc.stepTiming,
            parameters = adHoc.parameters,
            speedMasterUuid = adHoc.speedMasterUuid,
            rateSpeedMasterUuid = adHoc.rateSpeedMasterUuid,
        )
        val fxTarget = try {
            resolveTargetForCue(state, target, presetEffectDto)
        } catch (_: Exception) { null } ?: continue

        val instance = createInstanceFromPresetForCue(
            presetEffectDto, fxTarget, null, state, cueData.cueId
        )
        instance.cueId = cueData.cueId
        instance.priority = priority
        engine.addEffect(instance)
        effectCount++
    }

    return ApplyCueResponse(effectCount = effectCount, cueName = cueData.cueName)
}

/**
 * Derived priority for a cue-owned effect. `+1` keeps manual effects (priority 0) strictly
 * below; the magnitude gaps leave room for per-effect fine-tuning without renumbering.
 */
internal fun cueDerivedPriority(cueData: CueApplyData): Int =
    cueDerivedPriority(cueData.cueStackId, cueData.sortOrder)

/**
 * Position-only form, for recomputing the priority of *already applied* rows after a stack is
 * reordered — see [uk.me.cormack.lighting7.fx.FxEngine.repriorityCues].
 */
internal fun cueDerivedPriority(cueStackId: Int?, sortOrder: Int): Int =
    (cueStackId ?: 0) * 1_000_000 + sortOrder * 1_000 + 1

/**
 * `cueId → cueDerivedPriority` for every cue in [stack] at its current sort order. Hand the
 * result to [uk.me.cormack.lighting7.fx.FxEngine.repriorityCues] after changing the order, so
 * cues already on stage compose in the new order without needing to be re-applied.
 */
internal fun stackCuePriorities(stack: DaoCueStack): Map<Int, Int> =
    DaoCue.find { DaoCues.cueStack eq stack.id }
        .associate { it.id.value to cueDerivedPriority(stack.id.value, it.sortOrder) }

/**
 * The **cue-level** (cross-cue) stomp overlap: every `(target, property)` this cue holds, from both
 * halves of its composition.
 *
 * [buildStompOverlapFromAssignments] alone reads the cue's *local* rows, which was the whole of a
 * cue's surface before the layer model and is no longer — a cue whose colour comes entirely from a
 * layer asserted plenty and stomped nothing. [CookResult.assertedKeys] supplies the layers' half,
 * group aliases included, and the two are unioned rather than one replacing the other: cook emits
 * per-fixture rows for a group-targeted *local* row, so dropping the assignment pass would lose the
 * group keys an effect aimed at the group itself is matched on.
 */
internal fun buildStompOverlap(
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
    cueData: CueApplyData,
    cooked: CookResult,
): Set<FxEngine.PropertyKey> {
    val fromAssignments = buildStompOverlapFromAssignments(fixtures, cueData)
    if (cooked.assertedKeys.isEmpty()) return fromAssignments
    if (fromAssignments.isEmpty()) return cooked.assertedKeys
    return fromAssignments + cooked.assertedKeys
}

/**
 * Build the stomp overlap set from a cue's property assignments. Group targets are expanded
 * to member keys so the resolver can filter ad-hoc effects owned by other cues that target
 * individual fixtures within the same group.
 *
 * The cue's **layers** are not visible here — see [buildStompOverlap], which is what callers want.
 */
internal fun buildStompOverlapFromAssignments(
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
    cueData: CueApplyData,
): Set<FxEngine.PropertyKey> {
    if (cueData.propertyAssignments.isEmpty()) return emptySet()
    val out = HashSet<FxEngine.PropertyKey>()
    for (assignment in cueData.propertyAssignments) {
        val canonical = canonicalPropertyName(assignment.propertyName)
        when (val target = assignment.target) {
            is TargetRef.Group -> {
                out.add(FxEngine.PropertyKey(target.key, canonical))
                val members = try {
                    fixtures.untypedGroup(target.key).fixtures
                } catch (_: IllegalStateException) { emptyList() }
                for (member in members) {
                    if (member is Fixture) out.add(FxEngine.PropertyKey(member.key, canonical))
                }
            }
            is TargetRef.Fixture -> {
                out.add(FxEngine.PropertyKey(target.key, canonical))
            }
        }
    }
    return out
}

// Canonical form for property names is defined in [uk.me.cormack.lighting7.fx.canonicalPropertyName]
// — shared with [PersistedFixtureReferenceValidator] so route handlers and validation
// don't drift apart on the aliasing rule.

/**
 * Fixture property lookup used when building Layer 4 assignments. Returns the resolved
 * category / composition override for [propertyName] on [fixture], or null if the name is
 * not a known annotated property.
 *
 * Handles the synthetic aliases the target-resolution code already understands:
 * `"position"` (paired PAN+TILT), `"colour"` / `"color"` / `"rgbColour"` (RGB+W/A/UV bundle).
 * For these names [fixture] is consulted only to verify the capability exists.
 */
internal fun fixtureCategoryFor(
    fixture: Fixture,
    propertyName: String,
): Pair<PropertyCategory, CompositionRule>? {
    val canonical = canonicalPropertyName(propertyName)
    if (canonical.equals("position", ignoreCase = true)) {
        // Synthetic compound of PAN + TILT. Composition defaults to the PAN category's rule;
        // any override on the pan property is honoured.
        val panProp = fixture.fixtureProperty("pan")
        return panProp?.let { it.category to it.composition } ?: (PropertyCategory.PAN to CompositionRule.UNSET)
    }
    val prop = fixture.fixtureProperty(canonical) ?: return null
    return prop.category to prop.composition
}

/**
 * Build the flat [CueAssignmentResolver.Assignment] list for a single cue's [propertyAssignments],
 * expanding group targets to per-member rows. Member rows produced by a group expansion carry
 * `targetIsGroup = true` so the resolver's specificity rule can drop them when the same cue
 * also asserts a direct fixture-level row on the same (fixtureKey, property).
 *
 * Assignments whose fixture, group, or property cannot be resolved are logged at warn and
 * skipped — missing data must not break cue apply.
 *
 * There used to be a per-member branch here: a row whose value was `ref:{uuid}` resolved **once per
 * target fixture**, taking each member's *own* property category rather than the reference fixture's,
 * because a palette is per-fixture by construction and a mixed-type group is exactly the case it
 * existed to serve. The `ref:` grammar retired in session 4, so every row is a literal and one parse
 * against the reference fixture serves the whole target — which is what the "literal rows keep the
 * single parse before the fanout" fast path always was.
 */
internal fun buildCueAssignmentsForCue(
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
    cueData: CueApplyData,
    cascade: PaletteCascade = PaletteCascade.EMPTY,
): List<CueAssignmentResolver.Assignment> {
    if (cueData.propertyAssignments.isEmpty()) return emptyList()
    val priority = cueDerivedPriority(cueData)
    val effectivePalette = cascade.effective
    val out = ArrayList<CueAssignmentResolver.Assignment>(cueData.propertyAssignments.size * 2)

    for (assignment in cueData.propertyAssignments) {
        val canonical = canonicalPropertyName(assignment.propertyName)
        val target = assignment.target

        // Resolve a reference fixture for category lookup and, for groups, the member keys.
        // memberKeys is empty iff the target is a Fixture — used below as the fanout discriminator.
        val memberKeys: List<String>
        val referenceFixture: Fixture
        val targetFixtures: List<Fixture>
        when (target) {
            is TargetRef.Group -> {
                val group = try {
                    fixtures.untypedGroup(target.key)
                } catch (_: IllegalStateException) {
                    logger.warn("cue {}: group '{}' missing — skipping assignment for {}", cueData.cueId, target.key, assignment.propertyName)
                    continue
                }
                val members = group.fixtures.filterIsInstance<Fixture>()
                if (members.isEmpty()) {
                    logger.warn("cue {}: group '{}' has no Fixture members — skipping assignment", cueData.cueId, target.key)
                    continue
                }
                memberKeys = members.map { it.key }
                referenceFixture = members.first()
                targetFixtures = members
            }
            is TargetRef.Fixture -> {
                referenceFixture = try {
                    fixtures.untypedFixture(target.key)
                } catch (_: IllegalStateException) {
                    logger.warn("cue {}: fixture '{}' missing — skipping assignment for {}", cueData.cueId, target.key, assignment.propertyName)
                    continue
                }
                memberKeys = emptyList()
                targetFixtures = listOf(referenceFixture)
            }
        }

        // Assignment.fadeWeight always 1.0 here — crossfade progress is applied per-cue by
        // [FxEngine.updateCueFadeWeights] at publish time, not baked into individual rows.
        fun row(
            key: String,
            isGroup: Boolean,
            category: PropertyCategory,
            override: CompositionRule,
            value: CueAssignmentResolver.PropertyValue,
        ) = CueAssignmentResolver.Assignment(
            cueId = cueData.cueId,
            priority = priority,
            fadeWeight = 1.0,
            targetKey = key,
            targetIsGroup = isGroup,
            propertyName = canonical,
            category = category,
            compositionOverride = override,
            value = value,
            moveInDark = assignment.moveInDark,
        )

        // Until session 4 a per-fixture branch sat here: a value of `ref:{uuid}` had to be resolved
        // against the referenced Look *once per target fixture*, because a reference resolves per
        // head. A row's value is always a literal now, so one parse against the reference fixture
        // serves the whole target.
        val (category, override) = fixtureCategoryFor(referenceFixture, canonical) ?: run {
            logger.warn("cue {}: property '{}' not found on '{}' — skipping", cueData.cueId, assignment.propertyName, target.key)
            continue
        }

        val parsed = CueAssignmentResolver.parseAssignmentValue(category, canonical, assignment.value, effectivePalette) ?: run {
            logger.warn("cue {}: invalid value '{}' for {}.{} — skipping", cueData.cueId, assignment.value, target.key, assignment.propertyName)
            continue
        }

        if (memberKeys.isEmpty()) {
            out.add(row(target.key, isGroup = false, category, override, parsed))
        } else {
            // Emit only per-member rows; the group-level key isn't a resolvable fixture at
            // publish time. Mark these as targetIsGroup=true so a direct fixture-level row
            // for the same member overrides via [CueAssignmentResolver.applySpecificity].
            for (memberKey in memberKeys) out.add(row(memberKey, isGroup = true, category, override, parsed))
        }
    }
    return out
}

/**
 * Republish Layer 4 for [cueId] from pre-built [applyData], combining the cue's own property
 * assignments with those of each immediate preset application (timed presets don't contribute
 * — matching [applyCue]). Effects are left alone: this is the Layer 4 half of an apply.
 *
 * Used by cue-edit persists and by Record/Update after they rewrite a cue that is currently
 * live. Without it the DB rows and the published layer disagree, and the next Clear would
 * snap the rig back to the cue's pre-edit values.
 */
internal fun republishCueLayer(state: State, cueId: Int, applyData: CueApplyData) {
    val engine = state.show.fxEngine
    val combined = buildCombinedCueLayerRows(state, cueId, applyData)
    if (combined.rows.isNotEmpty()) {
        engine.setCueAssignments(
            cueId, combined.rows,
            cueStackId = applyData.cueStackId,
            stompSuppression = combined.stompSuppression,
        )
    } else {
        engine.removeCueAssignments(cueId)
    }
}

/**
 * The cook [republishCueLayer] would publish: the cue's layer stack flattened with its own local
 * rows on top (timed layers don't contribute unless named in [firedTimedLayerIds] — matching
 * [applyCue]).
 *
 * Returns the whole [CookResult], not just its rows. A caller that only wants values takes `.rows`;
 * one that *publishes* must carry `.stompSuppression` with them, or a stomping layer's effect
 * suppression outlives the cook that justified it.
 *
 * Split out from [republishCueLayer] so a caller that needs to rebuild several cues can publish
 * them in one pass — see `republishForLookEdit`, where publishing per cue would take the engine
 * lock and transmit once per cue for what is a single operator edit.
 *
 * [cuePalette] overrides the cue-scope palette. A stack cue's cue-scope palette is the *stack*
 * palette (`activateCueInStack` merges the cue's own palette into it before building), which the
 * cue's `applyData.palette` only equals when the cue carries a palette of its own — so the
 * preview path passes the resolved stack palette rather than letting a palette-less cue resolve
 * its references against nothing. Null means "use the cue's own palette", as the apply paths do.
 *
 * [firedTimedLayerIds] names the timed layers that have already fired, so a rebuild triggered while
 * a timed layer is live reproduces its contribution instead of dropping it. See
 * [uk.me.cormack.lighting7.fx.CueTriggerManager] for why firing re-cooks rather than appending.
 * Defaulting to null — "ask the trigger manager" — rather than to the empty set is deliberate: an
 * explicit-empty default made every caller silently retract a live timed layer's rows.
 */
internal fun buildCombinedCueLayerRows(
    state: State,
    cueId: Int,
    applyData: CueApplyData,
    cuePalette: List<ExtendedColour>? = null,
    firedTimedLayerIds: Set<Int>? = null,
): CookResult {
    val cascade = PaletteCascade(
        cue = cuePalette ?: applyData.palette.toPaletteColours(),
        global = state.show.fxEngine.getPalette(),
    )
    val cueOwn = buildCueAssignmentsForCue(state.show.fixtures, applyData, cascade)
    return CueComposer.cook(
        fixtures = state.show.fixtures,
        cueId = cueId,
        priority = cueDerivedPriority(applyData),
        layers = applyData.layers,
        localRows = cueOwn,
        cascade = cascade,
        lookRegistry = state.show.lookRegistry,
        includeTimed = firedTimedLayerIds ?: state.cueTriggerManager.firedTimedLayerIds(cueId),
    )
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
 * Element counterpart to [fixtureCategoryFor] — reflects on the element class's
 * `@FixtureProperty` annotations, since elements aren't `Fixture`s and don't participate in
 * the parent's [Fixture.fixtureProperties] catalogue.
 */
private fun elementCategoryFor(
    element: FixtureElement<*>,
    propertyName: String,
): Pair<PropertyCategory, CompositionRule>? {
    val canonical = canonicalPropertyName(propertyName)
    if (canonical.equals("position", ignoreCase = true)) {
        val pan = findElementPropertyAnnotation(element, "pan") ?: return null
        findElementPropertyAnnotation(element, "tilt") ?: return null
        return pan.category to pan.resolveComposition()
    }
    val ann = findElementPropertyAnnotation(element, canonical) ?: return null
    return ann.category to ann.resolveComposition()
}

private fun findElementPropertyAnnotation(element: FixtureElement<*>, name: String): FixtureProperty? {
    for (prop in element::class.memberProperties) {
        if (prop.name != name) continue
        return prop.annotations.filterIsInstance<FixtureProperty>().firstOrNull()
    }
    return null
}

// ─── Target resolution helpers ──────────────────────────────────────────

internal fun resolveTargetForCue(
    state: State,
    target: CueTargetDto,
    presetEffect: LookEffectSpec,
): FxTarget? {
    return when (target.target) {
        is TargetRef.Group -> {
            val group = state.show.fixtures.untypedGroup(target.key)
            val propertyName = presetEffect.propertyName
                ?: resolvePresetEffectPropertyForCue(presetEffect, group.detectCapabilities())
                ?: return null
            createGroupTargetForCue(group.name, propertyName, group)
        }
        is TargetRef.Fixture -> {
            val propertyName = presetEffect.propertyName
                ?: resolvePresetEffectPropertyForFixtureInCue(presetEffect)
                ?: return null
            createFixtureTargetForCue(target.key, propertyName, state)
        }
    }
}

internal fun resolvePresetEffectPropertyForCue(
    presetEffect: LookEffectSpec,
    capabilities: List<String>,
): String? {
    return when (presetEffect.category) {
        "dimmer" -> if ("dimmer" in capabilities) "dimmer" else null
        "colour" -> if ("colour" in capabilities) "colour" else null
        "position" -> if ("position" in capabilities) "position" else null
        "controls", "setting" -> presetEffect.propertyName
        else -> null
    }
}

private fun resolvePresetEffectPropertyForFixtureInCue(
    presetEffect: LookEffectSpec,
): String? {
    return when (presetEffect.category) {
        "dimmer" -> "dimmer"
        "colour" -> "colour"
        "position" -> "position"
        "controls", "setting" -> presetEffect.propertyName
        else -> null
    }
}

private fun createGroupTargetForCue(
    groupName: String,
    propertyName: String,
    group: FixtureGroup<*>,
): FxTarget {
    return when (propertyName.lowercase()) {
        "dimmer" -> SliderTarget.forGroup(groupName, "dimmer")
        "colour", "color", "rgbcolour" -> ColourTarget.forGroup(groupName)
        "position" -> PositionTarget.forGroup(groupName)
        "uv" -> SliderTarget.forGroup(groupName, "uv")
        else -> {
            val firstFixture = group.fixtures.firstOrNull() as? Fixture
            val prop = firstFixture?.fixtureProperties?.find { it.name == propertyName }
            val propValue = prop?.classProperty?.call(firstFixture)
            if (propValue is Slider) {
                SliderTarget.forGroup(groupName, propertyName)
            } else {
                SettingTarget.forGroup(groupName, propertyName)
            }
        }
    }
}

internal fun createFixtureTargetForCue(
    fixtureKey: String,
    propertyName: String,
    state: State,
): FxTarget {
    return when (propertyName.lowercase()) {
        "dimmer" -> SliderTarget(fixtureKey, "dimmer")
        "uv" -> SliderTarget(fixtureKey, "uv")
        "colour", "color", "rgbcolour" -> ColourTarget(fixtureKey)
        "position" -> PositionTarget(fixtureKey)
        else -> {
            val fixture = try {
                state.show.fixtures.untypedFixture(fixtureKey) as? Fixture
            } catch (_: Exception) { null }
            val prop = fixture?.fixtureProperties?.find { it.name == propertyName }
            val propValue = prop?.classProperty?.call(fixture)
            if (propValue is Slider) {
                SliderTarget(fixtureKey, propertyName)
            } else {
                SettingTarget(fixtureKey, propertyName)
            }
        }
    }
}


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

/**
 * Create an FxInstance from preset effect data for cue application.
 */
internal fun createInstanceFromPresetForCue(
    presetEffect: LookEffectSpec,
    fxTarget: FxTarget,
    presetId: Int?,
    state: State,
    cueId: Int,
    /** Per-cue-application override; null falls through to the preset effect's own master. */
    overrideSpeedMasterUuid: java.util.UUID? = null,
    /** Per-cue-application rate-master override; same fall-through rule. */
    overrideRateSpeedMasterUuid: java.util.UUID? = null,
): FxInstance {
    val engine = state.show.fxEngine
    return createInstanceFromPreset(
        presetEffect, fxTarget, presetId, state,
        paletteSupplier = { engine.getCuePalette(cueId) ?: engine.getPalette() },
        paletteVersionSupplier = { engine.getCuePaletteVersion(cueId) + engine.paletteVersion },
        overrideSpeedMasterUuid = overrideSpeedMasterUuid,
        overrideRateSpeedMasterUuid = overrideRateSpeedMasterUuid,
    )
}

/**
 * Build an [FxInstance] from a preset effect definition with caller-supplied palette suppliers.
 *
 * Split out of [createInstanceFromPresetForCue] for Include. That function's supplier reads
 * `getCuePalette(cueId) ?: getPalette()`, which silently falls back to the *global* palette
 * when the cue isn't live — so including a non-running cue whose FX use a palette ref (`P1`)
 * would resolve it against the wrong colours. Include passes a snapshot of the included cue's
 * own palette instead.
 */
internal fun createInstanceFromPreset(
    presetEffect: LookEffectSpec,
    fxTarget: FxTarget,
    presetId: Int?,
    state: State,
    paletteSupplier: () -> List<ExtendedColour>,
    paletteVersionSupplier: () -> Long,
    /** Per-cue-application override; null falls through to the preset effect's own master. */
    overrideSpeedMasterUuid: java.util.UUID? = null,
    overrideRateSpeedMasterUuid: java.util.UUID? = null,
): FxInstance {
    val effect = state.show.fxRegistry.createEffect(
        presetEffect.effectType,
        presetEffect.parameters,
        paletteSupplier = paletteSupplier,
        paletteVersionSupplier = paletteVersionSupplier,
    )
    val timing = FxTiming(presetEffect.beatDivision)
    val blendMode = try {
        BlendMode.valueOf(presetEffect.blendMode)
    } catch (_: Exception) {
        BlendMode.OVERRIDE
    }
    val distribution = try {
        DistributionStrategy.fromName(presetEffect.distribution)
    } catch (_: Exception) {
        DistributionStrategy.LINEAR
    }
    val elementMode = try {
        presetEffect.elementMode?.let { ElementMode.valueOf(it) } ?: ElementMode.PER_FIXTURE
    } catch (_: Exception) {
        ElementMode.PER_FIXTURE
    }
    val elementFilter = try {
        presetEffect.elementFilter?.let { ElementFilter.fromName(it) } ?: ElementFilter.ALL
    } catch (_: Exception) {
        ElementFilter.ALL
    }

    // Propagate timing source from the effect's registration
    val registration = state.show.fxRegistry.getRegistration(presetEffect.effectType)
    val timingSource = registration?.timingSource ?: uk.me.cormack.lighting7.fx.TimingSource.BEAT

    return FxInstance(effect, fxTarget, timing, blendMode).apply {
        this.presetId = presetId
        phaseOffset = presetEffect.phaseOffset
        distributionStrategy = distribution
        this.elementMode = elementMode
        this.elementFilter = elementFilter
        this.timingSource = timingSource
        presetEffect.stepTiming?.let { this.stepTiming = it }
        speedMasterUuid = overrideSpeedMasterUuid
            ?: speedMasterUuidOrNull(presetEffect.speedMasterUuid)
        rateSpeedMasterUuid = overrideRateSpeedMasterUuid
            ?: speedMasterUuidOrNull(presetEffect.rateSpeedMasterUuid)
    }
}
