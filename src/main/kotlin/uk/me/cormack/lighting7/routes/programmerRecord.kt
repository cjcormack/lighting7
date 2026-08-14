package uk.me.cormack.lighting7.routes

import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.CuePresetApplicationDto
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCuePresetApplication
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoFxPreset
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State

/**
 * How a recording lands on the target cue.
 *
 * Two invariants hold across every mode:
 *
 * - **Triggers are never touched.** Script hooks are not programmer state and the programmer
 *   has no way to represent them, so no Record can add, change, or remove one.
 * - **Timed children are never touched** (`delayMs`/`intervalMs` on preset applications and
 *   ad-hoc effects). Those are owned by `CueTriggerManager` and fire on their own schedule;
 *   Include never spawns them into the programmer, so a Record has nothing to say about them.
 *
 * Both are counted in [ProgrammerPreservedCounts] so the operator can see what a "replace
 * everything" mode deliberately left alone.
 */
enum class RecordMode {
    /** Make a new cue in a stack from the recording. */
    CREATE,

    /** Add to the cue: upsert recorded rows, keep everything else. */
    MERGE,

    /** Subtract from the cue: delete the rows the recording names. Values are ignored. */
    REMOVE,

    /** Replace the cue's in-mask content with the recording. */
    UPDATE_EXISTING,
}

/** What a write deliberately left alone, so the response can say so rather than implying. */
@Serializable
data class ProgrammerPreservedCounts(
    val triggers: Int = 0,
    val timedPresetApplications: Int = 0,
    val timedAdHocEffects: Int = 0,
    val outOfMaskAssignments: Int = 0,
)

/** The outcome of writing a recording into one cue. */
data class CueWriteOutcome(
    val cueId: Int,
    val created: Boolean,
    val assignmentsWritten: Int,
    val assignmentsRemoved: Int,
    val groupRowsEmitted: Int,
    val fxWritten: Int,
    val preserved: ProgrammerPreservedCounts,
    val warnings: List<String>,
)

/** A child with timing belongs to `CueTriggerManager`, not to Record. */
private val DaoCuePresetApplication.isTimed: Boolean get() = delayMs != null || intervalMs != null
private val DaoCueAdHocEffect.isTimed: Boolean get() = delayMs != null || intervalMs != null

/** Match key for a property assignment: target plus canonical property name. */
private fun assignmentKey(targetType: String, targetKey: String, propertyName: String) =
    Triple(targetType, targetKey, canonicalPropertyName(propertyName))

private fun DaoCuePropertyAssignment.matchKey() = assignmentKey(targetType, targetKey, propertyName)
private fun CuePropertyAssignmentDto.matchKey() = assignmentKey(targetType, targetKey, propertyName)

/**
 * Create a cue in [stack] from [recording]. Mirrors the `POST /cues` create path — including
 * `sortOrder = max + 1` rather than `count`, since sort orders may have gaps — so a recorded
 * cue is indistinguishable from a hand-made one.
 *
 * Must run inside a transaction.
 */
internal fun createCueFromRecording(
    project: DaoProject,
    stack: DaoCueStack,
    recording: ProgrammerRecording,
    name: String,
    cueNumber: String?,
    sortOrder: Int?,
    cueType: CueType,
): CueWriteOutcome {
    val cue = DaoCue.new {
        this.name = name
        this.project = project
        this.palette = recording.palette ?: emptyList()
        this.cueStack = stack
        this.cueNumber = cueNumber
        this.cueType = cueType.name
        this.sortOrder = sortOrder ?: ((stack.cues.maxOfOrNull { it.sortOrder } ?: -1) + 1)
    }
    createCueChildren(cue, recording.presetApplications, recording.adHocEffects, recording.rows)
    renumberAutoCues(stack)

    return CueWriteOutcome(
        cueId = cue.id.value,
        created = true,
        assignmentsWritten = recording.rows.size,
        assignmentsRemoved = 0,
        groupRowsEmitted = recording.groupRowsEmitted,
        fxWritten = recording.presetApplications.size + recording.adHocEffects.size,
        preserved = ProgrammerPreservedCounts(),
        warnings = emptyList(),
    )
}

/**
 * Apply [recording] to an existing [cue] under [mode].
 *
 * Must run inside a transaction. The caller republishes Layer 3 afterwards if the cue is live
 * — see [republishCueLayer3].
 */
