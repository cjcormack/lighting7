@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.dao.with
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.stackEntryPriorities
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State
import java.time.Duration
import uk.me.cormack.lighting7.models.asDuration


internal fun Route.routeApiRestProjectCues(state: State) {
    // GET /{projectId}/cues - List cues for a project
    get<ProjectCuesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val isCurrentProject = state.isCurrentProject(project)
            val cues = transaction(state.database) {
                val cues = DaoCue.find { DaoCues.project eq project.id }
                    .orderBy(DaoCues.name to SortOrder.ASC)
                    .with(
                        DaoCue::layers,
                        DaoCue::adHocEffects,
                        DaoCue::propertyAssignments,
                        DaoCue::triggers,
                        DaoCueTrigger::script,
                    )
                    .toList()
                // Every cue's entries and places in one go, not a stack's list per cue.
                val slots = entrySlotsOf(cues.map { it.id.value })
                val uses = cueUsesOf(project, cues, slots)
                cues.map { cue ->
                    cue.toCueDetails(isCurrentProject, state.show.fixtures, slots[cue.id.value].orEmpty())
                        .copy(usedBy = uses[cue.id.value]?.usedBy())
                }
            }
            call.respond(cues)
        }
    }

    // POST /{projectId}/cues - Create new cue (current project only)

    post<ProjectCuesResource> { resource ->
        withCurrentProject(
            state,
            resource.projectId,
            { p -> "Cannot create cues in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val newCue = call.receive<NewCue>()
            val validatedCueType = CueType.entries.firstOrNull { it.name == newCue.cueType }
            if (validatedCueType == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid cueType: '${newCue.cueType}'. Valid values: ${CueType.entries.joinToString()}"))
                return@withCurrentProject
            }
            // A marker is an entry, not a cue (cue model plan D12): it is made on its stack.
            if (validatedCueType == CueType.MARKER) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("A marker is not a cue — add it with POST /cue-stacks/{stackId}/markers"),
                )
                return@withCurrentProject
            }
            // No stack makes a loose cue (cue model plan D1).
            val cueStackId = newCue.cueStackId
            validateCueChildren(newCue.adHocEffects, newCue.layers, newCue.propertyAssignments)?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@withCurrentProject
            }
            // An insert shifts the positions after it, and a live cue's priority is its position.
            var priorities = emptyMap<Int, Int>()
            var numberTaken = false
            val result = transaction(state.database) {
                val stack = cueStackId?.let { id ->
                    val stack = DaoCueStack.findById(id) ?: return@transaction null to "Cue stack not found"
                    if (stack.project.id != project.id) return@transaction null to "Cue stack does not belong to project"
                    if (stack.type == CueStackType.SEPARATOR.name) return@transaction null to "Cannot add cues to a separator"
                    // Before anything is written: a refusal is a normal return, which commits.
                    newCue.cueNumber?.takeIf { it.isNotEmpty() }?.let { number ->
                        explicitNumberClash(stack, number)?.let { numberTaken = true; return@transaction null to it }
                    }
                    stack
                }

                val cue = DaoCue.new {
                    name = newCue.name
                    this.project = project
                    fadeDuration = newCue.fadeDurationMs.asDuration()
                    fadeCurve = newCue.fadeCurve
                    notes = newCue.notes
                    stomp = newCue.stomp
                }
                writeCueChildren(
                    cue,
                    newCue.adHocEffects,
                    newCue.propertyAssignments,
                    newCue.triggers,
                    newCue.layers,
                    created = true,
                )
                // Its place is its entry's: at `sortOrder` (a position in the list), else appended.
                // A cue created without a number gets one derived from where it landed.
                if (stack != null) {
                    placeCueInStack(
                        stack, cue,
                        cueNumber = newCue.cueNumber,
                        position = newCue.sortOrder,
                        autoAdvance = newCue.autoAdvance,
                        autoAdvanceDelay = newCue.autoAdvanceDelayMs.asDuration(),
                    )
                    priorities = stackEntryPriorities(stack)
                }
                cue.toCueDetails(isCurrentProject = true, state.show.fixtures) to null
            }
            val (cueDetails, error) = result
            if (error != null || cueDetails == null) {
                call.respond(
                    if (numberTaken) HttpStatusCode.Conflict else HttpStatusCode.BadRequest,
                    ErrorResponse(error ?: "Failed to create cue"),
                )
                return@withCurrentProject
            }
            state.show.cueStackManager.holds.restampEntries(priorities)
            state.show.fixtures.cueListChanged()
            state.show.fixtures.cueStackListChanged()
            call.respond(HttpStatusCode.Created, cueDetails)
        }
    }

    // GET /{projectId}/cues/{cueId} - Get cue details (any project)
    get<ProjectCueResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val isCurrentProject = state.isCurrentProject(project)
            val cue = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId) ?: return@transaction null
                if (cue.project.id != project.id) return@transaction null
                val slots = entrySlotsOf(listOf(cue.id.value))[cue.id.value].orEmpty()
                // The place fields describe the entry the caller names; it must be one of the cue's.
                if (resource.entryId != null && slots.none { it.entry.id.value == resource.entryId }) return@transaction null
                cue.toCueDetails(isCurrentProject, state.show.fixtures, slots, resource.entryId)
            }

            if (cue != null) {
                call.respond(cue)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found"))
            }
        }
    }

    // PUT /{projectId}/cues/{cueId} - Update cue (current project only)
    put<ProjectCueResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot modify cues in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val updatedData = call.receive<NewCue>()
            validateCueChildren(updatedData.adHocEffects, updatedData.layers, updatedData.propertyAssignments)?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@withCurrentProject
            }
            val cueDetails = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId) ?: return@transaction null
                if (cue.project.id != project.id) return@transaction null

                // Its number and follow are its entries' (cue model plan D2), written on the entry
                // route: a cue in several stacks has one of each per stack.
                cue.name = updatedData.name
                cue.fadeDuration = updatedData.fadeDurationMs.asDuration()
                cue.fadeCurve = updatedData.fadeCurve
                cue.notes = updatedData.notes
                cue.stomp = updatedData.stomp

                // Replace children, keeping the uuid of every child the write keeps.
                writeCueChildren(
                    cue,
                    updatedData.adHocEffects,
                    updatedData.propertyAssignments,
                    updatedData.triggers,
                    updatedData.layers,
                )

                cue.toCueDetails(isCurrentProject = true, state.show.fixtures)
            }

            if (cueDetails != null) {
                state.show.fixtures.cueListChanged()
                call.respond(cueDetails)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found"))
            }
        }
    }

    // PATCH /{projectId}/cues/{cueId} - Partial update of cue fields (current project only)
    // Only fields present in the JSON body are updated; absent fields are left unchanged.
    // Children arrays (layers, adHocEffects, propertyAssignments, triggers) are replaced wholesale when present,
    // each child the new list keeps keeping its uuid (`writeCueChildren`).
    patch<ProjectCueResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot modify cues in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val body = call.receive<JsonObject>()

            // Children arrays — replace wholesale when present. Decoded *before* the transaction
            // purely so the effect-enum check can answer 400 the way POST and PUT do: validating
            // after the scalar fields had been assigned would mean rejecting a body that had
            // already half-written the cue.
            val hasLayers = "layers" in body
            val hasEffects = "adHocEffects" in body
            val hasAssignments = "propertyAssignments" in body
            val hasTriggers = "triggers" in body
            val json = Json { ignoreUnknownKeys = true }
            val effects = if (hasEffects)
                json.decodeFromJsonElement<List<CueAdHocEffectDto>>(body["adHocEffects"]!!)
            else null
            val assignments = if (hasAssignments)
                json.decodeFromJsonElement<List<CuePropertyAssignmentDto>>(body["propertyAssignments"]!!)
            else null
            val triggers = if (hasTriggers)
                json.decodeFromJsonElement<List<CueTriggerDto>>(body["triggers"]!!)
            else null
            val layers = if (hasLayers)
                json.decodeFromJsonElement<List<CueLayerDto>>(body["layers"]!!)
            else null
            validateCueChildren(effects ?: emptyList(), layers ?: emptyList(), assignments ?: emptyList())?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@withCurrentProject
            }

            val cueDetails = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId) ?: return@transaction null
                if (cue.project.id != project.id) return@transaction null

                // Scalar fields. Number and follow are the entry's, on the entry route.
                if ("name" in body) cue.name = body["name"]!!.jsonPrimitive.content
                if ("fadeDurationMs" in body) cue.fadeDuration = body["fadeDurationMs"].nullableLong().asDuration()
                if ("fadeCurve" in body) cue.fadeCurve = body["fadeCurve"]!!.jsonPrimitive.content
                if ("notes" in body) cue.notes = body["notes"].nullableString()
                if ("stomp" in body) cue.stomp = body["stomp"]!!.jsonPrimitive.boolean

                // Only the lists present are replaced; a null one is left alone.
                writeCueChildren(cue, effects, assignments, triggers, layers)

                cue.toCueDetails(isCurrentProject = true, state.show.fixtures)
            }

            if (cueDetails != null) {
                state.show.fixtures.cueListChanged()
                state.show.fixtures.cueStackListChanged()
                call.respond(cueDetails)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found"))
            }
        }
    }

    // DELETE /{projectId}/cues/{cueId} - Delete a cue (current project only). Refused 409
    // CUE_IN_USE while anything names it, unless ?force=true (cue model plan D8).
    delete<ProjectCueResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot delete cues in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val outcome = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId)?.takeIf { it.project.id == project.id }
                    ?: return@transaction null
                val uses = cueUsesOf(project, listOf(cue)).getValue(cue.id.value)
                if (uses.isUsed() && !resource.force) return@transaction uses to null
                null to deleteCues(state, project, listOf(cue))
            }
            when {
                outcome == null -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found"))
                outcome.second == null -> {
                    val uses = listOf(outcome.first!!)
                    call.respond(HttpStatusCode.Conflict, CueInUseResponse(cueInUseMessage(uses), uses = uses))
                }
                else -> {
                    afterCuesDeleted(state, outcome.second!!)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    // POST /{projectId}/cues/delete - Delete several cues at once: all or none (cue model plan D8)
    post<DeleteCuesResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot delete cues in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val request = call.receive<DeleteCuesRequest>()
            val ids = request.cueIds.distinct()
            var refused: List<CueUsesDto>? = null
            val outcome = transaction(state.database) {
                val cues = ids.mapNotNull { id -> DaoCue.findById(id)?.takeIf { it.project.id == project.id } }
                if (cues.size != ids.size) return@transaction null
                val uses = cueUsesOf(project, cues).values.filter { it.isUsed() }.sortedBy { ids.indexOf(it.cueId) }
                when {
                    request.dryRun -> DeleteCuesResponse(emptyList(), uses) to CueDeletion.NONE
                    uses.isNotEmpty() && !request.force -> { refused = uses; null }
                    else -> DeleteCuesResponse(ids, uses) to deleteCues(state, project, cues)
                }
            }
            val refusedUses = refused
            when {
                refusedUses != null -> call.respond(HttpStatusCode.Conflict, CueInUseResponse(cueInUseMessage(refusedUses), uses = refusedUses))
                outcome == null -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found"))
                else -> {
                    afterCuesDeleted(state, outcome.second)
                    call.respond(outcome.first)
                }
            }
        }
    }

    // POST /{projectId}/cues/{cueId}/duplicate - A copy of the cue in this project, appended to the
    // end of `stackId`'s list, or loose without one (show-cue-editing plan D12)
    post<DuplicateCueResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot duplicate cues in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val request = call.receive<DuplicateCueRequest>()
            var priorities = emptyMap<Int, Int>()
            var notFound = false
            val result = transaction(state.database) {
                val source = DaoCue.findById(resource.cueId)?.takeIf { it.project.id == project.id }
                    ?: run { notFound = true; return@transaction null to "Cue not found" }
                val stack = request.stackId?.let { id ->
                    val stack = DaoCueStack.findById(id)?.takeIf { it.project.id == project.id }
                        ?: return@transaction null to "Cue stack not found"
                    if (stack.type == CueStackType.SEPARATOR.name) return@transaction null to "Cannot add cues to a separator"
                    stack
                }
                val copy = copyCueRecord(source, project, "${source.name} (copy)".take(DUPLICATE_NAME_MAX))
                // At the end, unnumbered, so it takes the next auto number and nothing else moves.
                val entry = stack?.let { placeCueInStack(it, copy) }
                if (stack != null) priorities = stackEntryPriorities(stack)
                DuplicateCueResponse(
                    cueId = copy.id.value,
                    name = copy.name,
                    stackId = stack?.id?.value,
                    entryId = entry?.id?.value,
                ) to null
            }
            val (response, error) = result
            if (response == null) {
                call.respond(
                    if (notFound) HttpStatusCode.NotFound else HttpStatusCode.BadRequest,
                    ErrorResponse(error ?: "Failed to duplicate cue"),
                )
                return@withCurrentProject
            }
            state.show.cueStackManager.holds.restampEntries(priorities)
            state.show.fixtures.cueListChanged()
            state.show.fixtures.cueStackListChanged()
            response.stackId?.let { announceCuePadsChanged(state, cueIdsOfStack(state, it)) }
            call.respond(HttpStatusCode.Created, response)
        }
    }

    // POST /{projectId}/cues/{cueId}/copy - Copy cue to another project
    post<CopyCueResource> { resource ->
        val sourceProject = state.resolveProject(resource.parent.projectId)
        if (sourceProject == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Source project not found"))
            return@post
        }

        val request = call.receive<CopyCueRequest>()
        if (refusedCopyTarget(state, request.targetProjectId)) return@post

        val result = transaction(state.database) {
            val sourceCue = DaoCue.findById(resource.cueId)
                ?: return@transaction null to "Cue not found"

            if (sourceCue.project.id != sourceProject.id) {
                return@transaction null to "Cue does not belong to specified project"
            }

            val targetProject = DaoProject.findById(request.targetProjectId)
                ?: return@transaction null to "Target project not found"

            // Cue names are not unique, so a name collision in the target project is fine —
            // the copy simply lands alongside the existing cue.
            val cueName = request.newName ?: sourceCue.name

            // Every cue must live in a stack. A cross-project copy lands in the target project's
            // "Unsorted" stack (created on demand); the operator can move it afterwards.
            val targetStack = getOrCreateUnsortedStack(targetProject)
            val newCue = copyCueRecord(sourceCue, targetProject, cueName)

            // Appended to the target's "Unsorted" stack. The copy carries no cue number, so it
            // picks one up from where it landed.
            placeCueInStack(targetStack, newCue)

            CopyCueResponse(
                cueId = newCue.id.value,
                cueName = newCue.name,
                targetProjectId = targetProject.id.value,
                targetProjectName = targetProject.name,
                message = "Cue copied successfully"
            ) to null
        }

        val (response, error) = result
        if (response != null) {
            state.show.fixtures.cueListChanged()
            state.show.fixtures.cueStackListChanged()
            call.respond(HttpStatusCode.Created, response)
        } else {
            val statusCode = when (error) {
                "Cue not found", "Target project not found" -> HttpStatusCode.NotFound
                "Cue does not belong to specified project" -> HttpStatusCode.BadRequest
                else -> HttpStatusCode.Conflict
            }
            call.respond(statusCode, ErrorResponse(error ?: "Unknown error"))
        }
    }

    // POST /{projectId}/cues/{cueId}/apply - Hold a cue loose (current project only)
    post<ApplyCueResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot apply cues from project '${p.name}' - only the current project's cues can be applied" },
        ) { project ->
            val exists = transaction(state.database) {
                DaoCue.findById(resource.cueId)?.project?.id == project.id
            }
            if (!exists) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found"))
                return@withCurrentProject
            }
            // A loose hold (cue model plan D6, D10): the cue composes above every stack, newest
            // press on top, with no follow and no tracking, whatever stacks it is in. A cue slot and
            // the hand's place-and-press come through here; `replaceAll` lets go of every other loose
            // hold first and never touches a stack's.
            val holds = state.show.cueStackManager.holds
            if (call.request.queryParameters["replaceAll"] == "true") holds.dropAllLoose(state, exceptCueId = resource.cueId)
            val press = holds.takeLooseCue(state, resource.cueId)
            if (press == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found"))
            } else {
                call.respond(ApplyCueResponse(effectCount = press.acquired.effectCount, cueName = press.cueName))
            }
        }
    }

    // POST /{projectId}/cues/{cueId}/stop - Let a cue's loose hold go
    post<StopCueResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot stop cues from project '${p.name}' - only the current project's cues can be stopped" },
        ) { _ ->
            // The loose hold only (§3.4): a stack that holds the cue keeps it, and stopping a stack
            // is the stack's own `deactivate`. The cue comes off when nothing else holds it.
            val released = state.show.cueStackManager.holds.dropLoose(state, resource.cueId)
            call.respond(StopCueResponse(removedCount = released.effectsRemoved, cueId = resource.cueId))
        }
    }

    // GET /{projectId}/cues/current-state - Get the active effects without creating a cue
    get<CueCurrentStateResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot read state for project '${p.name}' - only the current project is supported" },
        ) { _ ->
            val captured = captureCurrentState(state)
            call.respond(CueCurrentStateResponse(
                layers = captured.layers,
                adHocEffects = captured.adHocEffects,
            ))
        }
    }
}

