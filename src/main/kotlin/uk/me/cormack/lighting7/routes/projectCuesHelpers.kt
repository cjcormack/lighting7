@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package uk.me.cormack.lighting7.routes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
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
    val presetApplications: List<CuePresetApplicationDto>,
    val adHocEffects: List<CueAdHocEffectDto>,
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
    val presetApplications: List<CuePresetApplicationDto>,
    val adHocEffects: List<CueAdHocEffectDto>,
    val propertyAssignments: List<CuePropertyAssignmentDto>,
)

/**
 * Capture live palette, active effects, and Layer 3 property assignments from the FX engine.
 * Group-scoped assignments round-trip with `targetType="group"` intact when members share a
 * single composed value — see [captureLayer3Assignments] for the shape-preservation rules.
 */
internal fun captureCurrentState(state: State): CapturedState {
    val currentPalette = state.show.fxEngine.getPalette().map { it.toSerializedString() }
    val activeEffects = state.show.fxEngine.getActiveEffects()

    val presetApplications = mutableMapOf<Int, MutableList<CueTargetDto>>()
    val adHocEffects = mutableListOf<CueAdHocEffectDto>()

    for (effect in activeEffects) {
        val targetType = if (effect.isGroupEffect) "group" else "fixture"
        val targetKey = effect.target.targetKey

        if (effect.presetId != null) {
            val targets = presetApplications.getOrPut(effect.presetId!!) { mutableListOf() }
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
            ))
        }
    }

    val presetAppDtos = presetApplications.map { (presetId, targets) ->
        CuePresetApplicationDto(presetId = presetId, targets = targets)
    }

    val propertyAssignments = captureLayer3Assignments(state)

    return CapturedState(
        palette = currentPalette,
        presetApplications = presetAppDtos,
        adHocEffects = adHocEffects,
        propertyAssignments = propertyAssignments,
    )
}

/**
 * Layer 3 snapshot for `snapshot-from-live` / `/current-state`. Values come from the resolver
 * ([uk.me.cormack.lighting7.fx.LayerResolver.currentLayer3State]) so HTP / LTP / crossfade
 * composition is authoritative. Each active cue's DB rows contribute `(groupKey, propertyName)`
 * hints: a hint collapses to a single group row iff every member's composed value matches;
 * otherwise members fall through to per-fixture emission. Preserves operator-authored group
 * shape after surface edits (Phase 6 group-scoped `DefaultSurfaceActions.writeGroupProperty`).
 */