internal fun writeRecordingIntoCue(
    state: State,
    cue: DaoCue,
    recording: ProgrammerRecording,
    mode: RecordMode,
    mask: Set<PropertyMaskGroup>?,
): CueWriteOutcome {
    require(mode != RecordMode.CREATE) { "CREATE is handled by createCueFromRecording" }

    val warnings = ArrayList<String>()
    val timedPresets = cue.presetApplications.count { it.isTimed }
    val timedAdHoc = cue.adHocEffects.count { it.isTimed }
    val triggerCount = cue.triggers.count().toInt()

    var written = 0
    var removed = 0
    var outOfMask = 0

    when (mode) {
        RecordMode.CREATE -> error("unreachable")

        RecordMode.MERGE -> {
            val existing = cue.propertyAssignments.associateBy { it.matchKey() }
            for (row in recording.rows) {
                val match = existing[row.matchKey()]
                if (match != null) {
                    match.value = row.value
                    match.fadeDurationMs = row.fadeDurationMs
                    match.moveInDark = row.moveInDark
                } else {
                    DaoCuePropertyAssignment.new {
                        this.cue = cue
                        targetType = row.targetType
                        targetKey = row.targetKey
                        propertyName = row.propertyName
                        value = row.value
                        fadeDurationMs = row.fadeDurationMs
                        sortOrder = row.sortOrder
                        moveInDark = row.moveInDark
                    }
                }
                written++
            }
        }

        RecordMode.REMOVE -> {
            val doomed = recording.rows.map { it.matchKey() }.toSet()
            for (row in cue.propertyAssignments.toList()) {
                if (row.matchKey() in doomed) {
                    row.delete()
                    removed++
                }
            }
            // A recorded *fixture* key whose property is only covered by a group row is not a
            // licence to delete that group row — every other member would silently lose the
            // value too. Say so instead of guessing which the operator meant.
            val remainingGroupRows = cue.propertyAssignments.filter {
                it.targetType == TargetRef.Group.TYPE
            }
            for (groupRow in remainingGroupRows) {
                val property = canonicalPropertyName(groupRow.propertyName)
                val members = try {
                    state.show.fixtures.untypedGroup(groupRow.targetKey).fixtures.map { it.targetKey }
                } catch (_: Exception) {
                    continue
                }
                val shadowed = recording.rows.filter {
                    it.targetType == TargetRef.Fixture.TYPE &&
                        canonicalPropertyName(it.propertyName) == property &&
                        it.targetKey in members
                }
                if (shadowed.isNotEmpty()) {
                    warnings += "'${groupRow.targetKey}'.$property still covers " +
                        shadowed.joinToString { it.targetKey } +
                        " — remove the group row, or record a fixture override instead"
                }
            }
            removed += removeRecordedFxChildren(cue, recording)
        }

        RecordMode.UPDATE_EXISTING -> {
            for (row in cue.propertyAssignments.toList()) {
                val group = maskGroupForRow(state.show.fixtures, row.toDto())
                // A row whose fixture or property no longer resolves has no derivable mask
                // group. Under a mask that means "leave it alone": a patch change must never
                // turn a scoped re-record into a delete of rows it can't classify.
                if (mask != null && !maskAllows(mask, group)) {
                    outOfMask++
                    continue
                }
                row.delete()
                removed++
            }
            for (row in recording.rows) {
                DaoCuePropertyAssignment.new {
                    this.cue = cue
                    targetType = row.targetType
                    targetKey = row.targetKey
                    propertyName = row.propertyName
                    value = row.value
                    fadeDurationMs = row.fadeDurationMs
                    sortOrder = row.sortOrder
                    moveInDark = row.moveInDark
                }
                written++
            }
            replaceImmediateFxChildren(cue, recording, mask, state)
            if (recording.palette != null) cue.palette = recording.palette
        }
    }

    val fxWritten = when (mode) {
        RecordMode.MERGE -> appendFxChildren(cue, recording)
        RecordMode.UPDATE_EXISTING -> recording.presetApplications.size + recording.adHocEffects.size
        else -> 0
    }

    return CueWriteOutcome(
        cueId = cue.id.value,
        created = false,
        assignmentsWritten = written,
        assignmentsRemoved = removed,
        groupRowsEmitted = recording.rows.count { it.targetType == TargetRef.Group.TYPE },
        fxWritten = fxWritten,
        preserved = ProgrammerPreservedCounts(
            triggers = triggerCount,
            timedPresetApplications = timedPresets,
            timedAdHocEffects = timedAdHoc,
            outOfMaskAssignments = outOfMask,
        ),
        warnings = warnings,
    )
}