// Resource classes
@Resource("/{projectId}/cues")
data class ProjectCuesResource(val projectId: String)

@Resource("/{cueId}")
data class ProjectCueResource(
    val parent: ProjectCuesResource,
    val cueId: Int,
    /** GET: the entry the place fields describe (one of the cue's). */
    val entryId: Int? = null,
    /** DELETE: delete a cue in use, with everything that names it (cue model plan D8). */
    val force: Boolean = false,
)

@Resource("/delete")
data class DeleteCuesResource(val parent: ProjectCuesResource)

@Resource("/{cueId}/copy")
data class CopyCueResource(val parent: ProjectCuesResource, val cueId: Int)

@Resource("/{cueId}/duplicate")
data class DuplicateCueResource(val parent: ProjectCuesResource, val cueId: Int)

@Resource("/{cueId}/apply")
data class ApplyCueResource(val parent: ProjectCuesResource, val cueId: Int)

@Resource("/{cueId}/stop")
data class StopCueResource(val parent: ProjectCuesResource, val cueId: Int)

@Resource("/current-state")
data class CueCurrentStateResource(val parent: ProjectCuesResource)

// DTOs
@Serializable
data class NewCue(
    val name: String,
    val layers: List<CueLayerDto> = emptyList(),
    val adHocEffects: List<CueAdHocEffectDto> = emptyList(),
    val propertyAssignments: List<CuePropertyAssignmentDto> = emptyList(),
    val triggers: List<CueTriggerDto> = emptyList(),
    /**
     * POST only: the stack the new cue joins (absent makes it loose), and its entry's position,
     * follow and number. A PUT ignores them — they are the entry's, on `PATCH cue-stacks/{id}/entries/{entryId}`.
     */
    val cueStackId: Int? = null,
    val sortOrder: Int? = null,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
    val cueNumber: String? = null,
    val notes: String? = null,
    val cueType: String = "STANDARD",
    val stomp: Boolean = false,
)

