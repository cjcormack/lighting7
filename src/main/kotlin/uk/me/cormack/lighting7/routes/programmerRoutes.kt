package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.CueStackType
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

private const val CODE_CUE_EDIT_SESSION_OPEN = "CUE_EDIT_SESSION_OPEN"
private const val CODE_INCLUDE_TARGET_GONE = "INCLUDE_TARGET_GONE"

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
    /** Record anyway when a cue-edit session is open on the target cue. */
    val force: Boolean = false,
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

    if (mode == RecordMode.CREATE && request.cueStackId == null) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse("CREATE requires cueStackId"))
    }
    if (mode != RecordMode.CREATE && request.cueId == null) {
        return call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("${mode.name} requires cueId"),
        )
    }

    if (request.cueId != null && !request.force) {
        // Hard stop, not a warning: an open session snapshotted this cue's assignments, and
        // its Discard restores that snapshot wholesale — so anything recorded underneath would
        // vanish the moment the operator discarded. Silently losing a Record is worse than an
        // explicit confirm.
        val open = state.cueEditSessionRegistry
            .activeSession(state.projectManager.currentProject.id.value)
        if (open?.session?.cueId == request.cueId) {
            return call.respond(
                HttpStatusCode.Conflict,
                ProgrammerConflictResponse(
                    "A cue-edit session is open on this cue — recording underneath it would be " +
                        "reverted by Discard.",
                    CODE_CUE_EDIT_SESSION_OPEN,
                    request.cueId,
                ),
            )
        }
    }

    // Read the programmer outside the transaction: it touches the engine and the fixture patch,
    // neither of which should be held under a DB lock.
    val recording = collectProgrammerRecording(state, source, mask, request.includeFx)

    withCurrentProject(state, request.projectId, { p ->
        "Cannot record into project '${p.name}' — only the current project can be modified"
    }) { project ->
        data class Result(val outcome: CueWriteOutcome, val details: CueDetails, val stackId: Int?)

        val result = transaction(state.database) {
            if (mode == RecordMode.CREATE) {
                val stack = DaoCueStack.findById(request.cueStackId!!)
                    ?: return@transaction null to "Cue stack not found"
                if (stack.project.id != project.id) {
                    return@transaction null to "Cue stack belongs to a different project"
                }
                if (stack.type == CueStackType.SEPARATOR.name) {
                    return@transaction null to "Cannot record into a separator row"
                }
                val outcome = createCueFromRecording(
                    project, stack, recording,
                    name = request.name?.takeIf { it.isNotBlank() } ?: defaultRecordedCueName(stack),
                    cueNumber = request.cueNumber?.takeIf { it.isNotBlank() },
                    sortOrder = request.sortOrder,
                    cueType = cueType,
                )
                val cue = DaoCue.findById(outcome.cueId)!!
                Result(outcome, cue.toCueDetails(true, state.show.fixtures), stack.id.value) to null
            } else {
                val cue = DaoCue.findById(request.cueId!!)
                    ?: return@transaction null to "Cue not found"
                if (cue.project.id != project.id) {
                    return@transaction null to "Cue belongs to a different project"
                }
                val outcome = writeRecordingIntoCue(state, cue, recording, mode, mask)
                Result(outcome, cue.toCueDetails(true, state.show.fixtures), cue.cueStack.id.value) to null
            }
        }

        val (value, error) = result
        if (value == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(error ?: "Cue not found"))
            return@withCurrentProject
        }

        val republished = republishCueIfLive(state, value.outcome.cueId, value.stackId)

        // Record-then-tweak-then-Update is the obvious next gesture, so point the include
        // target at what we just wrote.
        state.show.programmerStore.lastIncludedTarget =
            uk.me.cormack.lighting7.fx.IncludedTarget.cue(value.outcome.cueId, value.stackId)

        state.show.fixtures.cueListChanged()
        if (value.outcome.created) state.show.fixtures.cueStackListChanged()

        call.respond(
            if (value.outcome.created) HttpStatusCode.Created else HttpStatusCode.OK,
            ProgrammerRecordResponse(
                cue = value.details,
                created = value.outcome.created,
                assignmentsWritten = value.outcome.assignmentsWritten,
                assignmentsRemoved = value.outcome.assignmentsRemoved,
                groupRowsEmitted = value.outcome.groupRowsEmitted,
                fxWritten = value.outcome.fxWritten,
                preserved = value.outcome.preserved,
                republishedLive = republished,
                skipped = recording.skipped.map { it.toDto() },
                warnings = value.outcome.warnings,
            ),
        )
    }
}

private fun defaultRecordedCueName(stack: DaoCueStack): String =
    "Cue ${(stack.cues.maxOfOrNull { it.sortOrder } ?: -1) + 2}"

// ── Include ─────────────────────────────────────────────────────────────────

@Serializable
internal data class ProgrammerIncludeRequest(
    val projectId: String = "current",
    val cueId: Int,
    val mask: List<String>? = null,
    val includeFx: Boolean = true,
    val fadeMs: Long? = null,
)

