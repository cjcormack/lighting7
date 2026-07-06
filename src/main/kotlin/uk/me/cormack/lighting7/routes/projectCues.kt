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
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
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
                        DaoCue::presetApplications,
                        DaoCue::adHocEffects,
                        DaoCue::propertyAssignments,
                        DaoCue::triggers,
                        DaoCue::cueStack,
                        DaoCuePresetApplication::preset,
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
            val cueDetails = transaction(state.database) {
                val stack = newCue.cueStackId?.let { DaoCueStack.findById(it) }
                val cue = DaoCue.new {
                    name = newCue.name
                    this.project = project
                    palette = newCue.palette
                    updateGlobalPalette = newCue.updateGlobalPalette
                    autoAdvance = newCue.autoAdvance
                    autoAdvanceDelayMs = newCue.autoAdvanceDelayMs
                    fadeDurationMs = newCue.fadeDurationMs
                    fadeCurve = newCue.fadeCurve
                    cueNumber = newCue.cueNumber
                    notes = newCue.notes
                    cueType = validatedCueType
                    stomp = newCue.stomp
                    if (stack != null) {
                        cueStack = stack
                        sortOrder = newCue.sortOrder ?: stack.cues.count().toInt()
                    }
                }
                createCueChildren(
                    cue,
                    newCue.presetApplications,
                    newCue.adHocEffects,
                    newCue.propertyAssignments,
                    newCue.triggers,
                )
                cue.toCueDetails(isCurrentProject = true, state.show.fixtures)
            }
            state.show.fixtures.cueListChanged()
            if (newCue.cueStackId != null) state.show.fixtures.cueStackListChanged()
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
            val cueDetails = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId) ?: return@transaction null
                if (cue.project.id != project.id) return@transaction null

                cue.name = updatedData.name
                cue.palette = updatedData.palette
                cue.updateGlobalPalette = updatedData.updateGlobalPalette
                cue.autoAdvance = updatedData.autoAdvance
                cue.autoAdvanceDelayMs = updatedData.autoAdvanceDelayMs
                cue.fadeDurationMs = updatedData.fadeDurationMs
                cue.fadeCurve = updatedData.fadeCurve
                cue.cueNumber = updatedData.cueNumber
                cue.notes = updatedData.notes
                cue.stomp = updatedData.stomp

                // Replace children: delete existing, create new
                deleteCueChildren(cue)
                createCueChildren(
                    cue,
                    updatedData.presetApplications,
                    updatedData.adHocEffects,
                    updatedData.propertyAssignments,
                    updatedData.triggers,
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
    // Children arrays (presetApplications, adHocEffects, triggers) are replaced wholesale when present.
    patch<ProjectCueResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot modify cues in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val body = call.receive<JsonObject>()
            val cueDetails = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId) ?: return@transaction null
                if (cue.project.id != project.id) return@transaction null

                // Scalar fields
                if ("name" in body) cue.name = body["name"]!!.jsonPrimitive.content
                if ("cueNumber" in body) cue.cueNumber = body["cueNumber"].nullableString()
                if ("fadeDurationMs" in body) cue.fadeDurationMs = body["fadeDurationMs"].nullableLong()
                if ("fadeCurve" in body) cue.fadeCurve = body["fadeCurve"]!!.jsonPrimitive.content
                if ("notes" in body) cue.notes = body["notes"].nullableString()
                if ("autoAdvance" in body) cue.autoAdvance = body["autoAdvance"]!!.jsonPrimitive.boolean
                if ("autoAdvanceDelayMs" in body) cue.autoAdvanceDelayMs = body["autoAdvanceDelayMs"].nullableLong()
                if ("stomp" in body) cue.stomp = body["stomp"]!!.jsonPrimitive.boolean

                // Children arrays — replace wholesale when present
                val hasPresets = "presetApplications" in body
                val hasEffects = "adHocEffects" in body
                val hasAssignments = "propertyAssignments" in body
                val hasTriggers = "triggers" in body

                if (hasPresets || hasEffects || hasAssignments || hasTriggers) {
                    val json = Json { ignoreUnknownKeys = true }
                    val presets = if (hasPresets)
                        json.decodeFromJsonElement<List<CuePresetApplicationDto>>(body["presetApplications"]!!)
                    else null
                    val effects = if (hasEffects)
                        json.decodeFromJsonElement<List<CueAdHocEffectDto>>(body["adHocEffects"]!!)
                    else null
                    val assignments = if (hasAssignments)
                        json.decodeFromJsonElement<List<CuePropertyAssignmentDto>>(body["propertyAssignments"]!!)
                    else null
                    val triggers = if (hasTriggers)
                        json.decodeFromJsonElement<List<CueTriggerDto>>(body["triggers"]!!)
                    else null

                    // Delete only the children being replaced
                    if (hasPresets) cue.presetApplications.forEach { it.delete() }
                    if (hasEffects) cue.adHocEffects.forEach { it.delete() }
                    if (hasAssignments) cue.propertyAssignments.forEach { it.delete() }
                    if (hasTriggers) cue.triggers.forEach { it.delete() }

                    createCueChildren(
                        cue,
                        presets ?: emptyList(),
                        effects ?: emptyList(),
                        assignments ?: emptyList(),
                        triggers ?: emptyList(),
                    )
                }

                cue.toCueDetails(isCurrentProject = true, state.show.fixtures)
            }

            if (cueDetails != null) {
                state.show.fixtures.cueListChanged()
                if (cueDetails.cueStackId != null) state.show.fixtures.cueStackListChanged()
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
                val removedAnchors = deletePromptBookAnchorsForCue(cue)
                cue.delete()
                removedAnchors
            }

            if (result != null) {
                state.show.fixtures.cueListChanged()
                if (result > 0) state.show.fixtures.promptBookListChanged()
                call.respond(HttpStatusCode.OK)
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

            val cueName = request.newName ?: sourceCue.name

            val existingCue = DaoCue.find {
                (DaoCues.project eq targetProject.id) and (DaoCues.name eq cueName)
            }.firstOrNull()
            if (existingCue != null) {
                return@transaction null to "A cue with name '$cueName' already exists in target project"
            }

            val newCue = DaoCue.new {
                name = cueName
                project = targetProject
                palette = sourceCue.palette
                updateGlobalPalette = sourceCue.updateGlobalPalette
                autoAdvance = sourceCue.autoAdvance
                autoAdvanceDelayMs = sourceCue.autoAdvanceDelayMs
                fadeDurationMs = sourceCue.fadeDurationMs
                fadeCurve = sourceCue.fadeCurve
                stomp = sourceCue.stomp
            }

            // Copy child entities
            for (app in sourceCue.presetApplications) {
                DaoCuePresetApplication.new {
                    cue = newCue
                    preset = app.preset
                    targets = app.targets
                    delayMs = app.delayMs
                    intervalMs = app.intervalMs
                    randomWindowMs = app.randomWindowMs
                    sortOrder = app.sortOrder
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
            val replaceAll = call.request.queryParameters["replaceAll"]?.toBoolean() ?: false

            // Read cue data and check stack membership
            val cueInfo = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId) ?: return@transaction null
                if (cue.project.id != project.id) return@transaction null
                val cueData = buildCueApplyData(cue)
                Pair(cueData, cueData.cueStackId)
            }

            if (cueInfo == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found"))
                return@withCurrentProject
            }

            val (cueData, cueStackId) = cueInfo

            try {
                if (cueStackId != null) {
                    // Cue belongs to a stack — delegate to CueStackManager
                    // This activates the stack (if not already active) and switches to this cue
                    val stackResult = state.show.cueStackManager.activateCueInStack(
                        state, cueStackId, resource.cueId
                    )
                    call.respond(ApplyCueResponse(
                        effectCount = stackResult.effectCount,
                        cueName = stackResult.cueName,
                    ))
                } else {
                    // Deactivate old triggers/timed effects for this cue before re-applying
                    state.cueTriggerManager.deactivateTriggersForCue(resource.cueId)

                    val result = applyCue(state, cueData, replaceAll = replaceAll)

                    // Activate timed effects (delayed/recurring presets and ad-hoc effects)
                    val timedPresets = cueData.presetApplications.filter { it.delayMs != null || it.intervalMs != null }
                    val timedAdHoc = cueData.adHocEffects.filter { it.delayMs != null || it.intervalMs != null }
                    if (timedPresets.isNotEmpty() || timedAdHoc.isNotEmpty()) {
                        state.cueTriggerManager.activateTimedEffectsForCue(
                            cueId = resource.cueId,
                            cueStackId = null,
                            priority = cueDerivedPriority(cueData),
                            timedPresets = timedPresets,
                            timedAdHocEffects = timedAdHoc,
                            scope = kotlinx.coroutines.GlobalScope,
                            cuePalette = cueData.palette.toPaletteColours(),
                        )
                    }

                    // Activate script triggers after effects are applied
                    if (cueData.triggers.isNotEmpty()) {
                        state.cueTriggerManager.activateTriggersForCue(
                            cueId = resource.cueId,
                            cueStackId = null,
                            triggers = cueData.triggers,
                            scope = kotlinx.coroutines.GlobalScope,
                        )
                    }

                    call.respond(result)
                }
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

    // POST /{projectId}/cues/{cueId}/snapshot-from-live - Capture live state into the cue (current project only)
    post<SnapshotFromLiveResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot snapshot cues from project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val captured = captureCurrentState(state)

            val cueDetails = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId) ?: return@transaction null
                if (cue.project.id != project.id) return@transaction null

                // Wholesale replace children with the captured live state. Same semantics as
                // PATCH'ing all four arrays at once.
                cue.presetApplications.forEach { it.delete() }
                cue.adHocEffects.forEach { it.delete() }
                cue.propertyAssignments.forEach { it.delete() }
                // Palette: replace the cue's stored palette with the captured one.
                cue.palette = captured.palette
                createCueChildren(
                    cue,
                    presetApplications = captured.presetApplications,
                    adHocEffects = captured.adHocEffects,
                    propertyAssignments = captured.propertyAssignments,
                    triggers = emptyList(),
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

    // GET /{projectId}/cues/current-state - Get current palette and active effects without creating a cue
    get<CueCurrentStateResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot read state for project '${p.name}' - only the current project is supported" },
        ) { _ ->
            val captured = captureCurrentState(state)

            // Resolve preset names from DB
            val presetDetails = transaction(state.database) {
                captured.presetApplications.map { app ->
                    CuePresetApplicationDetail(
                        presetId = app.presetId,
                        presetName = DaoFxPreset.findById(app.presetId)?.name,
                        targets = app.targets,
                    )
                }
            }

            call.respond(CueCurrentStateResponse(
                palette = captured.palette,
                presetApplications = presetDetails,
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

@Resource("/{cueId}/snapshot-from-live")
data class SnapshotFromLiveResource(val parent: ProjectCuesResource, val cueId: Int)

@Resource("/current-state")
data class CueCurrentStateResource(val parent: ProjectCuesResource)

// DTOs
@Serializable
data class NewCue(
    val name: String,
    val palette: List<String> = emptyList(),
    val presetApplications: List<CuePresetApplicationDto> = emptyList(),
    val adHocEffects: List<CueAdHocEffectDto> = emptyList(),
    val propertyAssignments: List<CuePropertyAssignmentDto> = emptyList(),
    val triggers: List<CueTriggerDto> = emptyList(),
    val updateGlobalPalette: Boolean = false,
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
    val palette: List<String>,
    val presetApplications: List<CuePresetApplicationDetail>,
    val adHocEffects: List<CueAdHocEffectDto>,
    val propertyAssignments: List<CuePropertyAssignmentDto> = emptyList(),
    val triggers: List<CueTriggerDetailDto> = emptyList(),
    val updateGlobalPalette: Boolean = false,
    val cueStackId: Int? = null,
    val cueStackName: String? = null,
    val sortOrder: Int = 0,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
    val stomp: Boolean = false,
    val canEdit: Boolean,
    val canDelete: Boolean,
)

@Serializable
data class CuePresetApplicationDetail(
    val presetId: Int,
    val presetName: String?,
    val targets: List<CueTargetDto>,
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    val sortOrder: Int = 0,
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
    val palette: List<String>,
    val presetApplications: List<CuePresetApplicationDetail>,
    val adHocEffects: List<CueAdHocEffectDto>,
)

@Serializable
data class StopCueResponse(
    val removedCount: Int,
    val cueId: Int,
)

