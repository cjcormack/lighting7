package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.models.timingOf
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.IncludedTarget
import uk.me.cormack.lighting7.fx.LookEffectSettings
import uk.me.cormack.lighting7.fx.lookEffectSettings
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.fx.parseMaskGroups
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import java.util.UUID

private val logger = LoggerFactory.getLogger("lookRecord")

/**
 * Recording the programmer into a **Look**, and Update writing back into one.
 *
 * This replaces the palette pair (`handleProgrammerRecordPalette` / `updateIncludedPalette`), whose
 * destination — `DaoPalette` / `DaoPaletteEntry` — no consumer resolves through any more. The
 * mode/source machinery is unchanged and deliberately so: CREATE / MERGE / REMOVE /
 * UPDATE_EXISTING × TOUCHED / ALL / STAGE_SNAPSHOT is a good vocabulary that cost real thought.
 * Only where the rows land changed.
 *
 * ## The mask stops being implicit
 *
 * A palette's *type was* its mask — `PaletteType` is a `typealias` of [PropertyMaskGroup], so a
 * COLOUR palette recorded colour and nothing else with no argument to get wrong. **A Look has no
 * type**: its families are derived from whatever rows it holds, so one Look can legitimately span
 * colour and position. The mask therefore becomes an explicit request field, and null means "record
 * every family" rather than being impossible to express.
 *
 * ## Which rows a destructive mode may delete
 *
 * The palette and cue write paths disagreed about `CREATE` / `UPDATE_EXISTING`, and the difference
 * was never a decision: `writeRecordingIntoPalette` replaced a palette's contents **outright**,
 * while `writeRecordingIntoCue` deletes only rows inside the recording's mask and target scope.
 *
 * A Look takes the **cue** rule, because the palette rule is unsafe once the mask is explicit. Under
 * replace-outright, "re-record this Look's colour for the front bar" — a mask of COLOUR and a scope
 * of one group — would delete every position row in the Look and every colour row for heads outside
 * the selection, none of which the operator named. [targetInScope]'s "wholly inside" reading is the
 * same predicate Record already uses in the other direction, so a partly-selected group row is
 * preserved rather than half-overwritten.
 */
internal data class LookRecordOutcome(
    val lookId: Int,
    val lookUuid: UUID,
    val written: Int,
    val removed: Int,
)

/**
 * Apply a collapsed recording to a Look's rows.
 *
 * - CREATE / UPDATE_EXISTING replace the rows **within the recording's remit** ([inRemit]), then
 *   insert the recorded ones. See the class doc for why that is not replace-outright.
 * - MERGE upserts the recorded rows and leaves everything else alone — the console re-record.
 * - REMOVE deletes the rows the recording names, values ignored.
 *
 * Recorded values are always literals — the programmer has held nothing else since the `ref:`
 * grammar retired. `validateLookRows` still rejects a `ref:`-shaped value at the write boundary as
 * an inlined shape check, because that rejection *is* the non-recursion guarantee `FU-LOOK-NESTED`
 * depends on.
 *
 * A collapsed row keyed by a **cell** — its `targetKey` is an element key, which is how the
 * programmer holds one — is written as an element row: `targetKey` the parent, `elementKey` the
 * cell (`DaoLookRows.elementKey`, the shape the sync export and the editor already carry).
 * [elementParents] is that resolution, `elementKey → parentKey`, computed by the caller against
 * the patch **before** the transaction so this stays a pure write; a key absent from it is a
 * whole-fixture row. Rows are matched on all of `(targetType, targetKey, elementKey, property)`,
 * so a cell's row and its parent's are two rows, as they are two targets.
 *
 * Must be called inside a transaction.
 */