private fun captureLayer3Assignments(state: State): List<CuePropertyAssignmentDto> {
    val layer3Snapshot = state.show.fxEngine.layerResolver.currentLayer3State
    if (layer3Snapshot.isEmpty()) return emptyList()

    val activeCueIds = state.show.fxEngine.activeCueAssignmentIds()

    val groupHints: Set<Pair<String, String>> = if (activeCueIds.isEmpty()) {
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

    return captureLayer3AssignmentsFromSnapshot(layer3Snapshot, groupHints, state.show.fixtures)
}

/** Pure snapshot-collapse pass — extracted from [captureLayer3Assignments] for DB-less testing. */
internal fun captureLayer3AssignmentsFromSnapshot(
    layer3Snapshot: Map<Layer3Resolver.Key, Layer3Resolver.PropertyValue>,
    groupHints: Set<Pair<String, String>>,
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
): List<CuePropertyAssignmentDto> {
    if (layer3Snapshot.isEmpty()) return emptyList()

    val emitted = mutableListOf<CuePropertyAssignmentDto>()
    val covered = HashSet<Pair<String, String>>() // (fixtureKey, propertyName)

    for ((groupKey, propertyName) in groupHints) {
        val members = try {
            fixtures.untypedGroup(groupKey).fixtures.filterIsInstance<Fixture>()
        } catch (_: IllegalStateException) { emptyList() }
        if (members.isEmpty()) continue

        val firstMember = members.first()
        val firstMemberValue = layer3Snapshot[Layer3Resolver.Key.fixture(firstMember.key, propertyName)]
            ?: continue
        val uniform = members.all { member ->
            member === firstMember ||
                layer3Snapshot[Layer3Resolver.Key.fixture(member.key, propertyName)] == firstMemberValue
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

    val fixtureRows = layer3Snapshot.entries
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
    presetApplications = cue.presetApplications.sortedBy { it.sortOrder }.map { app ->
        CuePresetApplicationDto(
            presetId = app.preset.id.value,
            targets = app.targets,
            delayMs = app.delayMs,
            intervalMs = app.intervalMs,
            randomWindowMs = app.randomWindowMs,
            sortOrder = app.sortOrder,
        )
    },
    adHocEffects = cue.adHocEffects.sortedBy { it.sortOrder }.map { it.toDto() },
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
private fun DaoCuePropertyAssignment.toDtoWithHealth(fixtures: uk.me.cormack.lighting7.show.Fixtures): CuePropertyAssignmentDto {
    val base = toDto()
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
)

/**
 * Convert a DaoCue entity to CueDetails API response. Property-assignment rows are tagged
 * with [AssignmentHealth] by resolving each `(targetType, targetKey, propertyName)` against
 * [fixtures] — dead references surface in the UI with markers (Phase 6) rather than
 * silently dropping at apply time.
 */
internal fun DaoCue.toCueDetails(
    isCurrentProject: Boolean,
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
): CueDetails {
    val presetDetails = presetApplications.sortedBy { it.sortOrder }.map { app ->
        CuePresetApplicationDetail(
            presetId = app.preset.id.value,
            presetName = app.preset.name,
            targets = app.targets,
            delayMs = app.delayMs,
            intervalMs = app.intervalMs,
            randomWindowMs = app.randomWindowMs,
            sortOrder = app.sortOrder,
        )
    }
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
        presetApplications = presetDetails,
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
        stomp = this.stomp,
        canEdit = isCurrentProject,
        canDelete = isCurrentProject,
    )
}

/** Create child preset application, ad-hoc effect, property assignment, and trigger entities for a cue. */
internal fun createCueChildren(
    cue: DaoCue,
    presetApplications: List<CuePresetApplicationDto>,
    adHocEffects: List<CueAdHocEffectDto>,
    propertyAssignments: List<CuePropertyAssignmentDto> = emptyList(),
    triggers: List<CueTriggerDto> = emptyList(),
) {
    for (app in presetApplications) {
        val preset = DaoFxPreset.findById(app.presetId) ?: continue
        DaoCuePresetApplication.new {
            this.cue = cue
            this.preset = preset
            this.targets = app.targets
            this.delayMs = app.delayMs
            this.intervalMs = app.intervalMs
            this.randomWindowMs = app.randomWindowMs
            this.sortOrder = app.sortOrder
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
 * Delete all child entities (preset applications, ad-hoc effects, property assignments, and
 * triggers) for a cue. Also used by the cue PUT route to *replace* children on update —
 * anything that must survive a cue edit (e.g. prompt-book anchors) must NOT be deleted here.
 */
internal fun deleteCueChildren(cue: DaoCue) {
    cue.presetApplications.forEach { it.delete() }
    cue.adHocEffects.forEach { it.delete() }
    cue.propertyAssignments.forEach { it.delete() }
    cue.triggers.forEach { it.delete() }
}

/**
 * Delete every prompt-book anchor bound to [cue]. Kept separate from [deleteCueChildren]
 * because anchors must survive cue edits and die only with the cue itself. Explicit rather
 * than DB-cascade because SQLite doesn't enforce cascades without a per-connection pragma.
 * Returns the number removed so callers know whether to fire promptBookListChanged.
 */
internal fun deletePromptBookAnchorsForCue(cue: DaoCue): Int =
    DaoPromptBookAnchors.deleteWhere { with(SqlExpressionBuilder) { DaoPromptBookAnchors.cue eq cue.id } }

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

    // Load each immediate preset once — effects + property assignments in a single DB hit.
    // Timed preset applications (delayMs/intervalMs) are handled entirely by CueTriggerManager;
    // at fire time they append their property assignments to this cue's Layer 3 via
    // [FxEngine.appendCueAssignments] so they compose like the cue's apply-time rows. The
    // contribution goes live at fire time, not cue-apply time — see the timed preset wiring
    // in [CueTriggerManager.activateTimedEffectsForCue].
    data class ImmediatePreset(
        val presetId: Int,
        val targets: List<CueTargetDto>,
        val effects: List<FxPresetEffectDto>,
        val assignments: List<FxPresetPropertyAssignmentDto>,
        val palette: List<ExtendedColour>,
    )
    val immediatePresets = transaction(state.database) {
        cueData.presetApplications
            .filter { it.delayMs == null && it.intervalMs == null }
            .mapNotNull { app ->
                val preset = DaoFxPreset.findById(app.presetId) ?: return@mapNotNull null
                ImmediatePreset(
                    presetId = app.presetId,
                    targets = app.targets,
                    effects = preset.effects,
                    assignments = preset.toPropertyAssignmentDtos(),
                    palette = preset.palette.toPaletteColours(),
                )
            }
    }

    val cascade = PaletteCascade(
        cue = cueData.palette.toPaletteColours(),
        global = engine.getPalette(),
    )

    // Publish Layer 3 before applying effects so the effect reset pass sees the cue's baseline
    // instead of Layer 5 zero. Combines the cue's own assignments with each immediate preset's
    // property assignments.
    val cueOwnAssignments = buildLayer3AssignmentsForCue(state.show.fixtures, cueData, cascade)
    val presetRows = immediatePresets.flatMap { ip ->
        buildLayer3AssignmentsForPreset(
            state.show.fixtures, cueData.cueId, priority,
            ip.presetId, ip.assignments, ip.targets,
            cascade = cascade.copy(preset = ip.palette),
        )
    }

    val layer3Assignments = when {
        presetRows.isEmpty() -> cueOwnAssignments
        cueOwnAssignments.isEmpty() -> presetRows
        else -> cueOwnAssignments + presetRows
    }
    if (layer3Assignments.isNotEmpty()) {
        engine.setCueAssignments(cueData.cueId, layer3Assignments)
    } else {
        // Re-applying a cue that lost its assignments must clear any stale state.
        engine.removeCueAssignments(cueData.cueId)
    }

    if (cueData.stomp) {
        val overlap = buildStompOverlapFromAssignments(state.show.fixtures, cueData)
        engine.stompForCue(cueData.cueId, overlap)
    }

    // 2. Set per-cue palette (isolated from global palette)
    if (cueData.palette.isNotEmpty()) {
        val colours = cueData.palette.map { parseExtendedColour(it) }
        engine.setCuePalette(cueData.cueId, colours)
        if (cueData.updateGlobalPalette) {
            engine.setPalette(colours)
        }
    }

    // 3. Spawn immediate preset effects from the data loaded above.
    for (ip in immediatePresets) {
        for (target in ip.targets) {
            val toggleTarget = TogglePresetTarget(target.target)
            for (presetEffect in ip.effects) {
                val fxTarget = try {
                    resolveTargetForCue(state, toggleTarget, presetEffect)
                } catch (_: Exception) { null } ?: continue

                val instance = createInstanceFromPresetForCue(
                    presetEffect, fxTarget, ip.presetId, state, cueData.cueId
                )
                instance.cueId = cueData.cueId
                instance.priority = priority
                engine.addEffect(instance)
                effectCount++
            }
        }
    }

    // 4. Apply immediate ad-hoc effects
    // (Timed ad-hoc effects with delayMs/intervalMs are handled by CueTriggerManager)
    for (adHoc in cueData.adHocEffects.filter { it.delayMs == null && it.intervalMs == null }) {
        val target = TogglePresetTarget(adHoc.target)
        val presetEffectDto = FxPresetEffectDto(
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
    (cueData.cueStackId ?: 0) * 1_000_000 + cueData.sortOrder * 1_000 + 1

/**
 * Build the stomp overlap set from a cue's property assignments. Group targets are expanded
 * to member keys so the resolver can filter ad-hoc effects owned by other cues that target
 * individual fixtures within the same group.
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
 * Fixture property lookup used when building Layer 3 assignments. Returns the resolved
 * category / composition override for [propertyName] on [fixture], or null if the name is
 * not a known annotated property.
 *
 * Handles the synthetic aliases the target-resolution code already understands:
 * `"position"` (paired PAN+TILT), `"colour"` / `"color"` / `"rgbColour"` (RGB+W/A/UV bundle).
 * For these names [fixture] is consulted only to verify the capability exists.
 */
private fun fixtureCategoryFor(
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
 * Build the flat [Layer3Resolver.Assignment] list for a single cue's [propertyAssignments],
 * expanding group targets to per-member rows. Member rows produced by a group expansion carry
 * `targetIsGroup = true` so the resolver's specificity rule can drop them when the same cue
 * also asserts a direct fixture-level row on the same (fixtureKey, property).
 *
 * Assignments whose fixture, group, or property cannot be resolved are logged at warn and
 * skipped — missing data must not break cue apply.
 */
internal fun buildLayer3AssignmentsForCue(
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
    cueData: CueApplyData,
    cascade: PaletteCascade = PaletteCascade.EMPTY,
): List<Layer3Resolver.Assignment> {
    if (cueData.propertyAssignments.isEmpty()) return emptyList()
    val priority = cueDerivedPriority(cueData)
    val effectivePalette = cascade.effective
    val out = ArrayList<Layer3Resolver.Assignment>(cueData.propertyAssignments.size * 2)

    for (assignment in cueData.propertyAssignments) {
        val canonical = canonicalPropertyName(assignment.propertyName)
        val target = assignment.target

        // Resolve a reference fixture for category lookup and, for groups, the member keys.
        // memberKeys is empty iff the target is a Fixture — used below as the fanout discriminator.
        val memberKeys: List<String>
        val referenceFixture: Fixture
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
            }
            is TargetRef.Fixture -> {
                referenceFixture = try {
                    fixtures.untypedFixture(target.key)
                } catch (_: IllegalStateException) {
                    logger.warn("cue {}: fixture '{}' missing — skipping assignment for {}", cueData.cueId, target.key, assignment.propertyName)
                    continue
                }
                memberKeys = emptyList()
            }
        }

        val (category, override) = fixtureCategoryFor(referenceFixture, canonical) ?: run {
            logger.warn("cue {}: property '{}' not found on '{}' — skipping", cueData.cueId, assignment.propertyName, target.key)
            continue
        }

        val parsed = Layer3Resolver.parseAssignmentValue(category, canonical, assignment.value, effectivePalette) ?: run {
            logger.warn("cue {}: invalid value '{}' for {}.{} — skipping", cueData.cueId, assignment.value, target.key, assignment.propertyName)
            continue
        }

        // Assignment.fadeWeight always 1.0 here — crossfade progress is applied per-cue by
        // [FxEngine.updateCueFadeWeights] at publish time, not baked into individual rows.
        fun row(key: String, isGroup: Boolean) = Layer3Resolver.Assignment(
            cueId = cueData.cueId,
            priority = priority,
            fadeWeight = 1.0,
            targetKey = key,
            targetIsGroup = isGroup,
            propertyName = canonical,
            category = category,
            compositionOverride = override,
            value = parsed,
            moveInDark = assignment.moveInDark,
        )

        if (memberKeys.isEmpty()) {
            out.add(row(target.key, isGroup = false))
        } else {
            // Emit only per-member rows; the group-level key isn't a resolvable fixture at
            // publish time. Mark these as targetIsGroup=true so a direct fixture-level row
            // for the same member overrides via [Layer3Resolver.applySpecificity].
            for (memberKey in memberKeys) out.add(row(memberKey, isGroup = true))
        }
    }
    return out
}

/**
 * Build Layer 3 rows for a preset application. Preset assignments are preset-local
 * (no target field) — the builder fans each (propertyName, value) across the supplied
 * [applyTargets], reusing the cue builder's group→member expansion and specificity tagging.
 *
 * Rows are tagged with [cueId] and [priority] so the cue's normal teardown
 * ([FxEngine.removeCueAssignments]) cleans up preset-originated rows alongside the cue's
 * own assignments. If both the applying cue and the preset assert the same
 * `(targetKey, propertyName)`, the caller concatenates the two lists and lets
 * [Layer3Resolver.resolve] pick the winner by [Layer3Resolver.Assignment.priority] —
 * callers should keep preset rows at the same priority as the cue's own rows so the sort
 * order alone decides (last-write-wins for OVERRIDE blend). Rows whose fixture / group /
 * property cannot be resolved are logged at warn and skipped — stale data must not break
 * cue apply.
 *
 * Palette refs in colour values resolve against [cascade] — see [PaletteCascade] for the
 * preset > cue > global scope rules.
 */
internal fun buildLayer3AssignmentsForPreset(
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
    cueId: Int,
    priority: Int,
    presetId: Int,
    presetAssignments: List<FxPresetPropertyAssignmentDto>,
    applyTargets: List<CueTargetDto>,
    cascade: PaletteCascade = PaletteCascade.EMPTY,
): List<Layer3Resolver.Assignment> {
    if (presetAssignments.isEmpty() || applyTargets.isEmpty()) return emptyList()
    val effectivePalette = cascade.effective
    val out = ArrayList<Layer3Resolver.Assignment>(presetAssignments.size * applyTargets.size * 2)

    for (target in applyTargets) {
        val targetRef = target.target
        val memberFixtures: List<Fixture>
        val referenceFixture: Fixture
        when (targetRef) {
            is TargetRef.Group -> {
                val group = try {
                    fixtures.untypedGroup(targetRef.key)
                } catch (_: IllegalStateException) {
                    logger.warn("preset {} (cue {}): group '{}' missing — skipping", presetId, cueId, targetRef.key)
                    continue
                }
                val members = group.fixtures.filterIsInstance<Fixture>()
                if (members.isEmpty()) {
                    logger.warn("preset {} (cue {}): group '{}' has no Fixture members — skipping", presetId, cueId, targetRef.key)
                    continue
                }
                memberFixtures = members
                referenceFixture = members.first()
            }
            is TargetRef.Fixture -> {
                referenceFixture = try {
                    fixtures.untypedFixture(targetRef.key)
                } catch (_: IllegalStateException) {
                    logger.warn("preset {} (cue {}): fixture '{}' missing — skipping", presetId, cueId, targetRef.key)
                    continue
                }
                memberFixtures = emptyList()
            }
        }

        for (assignment in presetAssignments) {
            val canonical = canonicalPropertyName(assignment.propertyName)
            val elementKey = assignment.elementKey

            // For element-scoped assignments, look up category + composition against the element
            // class's annotated properties so mode-dependent element types (e.g. SlenderBeam
            // 12CH vs 14CH vs 27CH) pick up the right metadata.
            val referenceCategoryInfo: Pair<PropertyCategory, CompositionRule>? = if (elementKey != null) {
                val referenceElement = findElement(referenceFixture, elementKey)
                if (referenceElement == null) {
                    logger.warn(
                        "preset {} (cue {}): element '{}' not found on '{}' (or members) — skipping",
                        presetId, cueId, elementKey, targetRef.key,
                    )
                    null
                } else {
                    elementCategoryFor(referenceElement, canonical)
                }
            } else {
                fixtureCategoryFor(referenceFixture, canonical)
            }
            val (category, override) = referenceCategoryInfo ?: run {
                logger.warn(
                    "preset {} (cue {}): property '{}' not on '{}'{} — skipping",
                    presetId, cueId, assignment.propertyName, targetRef.key,
                    if (elementKey != null) " element '$elementKey'" else "",
                )
                continue
            }
            val parsed = Layer3Resolver.parseAssignmentValue(category, canonical, assignment.value, effectivePalette) ?: run {
                logger.warn(
                    "preset {} (cue {}): invalid value '{}' for {}.{} — skipping",
                    presetId, cueId, assignment.value, targetRef.key, assignment.propertyName,
                )
                continue
            }

            fun row(key: String, isGroup: Boolean) = Layer3Resolver.Assignment(
                cueId = cueId,
                priority = priority,
                fadeWeight = 1.0,
                targetKey = key,
                targetIsGroup = isGroup,
                propertyName = canonical,
                category = category,
                compositionOverride = override,
                value = parsed,
            )

            val isGroup = memberFixtures.isNotEmpty()
            val fanout = memberFixtures.ifEmpty { listOf(referenceFixture) }
            if (elementKey != null) {
                for (fixture in fanout) {
                    val element = findElement(fixture, elementKey)
                    if (element == null) {
                        logger.warn(
                            "preset {} (cue {}): member '{}' has no element '{}' — skipping that member",
                            presetId, cueId, fixture.key, elementKey,
                        )
                        continue
                    }
                    out.add(row(element.elementKey, isGroup = isGroup))
                }
            } else if (!isGroup) {
                out.add(row(targetRef.key, isGroup = false))
            } else {
                for (member in fanout) out.add(row(member.key, isGroup = true))
            }
        }
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
    target: TogglePresetTarget,
    presetEffect: FxPresetEffectDto,
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
    presetEffect: FxPresetEffectDto,
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
    presetEffect: FxPresetEffectDto,
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
private fun categoryFromPropertyName(propertyName: String): String {
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
    presetEffect: FxPresetEffectDto,
    fxTarget: FxTarget,
    presetId: Int?,
    state: State,
    cueId: Int,
): FxInstance {
    val engine = state.show.fxEngine
    val effect = state.show.fxRegistry.createEffect(
        presetEffect.effectType,
        presetEffect.parameters,
        paletteSupplier = { engine.getCuePalette(cueId) ?: engine.getPalette() },
        paletteVersionSupplier = { engine.getCuePaletteVersion(cueId) + engine.paletteVersion },
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
    }
}
