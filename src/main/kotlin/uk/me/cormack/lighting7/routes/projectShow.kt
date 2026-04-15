package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.resources.delete
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.GlobalScope
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectShow(state: State) {
    // GET /{projectId}/show - Get the show state (entries + active entry)
    get<ProjectShowResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val isCurrentProject = state.isCurrentProject(project)
        val details = transaction(state.database) {
            project.toShowDetails(isCurrentProject)
        }
        call.respond(details)
    }

    // POST /{projectId}/show/add-stack - Add cue stack entry
    post<ShowAddStackResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@post
        }

        val request = call.receive<AddStackToShowRequest>()
        val result = transaction(state.database) {
            val stack = DaoCueStack.findById(request.cueStackId)
                ?: return@transaction "Cue stack not found" to null

            val sortOrder = request.sortOrder ?: ((project.showEntries.maxOfOrNull { it.sortOrder } ?: -1) + 1)

            DaoShowEntry.new {
                this.project = project
                cueStack = stack
                entryType = ShowEntryType.STACK.name
                this.sortOrder = sortOrder
                label = request.label
            }

            null to project.toShowDetails(isCurrentProject = true)
        }

        val (error, details) = result
        if (error != null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
        } else {
            state.show.fixtures.showEntriesChanged()
            call.respond(HttpStatusCode.Created, details!!)
        }
    }

    // POST /{projectId}/show/add-marker - Add marker entry
    post<ShowAddMarkerResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@post
        }

        val request = call.receive<AddMarkerToShowRequest>()
        val details = transaction(state.database) {
            val sortOrder = request.sortOrder ?: ((project.showEntries.maxOfOrNull { it.sortOrder } ?: -1) + 1)

            DaoShowEntry.new {
                this.project = project
                entryType = ShowEntryType.MARKER.name
                this.sortOrder = sortOrder
                label = request.label
            }

            project.toShowDetails(isCurrentProject = true)
        }

        state.show.fixtures.showEntriesChanged()
        call.respond(HttpStatusCode.Created, details)
    }

    // PUT /{projectId}/show/entries/{entryId} - Update entry
    put<ShowEntryResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@put
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@put
        }

        val request = call.receive<UpdateShowEntryRequest>()
        val details = transaction(state.database) {
            val entry = DaoShowEntry.findById(resource.entryId)
                ?: return@transaction null
            if (entry.project.id != project.id) return@transaction null

            request.label?.let { entry.label = it }
            request.sortOrder?.let { entry.sortOrder = it }

            project.toShowDetails(isCurrentProject = true)
        }

        if (details != null) {
            state.show.fixtures.showEntriesChanged()
            call.respond(details)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Entry not found"))
        }
    }

    // DELETE /{projectId}/show/entries/{entryId} - Remove entry
    delete<ShowEntryResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@delete
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@delete
        }

        val found = transaction(state.database) {
            val entry = DaoShowEntry.findById(resource.entryId)
                ?: return@transaction false
            if (entry.project.id != project.id) return@transaction false

            // Clear project.activeEntryId if this was the active entry
            if (project.activeEntryId == entry.id.value) {
                project.activeEntryId = null
            }

            entry.delete()
            true
        }

        if (found) {
            state.show.fixtures.showEntriesChanged()
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Entry not found"))
        }
    }

    // POST /{projectId}/show/reorder - Reorder entries
    post<ShowReorderResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@post
        }

        val request = call.receive<ReorderEntriesRequest>()
        transaction(state.database) {
            for ((index, entryId) in request.entryIds.withIndex()) {
                val entry = DaoShowEntry.findById(entryId) ?: continue
                if (entry.project.id == project.id) {
                    entry.sortOrder = index
                }
            }
        }
        state.show.fixtures.showEntriesChanged()
        call.respond(HttpStatusCode.OK)
    }

    // POST /{projectId}/show/activate - Activate first STACK entry
    post<ShowActivateResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot activate - not current project"))
            return@post
        }

        try {
            val result = transaction(state.database) {
                // If already active, short-circuit — a repeat /activate must not reset the running
                // cue stack to its first cue, which would disrupt a live show.
                val currentEntryId = project.activeEntryId
                if (currentEntryId != null) {
                    val currentEntry = DaoShowEntry.findById(currentEntryId)
                    return@transaction ActivateShowResult(
                        projectId = project.id.value,
                        activated = currentEntry?.let { entry ->
                            val csId = entry.cueStack?.id?.value
                            if (csId != null) ShowActivateData(
                                projectId = project.id.value,
                                entryId = entry.id.value,
                                cueStackId = csId,
                                cueStackName = entry.cueStack?.name ?: "",
                            ) else null
                        },
                        alreadyActive = true,
                    )
                }

                val firstStack = project.showEntries
                    .filter { it.entryType == ShowEntryType.STACK.name }
                    .sortedBy { it.sortOrder }
                    .firstOrNull()
                    ?: throw IllegalArgumentException("Show has no stack entries to activate")

                val cueStackId = firstStack.cueStack?.id?.value
                    ?: throw IllegalArgumentException("Entry has no associated cue stack")
                project.activeEntryId = firstStack.id.value

                ActivateShowResult(
                    projectId = project.id.value,
                    activated = ShowActivateData(
                        projectId = project.id.value,
                        entryId = firstStack.id.value,
                        cueStackId = cueStackId,
                        cueStackName = firstStack.cueStack?.name ?: "",
                    ),
                    alreadyActive = false,
                )
            }

            if (!result.alreadyActive && result.activated != null) {
                activateStackAtFirstCue(state, result.activated.cueStackId)

                state.show.fixtures.showChanged(
                    result.projectId,
                    result.activated.entryId,
                    result.activated.cueStackId,
                    result.activated.cueStackName,
                )
            }

            call.respond(ShowActivateResponse(
                projectId = result.projectId,
                activeEntryId = result.activated?.entryId,
                activatedStackId = result.activated?.cueStackId,
                activatedStackName = result.activated?.cueStackName,
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to activate"))
        }
    }

    // POST /{projectId}/show/deactivate - Deactivate active entry's stack
    post<ShowDeactivateResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot deactivate - not current project"))
            return@post
        }

        val result = transaction(state.database) {
            if (project.activeEntryId == null) return@transaction null

            val cueStackId = project.runningCueStackId()

            project.activeEntryId = null

            project.id.value to cueStackId
        }

        if (result != null) {
            val (projectId, cueStackId) = result
            if (cueStackId != null) {
                state.show.cueStackManager.deactivateStack(cueStackId, state)
            }
            state.show.fixtures.showChanged(projectId, null, null, null)
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Show not active"))
        }
    }

    // POST /{projectId}/show/advance - Advance to next/prev STACK entry
    post<ShowAdvanceResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot advance - not current project"))
            return@post
        }

        val request = call.receive<AdvanceShowRequest>()

        try {
            val result = transaction(state.database) {
                val currentEntryId = project.activeEntryId
                    ?: throw IllegalStateException("Show has no active entry")

                // Get STACK entries only (skip MARKERs)
                val stackEntries = project.showEntries
                    .filter { it.entryType == ShowEntryType.STACK.name }
                    .sortedBy { it.sortOrder }

                if (stackEntries.isEmpty()) throw IllegalArgumentException("Show has no stack entries")

                val currentIndex = stackEntries.indexOfFirst { it.id.value == currentEntryId }
                if (currentIndex == -1) throw IllegalStateException("Active entry is not a stack entry")

                val nextIndex = when (request.direction.uppercase()) {
                    "FORWARD" -> currentIndex + 1
                    "BACKWARD" -> currentIndex - 1
                    else -> throw IllegalArgumentException("Invalid direction: ${request.direction}")
                }

                if (nextIndex !in stackEntries.indices) {
                    throw IllegalArgumentException("Cannot advance ${request.direction} — at boundary")
                }

                val nextEntry = stackEntries[nextIndex]
                val previousCueStackId = if (request.deactivatePrevious != false) {
                    DaoShowEntry.findById(currentEntryId)?.cueStack?.id?.value
                } else null

                val cueStackId = nextEntry.cueStack?.id?.value
                    ?: throw IllegalArgumentException("Next entry has no associated cue stack")

                project.activeEntryId = nextEntry.id.value

                ShowAdvanceData(
                    projectId = project.id.value,
                    entryId = nextEntry.id.value,
                    cueStackId = cueStackId,
                    cueStackName = nextEntry.cueStack?.name ?: "",
                    previousCueStackId = previousCueStackId,
                )
            }

            // Deactivate previous stack if requested
            if (result.previousCueStackId != null) {
                state.show.cueStackManager.deactivateStack(result.previousCueStackId, state)
            }

            activateStackAtFirstCue(state, result.cueStackId)

            state.show.fixtures.showChanged(
                result.projectId, result.entryId, result.cueStackId, result.cueStackName,
            )
            call.respond(ShowActivateResponse(
                projectId = result.projectId,
                activeEntryId = result.entryId,
                activatedStackId = result.cueStackId,
                activatedStackName = result.cueStackName,
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to advance"))
        }
    }

    // POST /{projectId}/show/go-to - Go to specific entry
    post<ShowGoToResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot go-to - not current project"))
            return@post
        }

        val request = call.receive<GoToShowEntryRequest>()

        try {
            val result = transaction(state.database) {
                val entry = DaoShowEntry.findById(request.entryId)
                    ?: throw IllegalArgumentException("Entry not found")
                if (entry.project.id != project.id) throw IllegalArgumentException("Entry not in show")

                if (entry.entryType == ShowEntryType.MARKER.name) {
                    throw IllegalArgumentException("Cannot go-to a MARKER entry")
                }

                val cueStackId = entry.cueStack?.id?.value
                    ?: throw IllegalArgumentException("Entry has no associated cue stack")

                // Deactivate current stack if active
                val previousCueStackId = project.activeEntryId?.let { activeId ->
                    DaoShowEntry.findById(activeId)?.cueStack?.id?.value
                }

                project.activeEntryId = entry.id.value

                ShowAdvanceData(
                    projectId = project.id.value,
                    entryId = entry.id.value,
                    cueStackId = cueStackId,
                    cueStackName = entry.cueStack?.name ?: "",
                    previousCueStackId = previousCueStackId,
                )
            }

            // Deactivate previous stack
            if (result.previousCueStackId != null) {
                state.show.cueStackManager.deactivateStack(result.previousCueStackId, state)
            }

            activateStackAtFirstCue(state, result.cueStackId)

            state.show.fixtures.showChanged(
                result.projectId, result.entryId, result.cueStackId, result.cueStackName,
            )
            call.respond(ShowActivateResponse(
                projectId = result.projectId,
                activeEntryId = result.entryId,
                activatedStackId = result.cueStackId,
                activatedStackName = result.cueStackName,
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to go to entry"))
        }
    }
}