@Serializable
data class CueDetails(
    val id: Int,
    val name: String,
    /** The cue's ordered Look composition, in `sortOrder`. */
    val layers: List<CueLayerDto> = emptyList(),
    val adHocEffects: List<CueAdHocEffectDto>,
    val propertyAssignments: List<CuePropertyAssignmentDto> = emptyList(),
    val triggers: List<CueTriggerDetailDto> = emptyList(),
    /**
     * One place of the cue: the entry `?entryId=` names, else its only entry — its stack, id,
     * position, follow and number, and [trackedScenery] there. Null / defaults for a cue in several
     * stacks read without an entry, or in none; [entries] lists every place.
     */
    val cueStackId: Int? = null,
    val cueStackName: String? = null,
    val entryId: Int? = null,
    val sortOrder: Int = 0,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
    val cueNumber: String? = null,
    /** True when [cueNumber] was derived from position rather than typed by the operator. */
    val cueNumberAuto: Boolean = false,
    val notes: String? = null,
    val cueType: String = "STANDARD",
    val stomp: Boolean = false,
    val canEdit: Boolean,
    val canDelete: Boolean,
    /** What this cue moves on GO (stage-view plan session 8); edited by `PUT cues/{id}/scenery`. */
    val scenery: List<SceneryChangeDto> = emptyList(),
    /**
     * The scenery this cue does not move itself but shows anyway — tracked from an earlier cue of
     * its stack, or held by the stack's set. Read-only; the cue card draws it hatched.
     */
    val trackedScenery: List<TrackedSceneryDto> = emptyList(),
    /**
     * The tubes this cue fires on GO into it, each at its offset, while the desk is armed (stage-view
     * plan session 9); edited by `PUT cues/{id}/events`. Never tracked: a later cue shows none of them.
     */
    val events: List<CueEventDto> = emptyList(),
    /** Every entry naming the cue, in show order (stacks in show order, then position); empty when loose. */
    val entries: List<CueEntryPlaceDto> = emptyList(),
    /** The busk pads, cue slots and `FireCue` bindings naming the cue — the list's alone, null on one cue's read. */
    val usedBy: CueUsedByDto? = null,
)