internal fun writeRecordingIntoLook(
    look: DaoLook,
    collapsed: CollapsedAssignments,
    mode: RecordMode,
    inRemit: (DaoLookRow) -> Boolean,
    elementParents: Map<String, String> = emptyMap(),
): LookRecordOutcome {
    var written = 0
    var removed = 0

    /** The stored shape of a collapsed row: its parent and cell when it names a cell. */
    fun shape(row: CuePropertyAssignmentDto): Pair<String, String?> {
        // Fixture rows only, the same guard [elementParentsOf] applies: a group whose name happened
        // to equal an element key elsewhere in the recording must not be rewritten as a cell.
        if (row.targetType != TargetRef.Fixture.TYPE) return row.targetKey to null
        val parent = elementParents[row.targetKey] ?: return row.targetKey to null
        return parent to row.targetKey
    }

    val existing = look.rowsInOrder()
    val existingByKey = existing.associateBy { it.rowKey }

    /** Everything a recorded row sets but its key, whether written over a stored row or inserted. */
    fun DaoLookRow.take(row: CuePropertyAssignmentDto, sortOrder: Int) {
        attribute = canonicalPropertyName(row.propertyName)
        value = row.value
        fadeDuration = null
        this.sortOrder = sortOrder
    }

    fun insert(row: CuePropertyAssignmentDto, sortOrder: Int) {
        val (parentKey, cellKey) = shape(row)
        DaoLookRow.new {
            this.look = look
            targetType = row.targetType
            targetKey = parentKey
            elementKey = cellKey
            take(row, sortOrder)
        }
    }

    fun keyOf(row: CuePropertyAssignmentDto): LookRowKey {
        val (parentKey, cellKey) = shape(row)
        return lookRowKey(row.targetType, parentKey, cellKey, row.propertyName)
    }

    when (mode) {
        RecordMode.CREATE, RecordMode.UPDATE_EXISTING -> {
            // An in-remit row recorded again is written over, keeping its uuid.
            val (remit, kept) = existing.partition(inRemit)
            val pairing = pairByKey(remit, collapsed.rows, { it.rowKey }, ::keyOf)
            pairing.unmatched.forEach { it.delete() }
            removed = remit.size
            var nextSort = kept.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
            for ((row, stored) in pairing.pairs) {
                if (stored == null) insert(row, nextSort++) else stored.take(row, nextSort++)
                written++
            }
        }

        RecordMode.MERGE -> {
            var nextSort = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
            for (row in collapsed.rows) {
                val hit = existingByKey[keyOf(row)]
                if (hit != null) {
                    hit.value = row.value
                } else {
                    insert(row, nextSort++)
                }
                written++
            }
        }

        RecordMode.REMOVE -> {
            for (row in collapsed.rows) {
                existingByKey[keyOf(row)]?.let {
                    it.delete()
                    removed++
                }
            }
        }
    }

    return LookRecordOutcome(look.id.value, look.uuid, written, removed)
}

/**
 * `elementKey → parentKey` for every collapsed row whose target key resolves to a **cell**, against
 * the live patch. Read by [writeRecordingIntoLook], which must not touch the patch itself.
 */
internal fun elementParentsOf(fixtures: Fixtures, collapsed: CollapsedAssignments): Map<String, String> {
    val out = HashMap<String, String>()
    for (row in collapsed.rows) {
        if (row.targetType != TargetRef.Fixture.TYPE || row.targetKey in out) continue
        val element = runCatching { fixtures.untypedGroupableFixture(row.targetKey) }.getOrNull()
            as? FixtureElement<*> ?: continue
        out[row.targetKey] = element.parentFixture.key
    }
    return out
}

/**
 * Delete [cleared] from [look]'s rows, splitting a group row as [planClearedRemoval] says. A cell
 * row is named by its cell's key. Must run inside a transaction.
 */
internal fun removeClearedFromLook(
    look: DaoLook,
    cleared: Collection<uk.me.cormack.lighting7.fx.ProgrammerStore.IncludedKey>,
    fixtures: Fixtures,
): ClearedOutcome {
    if (cleared.isEmpty()) return ClearedOutcome()
    val rows = look.rowsInOrder()
    val plan = planClearedRemoval(
        rows.map { StoredValueRow(it, it.targetType, it.elementKey ?: it.targetKey, it.attribute) },
        cleared,
        groupMembersIn(fixtures),
    )
    var nextSort = (rows.maxOfOrNull { it.sortOrder } ?: -1) + 1
    for ((group, member) in plan.split) {
        DaoLookRow.new {
            this.look = look
            targetType = TargetRef.Fixture.TYPE
            targetKey = member
            elementKey = null
            attribute = group.attribute
            value = group.value
            fadeDuration = group.fadeDuration
            sortOrder = nextSort++
        }
    }
    plan.delete.forEach { it.delete() }
    return ClearedOutcome(plan.delete.size, plan.split.size, plan.unmatched.map(::unmatchedClearWarning))
}

/**
 * Is a stored Look row inside the remit of a recording made with [mask] and [scope]?
 *
 * Null on either axis means unrestricted. A row whose target or property no longer resolves answers
 * **false** — "leave alone" — matching [maskGroupForRow]'s documented reading for the destructive
 * `UPDATE_EXISTING` pass: a row we cannot classify is a row we must not delete.
 *
 * An **element row** is in scope when the scope covers its cell — the cell itself, or its parent,
 * by [TargetCoverage.covers] — and its mask group is read off the element's own properties.
 *
 * A row with **no target** is never in remit. Session 3 made a Look row always bound — the deferred
 * half became [uk.me.cormack.lighting7.models.DaoTemplates] — so this arm only ever sees a row left
 * behind by an older database, and "leave alone" is the one safe thing to do with a row this code
 * can no longer classify. It used to be classifiable, against the Look's `editorFixtureType`, and
 * that column is gone.
 */
