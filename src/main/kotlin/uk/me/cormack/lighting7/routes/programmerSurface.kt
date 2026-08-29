package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.IncludedTarget
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.buildCueApplyData
import uk.me.cormack.lighting7.fx.CueApplyData
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.state.State

/**
 * Record / Include / Update, with no HTTP in them.
 *
 * The three gestures have two callers now — the REST routes an operator's tablet drives, and the
 * AI tool surface — and they are the gestures where "nearly the same" is a data-loss bug: Record's
 * mode/source/mask/scope validation, Include's `lastIncludedTarget` bookkeeping and the Mode A/B
 * split in Update all decide *which rows get overwritten*. So the orchestration lives here once
 * and each surface only renders the result: `programmerRoutes.kt` into HTTP status codes and DTOs,
 * `AiTools` into a tool result. The pieces they compose ([collectProgrammerRecording],
 * [writeRecordingIntoCue], [includeCueIntoProgrammer], …) were already shared; only the sequencing
 * was not.
 */

// ── Record ──────────────────────────────────────────────────────────────────

internal sealed interface RecordCoreResult {
    data class Ok(
        val outcome: CueWriteOutcome,
        val details: CueDetails,
        val stackId: Int?,
        val republishedLive: Boolean,
        val skipped: List<RecordSkip>,
    ) : RecordCoreResult

    /** [notFound] separates "you asked for a cue that isn't there" (404) from a bad request (400). */
    data class Failure(val message: String, val notFound: Boolean = false) : RecordCoreResult
}

/**
 * What is wrong with a Record request's *shape*, before anything is read or written.
 *
 * Separate from [performProgrammerRecord] only so the REST route can run it at the precedence it
 * always had — ahead of `withCurrentProject`, so a malformed request reports itself rather than
 * the project mismatch. The core calls it too, which is what the AI surface relies on.
 */
internal fun recordShapeProblem(mode: RecordMode, cueStackId: Int?, cueId: Int?): String? = when {
    mode == RecordMode.CREATE && cueStackId == null -> "CREATE requires cueStackId"
    mode != RecordMode.CREATE && cueId == null -> "${mode.name} requires cueId"
    else -> null
}

/**
 * Record the programmer into a cue — creating one ([RecordMode.CREATE]) or writing into an
 * existing one.
 *
 * The programmer read and the target expansion happen before the transaction on purpose: both
 * touch the engine and the fixture patch, neither of which should be held under a DB lock.
 */
internal fun performProgrammerRecord(
    state: State,
    project: DaoProject,
    mode: RecordMode,
    source: RecordSource,
    cueType: CueType,
    cueStackId: Int?,
    cueId: Int?,
    mask: Set<PropertyMaskGroup>?,
    includeFx: Boolean,
    name: String?,
    cueNumber: String?,
    sortOrder: Int?,
    targets: List<CueTargetDto>?,
): RecordCoreResult {
    recordShapeProblem(mode, cueStackId, cueId)?.let { return RecordCoreResult.Failure(it) }

    val scope = targets?.let { expandTargetsToFixtureKeys(state, it) }
    if (targets != null && scope!!.isEmpty()) {
        return RecordCoreResult.Failure("None of the requested targets resolve to a fixture")
    }

    val recording = collectProgrammerRecording(state, source, mask, includeFx, scope)

    data class Written(val outcome: CueWriteOutcome, val details: CueDetails, val stackId: Int?)

    val (written, error) = transaction(state.database) {
        if (mode == RecordMode.CREATE) {
            val stack = DaoCueStack.findById(cueStackId!!)
                ?: return@transaction null to "Cue stack not found"
            if (stack.project.id != project.id) {
                return@transaction null to "Cue stack belongs to a different project"
            }
            if (stack.type == CueStackType.SEPARATOR.name) {
                return@transaction null to "Cannot record into a separator row"
            }
            val outcome = createCueFromRecording(
                project, stack, recording,
                name = name?.takeIf { it.isNotBlank() } ?: defaultRecordedCueName(stack),
                cueNumber = cueNumber?.takeIf { it.isNotBlank() },
                sortOrder = sortOrder,
                cueType = cueType,
            )
            val cue = DaoCue.findById(outcome.cueId)!!
            Written(outcome, cue.toCueDetails(true, state.show.fixtures), stack.id.value) to null
        } else {
            val cue = DaoCue.findById(cueId!!)
                ?: return@transaction null to "Cue not found"
            if (cue.project.id != project.id) {
                return@transaction null to "Cue belongs to a different project"
            }
            val outcome = writeRecordingIntoCue(state, cue, recording, mode, mask, scope)
            Written(outcome, cue.toCueDetails(true, state.show.fixtures), cue.cueStack.id.value) to null
        }
    }

    if (written == null) return RecordCoreResult.Failure(error ?: "Cue not found", notFound = true)

    val republished = republishCueIfLive(state, written.outcome.cueId, written.stackId)

    // Record-then-tweak-then-Update is the obvious next gesture, so point the include target at
    // what we just wrote.
    state.show.programmerStore.lastIncludedTarget =
        IncludedTarget.cue(written.outcome.cueId, written.stackId)

    state.show.fixtures.cueListChanged()
    if (written.outcome.created) state.show.fixtures.cueStackListChanged()

    return RecordCoreResult.Ok(
        outcome = written.outcome,
        details = written.details,
        stackId = written.stackId,
        republishedLive = republished,
        skipped = recording.skipped,
    )
}

