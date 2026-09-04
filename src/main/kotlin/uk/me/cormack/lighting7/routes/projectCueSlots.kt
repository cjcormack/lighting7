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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

/** 409 code: a Look with a deferred effect was offered to a slot, which has no selection to give it. */
internal const val CUE_SLOT_LOOK_NEEDS_SELECTION = "CUE_SLOT_LOOK_NEEDS_SELECTION"

internal fun Route.routeApiRestProjectCueSlots(state: State) {
    // GET /{projectId}/cue-slots - List all slot assignments for a project
    get<ProjectCueSlotsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val slots = transaction(state.database) {
                DaoCueSlot.find { DaoCueSlots.project eq project.id }
                    .map { it.toDetails() }
            }
            call.respond(slots)
        }
    }

    // POST /{projectId}/cue-slots - Assign a cue or cue stack to a slot (upsert)
    post<ProjectCueSlotsResource> { resource ->
        withCurrentProject(
            state,
            resource.projectId,
            { p -> "Cannot modify cue slots in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val input = call.receive<AssignCueSlotRequest>()

            // Validate exactly one reference is provided
            if (listOfNotNull(input.cueId, input.cueStackId, input.lookId).size != 1) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Exactly one of cueId, cueStackId or lookId must be provided"))
                return@withCurrentProject
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
                if (input.lookId != null) {
                    val look = DaoLook.findById(input.lookId)
                    if (look == null || look.project.id != project.id) {
                        return@transaction null to "Look not found in this project"
                    }
                    // The overlay has no selection, so a slot can hold only a Look that needs none:
                    // one with no deferred effect, which presses onto its own fixtures. Refused at
                    // assign rather than at press so the tile never sits there dead
                    // (busk-layout plan D7). A named 409 rather than the plain 400s above because
                    // the palette branches on it to dim the row.
                    if (look.effects.any { it.isDeferred }) {
                        return@transaction null to CUE_SLOT_LOOK_NEEDS_SELECTION
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
                    existing.look = input.lookId?.let { DaoLook.findById(it) }
                    existing
                } else {
                    DaoCueSlot.new {
                        this.project = project
                        page = input.page
                        slotIndex = input.slotIndex
                        cue = input.cueId?.let { DaoCue.findById(it) }
                        cueStack = input.cueStackId?.let { DaoCueStack.findById(it) }
                        look = input.lookId?.let { DaoLook.findById(it) }
                    }
                }

                slot.toDetails() to null
            }

            val (result, error) = details
            if (error == CUE_SLOT_LOOK_NEEDS_SELECTION) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("This Look has a deferred effect and needs a selection, which a slot cannot supply", code = CUE_SLOT_LOOK_NEEDS_SELECTION),
                )
            } else if (error != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
            } else {
                state.show.fixtures.cueSlotListChanged()
                call.respond(if (result != null) HttpStatusCode.OK else HttpStatusCode.Created, result!!)
            }
        }
    }

    // POST /{projectId}/cue-slots/swap - Swap two slot positions
    post<ProjectCueSlotSwapResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
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
    }

    // DELETE /{projectId}/cue-slots/{slotId} - Clear a slot
    delete<ProjectCueSlotResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val found = transaction(state.database) {
                val slot = DaoCueSlot.findById(resource.slotId) ?: return@transaction false
                if (slot.project.id != project.id) return@transaction false
                slot.delete()
                true
            }

            if (found) {
                state.show.fixtures.cueSlotListChanged()
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue slot not found"))
            }
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
)

/** Exactly one of [cueId] / [cueStackId] / [lookId]. See `DaoCueSlots`. */
@Serializable
data class AssignCueSlotRequest(
    val page: Int,
    val slotIndex: Int,
    val cueId: Int? = null,
    val cueStackId: Int? = null,
    val lookId: Int? = null,
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
    val resolvedLook = look

    return when {
        resolvedLook != null -> CueSlotDetails(
            id = id.value,
            page = page,
            slotIndex = slotIndex,
            itemType = "look",
            itemId = resolvedLook.id.value,
            itemName = resolvedLook.name,
        )
        resolvedCue != null -> CueSlotDetails(
            id = id.value,
            page = page,
            slotIndex = slotIndex,
            itemType = "cue",
            itemId = resolvedCue.id.value,
            itemName = resolvedCue.name,
        )
        resolvedStack != null -> CueSlotDetails(
            id = id.value,
            page = page,
            slotIndex = slotIndex,
            itemType = "cue_stack",
            itemId = resolvedStack.id.value,
            itemName = resolvedStack.name,
        )
        else -> error("CueSlot ${id.value} has no cue, cue stack or look set")
    }
}
