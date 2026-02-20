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
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val isCurrentProject = state.isCurrentProject(project)
        val manager = state.show.cueStackManager
        val stacks = transaction(state.database) {
            DaoCueStack.find { DaoCueStacks.project eq project.id }
                .orderBy(DaoCueStacks.name to SortOrder.ASC)
                .map { it.toCueStackDetails(isCurrentProject, manager) }
        }
        call.respond(stacks)
    }

    // POST /{projectId}/cue-stacks - Create stack
    post<ProjectCueStacksResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot create cue stacks in project '${project.name}' - only the current project can be modified")
            )
            return@post
        }

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

    // GET /{projectId}/cue-stacks/{stackId} - Get stack details
    get<ProjectCueStackResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

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

    // PUT /{projectId}/cue-stacks/{stackId} - Update stack settings
    put<ProjectCueStackResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@put
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot modify cue stacks in project '${project.name}' - only the current project can be modified")
            )
            return@put
        }

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

    // DELETE /{projectId}/cue-stacks/{stackId} - Delete stack
    // Query param: keepCues=true (default) or false
    delete<ProjectCueStackResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@delete
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot delete cue stacks in project '${project.name}' - only the current project can be modified")
            )
            return@delete
        }

        val keepCues = call.request.queryParameters["keepCues"]?.toBoolean() ?: true

        val found = transaction(state.database) {
            val stack = DaoCueStack.findById(resource.stackId) ?: return@transaction false
            if (stack.project.id != project.id) return@transaction false

            // Deactivate if running
            state.show.cueStackManager.deactivateStack(resource.stackId)

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

    // POST /{projectId}/cue-stacks/{stackId}/reorder - Reorder cues
    post<CueStackReorderResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@post
        }

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

    // POST /{projectId}/cue-stacks/{stackId}/add-cue - Add/move cue to stack
    post<CueStackAddCueResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@post
        }

        val request = call.receive<AddCueToStackRequest>()
        val result = transaction(state.database) {
            val stack = DaoCueStack.findById(resource.parent.stackId) ?: return@transaction "Stack not found"
            val cue = DaoCue.findById(request.cueId) ?: return@transaction "Cue not found"
            if (cue.project.id != project.id) return@transaction "Cue does not belong to project"

            cue.cueStack = stack
            cue.sortOrder = request.sortOrder ?: stack.cues.count().toInt()
            null // success
        }

        if (result != null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(result))
        } else {
            state.show.fixtures.cueStackListChanged()
            state.show.fixtures.cueListChanged()
            call.respond(HttpStatusCode.OK)
        }
    }

    // POST /{projectId}/cue-stacks/{stackId}/remove-cue - Remove cue from stack (becomes standalone)
    post<CueStackRemoveCueResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@post
        }

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

    // POST /{projectId}/cue-stacks/{stackId}/activate - Activate stack
    post<CueStackActivateResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot activate - not current project"))
            return@post
        }

        val request = try { call.receive<ActivateCueStackRequest>() } catch (_: Exception) { ActivateCueStackRequest() }
        val manager = state.show.cueStackManager

        val startCueId = request.cueId ?: transaction(state.database) {
            // Default to first cue in stack
            DaoCue.find { DaoCues.cueStack eq resource.parent.stackId }
                .orderBy(DaoCues.sortOrder to SortOrder.ASC)
                .firstOrNull()?.id?.value
        }

        if (startCueId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Stack has no cues"))
            return@post
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

    // POST /{projectId}/cue-stacks/{stackId}/deactivate - Deactivate stack
    post<CueStackDeactivateResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot deactivate - not current project"))
            return@post
        }

        val removedCount = state.show.cueStackManager.deactivateStack(resource.parent.stackId)
        call.respond(CueStackDeactivateResponse(stackId = resource.parent.stackId, removedCount = removedCount))
    }

    // POST /{projectId}/cue-stacks/{stackId}/advance - Advance forward/backward
    post<CueStackAdvanceResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot advance - not current project"))
            return@post
        }

        val request = call.receive<AdvanceCueStackRequest>()
        val direction = try {
            CueStackManager.AdvanceDirection.valueOf(request.direction.uppercase())
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid direction: ${request.direction}"))
            return@post
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

    // POST /{projectId}/cue-stacks/{stackId}/go-to - Go to specific cue
    post<CueStackGoToResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot go-to - not current project"))
            return@post
        }

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
)

@Serializable
data class ReorderCuesRequest(
    val cueIds: List<Int>,
)

@Serializable
data class AddCueToStackRequest(
    val cueId: Int,
    val sortOrder: Int? = null,
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