internal fun lookRowInRemit(
    fixtures: Fixtures,
    mask: Set<PropertyMaskGroup>?,
    scope: Set<String>?,
): (DaoLookRow) -> Boolean {
    val coverage = TargetCoverage { fixtures }
    val scopeTargets = scope?.mapTo(HashSet()) { CueTargetDto(TargetRef.Fixture.TYPE, it) }
    return { row ->
        val target = row.target
        val elementKey = row.elementKey
        if (target == null) {
            false
        } else if (elementKey != null) {
            val inScope = scopeTargets == null ||
                coverage.covers(scopeTargets, CueTargetDto(TargetRef.Fixture.TYPE, elementKey))
            val element = runCatching { fixtures.untypedGroupableFixture(elementKey) }.getOrNull()
            inScope && element != null && maskAllows(mask, maskGroupForProperty(element, row.attribute))
        } else if (!targetInScope(fixtures, target, scope)) {
            false
        } else {
            val fixture = referenceFixtureOf(fixtures, target)
            fixture != null && maskAllows(mask, maskGroupForProperty(fixture, row.attribute))
        }
    }
}

private fun referenceFixtureOf(fixtures: Fixtures, target: TargetRef): GroupableFixture? = try {
    when (target) {
        is TargetRef.Group -> fixtures.untypedGroup(target.key).fixtures.firstOrNull()
        is TargetRef.Fixture -> fixtures.untypedGroupableFixture(target.key)
    }
} catch (_: Exception) {
    null
}

// ─── POST /programmer/record-look ───────────────────────────────────────

@Resource("/record-look")
internal class ProgrammerRecordLookResource

@Serializable
internal data class ProgrammerRecordLookRequest(
    val projectId: String,
    /** [RecordMode] name. CREATE makes a new Look; the rest need [lookId]. */
    val mode: String = "CREATE",
    val lookId: Int? = null,
    val name: String? = null,
    val notes: String? = null,
    /** [RecordSource] name. Defaults to TOUCHED — what the operator actually edited. */
    val source: String = "TOUCHED",
    /**
     * [PropertyMaskGroup] names to record. Null or empty records every family.
     *
     * Explicit because a Look has no type to imply it — see the file's class doc.
     */
    val mask: List<String>? = null,
    /**
     * The operator's selection. Groups are expanded server-side.
     *
     * Strongly recommended: a Look recorded from the whole programmer captures every head the
     * programmer happens to hold, which is almost never what "Warm Amber" is meant to mean.
     */
    val targets: List<CueTargetDto>? = null,
    /**
     * Running programmer-band effects to fold into the Look, by [FxInstance.id].
     *
     * **Explicit ids rather than an `includeFx` flag**, unlike `POST /programmer/record`. An
     * effect is a thing an operator ticks: "the colour chase belongs in this look, the tilt sine
     * was just me looking at it" is a per-effect judgement, and a boolean cannot express it.
     *
     * A ticked effect is **moved**, not copied — it is removed from the programmer band, because
     * the layer this Look is applied through starts running it immediately and two copies would
     * beat against each other. An effect left out keeps running exactly as it was: leaving one out
     * of a Look is not the same as stopping it.
     *
     * Timing does not travel. `DaoLookEffects` has no delay/interval columns by design — timing
     * belongs to the layer applying the Look, so it is per-use rather than baked in.
     */
    val effectIds: List<Long> = emptyList(),
    /**
     * Record the programmer's held scenery too (scenery-programmer plan D7, D10): every held state,
     * since a Look asserts rather than tracks — so a Look recorded with only the moon held is a
     * scenery Look, a busk pad that flies it. Not governed by [mask] or [targets].
     */
    val scenery: Boolean = true,
    /**
     * Point the include target at the Look written, as a Record into a cue does. False from Make
     * layer, which applies the new Look as a layer of whatever is loaded: Update must still write
     * that layer into the loaded cue.
     */
    val retarget: Boolean = true,
)

@Serializable
internal data class ProgrammerRecordLookResponse(
    val look: LookDetails,
    val created: Boolean,
    val rowsWritten: Int,
    val rowsRemoved: Int,
    val groupRowsEmitted: Int,
    val skipped: List<ProgrammerSkipDto>,
    /** Programmer-band effects folded into the Look, and so removed from the band. */
    val effectsWritten: Int = 0,
    /** Set when the Look was already live: what the re-resolve moved. */
    val programmerKeysRefreshed: Int = 0,
    val cuesRepublished: List<Int> = emptyList(),
    /** Scenery rows written into the Look. */
    val sceneryWritten: Int = 0,
    /** Scenery rows deleted from the Look (REMOVE). */
    val sceneryRemoved: Int = 0,
)

