package uk.me.cormack.lighting7.routes

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
import uk.me.cormack.lighting7.fx.ElementFilter
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.IncludedTarget
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.fx.parseMaskGroups
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
 * Must be called inside a transaction.
 */
internal fun writeRecordingIntoLook(
    look: DaoLook,
    collapsed: CollapsedAssignments,
    mode: RecordMode,
    inRemit: (DaoLookRow) -> Boolean,
): LookRecordOutcome {
    var written = 0
    var removed = 0

    fun key(targetType: String, targetKey: String, propertyName: String) =
        Triple(targetType, targetKey, canonicalPropertyName(propertyName))

    // Sorted in memory rather than with `orderBy`: this iterates the referrer collection, and
    // Exposed refuses to order a SizedIterable once it has been loaded.
    val existing = look.rows.sortedBy { it.sortOrder }
    val existingByKey = existing.associateBy { key(it.targetType, it.targetKey, it.propertyName) }

    when (mode) {
        RecordMode.CREATE, RecordMode.UPDATE_EXISTING -> {
            for (row in existing) {
                if (!inRemit(row)) continue
                row.delete()
                removed++
            }
            var nextSort = existing.filterNot(inRemit).maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
            for (row in collapsed.rows) {
                DaoLookRow.new {
                    this.look = look
                    targetType = row.targetType
                    targetKey = row.targetKey
                    propertyName = canonicalPropertyName(row.propertyName)
                    value = row.value
                    sortOrder = nextSort++
                }
                written++
            }
        }

        RecordMode.MERGE -> {
            var nextSort = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
            for (row in collapsed.rows) {
                val hit = existingByKey[key(row.targetType, row.targetKey, row.propertyName)]
                if (hit != null) {
                    hit.value = row.value
                } else {
                    DaoLookRow.new {
                        this.look = look
                        targetType = row.targetType
                        targetKey = row.targetKey
                        propertyName = canonicalPropertyName(row.propertyName)
                        value = row.value
                        sortOrder = nextSort++
                    }
                }
                written++
            }
        }

        RecordMode.REMOVE -> {
            for (row in collapsed.rows) {
                existingByKey[key(row.targetType, row.targetKey, row.propertyName)]?.let {
                    it.delete()
                    removed++
                }
            }
        }
    }

    return LookRecordOutcome(look.id.value, look.uuid, written, removed)
}

/**
 * Is a stored Look row inside the remit of a recording made with [mask] and [scope]?
 *
 * Null on either axis means unrestricted. A row whose target or property no longer resolves answers
 * **false** — "leave alone" — matching [maskGroupForRow]'s documented reading for the destructive
 * `UPDATE_EXISTING` pass: a row we cannot classify is a row we must not delete.
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
): (DaoLookRow) -> Boolean = { row ->
    val target = row.target
    if (target == null) {
        false
    } else if (!targetInScope(fixtures, target, scope)) {
        false
    } else {
        val fixture = referenceFixtureOf(fixtures, target)
        fixture != null && maskAllows(mask, maskGroupForProperty(fixture, row.propertyName))
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
    if (mode == RecordMode.CREATE && request.name.isNullOrBlank()) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse("CREATE requires a name"))
    }
    if (mode != RecordMode.CREATE && request.lookId == null) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse("${mode.name} requires lookId"))
    }

    withCurrentProject(state, request.projectId, { p ->
        "Cannot record into project '${p.name}' — only the current project can be modified"
    }) { project ->
        val resolved = transaction(state.database) { resolveLookTarget(project, mode, request) }
        if (resolved.second != null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(resolved.second!!))
            return@withCurrentProject
        }
        val existing = resolved.first

        // Expand the selection outside the transaction — it reads the patch, not the DB.
        val scope = request.targets?.let { expandTargetsToFixtureKeys(state, it) }
        if (request.targets != null && scope!!.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("None of the requested targets resolve to a fixture"),
            )
            return@withCurrentProject
        }

        val (entries, skips) = collectProgrammerEntries(state, source, mask, targets = scope)
        val collapsed = collapseRecordingToAssignments(entries, state.show.fixtures)
        val inRemit = lookRowInRemit(state.show.fixtures, mask, scope)

        // Resolved before the transaction, because it reads the engine rather than the DB — and
        // resolved by id rather than re-derived, so a chase that started between the operator
        // ticking it and pressing Record cannot be swept in.
        val bandEffects = programmerBandEffectsById(state, request.effectIds)

        val outcome = transaction(state.database) {
            val look = existing ?: DaoLook.new {
                this.project = project
                this.name = request.name!!.trim()
                this.notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }
                this.sortOrder = (
                    DaoLook.find { DaoLooks.project eq project.id }.maxOfOrNull { it.sortOrder } ?: -1
                    ) + 1
            }
            val written = writeRecordingIntoLook(look, collapsed, mode, inRemit)
            writeLookEffects(look, bandEffects, mode)
            written
        }

        // Only once the rows are committed: the layer applying this Look starts running these the
        // moment it is added, so removing them first would leave a gap, and removing them after a
        // failed write would stop an effect the operator still has.
        for (effect in bandEffects) state.show.fxEngine.removeEffect(effect.id)

        // Re-resolve and republish: a re-record of a layered Look must move its consumers, exactly
        // as an edit does.
        val republish = republishForLookEdit(state, outcome.lookUuid)
        state.show.fixtures.lookListChanged()

        val details = transaction(state.database) {
            DaoLook.findById(outcome.lookId)!!.toDetailsDto(state)
        }
        logger.info(
            "record-look {} '{}': {} written, {} removed, {} group row(s), {} effect(s)",
            mode, details.name, outcome.written, outcome.removed, collapsed.groupRows,
            bandEffects.size,
        )
        call.respond(
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
            )
        )
    }
}

/**
 * The programmer-band effects the operator ticked, in the order they were asked for.
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
    return ids.mapNotNull { id ->
        val effect = byId[id] ?: return@mapNotNull null
        if (!FxEngine.isProgrammerFxPriority(effect.priority)) return@mapNotNull null
        if (effect.programmerLayerId != null) return@mapNotNull null
        effect
    }
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
 * A group-targeted instance stays group-targeted. That keeps the Look reusable in the way the
 * operator built it: a chase over "SL Wash" is about the group, not about four heads that happened
 * to be in it.
 */