@Serializable
internal data class ProgrammerIncludeResponse(
    val cueId: Int,
    val cueStackId: Int? = null,
    val cueName: String,
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

    withCurrentProject(state, request.projectId, { p ->
        "Cannot include from project '${p.name}' — only the current project can be modified"
    }) { project ->
        val cueData = transaction(state.database) {
            DaoCue.findById(request.cueId)
                ?.takeIf { it.project.id == project.id }
                ?.let { buildCueApplyData(it) }
        }
        if (cueData == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found in current project"))
            return@withCurrentProject
        }

        val warnings = ArrayList<String>()
        // Include only *reads* the cue, so an open cue-edit session is a caution, not a
        // conflict — unlike Record/Update, which write underneath it.
        val open = state.cueEditSessionRegistry.activeSession(project.id.value)
        if (open?.session?.cueId == request.cueId) {
            warnings += "A cue-edit session is open on this cue — its Discard will revert " +
                "anything Update writes back."
        }

        val presets = loadImmediatePresets(state, cueData)
        val outcome = includeCueIntoProgrammer(
            state, cueData, presets, mask, request.fadeMs ?: 0,
        )

        if (outcome.entriesWritten > 0 || outcome.fxSpawned > 0) {
            state.show.programmerStore.lastIncludedTarget = includedTargetFor(cueData)
        }

        call.respond(
            ProgrammerIncludeResponse(
                cueId = cueData.cueId,
                cueStackId = cueData.cueStackId,
                cueName = cueData.cueName,
                entriesWritten = outcome.entriesWritten,
                fixtureKeys = outcome.fixtureKeys,
                groupKeys = outcome.groupKeys,
                fxSpawned = outcome.fxSpawned,
                fxAlreadyRunning = outcome.fxAlreadyRunning,
                fxTimedSkipped = outcome.fxTimedSkipped,
                lastIncluded = includedTargetDto(state, state.show.programmerStore.lastIncludedTarget),
                skipped = outcome.skipped.map { it.toDto() },
                warnings = warnings + outcome.warnings,
            ),
        )
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

@Serializable
internal data class ProgrammerUpdateResponse(
    val applied: Boolean,
    /** `A` (include target), `B` (explicit targets), or `CHECKLIST` (nothing written). */
    val mode: String,
    val results: List<ProgrammerUpdateResult> = emptyList(),
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
    if (request.targets != null && request.targets.isEmpty()) {
        return call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("targets was empty — omit it for the checklist, or name at least one cue"),
        )
    }

    withCurrentProject(state, request.projectId, { p ->
        "Cannot update project '${p.name}' — only the current project can be modified"
    }) { project ->
        val includeTarget = state.show.programmerStore.lastIncludedTarget

        // Checklist: asked for explicitly, or the fallback when nothing was included.
        if (request.preview || (request.targets == null && includeTarget == null)) {
            call.respond(
                ProgrammerUpdateResponse(
                    applied = false,
                    mode = "CHECKLIST",
                    checklist = buildUpdateChecklist(state, mask),
                ),
            )
            return@withCurrentProject
        }

        val modeLabel = if (request.targets != null) "B" else "A"
        val cueIds = request.targets ?: listOf(includeTarget!!.cueId)

        if (!request.force) {
            val open = state.cueEditSessionRegistry.activeSession(project.id.value)
            val clash = open?.session?.cueId?.takeIf { it in cueIds }
            if (clash != null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ProgrammerConflictResponse(
                        "A cue-edit session is open on cue $clash — updating underneath it " +
                            "would be reverted by Discard.",
                        CODE_CUE_EDIT_SESSION_OPEN,
                        clash,
                    ),
                )
                return@withCurrentProject
            }
        }

        // Mode A writes only what changed since Include (which is what preserves palette refs
        // the operator didn't touch); Mode B writes each cue exactly the keys it was under.
        val allSkips = ArrayList<RecordSkip>()
        val plans = cueIds.associateWith { cueId ->
            if (modeLabel == "A") {
                val (changed, skips) = changedSinceInclude(state, mask)
                allSkips += skips
                changed
            } else {
                entriesUnderlyingCue(state, cueId, mask)
            }
        }

        val results = ArrayList<ProgrammerUpdateResult>(cueIds.size)
        val warnings = ArrayList<String>()

        for (cueId in cueIds) {
            val entries = plans[cueId].orEmpty()
            val recording = recordingForUpdate(state, entries, request.includeFx)

            val outcome = transaction(state.database) {
                val cue = DaoCue.findById(cueId)?.takeIf { it.project.id == project.id }
                    ?: return@transaction null
                val written = writeRecordingIntoCue(state, cue, recording, RecordMode.MERGE, mask)
                Triple(written, cue.name, cue.cueStack.id.value)
            }

            if (outcome == null) {
                if (modeLabel == "A") {
                    // The include target is stale — the cue was deleted or moved out of the
                    // project since Include. Clear it so the indicator stops offering it.
                    state.show.programmerStore.clearIncludeTargetForCue(cueId)
                    call.respond(
                        HttpStatusCode.Conflict,
                        ProgrammerConflictResponse(
                            "The cue that was included no longer exists in this project.",
                            CODE_INCLUDE_TARGET_GONE,
                            cueId,
                        ),
                    )
                    return@withCurrentProject
                }
                warnings += "Cue $cueId not found in this project — skipped"
                continue
            }

            val (written, cueName, stackId) = outcome
            results += ProgrammerUpdateResult(
                cueId = cueId,
                cueStackId = stackId,
                cueName = cueName,
                assignmentsWritten = written.assignmentsWritten,
                fxWritten = written.fxWritten,
                republishedLive = republishCueIfLive(state, cueId, stackId),
            )
            warnings += written.warnings
        }

        if (results.isNotEmpty()) state.show.fixtures.cueListChanged()

        call.respond(
            ProgrammerUpdateResponse(
                applied = results.isNotEmpty(),
                mode = modeLabel,
                results = results,
                skipped = allSkips.map { it.toDto() },
                warnings = warnings,
            ),
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

internal inline fun <reified T : Enum<T>> parseEnumOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
