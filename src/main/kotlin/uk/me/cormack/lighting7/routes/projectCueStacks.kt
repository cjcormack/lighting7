package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.fx.stackCuePriorities
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectCueStacks(state: State) {
    // GET /{projectId}/cue-stacks - List stacks + separators in show order, with cues + active cue info
    get<ProjectCueStacksResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val isCurrentProject = state.isCurrentProject(project)
            val manager = state.show.cueStackManager
            val stacks = transaction(state.database) {
                DaoCueStack.find { DaoCueStacks.project eq project.id }
                    .orderBy(DaoCueStacks.sortOrder to SortOrder.ASC, DaoCueStacks.name to SortOrder.ASC)
                    .map { it.toCueStackDetails(isCurrentProject, manager) }
            }
            call.respond(stacks)
        }
    }

    // POST /{projectId}/cue-stacks - Create a stack or a separator (type=SEPARATOR), appended to order
    post<ProjectCueStacksResource> { resource ->
        withCurrentProject(
            state,
            resource.projectId,
            { p -> "Cannot create cue stacks in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val input = call.receive<NewCueStack>()
            val manager = state.show.cueStackManager
            val details = transaction(state.database) {
                val stackType = input.type ?: CueStackType.STACK.name
                val resolvedName = if (stackType == CueStackType.SEPARATOR.name) {
                    (input.label ?: input.name).ifBlank { "Separator" }
                } else {
                    input.name
                }
                val nextSort = input.sortOrder ?: ((project.cueStacks.maxOfOrNull { it.sortOrder } ?: -1) + 1)
                val stack = DaoCueStack.new {
                    name = resolvedName
                    this.project = project
                    loop = input.loop
                    type = stackType
                    label = input.label
                    sortOrder = nextSort
                }
                stack.toCueStackDetails(isCurrentProject = true, manager)
            }
            state.show.fixtures.cueStackListChanged()
            call.respond(HttpStatusCode.Created, details)
        }
    }

    // POST /{projectId}/cue-stacks/reorder - Reorder the project's stacks + separators (show order)
    post<ProjectStacksReorderResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val request = call.receive<ReorderStacksRequest>()
            transaction(state.database) {
                for ((index, stackId) in request.stackIds.withIndex()) {
                    val stack = DaoCueStack.findById(stackId) ?: continue
                    if (stack.project.id == project.id) {
                        stack.sortOrder = index
                    }
                }
            }
            state.show.fixtures.cueStackListChanged()
            call.respond(HttpStatusCode.OK)
        }
    }

    // GET /{projectId}/cue-stacks/{stackId} - Get stack details
    get<ProjectCueStackResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val isCurrentProject = state.isCurrentProject(project)
            val manager = state.show.cueStackManager
            val details = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.stackId) ?: return@transaction null
                if (stack.project.id != project.id) return@transaction null
                stack.toCueStackDetails(isCurrentProject, manager)
            }

            if (details != null) {
                call.respond(details)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            }
        }
    }

    // PUT /{projectId}/cue-stacks/{stackId} - Update stack settings
    put<ProjectCueStackResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot modify cue stacks in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val input = call.receive<NewCueStack>()
            val manager = state.show.cueStackManager
            val details = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.stackId) ?: return@transaction null
                if (stack.project.id != project.id) return@transaction null

                if (stack.type == CueStackType.SEPARATOR.name) {
                    // A separator only carries a display label; keep `name` in sync so the NOT NULL
                    // column stays populated and list ordering by name is stable.
                    val newLabel = (input.label ?: input.name)
                    stack.label = newLabel
                    if (newLabel.isNotBlank()) stack.name = newLabel
                } else {
                    stack.name = input.name
                    stack.loop = input.loop
                }

                stack.toCueStackDetails(isCurrentProject = true, manager)
            }

            if (details != null) {
                state.show.fixtures.cueStackListChanged()
                call.respond(details)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            }
        }
    }

    // DELETE /{projectId}/cue-stacks/{stackId} - Delete stack (cascades its cues) or separator
    delete<ProjectCueStackResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot delete cue stacks in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val result = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.stackId) ?: return@transaction null
                if (stack.project.id != project.id) return@transaction null

                // Deactivate if running, and clear the project playhead if it pointed here.
                state.show.cueStackManager.deactivateStack(resource.stackId, state)
                if (project.activeStackId == resource.stackId) {
                    project.activeStackId = null
                }

                // Every cue belongs to a stack, so deleting a stack cascades its cues + children.
                var removedAnchors = 0
                stack.cues.forEach { cue ->
                    deleteCueChildren(cue)
                    removedAnchors += deletePromptBookAnchorsForCue(cue)
                    // Same reason the single-cue delete does this: a deleted cue can't be
                    // Updated into, and a surviving target leaves every client's programmer
                    // indicator offering a cue that no longer exists.
                    state.show.programmerStore.clearIncludeTargetForCue(cue.id.value)
                    cue.delete()
                }

                stack.delete()
                removedAnchors
            }

            if (result != null) {
                state.show.fixtures.cueStackListChanged()
                state.show.fixtures.cueListChanged()
                if (result > 0) state.show.fixtures.promptBookChanged()
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/reorder - Reorder cues
    post<CueStackReorderResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId) { _ ->
            val request = call.receive<ReorderCuesRequest>()
            val priorities = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.parent.stackId)
                    ?: return@transaction emptyMap<Int, Int>()
                for ((index, cueId) in request.cueIds.withIndex()) {
                    val cue = DaoCue.findById(cueId) ?: continue
                    if (cue.cueStack.id.value == resource.parent.stackId) {
                        cue.sortOrder = index
                    }
                }
                // Auto numbers are derived from position, so they move with the order.
                renumberAutoCues(stack)
                stackCuePriorities(stack)
            }
            // Cue priority is derived from stack position; keep anything already on stage in step.
            state.show.fxEngine.repriorityCues(priorities)
            state.show.fixtures.cueStackListChanged()
            state.show.fixtures.cueListChanged()
            call.respond(HttpStatusCode.OK)
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/add-cue - Add/move cue to stack
    post<CueStackAddCueResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId) { project ->
            val request = call.receive<AddCueToStackRequest>()
            val manager = state.show.cueStackManager
            val result = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.parent.stackId) ?: return@transaction Triple("Stack not found", null, null)
                if (stack.type == CueStackType.SEPARATOR.name) return@transaction Triple("Cannot add cues to a separator", null, null)
                val cue = DaoCue.findById(request.cueId) ?: return@transaction Triple("Cue not found", null, null)
                if (cue.project.id != project.id) return@transaction Triple("Cue does not belong to project", null, null)

                // Every cue already in the stack, MARKERs included — the shift below has to move
                // separators too, or they drift away from the cues they label.
                val existingCues = DaoCue.find { DaoCues.cueStack eq stack.id }
                    .orderBy(DaoCues.sortOrder to SortOrder.ASC)
                    .filter { it.id.value != cue.id.value }
                    .toList()

                if (request.insertByNumber) {
                    val parsed = cue.cueNumber?.let { parseCueNumber(it) }
                        ?: return@transaction Triple(
                            "insertByNumber requires a cue number with a numeric component",
                            null,
                            null,
                        )

                    // Position the cue within its own prefix group; other groups sort
                    // independently, so their numbers say nothing about where this one belongs.
                    val group = existingCues.mapNotNull { other ->
                        val otherParsed = other.cueNumber?.let { parseCueNumber(it) }
                        if (otherParsed != null && otherParsed.prefix.equals(parsed.prefix, ignoreCase = true)) {
                            other to otherParsed
                        } else {
                            null
                        }
                    }

                    val insertAfter = group.lastOrNull { (_, other) -> compareWithinGroup(other, parsed) < 0 }
                    val insertSortOrder = when {
                        insertAfter != null -> insertAfter.first.sortOrder + 1
                        group.isNotEmpty() -> group.first().first.sortOrder
                        // Nothing in this group yet — append.
                        else -> (existingCues.maxOfOrNull { it.sortOrder } ?: -1) + 1
                    }

                    existingCues.filter { it.sortOrder >= insertSortOrder }
                        .forEach { it.sortOrder = it.sortOrder + 1 }

                    cue.cueStack = stack
                    cue.sortOrder = insertSortOrder
                } else {
                    cue.cueStack = stack
                    // max+1, not count: sort orders are allowed to have gaps, so counting would
                    // hand out a value an existing cue already holds.
                    cue.sortOrder = request.sortOrder ?: ((existingCues.maxOfOrNull { it.sortOrder } ?: -1) + 1)
                }

                renumberAutoCues(stack)
                Triple(
                    null,
                    stack.toCueStackDetails(isCurrentProject = true, manager),
                    stackCuePriorities(stack),
                )
            }

            val (error, details, priorities) = result
            if (error != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
            } else {
                state.show.fxEngine.repriorityCues(priorities ?: emptyMap())
                state.show.fixtures.cueStackListChanged()
                state.show.fixtures.cueListChanged()
                call.respond(details!!)
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/activate - Activate stack
    post<CueStackActivateResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot activate - not current project") { _ ->
            val request = try { call.receive<ActivateCueStackRequest>() } catch (_: Exception) { ActivateCueStackRequest() }
            val manager = state.show.cueStackManager

            try {
                // A bare /activate on an already-active stack short-circuits, like /show/activate:
                // it routes to activateAtFirstCue, so honouring the repeat would throw the playhead
                // back to cue 1 on a live rig. Only a request naming a cue is a deliberate re-fire.
                val activeCueId = if (request.cueId == null) manager.getActiveCueId(resource.parent.stackId) else null
                if (activeCueId != null) {
                    val cueName = transaction(state.database) { DaoCue.findById(activeCueId)?.name } ?: ""
                    call.respond(CueStackActivateResponse(
                        stackId = resource.parent.stackId,
                        cueId = activeCueId,
                        cueName = cueName,
                        effectCount = 0,
                    ))
                    return@withCurrentProject
                }

                val result = if (request.cueId != null) {
                    manager.activateCueInStack(state, resource.parent.stackId, request.cueId)
                } else {
                    manager.activateAtFirstCue(state, resource.parent.stackId)
                }
                state.show.fixtures.cueStackListChanged()
                call.respond(result.toResponse())
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to activate stack"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/deactivate - Deactivate stack
    post<CueStackDeactivateResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot deactivate - not current project") { _ ->
            val removedCount = state.show.cueStackManager.deactivateStack(resource.parent.stackId, state)
            state.show.fixtures.cueStackListChanged()
            call.respond(CueStackDeactivateResponse(stackId = resource.parent.stackId, removedCount = removedCount))
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/advance - Advance forward/backward
    post<CueStackAdvanceResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot advance - not current project") { _ ->
            val request = call.receive<AdvanceCueStackRequest>()
            val direction = try {
                CueStackManager.AdvanceDirection.valueOf(request.direction.uppercase())
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid direction: ${request.direction}"))
                return@withCurrentProject
            }

            try {
                val result = state.show.cueStackManager.advanceStack(state, resource.parent.stackId, direction)
                state.show.fixtures.cueStackListChanged()
                if (result != null) {
                    call.respond(result.toResponse())
                } else {
                    call.respond(CueStackDeactivateResponse(stackId = resource.parent.stackId, removedCount = 0))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to advance stack"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/go-to - Go to specific cue
    post<CueStackGoToResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot go-to - not current project") { _ ->
            val request = call.receive<GoToCueRequest>()

            try {
                val result = state.show.cueStackManager.goToCue(state, resource.parent.stackId, request.cueId)
                state.show.fixtures.cueStackListChanged()
                call.respond(result.toResponse())
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to go to cue"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/standby - Arm (or disarm) the next GO
    post<CueStackStandbyResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot arm - not current project") { _ ->
            val request = try { call.receive<SetStandbyRequest>() } catch (_: Exception) { SetStandbyRequest() }
            val manager = state.show.cueStackManager
            val stackId = resource.parent.stackId

            try {
                if (request.cueId != null) {
                    manager.runState.setStandby(state, stackId, request.cueId)
                } else {
                    manager.runState.clearStandby(state, stackId)
                }
                // No `cueStackListChanged` — the manager already broadcast the run state, and
                // this changes nothing about the stack collection.
                call.respond(
                    CueStackRunStateResponse(
                        stackId = stackId,
                        activeCueId = manager.getActiveCueId(stackId),
                        standbyCueId = manager.runState.getStandbyCueId(stackId),
                        nextCueId = manager.runState.effectiveNextCueId(state, stackId),
                    )
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to arm cue"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/preview - Compose a cue without firing it
    post<CueStackPreviewResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot preview - not current project") { _ ->
            val request = try { call.receive<PreviewCueRequest>() } catch (_: Exception) { PreviewCueRequest() }
            try {
                when (val result = previewCueLook(state, resource.parent.stackId, request.cueId)) {
                    null -> call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Nothing to preview - the stack has no next cue"),
                    )
                    else -> call.respond(result)
                }
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to preview cue"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/sort-by-cue-number - Group-aware natural sort
    post<CueStackSortByNumberResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId) { project ->
            val manager = state.show.cueStackManager
            val result = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.parent.stackId) ?: return@transaction null
                if (stack.project.id != project.id) return@transaction null

                val standardCues = DaoCue.find { DaoCues.cueStack eq stack.id }
                    .orderBy(DaoCues.sortOrder to SortOrder.ASC)
                    .filter { it.cueType == CueType.STANDARD.name }

                // Each prefix group is sorted within the slots it already occupies, so groups
                // keep their relative placement. MARKERs and numbers with nothing to order by
                // ("A", blanks) never move — that's the replacement for the old rule that a cue
                // number had to *start* with a digit to participate, which excluded every
                // prefixed number like "S1-3".
                val slots = standardCues.map { it.sortOrder }
                groupAwareCueOrder(standardCues) { it.cueNumber }
                    .forEachIndexed { index, cue -> cue.sortOrder = slots[index] }

                // Counted before renumbering: that call is what fills blanks in, so reading these
                // afterwards would report zero unnumbered cues no matter what came in.
                val unparseable = standardCues.count {
                    val number = it.cueNumber
                    !number.isNullOrEmpty() && parseCueNumber(number) == null
                }
                val unnumbered = standardCues.count { it.cueNumber.isNullOrEmpty() }

                renumberAutoCues(stack)

                val details = stack.toCueStackDetails(isCurrentProject = true, manager)
                SortByNumberResponse(
                    updatedCues = details.cues,
                    pinnedCount = unparseable,
                    nullNumberCount = unnumbered,
                ) to stackCuePriorities(stack)
            }

            if (result == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            } else {
                val (response, priorities) = result
                state.show.fxEngine.repriorityCues(priorities)
                state.show.fixtures.cueStackListChanged()
                state.show.fixtures.cueListChanged()
                call.respond(response)
            }
        }
    }
}

// ─── Resource classes ──────────────────────────────────────────────────

@Resource("/{projectId}/cue-stacks")
data class ProjectCueStacksResource(val projectId: String)

// Project-level stack reorder. The literal `/reorder` segment is preferred by Ktor over the
// sibling `/{stackId}` (which only matches an Int), so there is no routing collision.
@Resource("/reorder")
data class ProjectStacksReorderResource(val parent: ProjectCueStacksResource)

@Resource("/{stackId}")
data class ProjectCueStackResource(val parent: ProjectCueStacksResource, val stackId: Int)

@Resource("/reorder")
data class CueStackReorderResource(val parent: ProjectCueStackResource)

@Resource("/add-cue")
data class CueStackAddCueResource(val parent: ProjectCueStackResource)

@Resource("/activate")
data class CueStackActivateResource(val parent: ProjectCueStackResource)

@Resource("/deactivate")
data class CueStackDeactivateResource(val parent: ProjectCueStackResource)

@Resource("/advance")
data class CueStackAdvanceResource(val parent: ProjectCueStackResource)

@Resource("/go-to")
data class CueStackGoToResource(val parent: ProjectCueStackResource)

@Resource("/standby")
data class CueStackStandbyResource(val parent: ProjectCueStackResource)

@Resource("/preview")
data class CueStackPreviewResource(val parent: ProjectCueStackResource)

@Resource("/sort-by-cue-number")
data class CueStackSortByNumberResource(val parent: ProjectCueStackResource)

// ─── DTOs ──────────────────────────────────────────────────────────────

@Serializable
data class NewCueStack(
    val name: String = "",
    val loop: Boolean = false,
    /** "STACK" (default) or "SEPARATOR". */
    val type: String? = null,
    /** Display text for a separator; ignored for a real stack. */
    val label: String? = null,
    /** Explicit position; when null the row is appended to the end of the project's order. */
    val sortOrder: Int? = null,
)

@Serializable
data class CueStackDetails(
    val id: Int,
    val name: String,
    val loop: Boolean,
    val sortOrder: Int,
    val type: String,
    val label: String?,
    val cues: List<CueStackCueEntry>,
    val activeCueId: Int?,
    /** The cue an operator has explicitly armed, if any. */
    val standbyCueId: Int?,
    /**
     * The cue the next GO fires — [standbyCueId] when set, else the positional next. Server-side
     * so a page load agrees with every other session; see `CueRunStateTracker.effectiveNextCueId`.
     */
    val nextCueId: Int?,
    val canEdit: Boolean,
    val canDelete: Boolean,
)

@Serializable
data class CueStackCueEntry(
    val id: Int,
    val name: String,
    val sortOrder: Int,
    /**
     * How many Look layers the cue carries.
     *
     * Was `presetCount`, counting `cue_preset_applications` — which retired in session 4. Renamed
     * rather than deleted because it is the count a collapsed cue row wants: a cue built entirely
     * from layers otherwise reads as empty in the Run list. **No client reads it yet** (the
     * frontend's `CueStackCueEntry` declares it and nothing consumes it, along with
     * `adHocEffectCount`), so treat it as available rather than as load-bearing.
     */
    val layerCount: Int,
    val adHocEffectCount: Int,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
    val cueNumber: String? = null,
    /** True when [cueNumber] was derived from position rather than typed by the operator. */
    val cueNumberAuto: Boolean = false,
    val notes: String? = null,
    val cueType: String = "STANDARD",
)

@Serializable
data class ReorderCuesRequest(
    val cueIds: List<Int>,
)

@Serializable
data class ReorderStacksRequest(
    val stackIds: List<Int>,
)

@Serializable
data class AddCueToStackRequest(
    val cueId: Int,
    val sortOrder: Int? = null,
    val insertByNumber: Boolean = false,
)

@Serializable
data class ActivateCueStackRequest(
    val cueId: Int? = null,
)

@Serializable
data class AdvanceCueStackRequest(
    val direction: String,
)

@Serializable
data class GoToCueRequest(
    val cueId: Int,
)

@Serializable
data class SetStandbyRequest(
    /** The cue to arm; null disarms and leaves the positional next on deck. */
    val cueId: Int? = null,
)

@Serializable
data class CueStackRunStateResponse(
    val stackId: Int,
    val activeCueId: Int?,
    val standbyCueId: Int?,
    val nextCueId: Int?,
)

@Serializable
data class CueStackActivateResponse(
    val stackId: Int,
    val cueId: Int,
    val cueName: String,
    val effectCount: Int,
)

private fun CueStackManager.ActivateResult.toResponse(): CueStackActivateResponse =
    CueStackActivateResponse(stackId = stackId, cueId = cueId, cueName = cueName, effectCount = effectCount)

@Serializable
data class CueStackDeactivateResponse(
    val stackId: Int,
    val removedCount: Int,
)

@Serializable
data class SortByNumberResponse(
    val updatedCues: List<CueStackCueEntry>,
    val pinnedCount: Int,
    val nullNumberCount: Int,
)

// The old flat `naturalSortKey` / `naturalCompare` / `CueNumberComparator` trio lived here.
// They compared cue numbers as one undifferentiated token stream, which is what forced the
// "must start with a digit" participation rule — a prefixed number like "S1-3" would otherwise
// sort against "T2-1" as if the prefixes were meaningful ordering data. `cueNumbering.kt` now
// models prefix / decimal run / suffix separately and compares only within a group.

// ─── Entity helpers ────────────────────────────────────────────────────

private fun DaoCueStack.toCueStackDetails(
    isCurrentProject: Boolean,
    manager: CueStackManager,
): CueStackDetails {
    val orderedCues = cues.sortedBy { it.sortOrder }.map { cue ->
        CueStackCueEntry(
            id = cue.id.value,
            name = cue.name,
            sortOrder = cue.sortOrder,
            layerCount = cue.layers.count().toInt(),
            adHocEffectCount = cue.adHocEffects.count().toInt(),
            autoAdvance = cue.autoAdvance,
            autoAdvanceDelayMs = cue.autoAdvanceDelayMs,
            fadeDurationMs = cue.fadeDurationMs,
            fadeCurve = cue.fadeCurve,
            cueNumber = cue.cueNumber,
            cueNumberAuto = cue.cueNumberAuto,
            notes = cue.notes,
            cueType = cue.cueType,
        )
    }
    val standardCueIds = orderedCues.filter { it.cueType == CueType.STANDARD.name }.map { it.id }
    return CueStackDetails(
        id = id.value,
        name = name,
        loop = loop,
        sortOrder = sortOrder,
        type = type,
        label = label,
        cues = orderedCues,
        activeCueId = manager.getActiveCueId(id.value),
        standbyCueId = manager.runState.getStandbyCueId(id.value),
        // The list overload: the cues are already loaded here, and the rules stay in the
        // tracker rather than being re-derived from `orderedCues` by the client.
        nextCueId = manager.runState.effectiveNextCueId(id.value, standardCueIds, loop),
        canEdit = isCurrentProject,
        canDelete = isCurrentProject,
    )
}
