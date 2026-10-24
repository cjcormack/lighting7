package uk.me.cormack.lighting7.routes

import java.time.Duration

import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.FxRegistry
import uk.me.cormack.lighting7.fx.EffectSpecCoercion
import uk.me.cormack.lighting7.fx.ElementFilter
import uk.me.cormack.lighting7.fx.ElementMode
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.fx.CueApplyData
import uk.me.cormack.lighting7.fx.buildCueApplyData
import uk.me.cormack.lighting7.fx.republishCueLayer
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.CueLayerDto
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.withStoredTargets
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.EffectTargetRef
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.toDto
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.models.asDuration
import uk.me.cormack.lighting7.models.entryInStack
import uk.me.cormack.lighting7.show.referenceFixtureFor

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
    /**
     * Timed layers left untouched. Was `timedPresetApplications`; a preset application is now a
     * layer, and Record has never touched a timed child of either kind — see the invariants above.
     */
    val timedLayers: Int = 0,
    val timedAdHocEffects: Int = 0,
    val outOfMaskAssignments: Int = 0,
    /** Rows left alone because they name a fixture outside the request's `targets` selection. */
    val outOfScopeAssignments: Int = 0,
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
    /** The programmer's held scenery written into the cue (scenery-programmer plan D7). */
    val scenery: SceneryRecordOutcome = SceneryRecordOutcome(),
)

/** A child with timing belongs to `CueTriggerManager`, not to Record. */
private val DaoCueAdHocEffect.isTimed: Boolean get() = delay != null || interval != null

/** Match key for a property assignment: target plus canonical property name. */
private fun assignmentKey(targetType: String, targetKey: String, propertyName: String) =
    Triple(targetType, targetKey, canonicalPropertyName(propertyName))

private fun DaoCuePropertyAssignment.matchKey() = assignmentKey(targetType, targetKey, attribute)
private fun CuePropertyAssignmentDto.matchKey() = assignmentKey(targetType, targetKey, propertyName)

/**
 * Create a cue in [stack] from [recording]. Mirrors the `POST /cues` create path — a new entry at
 * [sortOrder] (a position in the list) or appended, through `placeCueInStack` — so a recorded cue
 * is indistinguishable from a hand-made one.
 *
 * Must run inside a transaction.
 */
internal fun createCueFromRecording(
    project: DaoProject,
    /** The stack the new cue joins; null makes a loose cue (cue model plan §3.5). */
    stack: DaoCueStack?,
    recording: ProgrammerRecording,
    name: String,
    cueNumber: String?,
    sortOrder: Int?,
    cueType: CueType,
    registry: FxRegistry,
): CueWriteOutcome {
    check(cueType == CueType.STANDARD) { "A marker is not a cue — `performProgrammerRecord` refuses one first" }
    val cue = DaoCue.new {
        this.name = name
        this.project = project
    }
    writeCueChildren(
        cue, recording.adHocEffects, recording.rows,
        layers = recording.layers,
        created = true,
        registry = registry,
    )
    // Its place is a new entry: at `sortOrder` (a position in the list), else appended; an
    // unnumbered cue takes its number from where it landed.
    if (stack != null) placeCueInStack(stack, cue, cueNumber = cueNumber, position = sortOrder)

    return CueWriteOutcome(
        cueId = cue.id.value,
        created = true,
        assignmentsWritten = recording.rows.size,
        assignmentsRemoved = 0,
        groupRowsEmitted = recording.groupRowsEmitted,
        fxWritten = recording.adHocEffects.size,
        preserved = ProgrammerPreservedCounts(),
        warnings = emptyList(),
    )
}

/**
 * Apply [recording] to an existing [cue] under [mode].
 *
 * Must run inside a transaction. The caller republishes Layer 4 afterwards if the cue is live
 * — see [republishCueLayer].
 */