// ─── Resource classes ──────────────────────────────────────────────────

@Resource("/{projectId}/show")
data class ProjectShowResource(val projectId: String)

@Resource("/add-stack")
data class ShowAddStackResource(val parent: ProjectShowResource)

@Resource("/add-marker")
data class ShowAddMarkerResource(val parent: ProjectShowResource)

@Resource("/entries/{entryId}")
data class ShowEntryResource(val parent: ProjectShowResource, val entryId: Int)

@Resource("/reorder")
data class ShowReorderResource(val parent: ProjectShowResource)

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
    val activeEntryId: Int?,
    val entries: List<ShowEntryDto>,
    val canEdit: Boolean,
)

@Serializable
data class ShowEntryDto(
    val id: Int,
    val entryType: String,
    val sortOrder: Int,
    val label: String?,
    val cueStackId: Int?,
    val cueStackName: String?,
)

@Serializable
data class AddStackToShowRequest(
    val cueStackId: Int,
    val sortOrder: Int? = null,
    val label: String? = null,
)

@Serializable
data class AddMarkerToShowRequest(
    val label: String,
    val sortOrder: Int? = null,
)

@Serializable
data class UpdateShowEntryRequest(
    val label: String? = null,
    val sortOrder: Int? = null,
)

@Serializable
data class ReorderEntriesRequest(
    val entryIds: List<Int>,
)

