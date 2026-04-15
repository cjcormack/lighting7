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
import kotlinx.coroutines.GlobalScope
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectCueStacks(state: State) {
    // GET /{projectId}/cue-stacks - List all stacks with ordered cues + active cue info
    get<ProjectCueStacksResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val isCurrentProject = state.isCurrentProject(project)
            val manager = state.show.cueStackManager
            val stacks = transaction(state.database) {
                DaoCueStack.find { DaoCueStacks.project eq project.id }
                    .orderBy(DaoCueStacks.name to SortOrder.ASC)
                    .map { it.toCueStackDetails(isCurrentProject, manager) }
            }
            call.respond(stacks)
        }
    }

    // POST /{projectId}/cue-stacks - Create stack
    post<ProjectCueStacksResource> { resource ->
        withCurrentProject(
            state,
            resource.projectId,
            { p -> "Cannot create cue stacks in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val input = call.receive<NewCueStack>()
            val manager = state.show.cueStackManager
            val details = transaction(state.database) {
                val stack = DaoCueStack.new {
                    name = input.name
                    this.project = project
                    palette = input.palette
                    loop = input.loop
                }
                stack.toCueStackDetails(isCurrentProject = true, manager)
            }
            state.show.fixtures.cueStackListChanged()
            call.respond(HttpStatusCode.Created, details)
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

                stack.name = input.name
                stack.palette = input.palette
                stack.loop = input.loop

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

    // DELETE /{projectId}/cue-stacks/{stackId} - Delete stack
    // Query param: keepCues=true (default) or false
    delete<ProjectCueStackResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot delete cue stacks in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val keepCues = call.request.queryParameters["keepCues"]?.toBoolean() ?: true

            val found = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.stackId) ?: return@transaction false
                if (stack.project.id != project.id) return@transaction false

                // Deactivate if running
                state.show.cueStackManager.deactivateStack(resource.stackId, state)

                if (keepCues) {
                    // Detach cues from stack (make standalone)
                    stack.cues.forEach { cue ->
                        cue.cueStack = null
                        cue.sortOrder = 0
                    }
                } else {
                    // Delete cues and their children
                    stack.cues.forEach { cue ->
                        deleteCueChildren(cue)
                        cue.delete()
                    }
                }

                stack.delete()
                true
            }

            if (found) {
                state.show.fixtures.cueStackListChanged()
                state.show.fixtures.cueListChanged()
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
            transaction(state.database) {
                for ((index, cueId) in request.cueIds.withIndex()) {
                    val cue = DaoCue.findById(cueId) ?: continue
                    if (cue.cueStack?.id?.value == resource.parent.stackId) {
                        cue.sortOrder = index
                    }
                }
            }
            state.show.fixtures.cueStackListChanged()
            call.respond(HttpStatusCode.OK)
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/add-cue - Add/move cue to stack
    post<CueStackAddCueResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId) { project ->
            val request = call.receive<AddCueToStackRequest>()
            val manager = state.show.cueStackManager
            val result = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.parent.stackId) ?: return@transaction "Stack not found" to null
                val cue = DaoCue.findById(request.cueId) ?: return@transaction "Cue not found" to null
                if (cue.project.id != project.id) return@transaction "Cue does not belong to project" to null

                if (request.insertByNumber) {
                    val cueNum = cue.cueNumber
                    if (cueNum == null || cueNum.isEmpty() || !cueNum[0].isDigit()) {
                        return@transaction "insertByNumber requires a cue_number starting with a digit" to null
                    }

                    // Get existing STANDARD cues in this stack, ordered by sort_order
                    val existingCues = DaoCue.find {
                        (DaoCues.cueStack eq stack.id) and (DaoCues.cueType eq CueType.STANDARD.name)
                    }.orderBy(DaoCues.sortOrder to SortOrder.ASC)
                        .filter { it.id.value != cue.id.value }
                        .toList()

                    // Find participating cues (digit-first cue_number)
                    val participating = existingCues.filter { c ->
                        val num = c.cueNumber
                        num != null && num.isNotEmpty() && num[0].isDigit()
                    }

                    // Find insertion point: after last participating cue that sorts before new cue
                    val insertAfter = participating.lastOrNull { c ->
                        naturalCompare(c.cueNumber!!, cueNum) < 0
                    }

                    val insertSortOrder = if (insertAfter != null) {
                        insertAfter.sortOrder + 1
                    } else if (participating.isNotEmpty()) {
                        // Insert before all participating cues
                        participating.first().sortOrder
                    } else {
                        // No participating cues — append at end
                        (existingCues.maxOfOrNull { it.sortOrder } ?: -1) + 1
                    }

                    // Shift subsequent cues
                    existingCues.filter { it.sortOrder >= insertSortOrder }
                        .forEach { it.sortOrder = it.sortOrder + 1 }

                    cue.cueStack = stack
                    cue.sortOrder = insertSortOrder
                } else {
                    cue.cueStack = stack
                    cue.sortOrder = request.sortOrder ?: stack.cues.count().toInt()
                }

                val details = stack.toCueStackDetails(isCurrentProject = true, manager)
                null to details
            }

            val (error, details) = result
            if (error != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
            } else {
                state.show.fixtures.cueStackListChanged()
                state.show.fixtures.cueListChanged()
                call.respond(details!!)
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/remove-cue - Remove cue from stack (becomes standalone)
    post<CueStackRemoveCueResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId) { _ ->
            val request = call.receive<RemoveCueFromStackRequest>()
            val result = transaction(state.database) {
                val cue = DaoCue.findById(request.cueId) ?: return@transaction "Cue not found"
                if (cue.cueStack?.id?.value != resource.parent.stackId) return@transaction "Cue is not in this stack"

                cue.cueStack = null
                cue.sortOrder = 0
                null
            }

            if (result != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(result))
            } else {
                state.show.fixtures.cueStackListChanged()
                state.show.fixtures.cueListChanged()
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/activate - Activate stack
    post<CueStackActivateResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot activate - not current project") { _ ->
            val request = try { call.receive<ActivateCueStackRequest>() } catch (_: Exception) { ActivateCueStackRequest() }
            val manager = state.show.cueStackManager

            val startCueId = request.cueId ?: transaction(state.database) {
                // Default to first STANDARD cue in stack (skip MARKERs)
                DaoCue.find {
                    (DaoCues.cueStack eq resource.parent.stackId) and
                        (DaoCues.cueType eq CueType.STANDARD.name)
                }
                    .orderBy(DaoCues.sortOrder to SortOrder.ASC)
                    .firstOrNull()?.id?.value
            }

            if (startCueId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Stack has no cues"))
                return@withCurrentProject
            }

            try {
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                val result = manager.activateCueInStack(state, resource.parent.stackId, startCueId, GlobalScope)
                call.respond(CueStackActivateResponse(
                    stackId = result.stackId,
                    cueId = result.cueId,
                    cueName = result.cueName,
                    effectCount = result.effectCount,
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to activate stack"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/deactivate - Deactivate stack
    post<CueStackDeactivateResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Cannot deactivate - not current project") { _ ->
            val removedCount = state.show.cueStackManager.deactivateStack(resource.parent.stackId, state)
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
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                val result = state.show.cueStackManager.advanceStack(state, resource.parent.stackId, direction, GlobalScope)
                if (result != null) {
                    call.respond(CueStackActivateResponse(
                        stackId = result.stackId,
                        cueId = result.cueId,
                        cueName = result.cueName,
                        effectCount = result.effectCount,
                    ))
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
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                val result = state.show.cueStackManager.goToCue(state, resource.parent.stackId, request.cueId, GlobalScope)
                call.respond(CueStackActivateResponse(
                    stackId = result.stackId,
                    cueId = result.cueId,
                    cueName = result.cueName,
                    effectCount = result.effectCount,
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to go to cue"))
            }
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/sort-by-cue-number - Reorder by natural sort
    post<CueStackSortByNumberResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId) { project ->
            val manager = state.show.cueStackManager
            val result = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.parent.stackId) ?: return@transaction null
                if (stack.project.id != project.id) return@transaction null

                // Get all cues in the stack ordered by sort_order
                val allCues = DaoCue.find { DaoCues.cueStack eq stack.id }
                    .orderBy(DaoCues.sortOrder to SortOrder.ASC)
                    .toList()

                // Partition STANDARD cues only — MARKERs are not considered
                val standardCues = allCues.filter { it.cueType == CueType.STANDARD.name }

                // Three-group partition of STANDARD cues
                val participating = standardCues.filter { c ->
                    val num = c.cueNumber
                    !num.isNullOrEmpty() && num[0].isDigit()
                }
                val pinned = standardCues.filter { c ->
                    val num = c.cueNumber
                    !num.isNullOrEmpty() && !num[0].isDigit()
                }
                val unnumbered = standardCues.filter { it.cueNumber.isNullOrEmpty() }

                if (participating.isEmpty()) {
                    return@transaction SortByNumberResult(
                        error = "No participating cues to sort (need cue numbers starting with a digit)",
                        response = null,
                    )
                }

                // Sort participating by natural sort
                val sortedParticipating = participating.sortedWith(
                    compareBy(CueNumberComparator) { it.cueNumber!! }
                )

                // Collect the sort_order positions that participating cues currently occupy
                val participatingPositions = participating.map { it.sortOrder }.sorted()

                // Assign sorted participating cues to those positions
                sortedParticipating.forEachIndexed { index, cue ->
                    cue.sortOrder = participatingPositions[index]
                }

                // Append unnumbered after all others (find the max sort_order)
                val maxSortOrder = allCues.maxOfOrNull { it.sortOrder } ?: 0
                unnumbered.forEachIndexed { index, cue ->
                    cue.sortOrder = maxSortOrder + 1 + index
                }

                // Build response with updated stack details
                val details = stack.toCueStackDetails(isCurrentProject = true, manager)

                SortByNumberResult(
                    error = null,
                    response = SortByNumberResponse(
                        updatedCues = details.cues,
                        pinnedCount = pinned.size,
                        nullNumberCount = unnumbered.size,
                    ),
                )
            }

            if (result == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            } else if (result.error != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.error))
            } else {
                state.show.fixtures.cueStackListChanged()
                call.respond(result.response!!)
            }
        }
    }
}