/**
 * Append the recording's FX children, skipping ones the cue already asserts identically.
 *
 * Structural dedupe rather than blind append: recording twice in a row from the same busk
 * should not leave the cue with the effect applied twice, which under a non-OVERRIDE blend
 * mode would visibly double it.
 */
private fun appendFxChildren(cue: DaoCue, recording: ProgrammerRecording): Int {
    var count = 0
    val existingApps = cue.presetApplications.toList()
    for (app in recording.presetApplications) {
        val match = existingApps.firstOrNull { !it.isTimed && it.preset.id.value == app.presetId }
        if (match != null) {
            val merged = LinkedHashSet(match.targets)
            merged.addAll(app.targets)
            if (merged.size != match.targets.size) {
                match.targets = merged.toList()
                count++
            }
            continue
        }
        val preset = DaoFxPreset.findById(app.presetId) ?: continue
        DaoCuePresetApplication.new {
            this.cue = cue
            this.preset = preset
            this.targets = app.targets
            this.sortOrder = app.sortOrder
        }
        count++
    }

    val existingAdHoc = cue.adHocEffects.toList()
    for (effect in recording.adHocEffects) {
        // Upsert, not skip-if-present. `(target, effectType, property)` identifies *which*
        // effect this is; everything else about it — parameters, blend, phase, distribution —
        // is what the operator may have just changed in the programmer. Skipping on a match
        // would make Update silently unable to persist an FX edit: Include a cue, retune the
        // effect's speed, press Update, and the cue keeps the old speed while reporting
        // success. Matches the property-assignment branch of MERGE, which upserts.
        val match = existingAdHoc.firstOrNull {
            !it.isTimed &&
                it.targetType == effect.targetType &&
                it.targetKey == effect.targetKey &&
                it.effectType == effect.effectType &&
                it.propertyName == effect.propertyName
        }
        if (match != null) {
            if (!match.matches(effect)) {
                match.applyFrom(effect)
                count++
            }
            continue
        }
        newAdHocChild(cue, effect)
        count++
    }
    return count
}

/** True when the stored child already says exactly what [dto] says (ignoring cue-owned timing). */
private fun DaoCueAdHocEffect.matches(dto: CueAdHocEffectDto): Boolean =
    category == dto.category &&
        beatDivision == dto.beatDivision &&
        blendMode == dto.blendMode &&
        distribution == dto.distribution &&
        phaseOffset == dto.phaseOffset &&
        elementMode == dto.elementMode &&
        elementFilter == dto.elementFilter &&
        stepTiming == dto.stepTiming &&
        parameters == dto.parameters &&
        speedMasterUuid == speedMasterUuidOrNull(dto.speedMasterUuid) &&
        rateSpeedMasterUuid == speedMasterUuidOrNull(dto.rateSpeedMasterUuid)

/** Copy [dto]'s tunable fields over the stored child, leaving identity and timing alone. */
private fun DaoCueAdHocEffect.applyFrom(dto: CueAdHocEffectDto) {
    category = dto.category
    beatDivision = dto.beatDivision
    blendMode = dto.blendMode
    distribution = dto.distribution
    phaseOffset = dto.phaseOffset
    elementMode = dto.elementMode
    elementFilter = dto.elementFilter
    stepTiming = dto.stepTiming
    parameters = dto.parameters
    speedMasterUuid = speedMasterUuidOrNull(dto.speedMasterUuid)
    rateSpeedMasterUuid = speedMasterUuidOrNull(dto.rateSpeedMasterUuid)
}