@Serializable
data class AdvanceShowRequest(
    val direction: String,
    val deactivatePrevious: Boolean? = true,
)

@Serializable
data class GoToShowEntryRequest(
    val entryId: Int,
)

@Serializable
data class ShowActivateResponse(
    val projectId: Int,
    val activeEntryId: Int?,
    val activatedStackId: Int?,
    val activatedStackName: String?,
)

// ─── Internal data classes ────────────────────────────────────────────

private data class ShowActivateData(
    val projectId: Int,
    val entryId: Int,
    val cueStackId: Int,
    val cueStackName: String,
)

private data class ShowAdvanceData(
    val projectId: Int,
    val entryId: Int,
    val cueStackId: Int,
    val cueStackName: String,
    val previousCueStackId: Int?,
)

private data class ActivateShowResult(
    val projectId: Int,
    // Null only on the already-active short-circuit when the running entry has somehow lost its cue stack.
    val activated: ShowActivateData?,
    // True when the show was already active — the handler skips side effects to avoid resetting the running stack.
    val alreadyActive: Boolean,
)

// ─── Helpers ──────────────────────────────────────────────────────────

/**
 * Activate a cue stack at its first STANDARD cue.
 * Throws [IllegalArgumentException] if the stack has no standard cues.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
private fun activateStackAtFirstCue(state: State, cueStackId: Int) {
    val firstCueId = transaction(state.database) {
        DaoCue.find {
            (DaoCues.cueStack eq cueStackId) and
                (DaoCues.cueType eq CueType.STANDARD.name)
        }.orderBy(DaoCues.sortOrder to SortOrder.ASC)
            .firstOrNull()?.id?.value
    } ?: throw IllegalArgumentException("Cue stack has no standard cues")

    state.show.cueStackManager.activateCueInStack(state, cueStackId, firstCueId, GlobalScope)
}

// ─── Entity helpers ────────────────────────────────────────────────────

/** Cue stack id associated with this project's currently-active entry, or null when no entry is active or the entry has no stack. */
private fun DaoProject.runningCueStackId(): Int? =
    activeEntryId?.let { DaoShowEntry.findById(it) }?.cueStack?.id?.value

private fun DaoProject.toShowDetails(isCurrentProject: Boolean): ShowDetails {
    val orderedEntries = showEntries.sortedBy { it.sortOrder }.map { entry ->
        ShowEntryDto(
            id = entry.id.value,
            entryType = entry.entryType,
            sortOrder = entry.sortOrder,
            label = entry.label,
            cueStackId = entry.cueStack?.id?.value,
            cueStackName = entry.cueStack?.name,
        )
    }
    return ShowDetails(
        projectId = id.value,
        activeEntryId = activeEntryId,
        entries = orderedEntries,
        canEdit = isCurrentProject,
    )
}