internal fun writeLookEffects(look: DaoLook, effects: List<FxInstance>, mode: RecordMode) {
    // CREATE and UPDATE_EXISTING replace the Look's contents, so their effects go with the rows —
    // otherwise a re-record would accumulate a second copy of every chase. MERGE adds.
    if (mode == RecordMode.CREATE || mode == RecordMode.UPDATE_EXISTING) {
        look.effects.forEach { it.delete() }
    }
    if (effects.isEmpty()) return
    var nextSort = (look.effects.maxOfOrNull { it.sortOrder } ?: -1) + 1
    for (effect in effects) {
        DaoLookEffect.new {
            this.look = look
            targetType = if (effect.isGroupEffect) TargetRef.Group.TYPE else TargetRef.Fixture.TYPE
            targetKey = effect.target.targetKey
            effectType = effect.effectTypeId
            category = categoryFromPropertyName(effect.target.propertyName)
            propertyName = effect.target.propertyName
            beatDivision = effect.timing.beatDivision
            blendMode = effect.blendMode.name
            distribution = effect.distributionStrategy.javaClass.simpleName
            phaseOffset = effect.phaseOffset
            elementMode = if (effect.isGroupEffect) effect.elementMode.name else null
            elementFilter = if (effect.elementFilter != ElementFilter.ALL) {
                effect.elementFilter.name
            } else null
            stepTiming = if (effect.stepTiming != effect.effect.defaultStepTiming) {
                effect.stepTiming
            } else null
            parameters = effect.effect.parameters
            speedMasterUuid = effect.speedMasterUuid
            rateSpeedMasterUuid = effect.rateSpeedMasterUuid
            sortOrder = nextSort++
        }
    }
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
    request: ProgrammerRecordLookRequest,
): Pair<DaoLook?, String?> {
    if (mode == RecordMode.CREATE) {
        val name = request.name!!.trim()
        val clash = DaoLook.find {
            (DaoLooks.project eq project.id) and (DaoLooks.name eq name)
        }.firstOrNull()
        return if (clash != null) null to "A look called '$name' already exists" else null to null
    }
    val look = DaoLook.findById(request.lookId!!)?.takeIf { it.project.id == project.id }
        ?: return null to "Look not found"
    return look to null
}

// ─── Mode A Update, where the include target is a Look ──────────────────

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
 * MERGE rather than replace, so rows outside the current selection or mask survive — Update never
 * deletes, which is `Record REMOVE`'s job.
 */
internal suspend fun RoutingContext.updateIncludedLook(
    state: State,
    project: DaoProject,
    includeTarget: IncludedTarget,
    mask: Set<PropertyMaskGroup>?,
) {
    val lookId = includeTarget.lookId!!
    val located = transaction(state.database) {
        DaoLook.findById(lookId)?.takeIf { it.project.id == project.id }?.name
    }
    if (located == null) {
        // Stale target: the Look was deleted since Include. Clear it so the indicator stops
        // offering it, and say so rather than silently writing nothing.
        state.show.programmerStore.clearIncludeTargetForLook(lookId)
        call.respond(
            HttpStatusCode.Conflict,
            ProgrammerConflictResponse(
                "The look that was included no longer exists in this project.",
                CODE_INCLUDE_TARGET_GONE,
                lookId,
            ),
        )
        return
    }

    val (changed, skips) = changedSinceInclude(state, mask)
    val collapsed = collapseRecordingToAssignments(changed, state.show.fixtures)

    val outcome = transaction(state.database) {
        // MERGE never deletes, so the remit predicate is never consulted; passing "nothing is in
        // remit" states that rather than leaving a live predicate a later edit could start reading.
        writeRecordingIntoLook(DaoLook.findById(lookId)!!, collapsed, RecordMode.MERGE) { false }
    }
    val republish = republishForLookEdit(state, outcome.lookUuid)
    state.show.fixtures.lookListChanged()

    logger.info(
        "update-look '{}': {} row{} written, {} programmer key(s) refreshed, {} cue(s) republished",
        located, outcome.written, if (outcome.written == 1) "" else "s",
        republish.programmerKeysRefreshed, republish.cuesRepublished.size,
    )
    call.respond(
        ProgrammerUpdateResponse(
            applied = outcome.written > 0,
            mode = "A",
            lookResult = ProgrammerLookUpdateResult(
                lookId = lookId,
                lookName = located,
                rowsWritten = outcome.written,
                programmerKeysRefreshed = republish.programmerKeysRefreshed,
                cuesRepublished = republish.cuesRepublished,
            ),
            skipped = skips.map { it.toDto() },
        )
    )
}