/**
 * Record the programmer into a Look — the gesture that creates a **bound** Look, which nothing
 * could do while the only record destination was the retired palette tables.
 *
 * Writing contents ends with [republishForLookEdit], so re-recording a Look that cues already layer
 * moves them immediately. That is the same path a Look edit takes, and it is what makes re-record
 * the natural editing gesture rather than a separate concept.
 */
internal suspend fun RoutingContext.handleProgrammerRecordLook(state: State) {
    if (refusedRecordInVenue(state)) return
    val request = call.receive<ProgrammerRecordLookRequest>()
    val mode = parseEnumOrNull<RecordMode>(request.mode) ?: return call.respond(
        HttpStatusCode.BadRequest, ErrorResponse("Unknown record mode '${request.mode}'"),
    )
    val source = parseEnumOrNull<RecordSource>(request.source) ?: return call.respond(
        HttpStatusCode.BadRequest, ErrorResponse("Unknown record source '${request.source}'"),
    )
    val mask = try {
        parseMaskGroups(request.mask)
    } catch (e: IllegalArgumentException) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
    }
    recordLookShapeProblem(mode, request.name, request.lookId)?.let {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
    }

    withCurrentProject(state, request.projectId, { p ->
        "Cannot record into project '${p.name}' — only the current project can be modified"
    }) { project ->
        val result = performProgrammerRecordLook(
            state, project,
            mode = mode,
            source = source,
            lookId = request.lookId,
            name = request.name,
            notes = request.notes,
            mask = mask,
            targets = request.targets,
            effectIds = request.effectIds,
            scenery = request.scenery,
            retarget = request.retarget,
        )
        when (result) {
            is RecordLookCoreResult.Failure -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
            is RecordLookCoreResult.Ok -> call.respond(result.response)
        }
    }
}

internal sealed interface RecordLookCoreResult {
    data class Ok(val response: ProgrammerRecordLookResponse) : RecordLookCoreResult
    data class Failure(val message: String) : RecordLookCoreResult
}

/** What is wrong with a record-look request's shape, before anything is read or written. */
internal fun recordLookShapeProblem(mode: RecordMode, name: String?, lookId: Int?): String? = when {
    mode == RecordMode.CREATE && name.isNullOrBlank() -> "CREATE requires a name"
    mode != RecordMode.CREATE && lookId == null -> "${mode.name} requires lookId"
    else -> null
}

/**
 * Record the programmer into a Look — creating one ([RecordMode.CREATE]) or writing into
 * [lookId]. The REST route and the `record_look` tool both come through here, as Record into a cue
 * comes through [performProgrammerRecord].
 */