// ─── Resource classes ──────────────────────────────────────────────────

@Resource("/{projectId}/cue-stacks")
data class ProjectCueStacksResource(val projectId: String)

@Resource("/{stackId}")
data class ProjectCueStackResource(val parent: ProjectCueStacksResource, val stackId: Int)

@Resource("/reorder")
data class CueStackReorderResource(val parent: ProjectCueStackResource)

@Resource("/add-cue")
data class CueStackAddCueResource(val parent: ProjectCueStackResource)

@Resource("/remove-cue")
data class CueStackRemoveCueResource(val parent: ProjectCueStackResource)

@Resource("/activate")
data class CueStackActivateResource(val parent: ProjectCueStackResource)

@Resource("/deactivate")
data class CueStackDeactivateResource(val parent: ProjectCueStackResource)

@Resource("/advance")
data class CueStackAdvanceResource(val parent: ProjectCueStackResource)

@Resource("/go-to")
data class CueStackGoToResource(val parent: ProjectCueStackResource)

@Resource("/sort-by-cue-number")
data class CueStackSortByNumberResource(val parent: ProjectCueStackResource)

// ─── DTOs ──────────────────────────────────────────────────────────────

@Serializable
data class NewCueStack(
    val name: String,
    val palette: List<String> = emptyList(),
    val loop: Boolean = false,
)

