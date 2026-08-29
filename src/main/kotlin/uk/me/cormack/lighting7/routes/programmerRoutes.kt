package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.IncludedTarget
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.plugins.IncludedTargetDto
import uk.me.cormack.lighting7.plugins.includedTargetDto
import uk.me.cormack.lighting7.state.State

// ── Resources ───────────────────────────────────────────────────────────────

@Resource("/record")
internal class ProgrammerRecordResource

@Resource("/include")
internal class ProgrammerIncludeResource

@Resource("/update")
internal class ProgrammerUpdateResource

// ── Shared wire shapes ──────────────────────────────────────────────────────

/** One entry that could not be recorded, and why. */
@Serializable
internal data class ProgrammerSkipDto(
    val targetKey: String? = null,
    val propertyName: String? = null,
    val universe: Int? = null,
    val channel: Int? = null,
    val reason: String,
)

internal fun RecordSkip.toDto() = ProgrammerSkipDto(
    targetKey = targetKey,
    propertyName = propertyName,
    universe = universe,
    channel = channel,
    reason = reason.name,
)

/** Error body carrying a machine-readable code, so the client can branch on the conflict kind. */
@Serializable
internal data class ProgrammerConflictResponse(
    val error: String,
    val code: String,
    val cueId: Int? = null,
)

internal const val CODE_INCLUDE_TARGET_GONE = "INCLUDE_TARGET_GONE"

// ── Record ──────────────────────────────────────────────────────────────────

@Serializable
internal data class ProgrammerRecordRequest(
    val projectId: String = "current",
    /** [RecordMode] name. */
    val mode: String = "CREATE",
    /** [RecordSource] name. */
    val source: String = "TOUCHED",
    /** Required for CREATE. */
    val cueStackId: Int? = null,
    /** Required for MERGE / REMOVE / UPDATE_EXISTING. */
    val cueId: Int? = null,
    /** `INTENSITY` / `POSITION` / `COLOUR` / `BEAM`; null or all four means no mask. */
    val mask: List<String>? = null,
    val includeFx: Boolean = true,
    val name: String? = null,
    val cueNumber: String? = null,
    val sortOrder: Int? = null,
    val cueType: String = "STANDARD",
    /**
     * Inert since sweep item D1 retired `cueEdit.*` — there is no longer a session to record
     * underneath, so nothing consults this. Accepted rather than removed because `RecordSheet`
     * sends it on *every* submit, not only on the conflict retry, and the route's `Json` is
     * strict about unknown keys: dropping the field would 400 every Record until the frontend
     * sweep lands. Delete it with the frontend's sender.
     */
    val force: Boolean = false,
    /**
     * Restrict the record to these fixtures — MagicQ's selection-scoped record, "put just these
     * heads into this cue". Groups are expanded server-side.
     *
     * Null (the default) records the whole programmer, which is the historical behaviour and
     * still the common case: unlike a palette, a cue capturing everything the operator busked is
     * usually exactly what was meant.
     */
    val targets: List<CueTargetDto>? = null,
)

@Serializable
internal data class ProgrammerRecordResponse(
    val cue: CueDetails,
    val created: Boolean,
    val assignmentsWritten: Int,
    val assignmentsRemoved: Int,
    val groupRowsEmitted: Int,
    val fxWritten: Int,
    val preserved: ProgrammerPreservedCounts,
    val republishedLive: Boolean,
    val skipped: List<ProgrammerSkipDto> = emptyList(),
    val warnings: List<String> = emptyList(),
)

