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
import uk.me.cormack.lighting7.fx.IncludedTarget
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.fx.parseMaskGroups
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoLook
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
 * A **deferred** row is only ever in remit when there is no scope at all. A deferred row has no
 * target, so no selection can be said to name it, and deleting one because the operator re-recorded
 * two fixtures would silently strip the template half of a Look.
 */
internal fun lookRowInRemit(
    fixtures: Fixtures,
    mask: Set<PropertyMaskGroup>?,
    scope: Set<String>?,
): (DaoLookRow) -> Boolean = { row ->
    val target = row.target
    if (target == null) {
        scope == null && maskAllows(mask, deferredRowMaskGroup(fixtures, row))
    } else if (!targetInScope(fixtures, target, scope)) {
        false
    } else {
        val fixture = referenceFixtureOf(fixtures, target)
        fixture != null && maskAllows(mask, maskGroupForProperty(fixture, row.propertyName))
    }
}

/**
 * The mask group of a deferred row, resolved against the Look's `editorFixtureType` if the patch
 * happens to hold one of those. Null — "don't touch" — when it doesn't, which is the safe answer:
 * a deferred row's property may not exist on any patched head at all.
 */
private fun deferredRowMaskGroup(fixtures: Fixtures, row: DaoLookRow): PropertyMaskGroup? {
    val typeKey = row.look.editorFixtureType ?: return null
    val fixture = fixtures.fixtures.firstOrNull { it.typeKey == typeKey } ?: return null
    return maskGroupForProperty(fixture, row.propertyName)
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
)

@Serializable
internal data class ProgrammerRecordLookResponse(
    val look: LookDetails,
    val created: Boolean,
    val rowsWritten: Int,
    val rowsRemoved: Int,
    val groupRowsEmitted: Int,
    /**
     * Always 0. Counted programmer entries that were themselves `ref:{uuid}` references and so had
     * to be flattened, since Looks don't nest. The `ref:` grammar retired in session 4 and the
     * programmer can no longer hold one; the field stays on the wire so a client reading it doesn't
     * break, and because "how many references did this record flatten?" is a question a future
     * nested-Look feature (`FU-LOOK-NESTED`) would ask again.
     */
    val refsFlattened: Int,
    val skipped: List<ProgrammerSkipDto>,
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

        val outcome = transaction(state.database) {
            val look = existing ?: DaoLook.new {
                this.project = project
                this.name = request.name!!.trim()
                this.notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }
                this.sortOrder = (
                    DaoLook.find { DaoLooks.project eq project.id }.maxOfOrNull { it.sortOrder } ?: -1
                    ) + 1
            }
            writeRecordingIntoLook(look, collapsed, mode, inRemit)
        }

        // Re-resolve and republish: a re-record of a layered Look must move its consumers, exactly
        // as an edit does.
        val republish = republishForLookEdit(state, outcome.lookUuid)
        state.show.fixtures.lookListChanged()

        val details = transaction(state.database) {
            DaoLook.findById(outcome.lookId)!!.toDetailsDto(state)
        }
        logger.info(
            "record-look {} '{}': {} written, {} removed, {} group row(s)",
            mode, details.name, outcome.written, outcome.removed, collapsed.groupRows,
        )
        call.respond(
            ProgrammerRecordLookResponse(
                look = details,
                created = existing == null,
                rowsWritten = outcome.written,
                rowsRemoved = outcome.removed,
                groupRowsEmitted = collapsed.groupRows,
                refsFlattened = 0,
                skipped = skips.map { it.toDto() },
                programmerKeysRefreshed = republish.programmerKeysRefreshed,
                cuesRepublished = republish.cuesRepublished,
            )
        )
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