internal fun defaultRecordedCueName(stack: DaoCueStack): String =
    "Cue ${(stack.cues.maxOfOrNull { it.sortOrder } ?: -1) + 2}"

// ── Include ─────────────────────────────────────────────────────────────────

internal sealed interface IncludeCoreResult {
    data class Cue(val cueData: CueApplyData, val outcome: IncludeOutcome) : IncludeCoreResult
    data class Look(
        val lookId: Int,
        val lookName: String,
        val outcome: LookIncludeOutcome,
    ) : IncludeCoreResult

    data class Failure(val message: String, val notFound: Boolean = false) : IncludeCoreResult
}

/** See [recordShapeProblem] — the same precedence-preserving split. */
internal fun includeShapeProblem(cueId: Int?, lookId: Int?): String? =
    "Include needs exactly one of cueId or lookId".takeIf { listOfNotNull(cueId, lookId).size != 1 }

/** Stage a cue or a Look into the programmer as the edit buffer. Exactly one id may be given. */
internal fun performProgrammerInclude(
    state: State,
    project: DaoProject,
    cueId: Int?,
    lookId: Int?,
    mask: Set<PropertyMaskGroup>?,
    fadeMs: Long,
): IncludeCoreResult {
    includeShapeProblem(cueId, lookId)?.let { return IncludeCoreResult.Failure(it) }

    if (lookId != null) {
        val look = transaction(state.database) {
            DaoLook.findById(lookId)
                ?.takeIf { it.project.id == project.id }
                ?.let { it.name to it.uuid }
        } ?: return IncludeCoreResult.Failure("Look not found in current project", notFound = true)
        val (lookName, lookUuid) = look
        val outcome = includeLookIntoProgrammer(state, lookId, lookUuid, mask, fadeMs)
        return IncludeCoreResult.Look(lookId, lookName, outcome)
    }

    val cueData = transaction(state.database) {
        DaoCue.findById(cueId!!)
            ?.takeIf { it.project.id == project.id }
            ?.let { buildCueApplyData(it) }
    } ?: return IncludeCoreResult.Failure("Cue not found in current project", notFound = true)

    val outcome = includeCueIntoProgrammer(state, cueData, mask, fadeMs)

    // `layersInstalled` is load-bearing here, not decorative: a cue built entirely from layers
    // writes no INCLUDE slots and may spawn no effects, so without it the include target would
    // never be set and Update would silently fall through to the Mode B checklist — unable to
    // write the stack back to the cue the operator had just included.
    if (outcome.entriesWritten > 0 || outcome.fxSpawned > 0 || outcome.layersInstalled > 0) {
        state.show.programmerStore.lastIncludedTarget = includedTargetFor(cueData)
    }
    // The diff baseline, taken *after* the install so it records what actually landed — timed
    // layers were dropped, so diffing against the cue's own list would report every one of them
    // as deleted on the next Update.
    state.show.programmerStore.includedLayerSnapshot = state.show.programmerStore.layers

    return IncludeCoreResult.Cue(cueData, outcome)
}