internal suspend fun RoutingContext.handleProgrammerRecord(state: State) {
    val request = call.receive<ProgrammerRecordRequest>()

    val mode = parseEnumOrNull<RecordMode>(request.mode) ?: return call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse("Unknown record mode '${request.mode}'"),
    )
    val source = parseEnumOrNull<RecordSource>(request.source) ?: return call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse("Unknown record source '${request.source}'"),
    )
    val cueType = parseEnumOrNull<CueType>(request.cueType) ?: return call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse("Unknown cue type '${request.cueType}'"),
    )
    val mask = try {
        uk.me.cormack.lighting7.fx.parseMaskGroups(request.mask)
    } catch (e: IllegalArgumentException) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
    }

    // Ahead of `withCurrentProject`, where this check has always run: a malformed request should
    // report itself rather than the project mismatch. `performProgrammerRecord` re-runs it.
    recordShapeProblem(mode, request.cueStackId, request.cueId)?.let {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
    }

    withCurrentProject(state, request.projectId, { p ->
        "Cannot record into project '${p.name}' — only the current project can be modified"
    }) { project ->
        when (
            val result = performProgrammerRecord(
                state, project,
                mode = mode,
                source = source,
                cueType = cueType,
                cueStackId = request.cueStackId,
                cueId = request.cueId,
                mask = mask,
                includeFx = request.includeFx,
                name = request.name,
                cueNumber = request.cueNumber,
                sortOrder = request.sortOrder,
                targets = request.targets,
            )
        ) {
            is RecordCoreResult.Failure -> call.respond(
                if (result.notFound) HttpStatusCode.NotFound else HttpStatusCode.BadRequest,
                ErrorResponse(result.message),
            )

            is RecordCoreResult.Ok -> call.respond(
                if (result.outcome.created) HttpStatusCode.Created else HttpStatusCode.OK,
                ProgrammerRecordResponse(
                    cue = result.details,
                    created = result.outcome.created,
                    assignmentsWritten = result.outcome.assignmentsWritten,
                    assignmentsRemoved = result.outcome.assignmentsRemoved,
                    groupRowsEmitted = result.outcome.groupRowsEmitted,
                    fxWritten = result.outcome.fxWritten,
                    preserved = result.outcome.preserved,
                    republishedLive = result.republishedLive,
                    skipped = result.skipped.map { it.toDto() },
                    warnings = result.outcome.warnings,
                ),
            )
        }
    }
}

// ── Include ─────────────────────────────────────────────────────────────────

@Serializable
internal data class ProgrammerIncludeRequest(
    val projectId: String = "current",
    /** Exactly one of [cueId] / [lookId]. */
    val cueId: Int? = null,
    val lookId: Int? = null,
    val mask: List<String>? = null,
    val includeFx: Boolean = true,
    val fadeMs: Long? = null,
)

@Serializable
internal data class ProgrammerIncludeResponse(
    /** `CUE` or `LOOK`. */
    val kind: String = "CUE",
    /** Null unless a cue was included. */
    val cueId: Int? = null,
    val cueStackId: Int? = null,
    val lookId: Int? = null,
    /**
     * The included thing's name. Named `name` rather than `cueName` because it is now any of
     * three; a field called `cueName` holding a Look name is a lie.
     */
    val name: String,
    val entriesWritten: Int,
    /** For "Select Heads on Include" — the client selects these in the programmer sheet. */
    val fixtureKeys: List<String>,
    /** Groups whose rows were expanded, so a group-rollup view can select group-shaped. */
    val groupKeys: List<String>,
    val fxSpawned: Int,
    val fxAlreadyRunning: Int,
    val fxTimedSkipped: Int,
    val lastIncluded: IncludedTargetDto? = null,
    val skipped: List<ProgrammerSkipDto> = emptyList(),
    val warnings: List<String> = emptyList(),
)

internal suspend fun RoutingContext.handleProgrammerInclude(state: State) {
    val request = call.receive<ProgrammerIncludeRequest>()
    val mask = try {
        uk.me.cormack.lighting7.fx.parseMaskGroups(request.mask)
    } catch (e: IllegalArgumentException) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
    }

    includeShapeProblem(request.cueId, request.lookId)?.let {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
    }

    withCurrentProject(state, request.projectId, { p ->
        "Cannot include from project '${p.name}' — only the current project can be modified"
    }) { project ->
        when (
            val result = performProgrammerInclude(
                state, project, request.cueId, request.lookId, mask, request.fadeMs ?: 0,
            )
        ) {
            is IncludeCoreResult.Failure -> call.respond(
                if (result.notFound) HttpStatusCode.NotFound else HttpStatusCode.BadRequest,
                ErrorResponse(result.message),
            )

            is IncludeCoreResult.Cue -> call.respond(
                ProgrammerIncludeResponse(
                    kind = IncludedTarget.Kind.CUE.name,
                    cueId = result.cueData.cueId,
                    cueStackId = result.cueData.cueStackId,
                    name = result.cueData.cueName,
                    entriesWritten = result.outcome.entriesWritten,
                    fixtureKeys = result.outcome.fixtureKeys,
                    groupKeys = result.outcome.groupKeys,
                    fxSpawned = result.outcome.fxSpawned,
                    fxAlreadyRunning = result.outcome.fxAlreadyRunning,
                    fxTimedSkipped = result.outcome.fxTimedSkipped,
                    lastIncluded = includedTargetDto(state, state.show.programmerStore.lastIncludedTarget),
                    skipped = result.outcome.skipped.map { it.toDto() },
                    warnings = result.outcome.warnings,
                ),
            )

            is IncludeCoreResult.Look -> call.respond(
                ProgrammerIncludeResponse(
                    kind = IncludedTarget.Kind.LOOK.name,
                    lookId = result.lookId,
                    name = result.lookName,
                    entriesWritten = result.outcome.entriesWritten,
                    fixtureKeys = result.outcome.fixtureKeys,
                    // A Look's group rows expand to members before they reach the programmer, so
                    // there is no group-shaped selection to hand back.
                    groupKeys = emptyList(),
                    fxSpawned = 0,
                    fxAlreadyRunning = 0,
                    fxTimedSkipped = 0,
                    lastIncluded = includedTargetDto(state, state.show.programmerStore.lastIncludedTarget),
                    skipped = result.outcome.skipped.map { it.toDto() },
                    warnings = lookIncludeWarnings(result.lookName, result.outcome),
                ),
            )
        }
    }
}

