package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

/**
 * The "show" is now just the project's ordered list of cue stacks (see [DaoCueStacks]); the content
 * and order of that list live under `/{projectId}/cue-stacks`. These endpoints are the thin
 * *playhead* over it: which stack is currently live ([DaoProject.activeStackId]) and how to
 * step/jump between the runnable `STACK`-type rows (SEPARATORs are skipped).
 */
internal fun Route.routeApiRestProjectShow(state: State) {
    // GET /{projectId}/show - Current playhead state.
    get<ProjectShowResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val isCurrentProject = state.isCurrentProject(project)
            val details = transaction(state.database) {
                ShowDetails(
                    projectId = project.id.value,
                    activeStackId = project.activeStackId,
                    canEdit = isCurrentProject,
                )
            }
            call.respond(details)
        }
    }

    // POST /{projectId}/show/activate - Activate the first runnable stack in order.
    post<ShowActivateResource> { resource ->
        withCurrentProject(state, resource.parent.projectId, "Cannot activate - not current project") { project ->
            try {
                val result = transaction(state.database) {
                    // If already active, short-circuit — a repeat /activate must not reset the running
                    // cue stack to its first cue, which would disrupt a live show.
                    val currentStackId = project.activeStackId
                    if (currentStackId != null) {
                        val currentStack = DaoCueStack.findById(currentStackId)
                        return@transaction ActivateShowResult(
                            projectId = project.id.value,
                            activatedStackId = currentStack?.id?.value,
                            activatedStackName = currentStack?.name ?: "",
                            alreadyActive = true,
                        )
                    }

                    val firstStack = project.orderedStacks().firstOrNull()
                        ?: throw IllegalArgumentException("Show has no stacks to activate")

                    project.activeStackId = firstStack.id.value

                    ActivateShowResult(
                        projectId = project.id.value,
                        activatedStackId = firstStack.id.value,
                        activatedStackName = firstStack.name,
                        alreadyActive = false,
                    )
                }

                if (!result.alreadyActive && result.activatedStackId != null) {
                    state.show.cueStackManager.activateAtFirstCue(state, result.activatedStackId)
                    state.show.fixtures.showChanged(
                        result.projectId, result.activatedStackId, result.activatedStackName,
                    )
                }

                call.respond(ShowActivateResponse(
                    projectId = result.projectId,
                    activeStackId = result.activatedStackId,
                    activatedStackName = result.activatedStackName,
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to activate"))
            }
        }
    }

    // POST /{projectId}/show/deactivate - Deactivate the running stack.
    post<ShowDeactivateResource> { resource ->
        withCurrentProject(state, resource.parent.projectId, "Cannot deactivate - not current project") { project ->
            val result = transaction(state.database) {
                val stackId = project.activeStackId ?: return@transaction null
                project.activeStackId = null
                project.id.value to stackId
            }

            if (result != null) {
                val (projectId, cueStackId) = result
                state.show.cueStackManager.deactivateStack(cueStackId, state)
                state.show.fixtures.showChanged(projectId, null, null)
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Show not active"))
            }
        }
    }

    // POST /{projectId}/show/advance - Advance to next/prev runnable stack (skips separators).
    post<ShowAdvanceResource> { resource ->
        withCurrentProject(state, resource.parent.projectId, "Cannot advance - not current project") { project ->
            val request = call.receive<AdvanceShowRequest>()

            try {
                val result = transaction(state.database) {
                    val currentStackId = project.activeStackId
                        ?: throw IllegalStateException("Show has no active stack")

                    val stacks = project.orderedStacks()
                    if (stacks.isEmpty()) throw IllegalArgumentException("Show has no stacks")

                    val currentIndex = stacks.indexOfFirst { it.id.value == currentStackId }
                    if (currentIndex == -1) throw IllegalStateException("Active stack is not in the show")

                    val nextIndex = when (request.direction.uppercase()) {
                        "FORWARD" -> currentIndex + 1
                        "BACKWARD" -> currentIndex - 1
                        else -> throw IllegalArgumentException("Invalid direction: ${request.direction}")
                    }

                    if (nextIndex !in stacks.indices) {
                        throw IllegalArgumentException("Cannot advance ${request.direction} — at boundary")
                    }

                    val nextStack = stacks[nextIndex]
                    val previousCueStackId = if (request.deactivatePrevious != false) currentStackId else null

                    project.activeStackId = nextStack.id.value

                    ShowAdvanceData(
                        projectId = project.id.value,
                        cueStackId = nextStack.id.value,
                        cueStackName = nextStack.name,
                        previousCueStackId = previousCueStackId,
                    )
                }

                if (result.previousCueStackId != null) {
                    state.show.cueStackManager.deactivateStack(result.previousCueStackId, state)
                }

                state.show.cueStackManager.activateAtFirstCue(state, result.cueStackId)
                state.show.fixtures.showChanged(result.projectId, result.cueStackId, result.cueStackName)
                call.respond(ShowActivateResponse(
                    projectId = result.projectId,
                    activeStackId = result.cueStackId,
                    activatedStackName = result.cueStackName,
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to advance"))
            }
        }
    }

    // POST /{projectId}/show/go-to - Jump the playhead to a specific stack.
    post<ShowGoToResource> { resource ->
        withCurrentProject(state, resource.parent.projectId, "Cannot go-to - not current project") { project ->
            val request = call.receive<GoToStackRequest>()

            try {
                val result = transaction(state.database) {
                    val stack = DaoCueStack.findById(request.stackId)
                        ?: throw IllegalArgumentException("Stack not found")
                    if (stack.project.id != project.id) throw IllegalArgumentException("Stack not in this project")
                    if (stack.type == CueStackType.SEPARATOR.name) {
                        throw IllegalArgumentException("Cannot go-to a separator")
                    }

                    val previousCueStackId = project.activeStackId
                    project.activeStackId = stack.id.value

                    ShowAdvanceData(
                        projectId = project.id.value,
                        cueStackId = stack.id.value,
                        cueStackName = stack.name,
                        previousCueStackId = previousCueStackId,
                    )
                }

                if (result.previousCueStackId != null && result.previousCueStackId != result.cueStackId) {
                    state.show.cueStackManager.deactivateStack(result.previousCueStackId, state)
                }

                state.show.cueStackManager.activateAtFirstCue(state, result.cueStackId)
                state.show.fixtures.showChanged(result.projectId, result.cueStackId, result.cueStackName)
                call.respond(ShowActivateResponse(
                    projectId = result.projectId,
                    activeStackId = result.cueStackId,
                    activatedStackName = result.cueStackName,
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to go to stack"))
            }
        }
    }
}

// ─── Resource classes ──────────────────────────────────────────────────

@Resource("/{projectId}/show")
data class ProjectShowResource(val projectId: String)

@Resource("/activate")
data class ShowActivateResource(val parent: ProjectShowResource)

@Resource("/deactivate")
data class ShowDeactivateResource(val parent: ProjectShowResource)

@Resource("/advance")
data class ShowAdvanceResource(val parent: ProjectShowResource)

@Resource("/go-to")
data class ShowGoToResource(val parent: ProjectShowResource)

// ─── DTOs ──────────────────────────────────────────────────────────────

@Serializable
data class ShowDetails(
    val projectId: Int,
    val activeStackId: Int?,
    val canEdit: Boolean,
)

@Serializable
data class AdvanceShowRequest(
    val direction: String,
    val deactivatePrevious: Boolean? = true,
)

@Serializable
data class GoToStackRequest(
    val stackId: Int,
)

@Serializable
data class ShowActivateResponse(
    val projectId: Int,
    val activeStackId: Int?,
    val activatedStackName: String?,
)

// ─── Internal data classes ────────────────────────────────────────────

private data class ShowAdvanceData(
    val projectId: Int,
    val cueStackId: Int,
    val cueStackName: String,
    val previousCueStackId: Int?,
)

private data class ActivateShowResult(
    val projectId: Int,
    // Null only on the already-active short-circuit when the running stack has somehow vanished.
    val activatedStackId: Int?,
    val activatedStackName: String,
    // True when the show was already active — the handler skips side effects to avoid resetting the running stack.
    val alreadyActive: Boolean,
)

// ─── Entity helpers ────────────────────────────────────────────────────

/** This project's runnable stacks (excluding SEPARATOR rows), in show order. */
private fun DaoProject.orderedStacks(): List<DaoCueStack> =
    cueStacks.filter { it.type == CueStackType.STACK.name }.sortedBy { it.sortOrder }