internal fun performProgrammerRecordLook(
    state: State,
    project: DaoProject,
    mode: RecordMode,
    source: RecordSource,
    lookId: Int?,
    name: String?,
    notes: String?,
    mask: Set<PropertyMaskGroup>?,
    targets: List<CueTargetDto>?,
    effectIds: List<Long>,
    scenery: Boolean,
    retarget: Boolean = true,
): RecordLookCoreResult {
    recordLookShapeProblem(mode, name, lookId)?.let { return RecordLookCoreResult.Failure(it) }

    val resolved = transaction(state.database) { resolveLookTarget(project, mode, name, lookId) }
    resolved.second?.let { return RecordLookCoreResult.Failure(it) }
    val existing = resolved.first

    // Expand the selection outside the transaction — it reads the patch, not the DB.
    val scope = targets?.let { expandTargetsToFixtureKeys(state, it) }
    if (targets != null && scope!!.isEmpty()) {
        return RecordLookCoreResult.Failure("None of the requested targets resolve to a fixture")
    }

    // `allowElements`: a Look has an element row, so a cell the operator edited is recorded as
    // one rather than skipped — the only recording destination that may ask for this.
    val (entries, skips) = collectProgrammerEntries(state, source, mask, targets = scope, allowElements = true)
    val collapsed = collapseRecordingToAssignments(entries, state.show.fixtures)
    val inRemit = lookRowInRemit(state.show.fixtures, mask, scope)
    val elementParents = elementParentsOf(state.show.fixtures, collapsed)

    // Resolved before the transaction, because it reads the engine rather than the DB — and
    // resolved by id rather than re-derived, so a chase that started between the operator
    // ticking it and pressing Record cannot be swept in.
    val bandEffects = programmerBandEffectsById(state, effectIds)
    val held = if (scenery) heldSceneryOf(state, project.id.value) else emptyMap()

    val (outcome, sceneryOutcome) = transaction(state.database) {
        val look = existing ?: DaoLook.new {
            this.project = project
            this.name = name!!.trim()
            this.notes = notes?.trim()?.takeIf { it.isNotEmpty() }
        }
        val written = writeRecordingIntoLook(look, collapsed, mode, inRemit, elementParents)
        writeLookEffects(look, bandEffects, mode)
        written to writeHeldSceneryIntoLook(look, held, mode)
    }

    // Only once the rows are committed: the layer applying this Look starts running these the
    // moment it is added, so removing them first would leave a gap, and removing them after a
    // failed write would stop an effect the operator still has.
    for (effect in bandEffects) state.show.fxEngine.removeEffect(effect.id)

    // Re-resolve and republish: a re-record of a layered Look must move its consumers, exactly
    // as an edit does.
    val republish = republishForLookEdit(state, outcome.lookUuid)
    state.show.fixtures.lookListChanged()

    // As a Record into a cue does: what was just written is what Update writes back to.
    if (retarget) {
        state.show.programmerStore.lastIncludedTarget = IncludedTarget.look(outcome.lookId, outcome.lookUuid)
        stampRecordedValues(state)
        state.programmerScenery.rebaseline(
            project.id.value,
            written = scenery && mode != RecordMode.REMOVE && sceneryOutcome.warnings.isEmpty(),
        )
    }

    val details = transaction(state.database) {
        DaoLook.findById(outcome.lookId)!!.toDetailsDto(state)
    }
    logger.info(
        "record-look {} '{}': {} written, {} removed, {} group row(s), {} effect(s)",
        mode, details.name, outcome.written, outcome.removed, collapsed.groupRows,
        bandEffects.size,
    )
    return RecordLookCoreResult.Ok(
        ProgrammerRecordLookResponse(
            look = details,
            created = existing == null,
            rowsWritten = outcome.written,
            rowsRemoved = outcome.removed,
            groupRowsEmitted = collapsed.groupRows,
            skipped = skips.map { it.toDto() },
            effectsWritten = bandEffects.size,
            programmerKeysRefreshed = republish.programmerKeysRefreshed,
            cuesRepublished = republish.cuesRepublished,
            sceneryWritten = sceneryOutcome.written,
            sceneryRemoved = sceneryOutcome.removed,
        )
    )
}

/**
 * The programmer-band effects the operator ticked, in [FxEngine.inStoredApplyOrder] whatever order
 * they were asked for in.
 *
 * Filtered to the **programmer band** rather than trusting the ids outright: a client holding a
 * stale effect list could otherwise name a cue's effect and quietly tear it out of a running cue.
 * An id that no longer resolves is dropped silently — the effect has already stopped, which is the
 * outcome the operator was heading for anyway.
 *
 * Effects owned by a Look *layer* are skipped too. They belong to that Look already; recording
 * them into a second one would duplicate a running instance rather than move it, and the operator
 * never authored them here.
 */
internal fun programmerBandEffectsById(state: State, ids: List<Long>): List<FxInstance> {
    if (ids.isEmpty()) return emptyList()
    val byId = state.show.fxEngine.getActiveEffects().associateBy { it.id }
    return state.show.fxEngine.inStoredApplyOrder(
        ids.mapNotNull { id ->
            val effect = byId[id] ?: return@mapNotNull null
            if (!FxEngine.isProgrammerFxPriority(effect.priority)) return@mapNotNull null
            if (effect.programmerLayerId != null || effect.leaving) return@mapNotNull null
            effect
        },
    )
}

/**
 * Write running effects into a Look as [DaoLookEffect] rows.
 *
 * Mirrors `fxInstancesToCueChildren`, which does the same job for a cue's ad-hoc children — the
 * field-by-field shape is the same because [LookEffectDto] was unified with `CueAdHocEffectDto`
 * for exactly this reason. Two differences, both deliberate:
 *
 * - **the tempo travels**: `speedMasterUuid` / `rateSpeedMasterUuid` are carried, so a look
 *   recorded off master 2 still follows master 2 wherever it is applied;
 * - **the timing does not**: there is nowhere to put it. Delay, interval and random window live on
 *   the layer, so a busked "fire after 3s" becomes the layer's delay rather than the Look's.
 *
 * A group-targeted instance stays group-targeted, and one over a selection keeps its selection.
 * That keeps the Look reusable in the way the operator built it: a chase over "SL Wash" is about
 * the group, not about four heads that happened to be in it.
 *
 * The effects are written bottom first in the order given, which [programmerBandEffectsById] makes
 * their stored apply order, so a press spawns them in the order they applied (FX editor plan §3.5).
 */