/** One place a cue has in a stack's list (cue model plan D2): the entry's own fields. */
@Serializable
data class CueEntryPlaceDto(
    val entryId: Int,
    val stackId: Int,
    val stackName: String,
    /** The entry's position in its stack's list, from 0, markers counted. */
    val sortOrder: Int,
    val cueNumber: String? = null,
    val cueNumberAuto: Boolean = false,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
)


/** The stack whose list the copy is appended to; absent makes it loose. */
@Serializable
data class DuplicateCueRequest(val stackId: Int? = null)

/** The copy, and its place when it has one — what the client opens. */
@Serializable
data class DuplicateCueResponse(val cueId: Int, val name: String, val stackId: Int? = null, val entryId: Int? = null)

/** `cues.name`'s width. */
private const val DUPLICATE_NAME_MAX = 255

@Serializable
data class CopyCueRequest(
    val targetProjectId: Int,
    val newName: String? = null,
)

@Serializable
data class CopyCueResponse(
    val cueId: Int,
    val cueName: String,
    val targetProjectId: Int,
    val targetProjectName: String,
    val message: String,
)

@Serializable
data class ApplyCueResponse(
    val effectCount: Int,
    val cueName: String,
)

@Serializable
data class CueCurrentStateResponse(
    /**
     * The stage's layers, one per source record (Look **or** template) with a de-duplicated target
     * list.
     *
     * Was `presetApplications: List<CuePresetApplicationDetail>`, reconstructed from a preset id
     * on each running effect. Nothing stamped that field by the end — `captureCurrentState` keys
     * on the effect's source — so it was already always empty before session 4 deleted the DTO.
     */
    val layers: List<CueLayerDto>,
    val adHocEffects: List<CueAdHocEffectDto>,
)

@Serializable
data class StopCueResponse(
    val removedCount: Int,
    val cueId: Int,
)

/**
 * Returns the project's "Unsorted" cue stack, creating it (appended to the end of the show order)
 * if it doesn't exist yet. Only the REST cross-project copy lands a cue here, until the cue model
 * plan's session 6 replaces that copy. Must be called inside a transaction.
 */
internal fun getOrCreateUnsortedStack(project: DaoProject): DaoCueStack =
    DaoCueStack.find {
        (DaoCueStacks.project eq project.id) and
            (DaoCueStacks.name eq "Unsorted") and
            (DaoCueStacks.type eq CueStackType.STACK.name)
    }.firstOrNull() ?: DaoCueStack.new {
        name = "Unsorted"
        this.project = project
        loop = false
        type = CueStackType.STACK.name
        sortOrder = (project.cueStacks.maxOfOrNull { it.sortOrder } ?: -1) + 1
    }