/** Delete the immediate FX children the recording names — [RecordMode.REMOVE]'s FX half. */
private fun removeRecordedFxChildren(cue: DaoCue, recording: ProgrammerRecording): Int {
    var removed = 0
    for (app in recording.presetApplications) {
        val match = cue.presetApplications.firstOrNull {
            !it.isTimed && it.preset.id.value == app.presetId
        } ?: continue
        val remaining = match.targets.filterNot { it in app.targets }
        // Only count a removal that removed something. The recording can name the same preset
        // on a *different* target than the cue stores, in which case `remaining` is unchanged
        // and this is a no-op — reporting it as removed would tell the operator a row went
        // away when it didn't.
        if (remaining.size == match.targets.size) continue
        if (remaining.isEmpty()) {
            match.delete()
        } else {
            match.targets = remaining
        }
        removed++
    }
    for (effect in recording.adHocEffects) {
        val match = cue.adHocEffects.firstOrNull {
            !it.isTimed &&
                it.targetType == effect.targetType &&
                it.targetKey == effect.targetKey &&
                it.effectType == effect.effectType &&
                it.propertyName == effect.propertyName
        } ?: continue
        match.delete()
        removed++
    }
    return removed
}

/**
 * Replace the cue's in-mask *immediate* FX children with the recording's. Timed children are
 * left in place regardless of mask — they are not the programmer's to replace.
 */
private fun replaceImmediateFxChildren(
    cue: DaoCue,
    recording: ProgrammerRecording,
    mask: Set<PropertyMaskGroup>?,
    state: State,
) {
    for (effect in cue.adHocEffects.toList()) {
        if (effect.isTimed) continue
        if (mask != null && !maskAllows(mask, maskGroupForAdHocChild(state, effect))) continue
        effect.delete()
    }
    // A preset application has no single property to mask by — a preset can carry effects
    // across several attributes — so it is only replaced when the record is unmasked. A masked
    // re-record leaves presets alone rather than deleting more than was asked for.
    if (mask == null) {
        cue.presetApplications.toList().filterNot { it.isTimed }.forEach { it.delete() }
    }

    for (app in recording.presetApplications) {
        val preset = DaoFxPreset.findById(app.presetId) ?: continue
        if (mask != null && cue.presetApplications.any {
                !it.isTimed && it.preset.id.value == app.presetId
            }
        ) continue
        DaoCuePresetApplication.new {
            this.cue = cue
            this.preset = preset
            this.targets = app.targets
            this.sortOrder = app.sortOrder
        }
    }
    for (effect in recording.adHocEffects) newAdHocChild(cue, effect)
}

private fun maskGroupForAdHocChild(state: State, effect: DaoCueAdHocEffect): PropertyMaskGroup? {
    val propertyName = effect.propertyName ?: return null
    val fixture = try {
        when (val target = effect.target) {
            is TargetRef.Group -> state.show.fixtures.untypedGroup(target.key).fixtures.firstOrNull()
            is TargetRef.Fixture -> state.show.fixtures.untypedGroupableFixture(target.key)
        }
    } catch (_: Exception) {
        null
    } ?: return null
    return uk.me.cormack.lighting7.fx.maskGroupForProperty(fixture, propertyName)
}

private fun newAdHocChild(cue: DaoCue, effect: CueAdHocEffectDto) {
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
        sortOrder = effect.sortOrder
        speedMasterUuid = speedMasterUuidOrNull(effect.speedMasterUuid)
        rateSpeedMasterUuid = speedMasterUuidOrNull(effect.rateSpeedMasterUuid)
    }
}

/**
 * Republish [cueId]'s Layer 3 if it is the live cue of its stack.
 *
 * Without this the cue's DB rows and its published contribution disagree after a Record, and
 * the next Clear would drop the rig back to the pre-record values — the look would appear to
 * record fine and then vanish.
 *
 * Effects are deliberately not re-spawned: the programmer-band instances the recording came
 * from are still running, and spawning cue-owned twins would double-apply them. The cue's FX
 * children go live on its next GO.
 */
internal fun republishCueIfLive(state: State, cueId: Int, cueStackId: Int?): Boolean {
    if (cueStackId == null) return false
    if (state.show.cueStackManager.getActiveCueId(cueStackId) != cueId) return false
    val applyData = loadCueApplyDataForRepublish(state, cueId) ?: return false
    republishCueLayer3(state, cueId, applyData)
    return true
}

private fun loadCueApplyDataForRepublish(state: State, cueId: Int): CueApplyData? =
    org.jetbrains.exposed.v1.jdbc.transactions.transaction(state.database) {
        DaoCue.findById(cueId)?.let { buildCueApplyData(it) }
    }

/** Targets a preset application already covers, for dedupe. */
private operator fun List<CueTargetDto>.contains(other: CueTargetDto): Boolean =
    any { it.type == other.type && it.key == other.key }
