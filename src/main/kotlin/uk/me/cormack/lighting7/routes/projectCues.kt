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
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State


internal fun Route.routeApiRestProjectCues(state: State) {
    // GET /{projectId}/cues - List cues for a project
    get<ProjectCuesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val isCurrentProject = state.isCurrentProject(project)
            val cues = transaction(state.database) {
                DaoCue.find { DaoCues.project eq project.id }
                    .orderBy(DaoCues.name to SortOrder.ASC)
                    .with(
                        DaoCue::layers,
                        DaoCue::adHocEffects,
                        DaoCue::propertyAssignments,
                        DaoCue::triggers,
                        DaoCue::cueStack,
                        DaoCueTrigger::script,
                    )
                    .map { it.toCueDetails(isCurrentProject, state.show.fixtures) }
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
            val validatedCueType = try {
                CueType.valueOf(newCue.cueType).name
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid cueType: '${newCue.cueType}'. Valid values: ${CueType.entries.joinToString()}"))
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
            val result = transaction(state.database) {
                val stack = DaoCueStack.findById(cueStackId)
                    ?: return@transaction null to "Cue stack not found"
                if (stack.project.id != project.id) return@transaction null to "Cue stack does not belong to project"
                if (stack.type == CueStackType.SEPARATOR.name) return@transaction null to "Cannot add cues to a separator"

                val cue = DaoCue.new {
                    name = newCue.name
                    this.project = project
                    autoAdvance = newCue.autoAdvance
                    autoAdvanceDelayMs = newCue.autoAdvanceDelayMs
                    fadeDurationMs = newCue.fadeDurationMs
                    fadeCurve = newCue.fadeCurve
                    cueNumber = newCue.cueNumber
                    notes = newCue.notes
                    cueType = validatedCueType
                    stomp = newCue.stomp
                    pinnedToBusk = newCue.pinnedToBusk
                    cueStack = stack
                    // max+1, not count: sort orders may have gaps, so counting can hand out a
                    // value an existing cue already holds.
                    sortOrder = newCue.sortOrder
                        ?: ((stack.cues.maxOfOrNull { it.sortOrder } ?: -1) + 1)
                }
                createCueChildren(
                    cue,
                    newCue.adHocEffects,
                    newCue.propertyAssignments,
                    newCue.triggers,
                    newCue.layers,
                )
                // A cue created without a number gets one derived from where it landed.
                renumberAutoCues(stack)
                cue.toCueDetails(isCurrentProject = true, state.show.fixtures) to null
            }
            val (cueDetails, error) = result
            if (error != null || cueDetails == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error ?: "Failed to create cue"))
                return@withCurrentProject
            }
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
                cue.autoAdvance = updatedData.autoAdvance
                cue.autoAdvanceDelayMs = updatedData.autoAdvanceDelayMs
                cue.fadeDurationMs = updatedData.fadeDurationMs
                cue.fadeCurve = updatedData.fadeCurve
                cue.notes = updatedData.notes
                cue.stomp = updatedData.stomp
                cue.pinnedToBusk = updatedData.pinnedToBusk

                // A number that differs from what's stored is an operator edit, so it becomes
                // explicit; clearing it hands the cue back to the auto scheme. An *unchanged*
                // value leaves the auto flag alone, so a full-object save that merely round-trips
                // the current number (a properties PUT) can't silently freeze an auto
                // number as explicit.
                val incomingNumber = updatedData.cueNumber?.takeIf { it.isNotEmpty() }
                val numberChanged = incomingNumber != cue.cueNumber
                if (numberChanged) {
                    releaseAutoNumber(cue.cueStack, incomingNumber, cue.id.value)
                    cue.cueNumber = incomingNumber
                    cue.cueNumberAuto = false
                }

                // Replace children: delete existing, create new
                deleteCueChildren(cue)
                createCueChildren(
                    cue,
                    updatedData.adHocEffects,
                    updatedData.propertyAssignments,
                    updatedData.triggers,
                    updatedData.layers,
                )

                if (numberChanged) renumberAutoCues(cue.cueStack)
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
    // Children arrays (layers, adHocEffects, propertyAssignments, triggers) are replaced wholesale when present.
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
                val numberChanged = "cueNumber" in body
                if (numberChanged) {
                    val next = body["cueNumber"].nullableString()?.takeIf { it.isNotEmpty() }
                    releaseAutoNumber(cue.cueStack, next, cue.id.value)
                    cue.cueNumber = next
                    cue.cueNumberAuto = false
                }
                if ("fadeDurationMs" in body) cue.fadeDurationMs = body["fadeDurationMs"].nullableLong()
                if ("fadeCurve" in body) cue.fadeCurve = body["fadeCurve"]!!.jsonPrimitive.content
                if ("notes" in body) cue.notes = body["notes"].nullableString()
                if ("autoAdvance" in body) cue.autoAdvance = body["autoAdvance"]!!.jsonPrimitive.boolean
                if ("autoAdvanceDelayMs" in body) cue.autoAdvanceDelayMs = body["autoAdvanceDelayMs"].nullableLong()
                if ("stomp" in body) cue.stomp = body["stomp"]!!.jsonPrimitive.boolean
                if ("pinnedToBusk" in body) cue.pinnedToBusk = body["pinnedToBusk"]!!.jsonPrimitive.boolean

                if (hasLayers || hasEffects || hasAssignments || hasTriggers) {
                    // Delete only the children being replaced
                    if (hasLayers) cue.layers.forEach { it.delete() }
                    if (hasEffects) cue.adHocEffects.forEach { it.delete() }
                    if (hasAssignments) cue.propertyAssignments.forEach { it.delete() }
                    if (hasTriggers) cue.triggers.forEach { it.delete() }

                    createCueChildren(
                        cue,
                        effects ?: emptyList(),
                        assignments ?: emptyList(),
                        triggers ?: emptyList(),
                        layers ?: emptyList(),
                    )
                }

                if (numberChanged) renumberAutoCues(cue.cueStack)
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
                val stack = cue.cueStack
                deleteCueChildren(cue)
                val removedAnchors = deletePromptBookAnchorsForCue(cue)
                cue.delete()
                // The cues that followed have shifted up the derived sequence.
                renumberAutoCues(stack)
                removedAnchors
            }

            if (result != null) {
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
                // effects until the whole stack was released.
                state.cueTriggerManager.deactivateTriggersForCue(resource.cueId)
                state.show.fxEngine.removeEffectsForCue(resource.cueId)
                state.show.fxEngine.cueLayer.removeAssignments(resource.cueId)
                state.show.fixtures.cueListChanged()
                state.show.fixtures.cueStackListChanged()
                if (result > 0) state.show.fixtures.promptBookChanged()
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
                autoAdvance = sourceCue.autoAdvance
                autoAdvanceDelayMs = sourceCue.autoAdvanceDelayMs
                fadeDurationMs = sourceCue.fadeDurationMs
                fadeCurve = sourceCue.fadeCurve
                stomp = sourceCue.stomp
                cueStack = targetStack
                sortOrder = (targetStack.cues.maxOfOrNull { it.sortOrder } ?: -1) + 1
            }

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
                    delayMs = layer.delayMs
                    intervalMs = layer.intervalMs
                    randomWindowMs = layer.randomWindowMs
                }
            }
            for (assignment in sourceCue.propertyAssignments) {
                DaoCuePropertyAssignment.new {
                    cue = newCue
                    targetType = assignment.targetType
                    targetKey = assignment.targetKey
                    propertyName = assignment.propertyName
                    value = assignment.value
                    fadeDurationMs = assignment.fadeDurationMs
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
                    delayMs = effect.delayMs
                    intervalMs = effect.intervalMs
                    randomWindowMs = effect.randomWindowMs
                    sortOrder = effect.sortOrder
                    speedMasterUuid = effect.speedMasterUuid
                    rateSpeedMasterUuid = effect.rateSpeedMasterUuid
                }
            }

            for (trigger in sourceCue.triggers) {
                DaoCueTrigger.new {
                    cue = newCue
                    triggerType = trigger.triggerType
                    delayMs = trigger.delayMs
                    intervalMs = trigger.intervalMs
                    randomWindowMs = trigger.randomWindowMs
                    script = trigger.script
                    sortOrder = trigger.sortOrder
                }
            }

            // The copy carries no cue number, so it picks one up from where it landed.
            renumberAutoCues(targetStack)

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

    // POST /{projectId}/cues/{cueId}/apply - Apply a cue (current project only)
    post<ApplyCueResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot apply cues from project '${p.name}' - only the current project's cues can be applied" },
        ) { project ->
            // Read the cue's stack membership. Every cue belongs to a stack, so applying a cue is
            // just activating that cue within its stack (activates the stack if needed).
            val cueStackId = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId) ?: return@transaction null
                if (cue.project.id != project.id) return@transaction null
                cue.cueStack.id.value
            }

            if (cueStackId == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found"))
                return@withCurrentProject
            }

            try {
                val stackResult = state.show.cueStackManager.activateCueInStack(
                    state, cueStackId, resource.cueId
                )
                call.respond(ApplyCueResponse(
                    effectCount = stackResult.effectCount,
                    cueName = stackResult.cueName,
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to apply cue"))
            }
        }
    }

    // POST /{projectId}/cues/{cueId}/stop - Stop a running cue (remove its effects)
    post<StopCueResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot stop cues from project '${p.name}' - only the current project's cues can be stopped" },
        ) { _ ->
            // Check if this cue belongs to an active stack
            val cueStackId = transaction(state.database) {
                DaoCue.findById(resource.cueId)?.cueStack?.id?.value
            }
            val manager = state.show.cueStackManager
            if (cueStackId != null && manager.isStackActive(cueStackId)) {
                // Cue is in an active stack — deactivate the entire stack
                // (CueStackManager integration handles trigger deactivation)
                val removedCount = manager.deactivateStack(cueStackId, state)
                call.respond(StopCueResponse(removedCount = removedCount, cueId = resource.cueId))
            } else {
                state.cueTriggerManager.deactivateTriggersForCue(resource.cueId)
                val removedCount = state.show.fxEngine.removeEffectsForCue(resource.cueId)
                call.respond(StopCueResponse(removedCount = removedCount, cueId = resource.cueId))
            }
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
    val pinnedToBusk: Boolean = false,
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
    val cueStackId: Int? = null,
    val cueStackName: String? = null,
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
    /** True when this cue has a pad of its own on the busk view. */
    val pinnedToBusk: Boolean = false,
    val canEdit: Boolean,
    val canDelete: Boolean,
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
     * The stage's Look layers, one per Look with a de-duplicated target list.
     *
     * Was `presetApplications: List<CuePresetApplicationDetail>`, reconstructed from a preset id
     * on each running effect. Nothing stamped that field by the end — `captureCurrentState` keys
     * on `lookId` — so it was already always empty before session 4 deleted the DTO.
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

