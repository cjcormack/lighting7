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
                // Every cue's entry and place in one go, not a stack's list per cue.
                val slots = soleEntrySlotsOf(cues.map { it.id.value })
                cues.map { it.toCueDetails(isCurrentProject, state.show.fixtures, slots[it.id.value]) }
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
            // Every cue belongs to a stack — standalone cues no longer exist.
            val cueStackId = newCue.cueStackId
            if (cueStackId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("cueStackId is required — every cue must belong to a cue stack"))
                return@withCurrentProject
            }
            validateCueChildren(newCue.adHocEffects, newCue.layers)?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@withCurrentProject
            }
            // An insert shifts the positions after it, and a live cue's priority is its position.
            var priorities = emptyMap<Int, Int>()
            val result = transaction(state.database) {
                val stack = DaoCueStack.findById(cueStackId)
                    ?: return@transaction null to "Cue stack not found"
                if (stack.project.id != project.id) return@transaction null to "Cue stack does not belong to project"
                if (stack.type == CueStackType.SEPARATOR.name) return@transaction null to "Cannot add cues to a separator"

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
                placeCueInStack(
                    stack, cue,
                    cueNumber = newCue.cueNumber,
                    position = newCue.sortOrder,
                    autoAdvance = newCue.autoAdvance,
                    autoAdvanceDelay = newCue.autoAdvanceDelayMs.asDuration(),
                )
                priorities = stackEntryPriorities(stack)
                cue.toCueDetails(isCurrentProject = true, state.show.fixtures) to null
            }
            val (cueDetails, error) = result
            if (error != null || cueDetails == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error ?: "Failed to create cue"))
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
                cue.toCueDetails(isCurrentProject, state.show.fixtures)
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
            validateCueChildren(updatedData.adHocEffects, updatedData.layers)?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@withCurrentProject
            }
            val cueDetails = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId) ?: return@transaction null
                if (cue.project.id != project.id) return@transaction null

                cue.name = updatedData.name
                // A cue's place — its number and follow — is its entry's: the one it has until
                // sharing lands (cue model plan P4).
                val entry = soleEntryOf(cue)
                entry?.autoAdvance = updatedData.autoAdvance
                entry?.autoAdvanceDelay = updatedData.autoAdvanceDelayMs.asDuration()
                cue.fadeDuration = updatedData.fadeDurationMs.asDuration()
                cue.fadeCurve = updatedData.fadeCurve
                cue.notes = updatedData.notes
                cue.stomp = updatedData.stomp

                // A number that differs from what's stored is an operator edit, so it becomes
                // explicit; clearing it hands the cue back to the auto scheme. An *unchanged*
                // value leaves the auto flag alone, so a full-object save that merely round-trips
                // the current number (a properties PUT) can't silently freeze an auto
                // number as explicit.
                val incomingNumber = updatedData.cueNumber?.takeIf { it.isNotEmpty() }
                if (entry != null && incomingNumber != entry.cueNumber) setEntryNumber(entry, incomingNumber)

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
            validateCueChildren(effects ?: emptyList(), layers ?: emptyList())?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@withCurrentProject
            }

            val cueDetails = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId) ?: return@transaction null
                if (cue.project.id != project.id) return@transaction null

                // Scalar fields
                if ("name" in body) cue.name = body["name"]!!.jsonPrimitive.content
                // A typed number is explicit and wins any auto number a sibling was given;
                // clearing the field hands this cue back to the auto scheme, which fills it in
                // again from position. `""` is normalised to null so the unique index and the
                // "is it numbered?" checks agree on what blank means.
                // The number and follow are the cue's entry's — its one entry until sharing lands
                // (cue model plan P4).
                val entry = soleEntryOf(cue)
                if ("cueNumber" in body && entry != null) setEntryNumber(entry, body["cueNumber"].nullableString())
                if ("fadeDurationMs" in body) cue.fadeDuration = body["fadeDurationMs"].nullableLong().asDuration()
                if ("fadeCurve" in body) cue.fadeCurve = body["fadeCurve"]!!.jsonPrimitive.content
                if ("notes" in body) cue.notes = body["notes"].nullableString()
                if ("autoAdvance" in body) entry?.autoAdvance = body["autoAdvance"]!!.jsonPrimitive.boolean
                if ("autoAdvanceDelayMs" in body) entry?.autoAdvanceDelay = body["autoAdvanceDelayMs"].nullableLong().asDuration()
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

    // DELETE /{projectId}/cues/{cueId} - Delete cue (current project only)
    delete<ProjectCueResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot delete cues in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val result = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId) ?: return@transaction null
                if (cue.project.id != project.id) return@transaction null
                deleteCueChildren(cue)
                // Its scenery and its events die with it, but not with an edit of its children.
                deleteCueScenery(cue.id)
                deleteCueEvents(cue.id)
                val removedAnchors = deletePromptBookAnchorsForCue(cue)
                val pageIds = deleteCueReferences(cue)
                // Its entry goes with it, and the entries that followed shift up the derived
                // sequence (`deleteCueEntries` renumbers the stack it left) — and so do the
                // priorities of those already on stage, which are their positions.
                val stacks = deleteCueEntries(cue)
                cue.delete()
                Triple(removedAnchors, pageIds, stacks.fold(emptyMap<Int, Int>()) { acc, s -> acc + stackEntryPriorities(s) })
            }

            if (result != null) {
                val (removedAnchors, pageIds, priorities) = result
                state.show.cueStackManager.holds.restampEntries(priorities)
                // A deleted cue can't be Updated into. Update re-validates its target anyway,
                // but dropping it here keeps the programmer indicator from offering a cue that
                // no longer exists.
                state.show.programmerStore.clearIncludeTargetForCue(resource.cueId)
                // Same reasoning for the armed standby: GO already ignores an armed cue that
                // isn't a STANDARD cue of the stack, but leaving the entry makes
                // `standbyCueId` report a cue that no longer exists.
                state.show.cueStackManager.runState.clearStandbyForCue(state, resource.cueId)
                // And its live output. Nothing else ever will: every republish path rebuilds
                // from the DB row that no longer exists (and skips it with a warning), so a
                // live deleted cue would otherwise keep painting its old Layer 4 rows and
                // effects until every hold on it was released.
                // Every hold on it, released (§3.6): a stack that held it keeps its live entry until
                // its next GO, which then has nothing to let go of.
                state.show.cueStackManager.holds.dropAll(state, resource.cueId)
                state.show.fixtures.cueListChanged()
                state.show.fixtures.cueStackListChanged()
                if (removedAnchors > 0) state.show.fixtures.promptBookChanged()
                // Its pads went with it; the busk view re-reads those pages, and the overlay its slots.
                if (pageIds.isNotEmpty()) state.show.fixtures.buskLayoutChanged(pageIds.toList())
                state.show.fixtures.cueSlotListChanged()
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found"))
            }
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
            val newCue = DaoCue.new {
                name = cueName
                project = targetProject
                fadeDuration = sourceCue.fadeDuration
                fadeCurve = sourceCue.fadeCurve
                stomp = sourceCue.stomp
            }
            // The source's follow travels with the copy, as it did while it was the cue's.
            val sourceEntry = soleEntryOf(sourceCue)

            // Copy child entities.
            //
            // **Both referent columns**, not just `look`. This loop copied `look` alone, so every
            // template layer arrived in the target cue naming neither record — a row that violates
            // `DaoCueLayers`' exactly-one invariant, and the reason the read-time `check {}` on
            // `DaoCueLayer.source` was reachable at all: the copy wrote it and the *GO* threw.
            for (layer in sourceCue.layers) {
                DaoCueLayer.new {
                    cue = newCue
                    look = layer.look
                    template = layer.template
                    sortOrder = layer.sortOrder
                    enabled = layer.enabled
                    targets = layer.targets
                    propertyMask = layer.propertyMask
                    blendMode = layer.blendMode
                    amount = layer.amount
                    stomp = layer.stomp
                    speedMasterUuid = layer.speedMasterUuid
                    rateSpeedMasterUuid = layer.rateSpeedMasterUuid
                    delay = layer.delay
                    interval = layer.interval
                    randomWindow = layer.randomWindow
                }
            }
            for (assignment in sourceCue.propertyAssignments) {
                DaoCuePropertyAssignment.new {
                    cue = newCue
                    targetType = assignment.targetType
                    targetKey = assignment.targetKey
                    propertyName = assignment.propertyName
                    value = assignment.value
                    fadeDuration = assignment.fadeDuration
                    sortOrder = assignment.sortOrder
                    moveInDark = assignment.moveInDark
                }
            }
            for (effect in sourceCue.adHocEffects) {
                DaoCueAdHocEffect.new {
                    cue = newCue
                    targetType = effect.targetType
                    targetKey = effect.targetKey
                    effectType = effect.effectType
                    category = effect.category
                    propertyName = effect.propertyName
                    beatDivision = effect.beatDivision
                    blendMode = effect.blendMode
                    distribution = effect.distribution
                    phaseOffset = effect.phaseOffset
                    elementMode = effect.elementMode
                    elementFilter = effect.elementFilter
                    stepTiming = effect.stepTiming
                    parameters = effect.parameters
                    delay = effect.delay
                    interval = effect.interval
                    randomWindow = effect.randomWindow
                    sortOrder = effect.sortOrder
                    speedMasterUuid = effect.speedMasterUuid
                    rateSpeedMasterUuid = effect.rateSpeedMasterUuid
                    seconds = effect.seconds
                }
            }

            for (trigger in sourceCue.triggers) {
                DaoCueTrigger.new {
                    cue = newCue
                    triggerType = trigger.triggerType
                    delay = trigger.delay
                    interval = trigger.interval
                    randomWindow = trigger.randomWindow
                    script = trigger.script
                    sortOrder = trigger.sortOrder
                }
            }

            // Its scenery too — but only within the project: an element belongs to one project's
            // scene, so a copy into another has nothing to name.
            if (targetProject.id == sourceProject.id) {
                copyCueScenery(sourceCue, newCue)
                // A patch is a project's own, so events copy only within one.
                copyCueEvents(sourceCue, newCue)
            }

            // Appended to the target's "Unsorted" stack. The copy carries no cue number, so it
            // picks one up from where it landed.
            placeCueInStack(
                targetStack, newCue,
                autoAdvance = sourceEntry?.autoAdvance ?: false,
                autoAdvanceDelay = sourceEntry?.autoAdvanceDelay,
            )

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
data class ProjectCueResource(val parent: ProjectCuesResource, val cueId: Int)

@Resource("/{cueId}/copy")
data class CopyCueResource(val parent: ProjectCuesResource, val cueId: Int)

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
     * The cue's place: its one entry's stack, id, position, follow and number (cue model plan P4).
     * Null / defaults for a cue in no stack, which nothing makes until the plan's session 4.
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
)


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
 * if it doesn't exist yet. Used as the landing stack for cross-project cue copies and AI-created
 * cues, since every cue must belong to a stack. Must be called inside a transaction.
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

