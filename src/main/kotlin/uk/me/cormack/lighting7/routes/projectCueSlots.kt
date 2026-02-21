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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectCueSlots(state: State) {
    // GET /{projectId}/cue-slots - List all slot assignments for a project
    get<ProjectCueSlotsResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val slots = transaction(state.database) {
            DaoCueSlot.find { DaoCueSlots.project eq project.id }
                .map { it.toDetails() }
        }
        call.respond(slots)
    }

    // POST /{projectId}/cue-slots - Assign a cue or cue stack to a slot (upsert)
    post<ProjectCueSlotsResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot modify cue slots in project '${project.name}' - only the current project can be modified")
            )
            return@post
        }

        val input = call.receive<AssignCueSlotRequest>()

        // Validate exactly one reference is provided
        if ((input.cueId == null) == (input.cueStackId == null)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Exactly one of cueId or cueStackId must be provided"))
            return@post
        }

        val details = transaction(state.database) {
            // Validate referenced item exists
            if (input.cueId != null) {
                val cue = DaoCue.findById(input.cueId)
                if (cue == null || cue.project.id != project.id) {
                    return@transaction null to "Cue not found in this project"
                }
            }
            if (input.cueStackId != null) {
                val stack = DaoCueStack.findById(input.cueStackId)
                if (stack == null || stack.project.id != project.id) {
                    return@transaction null to "Cue stack not found in this project"
                }
            }

            // Upsert: find existing slot at this position or create new
            val existing = DaoCueSlot.find {
                (DaoCueSlots.project eq project.id) and
                (DaoCueSlots.page eq input.page) and
                (DaoCueSlots.slotIndex eq input.slotIndex)
            }.firstOrNull()

            val slot = if (existing != null) {
                existing.cue = input.cueId?.let { DaoCue.findById(it) }
                existing.cueStack = input.cueStackId?.let { DaoCueStack.findById(it) }
                existing
            } else {
                DaoCueSlot.new {
                    this.project = project
                    page = input.page
                    slotIndex = input.slotIndex
                    cue = input.cueId?.let { DaoCue.findById(it) }
                    cueStack = input.cueStackId?.let { DaoCueStack.findById(it) }
                }
            }

            slot.toDetails() to null
        }

        val (result, error) = details
        if (error != null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
        } else {
            state.show.fixtures.cueSlotListChanged()
            call.respond(if (result != null) HttpStatusCode.OK else HttpStatusCode.Created, result!!)
        }
    }

    // POST /{projectId}/cue-slots/swap - Swap two slot positions
    post<ProjectCueSlotSwapResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify - not current project"))
            return@post
        }

        val input = call.receive<SwapCueSlotsRequest>()

        transaction(state.database) {
            val fromSlot = DaoCueSlot.find {
                (DaoCueSlots.project eq project.id) and
                (DaoCueSlots.page eq input.fromPage) and
                (DaoCueSlots.slotIndex eq input.fromSlotIndex)
            }.firstOrNull()

            val toSlot = DaoCueSlot.find {
                (DaoCueSlots.project eq project.id) and
                (DaoCueSlots.page eq input.toPage) and
                (DaoCueSlots.slotIndex eq input.toSlotIndex)
            }.firstOrNull()

            if (fromSlot != null && toSlot != null) {
                // Swap: exchange page and slotIndex
                val tmpPage = fromSlot.page
                val tmpIndex = fromSlot.slotIndex
                fromSlot.page = toSlot.page
                fromSlot.slotIndex = toSlot.slotIndex
                toSlot.page = tmpPage
                toSlot.slotIndex = tmpIndex
            } else if (fromSlot != null) {
                // Move: from occupied to empty
                fromSlot.page = input.toPage
                fromSlot.slotIndex = input.toSlotIndex
            } else if (toSlot != null) {
                // Move: to occupied, from empty (reverse)
                toSlot.page = input.fromPage
                toSlot.slotIndex = input.fromSlotIndex
            }
            // Both empty: no-op
        }

        state.show.fixtures.cueSlotListChanged()
        call.respond(HttpStatusCode.OK)
    }

    // DELETE /{projectId}/cue-slots/{slotId} - Clear a slot
    delete<ProjectCueSlotResource> { resource ->
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
            val slot = DaoCueSlot.findById(resource.slotId) ?: return@transaction false
            if (slot.project.id != project.id) return@transaction false
            slot.delete()
            true
        }

        if (found) {
            state.show.fixtures.cueSlotListChanged()
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue slot not found"))
        }
    }
}

// ─── Resource classes ──────────────────────────────────────────────────

@Resource("/{projectId}/cue-slots")
data class ProjectCueSlotsResource(val projectId: String)

@Resource("/{slotId}")
data class ProjectCueSlotResource(val parent: ProjectCueSlotsResource, val slotId: Int)

@Resource("/swap")
data class ProjectCueSlotSwapResource(val parent: ProjectCueSlotsResource)

// ─── DTOs ──────────────────────────────────────────────────────────────

@Serializable
data class CueSlotDetails(
    val id: Int,
    val page: Int,
    val slotIndex: Int,
    val itemType: String,
    val itemId: Int,
    val itemName: String,
    val palette: List<String>,
)

@Serializable
data class AssignCueSlotRequest(
    val page: Int,
    val slotIndex: Int,
    val cueId: Int? = null,
    val cueStackId: Int? = null,
)

@Serializable
data class SwapCueSlotsRequest(
    val fromPage: Int,
    val fromSlotIndex: Int,
    val toPage: Int,
    val toSlotIndex: Int,
)

// ─── Entity helpers ────────────────────────────────────────────────────

private fun DaoCueSlot.toDetails(): CueSlotDetails {
    val resolvedCue = cue
    val resolvedStack = cueStack

    return when {
        resolvedCue != null -> CueSlotDetails(
            id = id.value,
            page = page,
            slotIndex = slotIndex,
            itemType = "cue",
            itemId = resolvedCue.id.value,
            itemName = resolvedCue.name,
            palette = resolvedCue.palette,
        )
        resolvedStack != null -> CueSlotDetails(
            id = id.value,
            page = page,
            slotIndex = slotIndex,
            itemType = "cue_stack",
            itemId = resolvedStack.id.value,
            itemName = resolvedStack.name,
            palette = resolvedStack.palette,
        )
        else -> error("CueSlot ${id.value} has neither cue nor cueStack set")
    }
}