internal fun writeLookEffects(look: DaoLook, effects: List<FxInstance>, mode: RecordMode) {
    fun targetTypeOf(effect: FxInstance) = effect.target.targetRef.discriminator

    fun DaoLookEffect.take(effect: FxInstance, sortOrder: Int) {
        family = recordedEffectCategory(effect)
        attribute = effect.target.propertyName
        takeSettings(effect.lookEffectSettings())
        this.sortOrder = sortOrder
    }

    // CREATE and UPDATE_EXISTING replace the Look's contents, so their effects go with the rows —
    // otherwise a re-record would accumulate a second copy of every chase. MERGE adds. A stored
    // effect recorded again is written over, keeping its uuid.
    val replace = mode == RecordMode.CREATE || mode == RecordMode.UPDATE_EXISTING
    val pairing = pairByKey(
        if (replace) look.effectsInOrder() else emptyList(), effects,
        { it.effectKey },
        { lookEffectKey(targetTypeOf(it), it.target.targetKey, it.target.propertyName, it.lookEffectSettings().effectType) },
    )
    pairing.unmatched.forEach { it.delete() }
    if (effects.isEmpty()) return
    var nextSort = if (replace) 0 else (look.effects.maxOfOrNull { it.sortOrder } ?: -1) + 1
    for ((effect, stored) in pairing.pairs) {
        val sortOrder = nextSort++
        if (stored != null) {
            stored.take(effect, sortOrder)
        } else {
            DaoLookEffect.new {
                this.look = look
                targetType = targetTypeOf(effect)
                targetKey = effect.target.targetKey
                take(effect, sortOrder)
            }
        }
    }
}

/** [settings] onto this row — everything but where the effect runs and its place in the Look. */
private fun DaoLookEffect.takeSettings(settings: LookEffectSettings) {
    fxType = settings.effectType
    cycle = settings.beatDivision
    blendMode = settings.blendMode
    order = settings.distribution
    phaseOffset = settings.phaseOffset
    elementMode = settings.elementMode
    elementFilter = settings.elementFilter
    timing = timingOf(settings.stepTiming)
    parameters = settings.parameters
    speedMasterUuid = settings.speedMasterUuid
    rateSpeedMasterUuid = settings.rateSpeedMasterUuid
    seconds = settings.seconds
    amount = settings.size
    direction = settings.direction
    runs = settings.nShot
    this.spread = settings.spread
    this.wings = settings.wings
    this.segments = settings.blocks
    this.parts = settings.groups
}

/**
 * [edited]'s changes from [baseline] onto this row, and nothing else: a field the operator did not
 * move keeps what the row says. That matters beyond tidiness — an included instance reports some
 * fields by where it runs (element mode only on a group effect, an element filter or step timing
 * only where it differs from the default), so an effect stored on a fixture with an element mode
 * reads `elementMode = null` on both sides; copying the whole settings would null the stored mode on
 * a parameter-only edit. Parameters merge the same way, key by key — unless the effect's type
 * changed, when they are the new type's.
 */
private fun DaoLookEffect.takeChanges(baseline: LookEffectSettings, edited: LookEffectSettings) {
    if (edited.effectType != baseline.effectType) {
        fxType = edited.effectType
        parameters = edited.parameters
    } else if (edited.parameters != baseline.parameters) {
        parameters = parameters + edited.parameters.filter { (key, value) -> baseline.parameters[key] != value }
    }
    if (edited.beatDivision != baseline.beatDivision) cycle = edited.beatDivision
    if (edited.blendMode != baseline.blendMode) blendMode = edited.blendMode
    if (edited.distribution != baseline.distribution) order = edited.distribution
    if (edited.phaseOffset != baseline.phaseOffset) phaseOffset = edited.phaseOffset
    if (edited.elementMode != baseline.elementMode) elementMode = edited.elementMode
    if (edited.elementFilter != baseline.elementFilter) elementFilter = edited.elementFilter
    if (edited.stepTiming != baseline.stepTiming) timing = timingOf(edited.stepTiming)
    if (edited.size != baseline.size) amount = edited.size
    if (edited.direction != baseline.direction) direction = edited.direction
    if (edited.nShot != baseline.nShot) runs = edited.nShot
    if (edited.spread != baseline.spread) spread = edited.spread
    if (edited.wings != baseline.wings) wings = edited.wings
    if (edited.blocks != baseline.blocks) segments = edited.blocks
    if (edited.groups != baseline.groups) parts = edited.groups
    // The timing is one choice (D7) — Seconds or a master — so it is written whole when any part of it
    // moved: a wall-clock row stored with the flag keeps reading Seconds after a rate master is named
    // unless the flag is cleared with it, and an edit onto Seconds nulls the masters beside it.
    val timingMoved = edited.seconds != baseline.seconds ||
        edited.speedMasterUuid != baseline.speedMasterUuid ||
        edited.rateSpeedMasterUuid != baseline.rateSpeedMasterUuid
    if (timingMoved) {
        speedMasterUuid = edited.speedMasterUuid
        rateSpeedMasterUuid = edited.rateSpeedMasterUuid
        seconds = edited.seconds
    }
}