// ── Update ──────────────────────────────────────────────────────────────────

@Serializable
internal data class ProgrammerUpdateRequest(
    val projectId: String = "current",
    /** Cue ids to write (Mode B commit). Omitted means Mode A, or the checklist. */
    val targets: List<Int>? = null,
    val mask: List<String>? = null,
    /** Return the checklist without writing anything, even when an include target exists. */
    val preview: Boolean = false,
    val includeFx: Boolean = true,
    /** Inert since D1 — see [ProgrammerRecordRequest.force]. `UpdateDialog` always sends it. */
    val force: Boolean = false,
)

@Serializable
internal data class ProgrammerUpdateResult(
    val cueId: Int,
    val cueStackId: Int? = null,
    val cueName: String,
    val assignmentsWritten: Int,
    val fxWritten: Int,
    val republishedLive: Boolean,
)

/**
 * Mode A written back into a Look rather than a cue.
 *
 * There was a `ProgrammerPaletteUpdateResult` beside this, kept separate rather than merged because
 * the two wrote through different code into different tables and collapsing them would have made one
 * field mean two destinations. The palette one retired with its tables in session 4, so this is the
 * only non-cue destination left.
 */
@Serializable
internal data class ProgrammerLookUpdateResult(
    val lookId: Int,
    val lookName: String,
    val rowsWritten: Int,
    /** What the re-resolve moved: live consumers of the Look. */
    val programmerKeysRefreshed: Int,
    val cuesRepublished: List<Int>,
)

@Serializable
internal data class ProgrammerUpdateResponse(
    val applied: Boolean,
    /** `A` (include target), `B` (explicit targets), or `CHECKLIST` (nothing written). */
    val mode: String,
    val results: List<ProgrammerUpdateResult> = emptyList(),
    /**
     * Set when Mode A's include target was a Look. A separate field rather than a nullable `cueId` on
     * [ProgrammerUpdateResult], so clients reading `results` are unaffected — the shape a retired
     * `paletteResult` field established beside it.
     */
    val lookResult: ProgrammerLookUpdateResult? = null,
    val checklist: ProgrammerUpdateChecklistDto? = null,
    val skipped: List<ProgrammerSkipDto> = emptyList(),
    val warnings: List<String> = emptyList(),
)

internal suspend fun RoutingContext.handleProgrammerUpdate(state: State) {
    val request = try {
        call.receive<ProgrammerUpdateRequest>()
    } catch (_: Exception) {
        ProgrammerUpdateRequest()
    }
    val mask = try {
        uk.me.cormack.lighting7.fx.parseMaskGroups(request.mask)
    } catch (e: IllegalArgumentException) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
    }

    updateShapeProblem(request.targets)?.let {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
    }

    withCurrentProject(state, request.projectId, { p ->
        "Cannot update project '${p.name}' — only the current project can be modified"
    }) { project ->
        when (
            val result = performProgrammerUpdate(
                state, project, request.targets, mask, request.preview, request.includeFx,
            )
        ) {
            is UpdateCoreResult.Failure ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))

            is UpdateCoreResult.IncludeTargetGone -> call.respond(
                HttpStatusCode.Conflict,
                ProgrammerConflictResponse(result.message, CODE_INCLUDE_TARGET_GONE, result.id),
            )

            is UpdateCoreResult.Checklist -> call.respond(
                ProgrammerUpdateResponse(applied = false, mode = "CHECKLIST", checklist = result.checklist),
            )

            is UpdateCoreResult.LookUpdated -> call.respond(
                ProgrammerUpdateResponse(
                    applied = result.result.rowsWritten > 0,
                    mode = "A",
                    lookResult = result.result,
                    skipped = result.skipped.map { it.toDto() },
                ),
            )

            is UpdateCoreResult.CuesUpdated -> call.respond(
                ProgrammerUpdateResponse(
                    applied = result.results.isNotEmpty(),
                    mode = result.mode,
                    results = result.results,
                    skipped = result.skipped.map { it.toDto() },
                    warnings = result.warnings,
                ),
            )
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

internal inline fun <reified T : Enum<T>> parseEnumOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
