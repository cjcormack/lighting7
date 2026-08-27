@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package uk.me.cormack.lighting7.routes

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

    // Keyed by Look: an effect names the Look it came from and nothing else. A Look applied twice
    // to different targets therefore collapses into one layer covering both — which is the honest
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
            ))
        }
    }

    val layerDtos = layerTargets.entries.mapIndexed { index, (lookId, targets) ->
        CueLayerDto(lookId = lookId, targets = targets, sortOrder = index)
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
    delayMs = delayMs,
    intervalMs = intervalMs,
    randomWindowMs = randomWindowMs,
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
            fixtures, base.target, base.propertyName,
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
        layers = this.layers.sortedBy { it.sortOrder }.mapNotNull { it.toDto() },
        adHocEffects = this.adHocEffects.sortedBy { it.sortOrder }.map { it.toDto() },
        propertyAssignments = assignmentDetails,
        triggers = triggerDetails,
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

/** Create child layer, ad-hoc effect, property assignment, and trigger entities for a cue. */
internal fun createCueChildren(
    cue: DaoCue,
    adHocEffects: List<CueAdHocEffectDto>,
    propertyAssignments: List<CuePropertyAssignmentDto> = emptyList(),
    triggers: List<CueTriggerDto> = emptyList(),
    layers: List<CueLayerDto> = emptyList(),
) {
    for (layer in layers) {
        // A layer naming a record that no longer exists is dropped rather than failing the write —
        // the rule a preset application used for a deleted preset, kept when that table went. The
        // same drop covers the malformed shapes (both ids, or neither): [resolveCueLayerSource]
        // warns and returns null and this layer simply does not appear, which is what the DTO's
        // exactly-one-of contract means at the write boundary.
        val resolved = resolveCueLayerSource(layer) ?: continue
        DaoCueLayer.new {
            this.cue = cue
            this.look = resolved.look
            this.template = resolved.template
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
 * Apply a cue: remove previous effects, then apply its layer effects and ad-hoc effects.
 *
 * @param replaceAll If true, remove ALL running cue effects (from any cue). If false, only
 *                   remove effects from this same cue (allowing multiple cues to run concurrently).
 */
internal fun applyCue(state: State, cueData: CueApplyData, replaceAll: Boolean = false): ApplyCueResponse {
    val engine = state.show.fxEngine
    // Instances are collected and added in one `addEffects` call: adding them one at a time
    // rebuilt the sorted snapshots and re-broadcast the whole active-effect list per effect,
    // which is O(N²) for a cue of any size (sweep item C7). List order is still spawn order,
    // so layer order still decides composition.
    val spawning = mutableListOf<FxInstance>()

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
        for (effect in toRemove) {
            engine.removeEffect(effect.id)
        }
    } else {
        val toRemove = engine.getActiveEffects().filter { it.cueId == cueData.cueId }
        for (effect in toRemove) {
            engine.removeEffect(effect.id)
        }
    }

    val priority = cueDerivedPriority(cueData)

    // Publish Layer 4 before applying effects so the effect reset pass sees the cue's baseline
    // instead of Layer 5 zero.
    //
    // Timed layers (delayMs/intervalMs) contribute nothing here — they are handled entirely by
    // CueTriggerManager, which at fire time re-cooks this cue with the fired layer included. It
    // re-cooks rather than appending because appending would put two contributors on one
    // (fixture, property) key, which is precisely the ambiguity cooking removes.
    //
    // One cook for both halves (sweep item C8): the rows publish here and the layers' effects go
    // up below, out of the same pass over the stack. Beyond walking it once, it means each Look
    // snapshot is read once — so a Look edited between the two reads cannot land its rows and its
    // effects on stage from different versions of itself.
    val localRows = buildCueAssignmentsForCue(state.show.fixtures, cueData)
    val cooked = CueComposer.cookAll(
        fixtures = state.show.fixtures,
        cueId = cueData.cueId,
        priority = priority,
        layers = cueData.layers,
        localRows = localRows,
        resolveLook = state.show.lookRegistry::snapshot,
        resolveTemplate = state.show.templateRegistry::snapshot,
    )
    if (cooked.values.rows.isNotEmpty()) {
        engine.cueLayer.setAssignments(
            cueData.cueId, cooked.values.rows,
            cueStackId = cueData.cueStackId,
            stompSuppression = cooked.values.stompSuppression,
            // An arrival: this cue is going on stage now, so its rows' own fades time it. The
            // Record/Update rewrite of an already-live cue (`republishCueLayer`) deliberately does
            // not, being an edit rather than an entrance.
            honourRowFades = true,
        )
    } else {
        // Re-applying a cue that lost its assignments must clear any stale state.
        engine.cueLayer.removeAssignments(cueData.cueId)
    }

    if (cueData.stomp) {
        engine.stompForCue(cueData.cueId, buildStompOverlap(state.show.fixtures, cueData, cooked.values))
    }

    // 2. Spawn the layers' effects, **in layer order**.
    //
    // No priority arithmetic is needed for that order to hold: `sortedEffectsComparator` is
    // `compareBy(priority, id)` with `id` a monotonic creation counter, and per-tick composition is
    // a sequential fold through `FxTarget.applyValue`. So same-priority effects already resolve
    // last-created-wins, and spawn order becomes composition order for free.
    for ((layer, lookEffect, target) in cooked.effects) {
        val effectSpec = lookEffect.toEffectSpec()
        val fxTarget = try {
            EffectSpawner.resolveTargetForCue(state, CueTargetDto(target), effectSpec)
        } catch (_: Exception) { null } ?: continue

        val instance = EffectSpawner.createInstanceFromPreset(
            effectSpec, fxTarget, state = state,
            overrideSpeedMasterUuid = layer.speedMasterUuid,
            overrideRateSpeedMasterUuid = layer.rateSpeedMasterUuid,
        )
        // Only a Look can own an effect (D7); `cookEffects` never yields a template layer here.
        instance.lookId = layer.source.id.takeUnless { layer.source.isTemplate }
        instance.cueLayerId = layer.layerId
        instance.cueId = cueData.cueId
        instance.priority = priority
        spawning += instance
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
            EffectSpawner.resolveTargetForCue(state, target, presetEffectDto)
        } catch (_: Exception) { null } ?: continue

        val instance = EffectSpawner.createInstanceFromPreset(presetEffectDto, fxTarget, state)
        instance.cueId = cueData.cueId
        instance.priority = priority
        spawning += instance
    }

    engine.addEffects(spawning)

    return ApplyCueResponse(effectCount = spawning.size, cueName = cueData.cueName)
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