/**
 * Write back the Look effects Include ran in the programmer that the operator has changed since —
 * Update's effect half, the same "only what changed" rule its value half keeps, against the
 * settings each instance was spawned with ([uk.me.cormack.lighting7.fx.IncludedLookEffect.baseline]),
 * and field by field ([takeChanges]).
 *
 * The edit lands **on the effect it came from**, which keeps its target: what was edited is how it
 * runs, not where. One effect can have several instances — Include again after changing an included
 * effect's type spawns a second — so where more than one was changed the first is taken. An effect
 * the operator stopped is left in the Look (Update deletes no effect — that is Record's Replace or
 * REMOVE), and so is one whose row went since the Include.
 *
 * Must be called inside a transaction.
 */
internal fun writeIncludedEffectsBack(look: DaoLook, instances: List<FxInstance>): Int {
    if (instances.isEmpty()) return 0
    val inOrder = look.effectsInOrder()
    val stored = inOrder.associateBy { it.id.value }
    val changedRows = HashSet<DaoLookEffect>()
    for ((lookEffectId, realising) in instances.groupBy { it.includedLookEffect!!.lookEffectId }) {
        val row = stored[lookEffectId] ?: continue
        val changed = realising.firstNotNullOfOrNull { instance ->
            val baseline = instance.includedLookEffect!!.baseline
            instance.lookEffectSettings().takeIf { it != baseline }?.let { baseline to it }
        } ?: continue
        row.takeChanges(changed.first, changed.second)
        changedRows += row
    }
    // The included effects keep the order they run in now (`instances` is in stored apply order),
    // among the places they hold.
    val realised = instances.mapNotNull { stored[it.includedLookEffect!!.lookEffectId] }
    val moved = renumberInApplyOrder(inOrder, realised, DaoLookEffect::sortOrder)
    return changedRows.size + moved.count { it !in changedRows && it in realised }
}

/**
 * Which Look a record targets: (existing, error). CREATE resolves to (null, null) after checking the
 * name is free — `DaoLooks` carries a `uniqueIndex(project, name)`, so a clash is a 400 rather than
 * a constraint violation surfacing as a 500.
 *
 * Must be called inside a transaction.
 */
private fun resolveLookTarget(
    project: DaoProject,
    mode: RecordMode,
    requestedName: String?,
    lookId: Int?,
): Pair<DaoLook?, String?> {
    if (mode == RecordMode.CREATE) {
        val name = requestedName!!.trim()
        val clash = DaoLook.find {
            (DaoLooks.project eq project.id) and (DaoLooks.name eq name)
        }.firstOrNull()
        return if (clash != null) null to "A look called '$name' already exists" else null to null
    }
    val look = DaoLook.findById(lookId!!)?.takeIf { it.project.id == project.id }
        ?: return null to "Look not found"
    return look to null
}

/**
 * The running programmer-band effects Include spawned from [lookId]'s effects, under [mask] — the
 * family of the property each drives, as Include masked it.
 */
private fun includedLookEffectsOf(state: State, lookId: Int, mask: Set<PropertyMaskGroup>?): List<FxInstance> =
    state.show.fxEngine.inStoredApplyOrder(
        state.show.fxEngine.getActiveEffects().filter { effect ->
            if (effect.includedLookEffect?.lookId != lookId || !FxEngine.isProgrammerFxPriority(effect.priority) || effect.leaving) {
                return@filter false
            }
            if (mask == null) return@filter true
            val reference = state.show.fxEngine.fixtureKeysCoveredBy(effect).firstOrNull()
                ?.let { runCatching { state.show.fixtures.untypedGroupableFixture(it) }.getOrNull() }
            maskAllows(mask, reference?.let { maskGroupForProperty(it, effect.target.propertyName) })
        },
    )

// ─── Mode A Update, where the include target is a Look ──────────────────

/** What [updateIncludedLook] wrote, inside its one transaction. */
private data class LookUpdateWrites(
    val values: LookRecordOutcome,
    val cleared: ClearedOutcome,
    val scenery: SceneryRecordOutcome,
    val effectsWritten: Int,
)