internal fun writeRecordingIntoCue(
    state: State,
    cue: DaoCue,
    recording: ProgrammerRecording,
    mode: RecordMode,
    mask: Set<PropertyMaskGroup>?,
    /**
     * Fixture scope, as [targetInScope] reads it. Only [RecordMode.UPDATE_EXISTING] needs it
     * explicitly: it is the one mode that deletes rows the recording does *not* name, so without
     * a scope guard "re-record these two heads" would clear the other ten. MERGE and REMOVE act
     * only on `recording.rows`, which the capture already filtered.
     */
    scope: Set<String>? = null,
): CueWriteOutcome {
    require(mode != RecordMode.CREATE) { "CREATE is handled by createCueFromRecording" }

    val warnings = ArrayList<String>()
    // Timed children of either kind are never touched by Record; counted so the response can say
    // so rather than leaving the operator to wonder why a delayed layer survived a REMOVE.
    val timedLayerCount = cue.layers.count { it.isTimed }
    val timedAdHoc = cue.adHocEffects.count { it.isTimed }
    val triggerCount = cue.triggers.count().toInt()

    var written = 0
    var removed = 0
    var outOfMask = 0
    var outOfScope = 0
    var fxReplaced = 0

    when (mode) {
        RecordMode.CREATE -> error("unreachable")

        RecordMode.MERGE -> {
            val existing = cue.propertyAssignments.associateBy { it.matchKey() }
            for (row in recording.rows) {
                val match = existing[row.matchKey()]
                if (match != null) {
                    match.value = row.value
                    match.fadeDuration = row.fadeDurationMs.asDuration()
                    match.moveInDark = row.moveInDark
                } else {
                    DaoCuePropertyAssignment.new {
                        this.cue = cue
                        targetType = row.targetType
                        targetKey = row.targetKey
                        attribute = row.propertyName
                        value = row.value
                        fadeDuration = row.fadeDurationMs.asDuration()
                        sortOrder = row.sortOrder
                        moveInDark = row.moveInDark
                    }
                }
                written++
            }
        }

        RecordMode.REMOVE -> {
            for (layer in recording.layers) {
                cue.layers.toList()
                    .filter { !it.isTimed && it.isSameLayerAs(layer) }
                    .forEach { it.delete() }
            }
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
                val property = canonicalPropertyName(groupRow.attribute)
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
            val replaced = ArrayList<DaoCuePropertyAssignment>()
            for (row in cue.propertyAssignmentsInOrder()) {
                // Scope first, and checked before the mask so the count the operator sees names
                // the narrowing they chose. A group row survives unless *every* member is in the
                // selection — replacing one would rewrite the value for heads outside it.
                if (!targetInScope(state.show.fixtures, row.toDto().target, scope)) {
                    outOfScope++
                    continue
                }
                val group = maskGroupForRow(state.show.fixtures, row.toDto())
                // A row whose fixture or property no longer resolves has no derivable mask
                // group. Under a mask that means "leave it alone": a patch change must never
                // turn a scoped re-record into a delete of rows it can't classify.
                if (mask != null && !maskAllows(mask, group)) {
                    outOfMask++
                    continue
                }
                replaced += row
            }
            // A replaced row recorded again is written over, keeping its uuid; it ends as a fresh
            // insert would. The rest of the replaced rows go, and every one counts as removed.
            val pairing = pairByKey(replaced, recording.rows, { it.rowKey }, { it.rowKey })
            pairing.unmatched.forEach { it.delete() }
            removed += replaced.size
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
                written++
            }
            fxReplaced = replaceImmediateFxChildren(cue, recording, mask, state, scope, warnings)
        }
    }

    val fxWritten = when (mode) {
        RecordMode.MERGE -> appendFxChildren(cue, recording, state.show.fxRegistry)
        RecordMode.UPDATE_EXISTING -> fxReplaced
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
            timedLayers = timedLayerCount,
            timedAdHocEffects = timedAdHoc,
            outOfMaskAssignments = outOfMask,
            outOfScopeAssignments = outOfScope,
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
private fun appendFxChildren(cue: DaoCue, recording: ProgrammerRecording, registry: FxRegistry): Int {
    var count = 0

    // Layers first, upserted on `(source, targets)` — a Look's on its source, since it lands whole
    // (absolute-looks plan P5). Upsert rather than append for the same reason the ad-hoc branch below
    // does: the key says *which* layer this is, while amount, blend and mask are what the operator may
    // just have changed in the programmer. Appending would give a cue a second copy of the same layer
    // on every Update, and each Record would double it.
    val existingLayers = cue.layers.toList()
    for (layer in recording.layers) {
        val match = existingLayers.firstOrNull { !it.isTimed && it.isSameLayerAs(layer) }
        if (match != null) {
            if (match.applyFrom(layer)) count++
            continue
        }
        val resolved = resolveCueLayerSource(layer) ?: continue
        DaoCueLayer.new {
            this.cue = cue
            this.look = resolved.look
            this.palette = resolved.template
            this.sortOrder = layer.sortOrder
            this.enabled = layer.enabled
            this.targets = layer.withStoredTargets().targets
            this.attributeMask = layer.propertyMask
            this.blendMode = layer.blendMode
            this.amount = layer.amount
            this.stomp = layer.stomp
            this.speedMasterUuid = speedMasterUuidOrNull(layer.speedMasterUuid)
            this.rateSpeedMasterUuid = speedMasterUuidOrNull(layer.rateSpeedMasterUuid)
        }
        count++
    }

    // A preset-application merge loop stood here, beside the layer one above. It was already
    // unreachable: `recording.presetApplications` had been `emptyList()` since `captureCurrentState`
    // moved to keying on `lookId`, so it iterated nothing. Gone with `cue_preset_applications`.
    val existingAdHoc = cue.adHocEffectsInOrder()
    // Upsert, not skip-if-present. `(target, effectType, property)` identifies *which* effect this
    // is; everything else about it — parameters, blend, phase, distribution — is what the operator
    // may have just changed in the programmer. Skipping on a match would make Update silently
    // unable to persist an FX edit: Include a cue, retune the effect's speed, press Update, and the
    // cue keeps the old speed while reporting success. Matches the property-assignment branch of
    // MERGE, which upserts.
    val matches = matchStoredChildren(existingAdHoc, recording.adHocEffects)
    // Every child the recording names, in its (apply) order, and those of them nothing else changed.
    val recorded = ArrayList<DaoCueAdHocEffect>()
    val unchanged = HashSet<DaoCueAdHocEffect>()
    for ((effect, match) in recording.adHocEffects.zip(matches)) {
        if (match != null) {
            if (!match.matches(effect, registry)) {
                match.applyFrom(effect)
                count++
            } else {
                unchanged += match
            }
            recorded += match
            continue
        }
        recorded += newAdHocChild(cue, effect)
        count++
    }
    // A child written again only to take its place in the apply order is written too.
    if (recorded.isNotEmpty()) {
        count += renumberInApplyOrder(existingAdHoc, recorded, DaoCueAdHocEffect::sortOrder).count { it in unchanged }
    }
    return count
}

/**
 * The stored child each of [recorded] writes onto, or null for a new one. One that Include brought
 * back (its `uuid` names the child) takes that child, whatever its type is now; the rest take, in
 * order, the first child of their target, type and property that nothing else took. So two stacked
 * FX of one type on one head stay two children, each written back onto its own.
 */
private fun matchStoredChildren(
    stored: List<DaoCueAdHocEffect>,
    recorded: List<CueAdHocEffectDto>,
): List<DaoCueAdHocEffect?> {
    fun DaoCueAdHocEffect.drives(effect: CueAdHocEffectDto) =
        !isTimed && targetType == effect.targetType && targetKey == effect.targetKey && attribute == effect.propertyName
    fun DaoCueAdHocEffect.isChildOf(effect: CueAdHocEffectDto) = drives(effect) && fxType == effect.effectType
    val byUuid = stored.associateBy { it.uuid.toString() }
    val taken = HashSet<DaoCueAdHocEffect>()
    // Origins first, so an FX matched by key alone cannot take the child another FX came from. Its
    // type need not match: one swapped in place is still that child.
    val byOrigin = recorded.map { effect ->
        effect.uuid?.let(byUuid::get)?.takeIf { it.drives(effect) && taken.add(it) }
    }
    return recorded.zip(byOrigin) { effect, origin ->
        origin ?: stored.firstOrNull { it.isChildOf(effect) && it !in taken }?.also { taken += it }
    }
}

/**
 * Overwrite a stored layer's tunable fields from [dto], returning whether anything moved.
 *
 * `lookId` and `targets` are the identity this was matched on, so they are not written; timing is
 * cue-owned and never touched by Record (§ the two invariants at the top of this file).
 */
private fun DaoCueLayer.applyFrom(dto: CueLayerDto): Boolean {
    var changed = false
    if (enabled != dto.enabled) { enabled = dto.enabled; changed = true }
    if (attributeMask != dto.propertyMask) { attributeMask = dto.propertyMask; changed = true }
    if (blendMode != dto.blendMode) { blendMode = dto.blendMode; changed = true }
    if (amount != dto.amount) { amount = dto.amount; changed = true }
    if (stomp != dto.stomp) { stomp = dto.stomp; changed = true }
    val speed = speedMasterUuidOrNull(dto.speedMasterUuid)
    if (speedMasterUuid != speed) { speedMasterUuid = speed; changed = true }
    val rate = speedMasterUuidOrNull(dto.rateSpeedMasterUuid)
    if (rateSpeedMasterUuid != rate) { rateSpeedMasterUuid = rate; changed = true }
    return changed
}

/** True when the stored child already says what [dto] says, read as [canonical] reads both. */
private fun DaoCueAdHocEffect.matches(dto: CueAdHocEffectDto, registry: FxRegistry): Boolean =
    toDto().canonical(registry) == dto.canonical(registry)

/**
 * [effect]'s settings in one spelling, so a stored child and the FX Capture read back compare equal
 * whenever they spawn the same effect: names as the spawner parses them, an element mode only over
 * members, the filter and step timing filled in with their defaults, the parameters laid over the
 * library's, Seconds and the masters as `applyTimingChoice` reads them, and no category beside a
 * property, which the spawner then never reads. Timing, place and uuids are blanked: the cue owns the
 * first two, and the match was made on the last.
 */
private fun CueAdHocEffectDto.canonical(registry: FxRegistry): CueAdHocEffectDto {
    val registration = registry.getRegistration(effectType)
    val overMembers = targetType == TargetRef.Group.TYPE || targetType == EffectTargetRef.Selection.TYPE
    val names = EffectSpecCoercion.Names
    // A wall-clock effect is on Seconds whenever it has no rate master, flag or not.
    val beatSeconds = seconds && registration?.timingSource != TimingSource.WALL_CLOCK
    return copy(
        effectType = registration?.id ?: effectType,
        category = if (propertyName != null) "" else category,
        blendMode = names.blendMode(blendMode)?.name ?: blendMode,
        distribution = names.distribution(distribution)?.javaClass?.simpleName ?: distribution,
        elementMode = if (overMembers) {
            elementMode?.let { names.elementMode(it)?.name ?: it } ?: ElementMode.PER_FIXTURE.name
        } else null,
        elementFilter = elementFilter?.let { names.elementFilter(it)?.name ?: it } ?: ElementFilter.ALL.name,
        stepTiming = stepTiming ?: registration?.defaultStepTiming ?: false,
        parameters = registration?.parameters.orEmpty().associate { it.name to it.defaultValue } + parameters,
        seconds = beatSeconds,
        speedMasterUuid = if (beatSeconds) null else speedMasterUuidOrNull(speedMasterUuid)?.toString(),
        rateSpeedMasterUuid = if (seconds) null else speedMasterUuidOrNull(rateSpeedMasterUuid)?.toString(),
        direction = names.canonicalDirection(direction),
        delayMs = null,
        intervalMs = null,
        randomWindowMs = null,
        sortOrder = 0,
        uuid = null,
        runUuid = null,
    )
}

/**
 * [take] [dto] over the stored child but for its timing and place: the cue owns the one, and the
 * renumber the other. Its type is written too, which differs only for an FX Include brought back and
 * swapped since.
 */
private fun DaoCueAdHocEffect.applyFrom(dto: CueAdHocEffectDto) = take(
    dto.copy(
        delayMs = delay?.toMillis(),
        intervalMs = interval?.toMillis(),
        randomWindowMs = randomWindow?.toMillis(),
        sortOrder = sortOrder,
    ),
)

/**
 * Delete the immediate FX children the recording names — [RecordMode.REMOVE]'s FX half. Each
 * recorded FX deletes the child [matchStoredChildren] pairs it with, so of two stacked FX of one
 * type on one head that Include brought back, the one still running is the one removed.
 */
private fun removeRecordedFxChildren(cue: DaoCue, recording: ProgrammerRecording): Int {
    val matches = matchStoredChildren(cue.adHocEffectsInOrder(), recording.adHocEffects).filterNotNull()
    matches.forEach { it.delete() }
    return matches.size
}

/**
 * Replace the cue's in-mask *immediate* FX children with the recording's. Timed children are
 * left in place regardless of mask — they are not the programmer's to replace.
 *
 * Returns how many layers and effects it wrote.
 */
private fun replaceImmediateFxChildren(
    cue: DaoCue,
    recording: ProgrammerRecording,
    mask: Set<PropertyMaskGroup>?,
    state: State,
    scope: Set<String>? = null,
    warnings: MutableList<String> = ArrayList(),
): Int {
    var written = 0
    // What the recording replaces. A replaced child the recording names again is written over,
    // keeping its uuid (show-cue-editing plan P3); the rest are deleted once the recording is in.
    val replacedEffects = cue.adHocEffectsInOrder().filter { effect ->
        !effect.isTimed &&
            effectTargetInScope(state.show.fixtures, effect.target, scope) &&
            (mask == null || maskAllows(mask, maskGroupForAdHocChild(state, effect)))
    }
    // Layers, same rule as preset applications had and for the same reason: a Look can span
    // several attribute families, so there is no single property to mask by. A masked re-record
    // therefore leaves the layer stack alone rather than deleting more than was asked for. A scoped
    // one strips only the selected targets from a palette layer, and takes a Look — the cue's and
    // the recording's alike — only when it lands on fixtures and every one is in scope
    // (absolute-looks plan P5): a Look cannot be narrowed, so one reaching outside the selection,
    // or landing on none, is left as it is.
    if (mask == null) {
        val inScope = HashMap<Int, Boolean>()
        fun lookInScope(lookId: Int?): Boolean = lookId == null || scope == null || inScope.getOrPut(lookId) {
            val own = DaoLook.findById(lookId)?.ownTargets(state.show.fixtures).orEmpty()
            own.isNotEmpty() && own.all { targetInScope(state.show.fixtures, it.target, scope) }
        }
        val replacedLayers = ArrayList<DaoCueLayer>()
        for (layer in cue.layersInOrder()) {
            if (layer.isTimed) continue
            val lookId = layer.readValues[DaoCueLayers.look]?.value
            if (scope == null || (lookId != null && lookInScope(lookId))) {
                replacedLayers += layer
                continue
            }
            if (lookId != null) continue
            val kept = layer.targets.filterNot { targetInScope(state.show.fixtures, it.target, scope) }
            if (kept.isEmpty()) replacedLayers += layer else layer.targets = kept
        }
        val (recorded, leftOut) = recording.layers.partition { lookInScope(it.lookId) }
        for (layer in leftOut) {
            val name = layer.lookId?.let { DaoLook.findById(it)?.name } ?: "#${layer.lookId}"
            warnings += "Look '$name' was not recorded — it lands outside the selection, and a Look is recorded whole"
        }
        val resolved = recorded.mapNotNull { layer -> resolveCueLayerSource(layer)?.let { layer to it } }
        val pairing = pairByKey(replacedLayers, resolved, { it.layerKey }, { it.second.layerKey })
        pairing.unmatched.forEach { it.delete() }
        for ((item, stored) in pairing.pairs) {
            val (layer, source) = item
            if (stored != null) {
                stored.take(layer)
            } else {
                DaoCueLayer.new {
                    this.cue = cue
                    this.look = source.look
                    this.palette = source.template
                    take(layer)
                }
            }
            written++
        }
    }

    // Two more preset-application blocks stood here — an unmasked-only replace sweep, and an
    // insert loop with the same group-shadow warning the ad-hoc one below still emits. Both had
    // layer equivalents beside them already, and both iterated an always-empty list.
    val storedEffects = cue.adHocEffectsInOrder()
    val recorded = ArrayList<DaoCueAdHocEffect>()
    val queues = replacedEffects.groupByTo(LinkedHashMap()) { it.effectKey }.mapValues { ArrayDeque(it.value) }
    // Replaced effects not yet written over: gone as far as the shadow check is concerned, exactly
    // as if they had been deleted before the recording went in.
    val pending = replacedEffects.mapTo(HashSet()) { it.id.value }
    for (effect in recording.adHocEffects) {
        // Same hazard, same rule — an ad-hoc child is identified by (effectType, property).
        val shadowed = shadowingCoverage(
            state, scope,
            expandTargetsToFixtureKeys(state, listOf(CueTargetDto(effect.targetType, effect.targetKey))),
            cue.adHocEffects
                .filter {
                    it.id.value !in pending && !it.isTimed && it.fxType == effect.effectType &&
                        it.attribute == effect.propertyName
                }
                .map { listOf(CueTargetDto(it.targetType, it.targetKey)) },
        )
        if (shadowed.isNotEmpty()) {
            warnings += "${effect.effectType} already covers ${shadowed.joinToString()} via a " +
                "group row the selection only partly covers — narrow the selection to the whole " +
                "group, or remove that row"
            continue
        }
        val stored = queues[effect.effectKey]?.removeFirstOrNull()
        if (stored != null) {
            pending -= stored.id.value
            // Its place is the renumber's to change, not the recording's index.
            val place = stored.sortOrder
            stored.take(effect)
            stored.sortOrder = place
            recorded += stored
        } else {
            recorded += newAdHocChild(cue, effect)
        }
        written++
    }
    replacedEffects.filter { it.id.value in pending }.forEach { it.delete() }
    if (recorded.isNotEmpty()) {
        renumberInApplyOrder(storedEffects.filter { it.id.value !in pending }, recorded, DaoCueAdHocEffect::sortOrder)
    }
    return written
}

/**
 * Which of [wanted] are already covered by one of [existing]'s target sets.
 *
 * Only meaningful for a scoped record: an unscoped one deletes every immediate child before
 * re-inserting, so nothing can survive to overlap. Returns empty when [scope] is null so the
 * unscoped path keeps its existing behaviour exactly.
 */
private fun shadowingCoverage(
    state: State,
    scope: Set<String>?,
    wanted: Set<String>,
    existing: List<List<CueTargetDto>>,
): Set<String> {
    if (scope == null || existing.isEmpty()) return emptySet()
    val covered = existing.flatMapTo(HashSet()) { expandTargetsToFixtureKeys(state, it) }
    return wanted intersect covered
}

private fun maskGroupForAdHocChild(state: State, effect: DaoCueAdHocEffect): PropertyMaskGroup? {
    val propertyName = effect.attribute ?: return null
    val fixture = state.show.fixtures.referenceFixtureFor(effect.target) ?: return null
    return uk.me.cormack.lighting7.fx.maskGroupForProperty(fixture, propertyName)
}

private fun newAdHocChild(cue: DaoCue, effect: CueAdHocEffectDto): DaoCueAdHocEffect =
    DaoCueAdHocEffect.new {
        this.cue = cue
        targetType = effect.targetType
        targetKey = effect.targetKey
        take(effect)
    }

/**
 * Republish [cueId]'s Layer 4 if anything holds it live — a stack or a loose press.
 *
 * Without this the cue's DB rows and its published contribution disagree after a Record, and
 * the next Clear would drop the rig back to the pre-record values — the look would appear to
 * record fine and then vanish.
 *
 * Effects are deliberately not re-spawned: the programmer-band instances the recording came
 * from are still running, and spawning cue-owned twins would double-apply them. The cue's FX
 * children go live on its next GO.
 */
internal fun republishCueIfLive(state: State, cueId: Int): Boolean {
    // Live is held — by a stack or a loose press (cue model plan §3.4). It composes at its holds'
    // priority, which the republish asks the registry for, so it needs no entry here.
    if (!state.show.cueStackManager.holds.isHeld(cueId)) return false
    val applyData = loadCueApplyDataForRepublish(state, cueId) ?: return false
    republishCueLayer(state, cueId, applyData)
    return true
}

private fun loadCueApplyDataForRepublish(state: State, cueId: Int): CueApplyData? =
    org.jetbrains.exposed.v1.jdbc.transactions.transaction(state.database) {
        DaoCue.findById(cueId)?.let { buildCueApplyData(it, null) }
    }

/** Targets a preset application already covers, for dedupe. */
private operator fun List<CueTargetDto>.contains(other: CueTargetDto): Boolean =
    any { it.type == other.type && it.key == other.key }