@Serializable
data class CueStackDetails(
    val id: Int,
    val name: String,
    val palette: List<String>,
    val loop: Boolean,
    val cues: List<CueStackCueEntry>,
    val activeCueId: Int?,
    val canEdit: Boolean,
    val canDelete: Boolean,
)

@Serializable
data class CueStackCueEntry(
    val id: Int,
    val name: String,
    val sortOrder: Int,
    val paletteSize: Int,
    val presetCount: Int,
    val adHocEffectCount: Int,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
    val cueNumber: String? = null,
    val notes: String? = null,
    val cueType: String = "STANDARD",
)

@Serializable
data class ReorderCuesRequest(
    val cueIds: List<Int>,
)

@Serializable
data class AddCueToStackRequest(
    val cueId: Int,
    val sortOrder: Int? = null,
    val insertByNumber: Boolean = false,
)

@Serializable
data class RemoveCueFromStackRequest(
    val cueId: Int,
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
data class CueStackActivateResponse(
    val stackId: Int,
    val cueId: Int,
    val cueName: String,
    val effectCount: Int,
)

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

private data class SortByNumberResult(
    val error: String?,
    val response: SortByNumberResponse?,
)

// ─── Natural sort ─────────────────────────────────────────────────────

/**
 * Splits a cue number into alternating numeric/non-numeric segments for natural sort.
 * E.g. "14A" → [14, "A"], "1.5" → [1, ".", 5]
 */
internal fun naturalSortKey(cueNumber: String): List<Comparable<*>> {
    val segments = mutableListOf<Comparable<*>>()
    var i = 0
    while (i < cueNumber.length) {
        if (cueNumber[i].isDigit()) {
            val start = i
            while (i < cueNumber.length && cueNumber[i].isDigit()) i++
            segments.add(cueNumber.substring(start, i).toLong())
        } else {
            val start = i
            while (i < cueNumber.length && !cueNumber[i].isDigit()) i++
            segments.add(cueNumber.substring(start, i))
        }
    }
    return segments
}

/**
 * Compares two cue numbers using natural sort order.
 * Numeric segments are compared numerically, non-numeric segments lexicographically.
 * Result: 1 < 1.5 < 2 < 14 < 14A < 14B < 15 < 100
 */
internal fun naturalCompare(a: String, b: String): Int {
    val aKey = naturalSortKey(a)
    val bKey = naturalSortKey(b)
    val len = minOf(aKey.size, bKey.size)
    for (idx in 0 until len) {
        val aVal = aKey[idx]
        val bVal = bKey[idx]
        val cmp = when {
            aVal is Long && bVal is Long -> aVal.compareTo(bVal)
            aVal is String && bVal is String -> aVal.compareTo(bVal)
            aVal is Long -> -1 // numbers before strings
            else -> 1
        }
        if (cmp != 0) return cmp
    }
    return aKey.size.compareTo(bKey.size)
}

/**
 * Comparator for cue numbers using natural sort order.
 */
internal object CueNumberComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int = naturalCompare(a, b)
}

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
            paletteSize = cue.palette.size,
            presetCount = cue.presetApplications.count().toInt(),
            adHocEffectCount = cue.adHocEffects.count().toInt(),
            autoAdvance = cue.autoAdvance,
            autoAdvanceDelayMs = cue.autoAdvanceDelayMs,
            fadeDurationMs = cue.fadeDurationMs,
            fadeCurve = cue.fadeCurve,
            cueNumber = cue.cueNumber,
            notes = cue.notes,
            cueType = cue.cueType,
        )
    }
    return CueStackDetails(
        id = id.value,
        name = name,
        palette = palette,
        loop = loop,
        cues = orderedCues,
        activeCueId = manager.getActiveCueId(id.value),
        canEdit = isCurrentProject,
        canDelete = isCurrentProject,
    )
}