/**
 * Mode A Update where the include target is a Look: write back what changed since Include.
 *
 * This is what makes **Include-a-Look a round trip**. It used to be deliberately one-way — the desk
 * disabled Update for a `LOOK` target and `handleProgrammerUpdate` refused one with
 * `INCLUDE_TARGET_READ_ONLY` — not out of caution but because the only write-back path led into
 * `DaoPalette`, so a success would have reported rows no consumer reads. Both halves of that guard
 * are deleted now that the rows land somewhere real.
 *
 * The "only what changed" rule is what keeps a Look's untouched rows untouched, exactly as it does
 * for a cue: Include stakes an INCLUDE slot per row, and anything still matching it was not edited.
 * MERGE rather than replace, so rows outside the current selection or mask survive. The one row it
 * deletes is one Include staged that the operator has since cleared ([clearedSinceInclude]).
 */
internal fun updateIncludedLook(
    state: State,
    project: DaoProject,
    includeTarget: IncludedTarget,
    mask: Set<PropertyMaskGroup>?,
    /** Write back the Look's included effects the operator changed, as a cue Update writes its FX. */
    includeFx: Boolean = true,
): UpdateCoreResult {
    val lookId = includeTarget.lookId!!
    val located = transaction(state.database) {
        DaoLook.findById(lookId)?.takeIf { it.project.id == project.id }?.name
    }
    if (located == null) {
        // Stale target: the Look was deleted since Include. Clear it so the indicator stops
        // offering it, and say so rather than silently writing nothing.
        state.show.programmerStore.clearIncludeTargetForLook(lookId)
        return UpdateCoreResult.IncludeTargetGone(
            "The look that was included no longer exists in this project.",
            lookId,
        )
    }

    // Cells included: Include stages a Look's element rows, so a nudged cell has to be able to
    // come back through Update as the element row it was — `changedSinceInclude` keeps them.
    val (changed, skips) = changedSinceInclude(state, mask)
    val collapsed = collapseRecordingToAssignments(changed, state.show.fixtures)
    val elementParents = elementParentsOf(state.show.fixtures, collapsed)
    val cleared = clearedSinceInclude(state, includeTarget, mask)
    // The held scenery goes back too (scenery-programmer plan D8): every held state, as a Look takes
    // it, merged over the Look's own rows so a piece the operator never held keeps its row.
    val held = heldSceneryOf(state, project.id.value)
    // The Look's own effects as Include ran them, under the same mask as the values.
    val includedEffects = if (includeFx) includedLookEffectsOf(state, lookId, mask) else emptyList()

    val (outcome, removal, scenery, effectsWritten) = transaction(state.database) {
        val look = DaoLook.findById(lookId)!!
        // MERGE never consults the remit predicate; passing "nothing is in remit" states that
        // rather than leaving a live predicate a later edit could start reading.
        LookUpdateWrites(
            writeRecordingIntoLook(look, collapsed, RecordMode.MERGE, { false }, elementParents),
            removeClearedFromLook(look, cleared, state.show.fixtures),
            writeHeldSceneryIntoLook(look, held, RecordMode.MERGE),
            writeIncludedEffectsBack(look, includedEffects),
        )
    }
    foldUpdateIntoRecorded(state, changed, cleared)
    state.show.programmerStore.forgetIncludedKeys(cleared)
    state.show.programmerStore.rebaseIncludeTarget()
    // What was written is the new baseline: a second Update writes nothing it already wrote.
    for (instance in includedEffects) {
        val origin = instance.includedLookEffect ?: continue
        instance.includedLookEffect = origin.copy(baseline = instance.lookEffectSettings())
    }
    // The rebaseline moved what each reports as `editedSinceInclude`; say so, or the source strip
    // keeps offering an Update that has nothing left to write.
    if (includedEffects.isNotEmpty()) state.show.fxEngine.republishEffectStates()
    state.programmerScenery.rebaseline(project.id.value, written = true)
    val republish = republishForLookEdit(state, outcome.lookUuid)
    state.show.fixtures.lookListChanged()

    logger.info(
        "update-look '{}': {} row{} written, {} removed and {} effect(s) written, {} programmer key(s) refreshed, {} cue(s) republished",
        located, outcome.written + removal.written, if (outcome.written + removal.written == 1) "" else "s",
        removal.removed, effectsWritten,
        republish.programmerKeysRefreshed, republish.cuesRepublished.size,
    )
    return UpdateCoreResult.LookUpdated(
        ProgrammerLookUpdateResult(
            lookId = lookId,
            lookName = located,
            rowsWritten = outcome.written + removal.written,
            rowsRemoved = removal.removed,
            effectsWritten = effectsWritten,
            sceneryWritten = scenery.written,
            programmerKeysRefreshed = republish.programmerKeysRefreshed,
            cuesRepublished = republish.cuesRepublished,
        ),
        skipped = skips,
        warnings = removal.warnings,
    )
}