// ── Update ──────────────────────────────────────────────────────────────────

internal sealed interface UpdateCoreResult {
    data class Checklist(val checklist: ProgrammerUpdateChecklistDto) : UpdateCoreResult
    data class LookUpdated(
        val result: ProgrammerLookUpdateResult,
        val skipped: List<RecordSkip>,
    ) : UpdateCoreResult

    data class CuesUpdated(
        /** `A` (include target) or `B` (explicit targets). */
        val mode: String,
        val results: List<ProgrammerUpdateResult>,
        val skipped: List<RecordSkip>,
        val warnings: List<String>,
    ) : UpdateCoreResult

    /** Mode A's include target was deleted since Include; it has been cleared. */
    data class IncludeTargetGone(val message: String, val id: Int) : UpdateCoreResult

    data class Failure(val message: String) : UpdateCoreResult
}

/** See [recordShapeProblem] — the same precedence-preserving split. */
internal fun updateShapeProblem(targets: List<Int>?): String? =
    "targets was empty — omit it for the checklist, or name at least one cue"
        .takeIf { targets != null && targets.isEmpty() }

/**
 * Write the programmer back into what it came from.
 *
 * Mode A (no [targets]) writes only what changed since Include, into the include target — which
 * may be a Look. Mode B writes each named cue exactly the keys it was under. Anything else — an
 * explicit [preview], or nothing included and no targets — returns the checklist instead.
 */
internal fun performProgrammerUpdate(
    state: State,
    project: DaoProject,
    targets: List<Int>?,
    mask: Set<PropertyMaskGroup>?,
    preview: Boolean,
    includeFx: Boolean,
): UpdateCoreResult {
    updateShapeProblem(targets)?.let { return UpdateCoreResult.Failure(it) }

    val includeTarget = state.show.programmerStore.lastIncludedTarget

    // Checklist: asked for explicitly, or the fallback when nothing was included.
    if (preview || (targets == null && includeTarget == null)) {
        return UpdateCoreResult.Checklist(buildUpdateChecklist(state, mask))
    }

    val modeLabel = if (targets != null) "B" else "A"

    // Mode A into a Look. Handled before the `cueId!!` below, which is null for a Look target.
    //
    // Mode B stays cue-only on purpose: its premise is "which cue am I sitting on top of",
    // answered from the Layer 4 winner map, and there is no equivalent to derive for a Look.
    if (modeLabel == "A" && includeTarget!!.kind == IncludedTarget.Kind.LOOK) {
        return updateIncludedLook(state, project, includeTarget, mask)
    }

    val cueIds = targets ?: listOf(includeTarget!!.cueId!!)

    // Mode A writes only what changed since Include (which is what preserves references the
    // operator didn't touch); Mode B writes each cue exactly the keys it was under.
    //
    // **Mode A also diffs the layer stack; Mode B deliberately does not.** Mode B's premise is
    // "which cue am I sitting on top of", derived from the Layer 4 winner map — and that map
    // cannot attribute a key to a *layer of another cue*, only to the cue as a whole. Writing the
    // programmer's stack into a cue the operator never included would replace that cue's
    // composition with one built for a different cue.
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
        val recording = recordingForUpdate(state, entries, includeFx)

        val outcome = transaction(state.database) {
            val cue = DaoCue.findById(cueId)?.takeIf { it.project.id == project.id }
                ?: return@transaction null
            val written = writeRecordingIntoCue(state, cue, recording, RecordMode.MERGE, mask)
            if (modeLabel == "A") {
                writeLayerStackIntoCue(
                    cue,
                    state.show.programmerStore.layers,
                    state.show.programmerStore.includedLayerSnapshot,
                )
            }
            Triple(written, cue.name, cue.cueStack.id.value)
        }

        if (outcome == null) {
            if (modeLabel == "A") {
                // The include target is stale — the cue was deleted or moved out of the project
                // since Include. Clear it so the indicator stops offering it.
                state.show.programmerStore.clearIncludeTargetForCue(cueId)
                return UpdateCoreResult.IncludeTargetGone(
                    "The cue that was included no longer exists in this project.",
                    cueId,
                )
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

    return UpdateCoreResult.CuesUpdated(modeLabel, results, allSkips, warnings)
}
