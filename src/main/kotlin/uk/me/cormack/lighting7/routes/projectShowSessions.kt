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

internal fun Route.routeApiRestProjectShowSessions(state: State) {
    // GET /{projectId}/show-sessions - List all sessions with entries
    get<ProjectShowSessionsResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val isCurrentProject = state.isCurrentProject(project)
        val sessions = transaction(state.database) {
            DaoShowSession.find { DaoShowSessions.project eq project.id }
                .map { it.toShowSessionDetails(isCurrentProject) }
        }
        call.respond(sessions)
    }

    // POST /{projectId}/show-sessions - Create session
    post<ProjectShowSessionsResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot create - not current project"))
            return@post
        }

        val input = call.receive<NewShowSession>()
        val details = transaction(state.database) {
            val session = DaoShowSession.new {
                this.project = project
                name = input.name
                sessionType = input.sessionType
            }
            session.toShowSessionDetails(isCurrentProject = true)
        }
        state.show.fixtures.showSessionListChanged()
        call.respond(HttpStatusCode.Created, details)
    }

    // GET /{projectId}/show-sessions/{sessionId} - Get session details
    get<ProjectShowSessionResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val isCurrentProject = state.isCurrentProject(project)
        val details = transaction(state.database) {
            val session = DaoShowSession.findById(resource.sessionId) ?: return@transaction null
            if (session.project.id != project.id) return@transaction null
            session.toShowSessionDetails(isCurrentProject)
        }

        if (details != null) {
            call.respond(details)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Show session not found"))
        }
    }

    // PUT /{projectId}/show-sessions/{sessionId} - Update session
    put<ProjectShowSessionResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@put
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@put
        }

        val input = call.receive<UpdateShowSession>()
        val details = transaction(state.database) {
            val session = DaoShowSession.findById(resource.sessionId) ?: return@transaction null
            if (session.project.id != project.id) return@transaction null

            session.name = input.name
            session.sessionType = input.sessionType
            session.updatedAt = System.currentTimeMillis()

            session.toShowSessionDetails(isCurrentProject = true)
        }

        if (details != null) {
            state.show.fixtures.showSessionListChanged()
            call.respond(details)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Show session not found"))
        }
    }

    // DELETE /{projectId}/show-sessions/{sessionId} - Delete session
    delete<ProjectShowSessionResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@delete
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot delete - not current project"))
            return@delete
        }

        val result = transaction(state.database) {
            val session = DaoShowSession.findById(resource.sessionId) ?: return@transaction null
            if (session.project.id != project.id) return@transaction null

            // Capture the running cue stack (if this session was active) so we can stop it
            // outside the transaction. Clearing activeEntryId first satisfies the deferrable FK
            // before the entries are deleted.
            val activeCueStackId = if (session.isActive) session.runningCueStackId() else null
            val wasActive = session.isActive
            val sessionId = session.id.value
            session.activeEntryId = null
            session.isActive = false

            // Delete entries first (cascade would handle this, but be explicit)
            session.entries.forEach { it.delete() }
            session.delete()
            Triple(sessionId, activeCueStackId, wasActive)
        }

        if (result != null) {
            val (sessionId, activeCueStackId, wasActive) = result
            if (activeCueStackId != null) {
                state.show.cueStackManager.deactivateStack(activeCueStackId, state)
            }
            if (wasActive) {
                state.show.fixtures.showSessionChanged(sessionId, null, null, null, isActive = false)
            }
            state.show.fixtures.showSessionListChanged()
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Show session not found"))
        }
    }

    // POST /{projectId}/show-sessions/{sessionId}/add-stack - Add cue stack entry
    post<ShowSessionAddStackResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@post
        }

        val request = call.receive<AddStackToSessionRequest>()
        val result = transaction(state.database) {
            val session = DaoShowSession.findById(resource.parent.sessionId)
                ?: return@transaction "Session not found" to null
            if (session.project.id != project.id) return@transaction "Session not found" to null

            val stack = DaoCueStack.findById(request.cueStackId)
                ?: return@transaction "Cue stack not found" to null

            val sortOrder = request.sortOrder ?: session.entries.count().toInt()

            DaoShowSessionEntry.new {
                showSession = session
                cueStack = stack
                entryType = ShowSessionEntryType.STACK.name
                this.sortOrder = sortOrder
                label = request.label
            }

            session.updatedAt = System.currentTimeMillis()
            null to session.toShowSessionDetails(isCurrentProject = true)
        }

        val (error, details) = result
        if (error != null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
        } else {
            state.show.fixtures.showSessionListChanged()
            call.respond(HttpStatusCode.Created, details!!)
        }
    }

    // POST /{projectId}/show-sessions/{sessionId}/add-marker - Add marker entry
    post<ShowSessionAddMarkerResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@post
        }

        val request = call.receive<AddMarkerToSessionRequest>()
        val details = transaction(state.database) {
            val session = DaoShowSession.findById(resource.parent.sessionId)
                ?: return@transaction null
            if (session.project.id != project.id) return@transaction null

            val sortOrder = request.sortOrder ?: session.entries.count().toInt()

            DaoShowSessionEntry.new {
                showSession = session
                entryType = ShowSessionEntryType.MARKER.name
                this.sortOrder = sortOrder
                label = request.label
            }

            session.updatedAt = System.currentTimeMillis()
            session.toShowSessionDetails(isCurrentProject = true)
        }

        if (details != null) {
            state.show.fixtures.showSessionListChanged()
            call.respond(HttpStatusCode.Created, details)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Show session not found"))
        }
    }

    // PUT /{projectId}/show-sessions/{sessionId}/entries/{entryId} - Update entry
    put<ShowSessionEntryResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@put
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@put
        }

        val request = call.receive<UpdateShowSessionEntry>()
        val details = transaction(state.database) {
            val session = DaoShowSession.findById(resource.parent.sessionId)
                ?: return@transaction null
            if (session.project.id != project.id) return@transaction null

            val entry = DaoShowSessionEntry.findById(resource.entryId)
                ?: return@transaction null
            if (entry.showSession.id != session.id) return@transaction null

            request.label?.let { entry.label = it }
            request.sortOrder?.let { entry.sortOrder = it }

            session.updatedAt = System.currentTimeMillis()
            session.toShowSessionDetails(isCurrentProject = true)
        }

        if (details != null) {
            state.show.fixtures.showSessionListChanged()
            call.respond(details)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Entry not found"))
        }
    }

    // DELETE /{projectId}/show-sessions/{sessionId}/entries/{entryId} - Remove entry
    delete<ShowSessionEntryResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@delete
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@delete
        }

        val found = transaction(state.database) {
            val session = DaoShowSession.findById(resource.parent.sessionId)
                ?: return@transaction false
            if (session.project.id != project.id) return@transaction false

            val entry = DaoShowSessionEntry.findById(resource.entryId)
                ?: return@transaction false
            if (entry.showSession.id != session.id) return@transaction false

            // Clear active_entry_id if this was the active entry
            if (session.activeEntryId == entry.id.value) {
                session.activeEntryId = null
            }

            entry.delete()
            session.updatedAt = System.currentTimeMillis()
            true
        }

        if (found) {
            state.show.fixtures.showSessionListChanged()
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Entry not found"))
        }
    }

    // POST /{projectId}/show-sessions/{sessionId}/reorder - Reorder entries
    post<ShowSessionReorderResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
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
            val session = DaoShowSession.findById(resource.parent.sessionId) ?: return@transaction
            if (session.project.id != project.id) return@transaction

            for ((index, entryId) in request.entryIds.withIndex()) {
                val entry = DaoShowSessionEntry.findById(entryId) ?: continue
                if (entry.showSession.id == session.id) {
                    entry.sortOrder = index
                }
            }
            session.updatedAt = System.currentTimeMillis()
        }
        state.show.fixtures.showSessionListChanged()
        call.respond(HttpStatusCode.OK)
    }

    // POST /{projectId}/show-sessions/{sessionId}/activate - Activate first STACK entry
    post<ShowSessionActivateResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
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
                val session = DaoShowSession.findById(resource.parent.sessionId)
                    ?: throw IllegalArgumentException("Session not found")
                if (session.project.id != project.id) throw IllegalArgumentException("Session not found")

                // If already active, short-circuit — a repeat /activate must not reset the running
                // cue stack to its first cue, which would disrupt a live show.
                if (session.isActive) {
                    val currentEntryId = session.activeEntryId
                    val currentEntry = currentEntryId?.let { DaoShowSessionEntry.findById(it) }
                    return@transaction ActivateSessionResult(
                        sessionId = session.id.value,
                        activated = currentEntry?.let { entry ->
                            val csId = entry.cueStack?.id?.value
                            if (csId != null) ShowSessionActivateData(
                                sessionId = session.id.value,
                                entryId = entry.id.value,
                                cueStackId = csId,
                                cueStackName = entry.cueStack?.name ?: "",
                            ) else null
                        },
                        deactivated = emptyList(),
                        alreadyActive = true,
                    )
                }

                // First STACK entry drives the cue-stack activation side effect. An empty session
                // can still be marked active — it just won't start a stack.
                val firstStack = session.entries
                    .filter { it.entryType == ShowSessionEntryType.STACK.name }
                    .sortedBy { it.sortOrder }
                    .firstOrNull()

                // Enforce single-active-session-per-project: deactivate any siblings that are
                // currently marked active. We collect their cueStackIds here for runtime
                // deactivation outside this transaction (CueStackManager is not transactional).
                val now = System.currentTimeMillis()
                val deactivatedSiblings = DaoShowSession.find {
                    (DaoShowSessions.project eq project.id) and
                        (DaoShowSessions.isActive eq true) and
                        (DaoShowSessions.id neq session.id)
                }.map { sibling ->
                    val siblingCueStackId = sibling.runningCueStackId()
                    sibling.activeEntryId = null
                    sibling.isActive = false
                    sibling.updatedAt = now
                    DeactivatedSiblingData(sibling.id.value, siblingCueStackId)
                }

                val activatedData = if (firstStack != null) {
                    val cueStackId = firstStack.cueStack?.id?.value
                        ?: throw IllegalArgumentException("Entry has no associated cue stack")
                    session.activeEntryId = firstStack.id.value
                    ShowSessionActivateData(
                        sessionId = session.id.value,
                        entryId = firstStack.id.value,
                        cueStackId = cueStackId,
                        cueStackName = firstStack.cueStack?.name ?: "",
                    )
                } else {
                    // Empty session: mark active, but no entry / stack to start.
                    session.activeEntryId = null
                    null
                }
                session.isActive = true
                session.updatedAt = now

                ActivateSessionResult(
                    sessionId = session.id.value,
                    activated = activatedData,
                    deactivated = deactivatedSiblings,
                    alreadyActive = false,
                )
            }

            // Already-active short-circuit: skip side effects and broadcasts; only echo state.
            if (!result.alreadyActive) {
                // Deactivate sibling cue stacks first, then activate the target (if any).
                for (sibling in result.deactivated) {
                    if (sibling.cueStackId != null) {
                        state.show.cueStackManager.deactivateStack(sibling.cueStackId, state)
                    }
                }

                if (result.activated != null) {
                    activateStackAtFirstCue(state, result.activated.cueStackId)
                }

                // Broadcast per-session events: deactivated siblings first, then the newly active one.
                for (sibling in result.deactivated) {
                    state.show.fixtures.showSessionChanged(sibling.sessionId, null, null, null, isActive = false)
                }
                state.show.fixtures.showSessionChanged(
                    result.sessionId,
                    result.activated?.entryId,
                    result.activated?.cueStackId,
                    result.activated?.cueStackName,
                    isActive = true,
                )
                // Invalidate list caches on all clients so isActive flips propagate via refetch.
                state.show.fixtures.showSessionListChanged()
            }

            call.respond(ShowSessionActivateResponse(
                sessionId = result.sessionId,
                activeEntryId = result.activated?.entryId,
                activatedStackId = result.activated?.cueStackId,
                activatedStackName = result.activated?.cueStackName,
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to activate"))
        }
    }

    // POST /{projectId}/show-sessions/{sessionId}/deactivate - Deactivate active entry's stack
    post<ShowSessionDeactivateResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot deactivate - not current project"))
            return@post
        }

        val result = transaction(state.database) {
            val session = DaoShowSession.findById(resource.parent.sessionId)
                ?: return@transaction null
            if (session.project.id != project.id) return@transaction null

            // A session may have isActive=true with no running entry (empty session picked up mid-flow).
            // Treat either condition as needing deactivation.
            if (!session.isActive && session.activeEntryId == null) return@transaction null

            val cueStackId = session.runningCueStackId()

            session.activeEntryId = null
            session.isActive = false
            session.updatedAt = System.currentTimeMillis()

            session.id.value to cueStackId
        }

        if (result != null) {
            val (sessionId, cueStackId) = result
            if (cueStackId != null) {
                state.show.cueStackManager.deactivateStack(cueStackId, state)
            }
            state.show.fixtures.showSessionChanged(sessionId, null, null, null, isActive = false)
            state.show.fixtures.showSessionListChanged()
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Session not found or not active"))
        }
    }

    // POST /{projectId}/show-sessions/{sessionId}/advance - Advance to next/prev STACK entry
    post<ShowSessionAdvanceResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot advance - not current project"))
            return@post
        }

        val request = call.receive<AdvanceShowSessionRequest>()

        try {
            val result = transaction(state.database) {
                val session = DaoShowSession.findById(resource.parent.sessionId)
                    ?: throw IllegalArgumentException("Session not found")
                if (session.project.id != project.id) throw IllegalArgumentException("Session not found")

                val currentEntryId = session.activeEntryId
                    ?: throw IllegalStateException("Session has no active entry")

                // Get STACK entries only (skip MARKERs)
                val stackEntries = session.entries
                    .filter { it.entryType == ShowSessionEntryType.STACK.name }
                    .sortedBy { it.sortOrder }

                if (stackEntries.isEmpty()) throw IllegalArgumentException("Session has no stack entries")

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
                    DaoShowSessionEntry.findById(currentEntryId)?.cueStack?.id?.value
                } else null

                val cueStackId = nextEntry.cueStack?.id?.value
                    ?: throw IllegalArgumentException("Next entry has no associated cue stack")

                session.activeEntryId = nextEntry.id.value
                session.updatedAt = System.currentTimeMillis()

                ShowSessionAdvanceData(
                    sessionId = session.id.value,
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

            state.show.fixtures.showSessionChanged(
                result.sessionId, result.entryId, result.cueStackId, result.cueStackName, isActive = true,
            )
            call.respond(ShowSessionActivateResponse(
                sessionId = result.sessionId,
                activeEntryId = result.entryId,
                activatedStackId = result.cueStackId,
                activatedStackName = result.cueStackName,
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to advance"))
        }
    }

    // POST /{projectId}/show-sessions/{sessionId}/go-to - Go to specific entry
    post<ShowSessionGoToResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot go-to - not current project"))
            return@post
        }

        val request = call.receive<GoToSessionEntryRequest>()

        try {
            val result = transaction(state.database) {
                val session = DaoShowSession.findById(resource.parent.sessionId)
                    ?: throw IllegalArgumentException("Session not found")
                if (session.project.id != project.id) throw IllegalArgumentException("Session not found")

                val entry = DaoShowSessionEntry.findById(request.entryId)
                    ?: throw IllegalArgumentException("Entry not found")
                if (entry.showSession.id != session.id) throw IllegalArgumentException("Entry not in session")

                if (entry.entryType == ShowSessionEntryType.MARKER.name) {
                    throw IllegalArgumentException("Cannot go-to a MARKER entry")
                }

                val cueStackId = entry.cueStack?.id?.value
                    ?: throw IllegalArgumentException("Entry has no associated cue stack")

                // Deactivate current stack if active
                val previousCueStackId = session.activeEntryId?.let { activeId ->
                    DaoShowSessionEntry.findById(activeId)?.cueStack?.id?.value
                }

                session.activeEntryId = entry.id.value
                session.updatedAt = System.currentTimeMillis()

                ShowSessionAdvanceData(
                    sessionId = session.id.value,
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

            state.show.fixtures.showSessionChanged(
                result.sessionId, result.entryId, result.cueStackId, result.cueStackName, isActive = true,
            )
            call.respond(ShowSessionActivateResponse(
                sessionId = result.sessionId,
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

@Resource("/{projectId}/show-sessions")
data class ProjectShowSessionsResource(val projectId: String)

@Resource("/{sessionId}")
data class ProjectShowSessionResource(val parent: ProjectShowSessionsResource, val sessionId: Int)

@Resource("/add-stack")
data class ShowSessionAddStackResource(val parent: ProjectShowSessionResource)

@Resource("/add-marker")
data class ShowSessionAddMarkerResource(val parent: ProjectShowSessionResource)

@Resource("/entries/{entryId}")
data class ShowSessionEntryResource(val parent: ProjectShowSessionResource, val entryId: Int)

@Resource("/reorder")
data class ShowSessionReorderResource(val parent: ProjectShowSessionResource)

@Resource("/activate")
data class ShowSessionActivateResource(val parent: ProjectShowSessionResource)

@Resource("/deactivate")
data class ShowSessionDeactivateResource(val parent: ProjectShowSessionResource)

@Resource("/advance")
data class ShowSessionAdvanceResource(val parent: ProjectShowSessionResource)

@Resource("/go-to")
data class ShowSessionGoToResource(val parent: ProjectShowSessionResource)

// ─── DTOs ──────────────────────────────────────────────────────────────

@Serializable
data class NewShowSession(
    val name: String,
    val sessionType: String = "SHOW",
)

@Serializable
data class UpdateShowSession(
    val name: String,
    val sessionType: String = "SHOW",
)

@Serializable
data class ShowSessionDetails(
    val id: Int,
    val name: String,
    val sessionType: String,
    val activeEntryId: Int?,
    val isActive: Boolean,
    val entries: List<ShowSessionEntryDto>,
    val canEdit: Boolean,
    val canDelete: Boolean,
)

@Serializable
data class ShowSessionEntryDto(
    val id: Int,
    val entryType: String,
    val sortOrder: Int,
    val label: String?,
    val cueStackId: Int?,
    val cueStackName: String?,
)

@Serializable
data class AddStackToSessionRequest(
    val cueStackId: Int,
    val sortOrder: Int? = null,
    val label: String? = null,
)

@Serializable
data class AddMarkerToSessionRequest(
    val label: String,
    val sortOrder: Int? = null,
)

@Serializable
data class UpdateShowSessionEntry(
    val label: String? = null,
    val sortOrder: Int? = null,
)

@Serializable
data class ReorderEntriesRequest(
    val entryIds: List<Int>,
)

@Serializable
data class AdvanceShowSessionRequest(
    val direction: String,
    val deactivatePrevious: Boolean? = true,
)

@Serializable
data class GoToSessionEntryRequest(
    val entryId: Int,
)

@Serializable
data class ShowSessionActivateResponse(
    val sessionId: Int,
    // Null when activating an empty session (isActive=true but no entry to run yet).
    val activeEntryId: Int?,
    val activatedStackId: Int?,
    val activatedStackName: String?,
)

// ─── Internal data classes ────────────────────────────────────────────

private data class ShowSessionActivateData(
    val sessionId: Int,
    val entryId: Int,
    val cueStackId: Int,
    val cueStackName: String,
)

private data class ShowSessionAdvanceData(
    val sessionId: Int,
    val entryId: Int,
    val cueStackId: Int,
    val cueStackName: String,
    val previousCueStackId: Int?,
)

private data class DeactivatedSiblingData(
    val sessionId: Int,
    val cueStackId: Int?,
)

private data class ActivateSessionResult(
    val sessionId: Int,
    // Null when the session is empty — isActive is set, but no stack is started.
    val activated: ShowSessionActivateData?,
    val deactivated: List<DeactivatedSiblingData>,
    // True when the session was already isActive=true — the handler skips side effects.
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

/** Cue stack id associated with this session's currently-active entry, or null when no entry is active or the entry has no stack. */
private fun DaoShowSession.runningCueStackId(): Int? =
    activeEntryId?.let { DaoShowSessionEntry.findById(it) }?.cueStack?.id?.value

private fun DaoShowSession.toShowSessionDetails(isCurrentProject: Boolean): ShowSessionDetails {
    val orderedEntries = entries.sortedBy { it.sortOrder }.map { entry ->
        ShowSessionEntryDto(
            id = entry.id.value,
            entryType = entry.entryType,
            sortOrder = entry.sortOrder,
            label = entry.label,
            cueStackId = entry.cueStack?.id?.value,
            cueStackName = entry.cueStack?.name,
        )
    }
    return ShowSessionDetails(
        id = id.value,
        name = name,
        sessionType = sessionType,
        activeEntryId = activeEntryId,
        isActive = isActive,
        entries = orderedEntries,
        canEdit = isCurrentProject,
        canDelete = isCurrentProject,
    )
}
