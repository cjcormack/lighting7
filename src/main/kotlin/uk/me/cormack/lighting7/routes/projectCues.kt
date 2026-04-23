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
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

private val logger = LoggerFactory.getLogger("projectCues")

/**
 * Dead-assignment warn throttle. Keeps the log quiet when the same cue is fired repeatedly
 * with the same dead-reference shape (e.g. a stack advancing the same dead cue on every GO).
 * Capped at [DEAD_WARN_STATE_MAX] entries so a long-running process can't accumulate one
 * entry per ever-applied cue — on overflow the oldest entry is evicted. Not a cache of
 * correctness — just a log-rate gate, so imprecise eviction is fine.
 */
private const val DEAD_WARN_THROTTLE_MS = 30_000L
private const val DEAD_WARN_STATE_MAX = 1024
private val deadWarnState = java.util.Collections.synchronizedMap(
    object : java.util.LinkedHashMap<Int, Pair<Long, String>>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<Long, String>>?): Boolean =
            size > DEAD_WARN_STATE_MAX
    },
)

private fun maybeLogDeadAssignments(
    cueId: Int,
    cueName: String,
    deadRows: List<CuePropertyAssignmentDto>,
) {
    val signature = deadRows.joinToString(";") { "${it.targetType}:${it.targetKey}.${it.propertyName}" }
    val now = System.currentTimeMillis()
    val previous = deadWarnState[cueId]
    if (previous != null && previous.second == signature && (now - previous.first) < DEAD_WARN_THROTTLE_MS) {
        return
    }
    deadWarnState[cueId] = now to signature
    logger.warn(
        "applyCue '{}' ({}): {} dead assignment(s): {}",
        cueName, cueId, deadRows.size, signature,
    )
}

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
            val found = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId) ?: return@transaction false
                if (cue.project.id != project.id) return@transaction false
                deleteCueChildren(cue)
                cue.delete()
                true
            }

            if (found) {
                state.show.fixtures.cueListChanged()
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

// Internal data class for apply logic
internal data class CueApplyData(
    val cueId: Int,
    val cueName: String,
    val palette: List<String>,
    val updateGlobalPalette: Boolean,
    val presetApplications: List<CuePresetApplicationDto>,
    val adHocEffects: List<CueAdHocEffectDto>,
    val propertyAssignments: List<CuePropertyAssignmentDto> = emptyList(),
    val triggers: List<CueTriggerDto> = emptyList(),
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
    val stomp: Boolean = false,
    val cueStackId: Int? = null,
    val sortOrder: Int = 0,
)

// ─── State capture ──────────────────────────────────────────────────────

private data class CapturedState(
    val palette: List<String>,
    val presetApplications: List<CuePresetApplicationDto>,
    val adHocEffects: List<CueAdHocEffectDto>,
    val propertyAssignments: List<CuePropertyAssignmentDto>,
)

/**
 * Capture the current palette, active effects, and active Layer 3 property assignments from
 * the FX engine. Property assignments are read from [uk.me.cormack.lighting7.fx.LayerResolver.currentLayer3State]
 * — which already holds the fully-resolved-and-composed values from all active cues — and
 * emitted one row per (targetKey, propertyName) via [Layer3Resolver.PropertyValue.serialize].
 */
private fun captureCurrentState(state: State): CapturedState {
    val currentPalette = state.show.fxEngine.getPalette().map { it.toSerializedString() }
    val activeEffects = state.show.fxEngine.getActiveEffects()

    val presetApplications = mutableMapOf<Int, MutableList<CueTargetDto>>()
    val adHocEffects = mutableListOf<CueAdHocEffectDto>()

    for (effect in activeEffects) {
        val targetType = if (effect.isGroupEffect) "group" else "fixture"
        val targetKey = effect.target.targetKey

        if (effect.presetId != null) {
            val targets = presetApplications.getOrPut(effect.presetId!!) { mutableListOf() }
            val target = CueTargetDto(type = targetType, key = targetKey)
            if (target !in targets) {
                targets.add(target)
            }
        } else {
            adHocEffects.add(CueAdHocEffectDto(
                targetType = targetType,
                targetKey = targetKey,
                effectType = effect.effect.name.replace(" ", ""),
                category = categoryFromPropertyName(effect.target.propertyName),
                propertyName = effect.target.propertyName,
                beatDivision = effect.timing.beatDivision,
                blendMode = effect.blendMode.name,
                distribution = effect.distributionStrategy.javaClass.simpleName,
                phaseOffset = effect.phaseOffset,
                elementMode = if (effect.isGroupEffect) effect.elementMode.name else null,
                elementFilter = if (effect.elementFilter != ElementFilter.ALL) effect.elementFilter.name else null,
                stepTiming = if (effect.stepTiming != effect.effect.defaultStepTiming) effect.stepTiming else null,
                parameters = effect.effect.parameters,
            ))
        }
    }

    val presetAppDtos = presetApplications.map { (presetId, targets) ->
        CuePresetApplicationDto(presetId = presetId, targets = targets)
    }

    // Layer 3 state → property assignment rows. Key.targetKey is always a fixture key here
    // (buildLayer3AssignmentsForCue expands groups to member rows before publishing), so the
    // captured snapshot is fixture-scoped — "flatten live" rather than "preserve group shape".
    // That's deliberate: the operator can re-grouping by hand when they paste into a cue, and
    // the machinery has no way to know which group a live fixture-level contribution came from.
    val layer3Snapshot = state.show.fxEngine.layerResolver.currentLayer3State
    val propertyAssignments = layer3Snapshot.entries
        .sortedWith(compareBy({ it.key.targetKey }, { it.key.propertyName }))
        .mapIndexed { index, (key, value) ->
            CuePropertyAssignmentDto(
                targetType = "fixture",
                targetKey = key.targetKey,
                propertyName = key.propertyName,
                value = value.serialize(),
                sortOrder = index,
            )
        }

    return CapturedState(
        palette = currentPalette,
        presetApplications = presetAppDtos,
        adHocEffects = adHocEffects,
        propertyAssignments = propertyAssignments,
    )
}

// ─── Entity helpers ─────────────────────────────────────────────────────

/**
 * Build a [CueApplyData] snapshot from a [DaoCue] entity. Must be called inside an Exposed
 * transaction — dereferences the cue's child collections eagerly.
 */
internal fun buildCueApplyData(cue: DaoCue): CueApplyData = CueApplyData(
    cueId = cue.id.value,
    cueName = cue.name,
    palette = cue.palette,
    updateGlobalPalette = cue.updateGlobalPalette,
    presetApplications = cue.presetApplications.sortedBy { it.sortOrder }.map { app ->
        CuePresetApplicationDto(
            presetId = app.preset.id.value,
            targets = app.targets,
            delayMs = app.delayMs,
            intervalMs = app.intervalMs,
            randomWindowMs = app.randomWindowMs,
            sortOrder = app.sortOrder,
        )
    },
    adHocEffects = cue.adHocEffects.sortedBy { it.sortOrder }.map { it.toDto() },
    propertyAssignments = cue.propertyAssignments.sortedBy { it.sortOrder }.map { it.toDto() },
    triggers = cue.triggers.sortedBy { it.sortOrder }.map { trigger ->
        CueTriggerDto(
            triggerType = trigger.triggerType.name,
            delayMs = trigger.delayMs,
            intervalMs = trigger.intervalMs,
            randomWindowMs = trigger.randomWindowMs,
            scriptId = trigger.script.id.value,
            sortOrder = trigger.sortOrder,
        )
    },
    stomp = cue.stomp,
    cueStackId = cue.cueStack?.id?.value,
    sortOrder = cue.sortOrder,
)

/** Convert a DaoCuePropertyAssignment entity to its DTO form. Health defaults to [AssignmentHealth.Ok]. */
internal fun DaoCuePropertyAssignment.toDto() = CuePropertyAssignmentDto(
    targetType = targetType,
    targetKey = targetKey,
    propertyName = propertyName,
    value = value,
    fadeDurationMs = fadeDurationMs,
    sortOrder = sortOrder,
)

/**
 * DTO + health evaluated against the live patch [fixtures]. Used for REST responses where
 * dead-reference diagnostics are surfaced (Phase 6). Apply-path and snapshot callers keep
 * using [toDto] — they don't need health and shouldn't pay the lookup cost.
 */
internal fun DaoCuePropertyAssignment.toDtoWithHealth(fixtures: uk.me.cormack.lighting7.show.Fixtures): CuePropertyAssignmentDto {
    val base = toDto()
    return base.copy(
        health = PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures, base.targetType, base.targetKey, base.propertyName,
        ),
    )
}

/** Convert a DaoCueAdHocEffect entity to its DTO form. */
internal fun DaoCueAdHocEffect.toDto() = CueAdHocEffectDto(
    targetType = targetType,
    targetKey = targetKey,
    effectType = effectType,
    category = category,
    propertyName = propertyName,
    beatDivision = beatDivision,
    blendMode = blendMode,
    distribution = distribution,
    phaseOffset = phaseOffset,
    elementMode = elementMode,
    elementFilter = elementFilter,
    stepTiming = stepTiming,
    parameters = parameters,
    delayMs = delayMs,
    intervalMs = intervalMs,
    randomWindowMs = randomWindowMs,
    sortOrder = sortOrder,
)

/**
 * Convert a DaoCue entity to CueDetails API response. Property-assignment rows are tagged
 * with [AssignmentHealth] by resolving each `(targetType, targetKey, propertyName)` against
 * [fixtures] — dead references surface in the UI with markers (Phase 6) rather than
 * silently dropping at apply time.
 */
internal fun DaoCue.toCueDetails(
    isCurrentProject: Boolean,
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
): CueDetails {
    val presetDetails = presetApplications.sortedBy { it.sortOrder }.map { app ->
        CuePresetApplicationDetail(
            presetId = app.preset.id.value,
            presetName = app.preset.name,
            targets = app.targets,
            delayMs = app.delayMs,
            intervalMs = app.intervalMs,
            randomWindowMs = app.randomWindowMs,
            sortOrder = app.sortOrder,
        )
    }
    val triggerDetails = this.triggers.sortedBy { it.sortOrder }.map { trigger ->
        CueTriggerDetailDto(
            triggerType = trigger.triggerType.name,
            delayMs = trigger.delayMs,
            intervalMs = trigger.intervalMs,
            randomWindowMs = trigger.randomWindowMs,
            scriptId = trigger.script.id.value,
            scriptName = trigger.script.name,
            sortOrder = trigger.sortOrder,
        )
    }
    val assignmentDetails = this.propertyAssignments.sortedBy { it.sortOrder }
        .map { it.toDtoWithHealth(fixtures) }
    return CueDetails(
        id = this.id.value,
        name = this.name,
        palette = this.palette,
        presetApplications = presetDetails,
        adHocEffects = this.adHocEffects.sortedBy { it.sortOrder }.map { it.toDto() },
        propertyAssignments = assignmentDetails,
        triggers = triggerDetails,
        updateGlobalPalette = this.updateGlobalPalette,
        cueStackId = this.cueStack?.id?.value,
        cueStackName = this.cueStack?.name,
        sortOrder = this.sortOrder,
        autoAdvance = this.autoAdvance,
        autoAdvanceDelayMs = this.autoAdvanceDelayMs,
        fadeDurationMs = this.fadeDurationMs,
        fadeCurve = this.fadeCurve,
        stomp = this.stomp,
        canEdit = isCurrentProject,
        canDelete = isCurrentProject,
    )
}

/** Create child preset application, ad-hoc effect, property assignment, and trigger entities for a cue. */
internal fun createCueChildren(
    cue: DaoCue,
    presetApplications: List<CuePresetApplicationDto>,
    adHocEffects: List<CueAdHocEffectDto>,
    propertyAssignments: List<CuePropertyAssignmentDto> = emptyList(),
    triggers: List<CueTriggerDto> = emptyList(),
) {
    for (app in presetApplications) {
        val preset = DaoFxPreset.findById(app.presetId) ?: continue
        DaoCuePresetApplication.new {
            this.cue = cue
            this.preset = preset
            this.targets = app.targets
            this.delayMs = app.delayMs
            this.intervalMs = app.intervalMs
            this.randomWindowMs = app.randomWindowMs
            this.sortOrder = app.sortOrder
        }
    }
    for (effect in adHocEffects) {
        DaoCueAdHocEffect.new {
            this.cue = cue
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
    for (assignment in propertyAssignments) {
        DaoCuePropertyAssignment.new {
            this.cue = cue
            targetType = assignment.targetType
            targetKey = assignment.targetKey
            propertyName = assignment.propertyName
            value = assignment.value
            fadeDurationMs = assignment.fadeDurationMs
            sortOrder = assignment.sortOrder
        }
    }
    for (trigger in triggers) {
        val script = DaoScript.findById(trigger.scriptId) ?: continue
        // Normalize legacy trigger types: DELAYED/RECURRING → ACTIVATION with timing fields
        val normalizedType = when (trigger.triggerType) {
            "DELAYED" -> TriggerType.ACTIVATION
            "RECURRING" -> TriggerType.ACTIVATION
            else -> try {
                TriggerType.valueOf(trigger.triggerType)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Unknown trigger type: '${trigger.triggerType}'. Valid types: ${TriggerType.entries.joinToString()}")
            }
        }
        DaoCueTrigger.new {
            this.cue = cue
            this.triggerType = normalizedType
            this.delayMs = trigger.delayMs
            this.intervalMs = trigger.intervalMs
            this.randomWindowMs = trigger.randomWindowMs
            this.script = script
            this.sortOrder = trigger.sortOrder
        }
    }
}

/** Delete all child entities (preset applications, ad-hoc effects, property assignments, and triggers) for a cue. */
internal fun deleteCueChildren(cue: DaoCue) {
    cue.presetApplications.forEach { it.delete() }
    cue.adHocEffects.forEach { it.delete() }
    cue.propertyAssignments.forEach { it.delete() }
    cue.triggers.forEach { it.delete() }
}

// ─── Apply logic ────────────────────────────────────────────────────────

/**
 * Apply a cue: remove previous effects, set palette, apply preset effects and ad-hoc effects.
 *
 * @param replaceAll If true, remove ALL running cue effects (from any cue). If false, only
 *                   remove effects from this same cue (allowing multiple cues to run concurrently).
 */
internal fun applyCue(state: State, cueData: CueApplyData, replaceAll: Boolean = false): ApplyCueResponse {
    val engine = state.show.fxEngine
    var effectCount = 0

    // Pre-apply validation: warn once per cue-apply when any assignment targets a
    // removed/renamed fixture / group / property. The per-row warns inside the build helpers
    // stay (they're the detailed diagnostic trail); this summary is the rate-limited
    // operator-facing signal. Same-shape warns within `DEAD_WARN_THROTTLE_MS` are dropped
    // so a stack GO'ing the same dead cue on every beat doesn't flood the logs.
    val deadRows = cueData.propertyAssignments.filter {
        PersistedFixtureReferenceValidator.validateTargetedReference(
            state.show.fixtures, it.targetType, it.targetKey, it.propertyName,
        ) != AssignmentHealth.Ok
    }
    if (deadRows.isNotEmpty()) {
        maybeLogDeadAssignments(cueData.cueId, cueData.cueName, deadRows)
    }

    // 1. Remove effects — either all cue effects or just this cue's effects
    if (replaceAll) {
        val toRemove = engine.getActiveEffects().filter { it.cueId != null }
        val removedCueIds = toRemove.mapNotNull { it.cueId }.toSet()
        for (effect in toRemove) {
            engine.removeEffect(effect.id)
        }
        for (removedCueId in removedCueIds) {
            engine.removeCuePalette(removedCueId)
        }
    } else {
        val toRemove = engine.getActiveEffects().filter { it.cueId == cueData.cueId }
        for (effect in toRemove) {
            engine.removeEffect(effect.id)
        }
        engine.removeCuePalette(cueData.cueId)
    }

    val priority = cueDerivedPriority(cueData)

    // Load each immediate preset once — effects + property assignments in a single DB hit.
    // Timed preset applications (delayMs/intervalMs) are handled entirely by CueTriggerManager;
    // at fire time they append their property assignments to this cue's Layer 3 via
    // [FxEngine.appendCueAssignments] so they compose like the cue's apply-time rows. The
    // contribution goes live at fire time, not cue-apply time — see the timed preset wiring
    // in [CueTriggerManager.activateTimedEffectsForCue].
    data class ImmediatePreset(
        val presetId: Int,
        val targets: List<CueTargetDto>,
        val effects: List<FxPresetEffectDto>,
        val assignments: List<FxPresetPropertyAssignmentDto>,
    )
    val immediatePresets = transaction(state.database) {
        cueData.presetApplications
            .filter { it.delayMs == null && it.intervalMs == null }
            .mapNotNull { app ->
                val preset = DaoFxPreset.findById(app.presetId) ?: return@mapNotNull null
                ImmediatePreset(
                    presetId = app.presetId,
                    targets = app.targets,
                    effects = preset.effects,
                    assignments = preset.toPropertyAssignmentDtos(),
                )
            }
    }

    // Publish Layer 3 before applying effects so the effect reset pass sees the cue's baseline
    // instead of Layer 5 zero. Combines the cue's own assignments with each immediate preset's
    // property assignments.
    val cueOwnAssignments = buildLayer3AssignmentsForCue(state.show.fixtures, cueData)
    val presetRows = immediatePresets.flatMap { ip ->
        buildLayer3AssignmentsForPreset(
            state.show.fixtures, cueData.cueId, priority,
            ip.presetId, ip.assignments, ip.targets,
        )
    }

    val layer3Assignments = when {
        presetRows.isEmpty() -> cueOwnAssignments
        cueOwnAssignments.isEmpty() -> presetRows
        else -> cueOwnAssignments + presetRows
    }
    if (layer3Assignments.isNotEmpty()) {
        engine.setCueAssignments(cueData.cueId, layer3Assignments)
    } else {
        // Re-applying a cue that lost its assignments must clear any stale state.
        engine.removeCueAssignments(cueData.cueId)
    }

    if (cueData.stomp) {
        val overlap = buildStompOverlapFromAssignments(state.show.fixtures, cueData)
        engine.stompForCue(cueData.cueId, overlap)
    }

    // 2. Set per-cue palette (isolated from global palette)
    if (cueData.palette.isNotEmpty()) {
        val colours = cueData.palette.map { parseExtendedColour(it) }
        engine.setCuePalette(cueData.cueId, colours)
        if (cueData.updateGlobalPalette) {
            engine.setPalette(colours)
        }
    }

    // 3. Spawn immediate preset effects from the data loaded above.
    for (ip in immediatePresets) {
        for (target in ip.targets) {
            val toggleTarget = TogglePresetTarget(type = target.type, key = target.key)
            for (presetEffect in ip.effects) {
                val fxTarget = try {
                    resolveTargetForCue(state, toggleTarget, presetEffect)
                } catch (_: Exception) { null } ?: continue

                val instance = createInstanceFromPresetForCue(
                    presetEffect, fxTarget, ip.presetId, state, cueData.cueId
                )
                instance.cueId = cueData.cueId
                instance.priority = priority
                engine.addEffect(instance)
                effectCount++
            }
        }
    }

    // 4. Apply immediate ad-hoc effects
    // (Timed ad-hoc effects with delayMs/intervalMs are handled by CueTriggerManager)
    for (adHoc in cueData.adHocEffects.filter { it.delayMs == null && it.intervalMs == null }) {
        val target = TogglePresetTarget(type = adHoc.targetType, key = adHoc.targetKey)
        val presetEffectDto = FxPresetEffectDto(
            effectType = adHoc.effectType,
            category = adHoc.category,
            propertyName = adHoc.propertyName,
            beatDivision = adHoc.beatDivision,
            blendMode = adHoc.blendMode,
            distribution = adHoc.distribution,
            phaseOffset = adHoc.phaseOffset,
            elementMode = adHoc.elementMode,
            elementFilter = adHoc.elementFilter,
            stepTiming = adHoc.stepTiming,
            parameters = adHoc.parameters,
        )
        val fxTarget = try {
            resolveTargetForCue(state, target, presetEffectDto)
        } catch (_: Exception) { null } ?: continue

        val instance = createInstanceFromPresetForCue(
            presetEffectDto, fxTarget, null, state, cueData.cueId
        )
        instance.cueId = cueData.cueId
        instance.priority = priority
        engine.addEffect(instance)
        effectCount++
    }

    return ApplyCueResponse(effectCount = effectCount, cueName = cueData.cueName)
}

/**
 * Derived priority for a cue-owned effect. `+1` keeps manual effects (priority 0) strictly
 * below; the magnitude gaps leave room for per-effect fine-tuning without renumbering.
 */
internal fun cueDerivedPriority(cueData: CueApplyData): Int =
    (cueData.cueStackId ?: 0) * 1_000_000 + cueData.sortOrder * 1_000 + 1

/**
 * Build the stomp overlap set from a cue's property assignments. Group targets are expanded
 * to member keys so the resolver can filter ad-hoc effects owned by other cues that target
 * individual fixtures within the same group.
 */
internal fun buildStompOverlapFromAssignments(
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
    cueData: CueApplyData,
): Set<FxEngine.PropertyKey> {
    if (cueData.propertyAssignments.isEmpty()) return emptySet()
    val out = HashSet<FxEngine.PropertyKey>()
    for (assignment in cueData.propertyAssignments) {
        val canonical = canonicalPropertyName(assignment.propertyName)
        if (assignment.targetType == "group") {
            out.add(FxEngine.PropertyKey(assignment.targetKey, canonical))
            val members = try {
                fixtures.untypedGroup(assignment.targetKey).fixtures
            } catch (_: IllegalStateException) { emptyList() }
            for (member in members) {
                if (member is Fixture) out.add(FxEngine.PropertyKey(member.key, canonical))
            }
        } else {
            out.add(FxEngine.PropertyKey(assignment.targetKey, canonical))
        }
    }
    return out
}

// Canonical form for property names is defined in [uk.me.cormack.lighting7.fx.canonicalPropertyName]
// — shared with [PersistedFixtureReferenceValidator] so route handlers and validation
// don't drift apart on the aliasing rule.

/**
 * Fixture property lookup used when building Layer 3 assignments. Returns the resolved
 * category / composition override for [propertyName] on [fixture], or null if the name is
 * not a known annotated property.
 *
 * Handles the synthetic aliases the target-resolution code already understands:
 * `"position"` (paired PAN+TILT), `"colour"` / `"color"` / `"rgbColour"` (RGB+W/A/UV bundle).
 * For these names [fixture] is consulted only to verify the capability exists.
 */
private fun fixtureCategoryFor(
    fixture: Fixture,
    propertyName: String,
): Pair<PropertyCategory, CompositionRule>? {
    val canonical = canonicalPropertyName(propertyName)
    if (canonical.equals("position", ignoreCase = true)) {
        // Synthetic compound of PAN + TILT. Composition defaults to the PAN category's rule;
        // any override on the pan property is honoured.
        val panProp = fixture.fixtureProperty("pan")
        return panProp?.let { it.category to it.composition } ?: (PropertyCategory.PAN to CompositionRule.UNSET)
    }
    val prop = fixture.fixtureProperty(canonical) ?: return null
    return prop.category to prop.composition
}

/**
 * Build the flat [Layer3Resolver.Assignment] list for a single cue's [propertyAssignments],
 * expanding group targets to per-member rows. Member rows produced by a group expansion carry
 * `targetIsGroup = true` so the resolver's specificity rule can drop them when the same cue
 * also asserts a direct fixture-level row on the same (fixtureKey, property).
 *
 * Assignments whose fixture, group, or property cannot be resolved are logged at warn and
 * skipped — missing data must not break cue apply.
 */
internal fun buildLayer3AssignmentsForCue(
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
    cueData: CueApplyData,
): List<Layer3Resolver.Assignment> {
    if (cueData.propertyAssignments.isEmpty()) return emptyList()
    val priority = cueDerivedPriority(cueData)
    val out = ArrayList<Layer3Resolver.Assignment>(cueData.propertyAssignments.size * 2)

    for (assignment in cueData.propertyAssignments) {
        val canonical = canonicalPropertyName(assignment.propertyName)

        // Resolve a reference fixture for category lookup and, for groups, the member keys.
        val memberKeys: List<String>
        val referenceFixture: Fixture
        if (assignment.targetType == "group") {
            val group = try {
                fixtures.untypedGroup(assignment.targetKey)
            } catch (_: IllegalStateException) {
                logger.warn("cue {}: group '{}' missing — skipping assignment for {}", cueData.cueId, assignment.targetKey, assignment.propertyName)
                continue
            }
            val members = group.fixtures.filterIsInstance<Fixture>()
            if (members.isEmpty()) {
                logger.warn("cue {}: group '{}' has no Fixture members — skipping assignment", cueData.cueId, assignment.targetKey)
                continue
            }
            memberKeys = members.map { it.key }
            referenceFixture = members.first()
        } else {
            referenceFixture = try {
                fixtures.untypedFixture(assignment.targetKey)
            } catch (_: IllegalStateException) {
                logger.warn("cue {}: fixture '{}' missing — skipping assignment for {}", cueData.cueId, assignment.targetKey, assignment.propertyName)
                continue
            }
            memberKeys = emptyList()
        }

        val (category, override) = fixtureCategoryFor(referenceFixture, canonical) ?: run {
            logger.warn("cue {}: property '{}' not found on '{}' — skipping", cueData.cueId, assignment.propertyName, assignment.targetKey)
            continue
        }

        val parsed = Layer3Resolver.parseAssignmentValue(category, canonical, assignment.value) ?: run {
            logger.warn("cue {}: invalid value '{}' for {}.{} — skipping", cueData.cueId, assignment.value, assignment.targetKey, assignment.propertyName)
            continue
        }

        // Assignment.fadeWeight always 1.0 here — crossfade progress is applied per-cue by
        // [FxEngine.updateCueFadeWeights] at publish time, not baked into individual rows.
        fun row(key: String, isGroup: Boolean) = Layer3Resolver.Assignment(
            cueId = cueData.cueId,
            priority = priority,
            fadeWeight = 1.0,
            targetKey = key,
            targetIsGroup = isGroup,
            propertyName = canonical,
            category = category,
            compositionOverride = override,
            value = parsed,
        )

        if (assignment.targetType == "group") {
            // Emit only per-member rows; the group-level key isn't a resolvable fixture at
            // publish time. Mark these as targetIsGroup=true so a direct fixture-level row
            // for the same member overrides via [Layer3Resolver.applySpecificity].
            for (memberKey in memberKeys) out.add(row(memberKey, isGroup = true))
        } else {
            out.add(row(assignment.targetKey, isGroup = false))
        }
    }
    return out
}

/**
 * Build Layer 3 rows for a preset application. Preset assignments are preset-local
 * (no target field) — the builder fans each (propertyName, value) across the supplied
 * [applyTargets], reusing the cue builder's group→member expansion and specificity tagging.
 *
 * Rows are tagged with [cueId] and [priority] so the cue's normal teardown
 * ([FxEngine.removeCueAssignments]) cleans up preset-originated rows alongside the cue's
 * own assignments. If both the applying cue and the preset assert the same
 * `(targetKey, propertyName)`, the caller concatenates the two lists and lets
 * [Layer3Resolver.resolve] pick the winner by [Layer3Resolver.Assignment.priority] —
 * callers should keep preset rows at the same priority as the cue's own rows so the sort
 * order alone decides (last-write-wins for OVERRIDE blend). Rows whose fixture / group /
 * property cannot be resolved are logged at warn and skipped — stale data must not break
 * cue apply.
 */
internal fun buildLayer3AssignmentsForPreset(
    fixtures: uk.me.cormack.lighting7.show.Fixtures,
    cueId: Int,
    priority: Int,
    presetId: Int,
    presetAssignments: List<FxPresetPropertyAssignmentDto>,
    applyTargets: List<CueTargetDto>,
): List<Layer3Resolver.Assignment> {
    if (presetAssignments.isEmpty() || applyTargets.isEmpty()) return emptyList()
    val out = ArrayList<Layer3Resolver.Assignment>(presetAssignments.size * applyTargets.size * 2)

    for (target in applyTargets) {
        val memberKeys: List<String>
        val referenceFixture: Fixture
        if (target.type == "group") {
            val group = try {
                fixtures.untypedGroup(target.key)
            } catch (_: IllegalStateException) {
                logger.warn("preset {} (cue {}): group '{}' missing — skipping", presetId, cueId, target.key)
                continue
            }
            val members = group.fixtures.filterIsInstance<Fixture>()
            if (members.isEmpty()) {
                logger.warn("preset {} (cue {}): group '{}' has no Fixture members — skipping", presetId, cueId, target.key)
                continue
            }
            memberKeys = members.map { it.key }
            referenceFixture = members.first()
        } else {
            referenceFixture = try {
                fixtures.untypedFixture(target.key)
            } catch (_: IllegalStateException) {
                logger.warn("preset {} (cue {}): fixture '{}' missing — skipping", presetId, cueId, target.key)
                continue
            }
            memberKeys = emptyList()
        }

        for (assignment in presetAssignments) {
            val canonical = canonicalPropertyName(assignment.propertyName)
            val (category, override) = fixtureCategoryFor(referenceFixture, canonical) ?: run {
                logger.warn(
                    "preset {} (cue {}): property '{}' not on '{}' — skipping",
                    presetId, cueId, assignment.propertyName, target.key,
                )
                continue
            }
            val parsed = Layer3Resolver.parseAssignmentValue(category, canonical, assignment.value) ?: run {
                logger.warn(
                    "preset {} (cue {}): invalid value '{}' for {}.{} — skipping",
                    presetId, cueId, assignment.value, target.key, assignment.propertyName,
                )
                continue
            }

            fun row(key: String, isGroup: Boolean) = Layer3Resolver.Assignment(
                cueId = cueId,
                priority = priority,
                fadeWeight = 1.0,
                targetKey = key,
                targetIsGroup = isGroup,
                propertyName = canonical,
                category = category,
                compositionOverride = override,
                value = parsed,
            )

            if (target.type == "group") {
                for (memberKey in memberKeys) out.add(row(memberKey, isGroup = true))
            } else {
                out.add(row(target.key, isGroup = false))
            }
        }
    }
    return out
}

// ─── Target resolution helpers ──────────────────────────────────────────

internal fun resolveTargetForCue(
    state: State,
    target: TogglePresetTarget,
    presetEffect: FxPresetEffectDto,
): FxTarget? {
    return if (target.type == "group") {
        val group = state.show.fixtures.untypedGroup(target.key)
        val propertyName = presetEffect.propertyName
            ?: resolvePresetEffectPropertyForCue(presetEffect, group.detectCapabilities())
            ?: return null
        createGroupTargetForCue(group.name, propertyName, group)
    } else {
        val propertyName = presetEffect.propertyName
            ?: resolvePresetEffectPropertyForFixtureInCue(presetEffect)
            ?: return null
        createFixtureTargetForCue(target.key, propertyName, state)
    }
}

internal fun resolvePresetEffectPropertyForCue(
    presetEffect: FxPresetEffectDto,
    capabilities: List<String>,
): String? {
    return when (presetEffect.category) {
        "dimmer" -> if ("dimmer" in capabilities) "dimmer" else null
        "colour" -> if ("colour" in capabilities) "colour" else null
        "position" -> if ("position" in capabilities) "position" else null
        "controls", "setting" -> presetEffect.propertyName
        else -> null
    }
}

internal fun resolvePresetEffectPropertyForFixtureInCue(
    presetEffect: FxPresetEffectDto,
): String? {
    return when (presetEffect.category) {
        "dimmer" -> "dimmer"
        "colour" -> "colour"
        "position" -> "position"
        "controls", "setting" -> presetEffect.propertyName
        else -> null
    }
}

internal fun createGroupTargetForCue(
    groupName: String,
    propertyName: String,
    group: FixtureGroup<*>,
): FxTarget {
    return when (propertyName.lowercase()) {
        "dimmer" -> SliderTarget.forGroup(groupName, "dimmer")
        "colour", "color", "rgbcolour" -> ColourTarget.forGroup(groupName)
        "position" -> PositionTarget.forGroup(groupName)
        "uv" -> SliderTarget.forGroup(groupName, "uv")
        else -> {
            val firstFixture = group.fixtures.firstOrNull() as? Fixture
            val prop = firstFixture?.fixtureProperties?.find { it.name == propertyName }
            val propValue = prop?.classProperty?.call(firstFixture)
            if (propValue is Slider) {
                SliderTarget.forGroup(groupName, propertyName)
            } else {
                SettingTarget.forGroup(groupName, propertyName)
            }
        }
    }
}

internal fun createFixtureTargetForCue(
    fixtureKey: String,
    propertyName: String,
    state: State,
): FxTarget {
    return when (propertyName.lowercase()) {
        "dimmer" -> SliderTarget(fixtureKey, "dimmer")
        "uv" -> SliderTarget(fixtureKey, "uv")
        "colour", "color", "rgbcolour" -> ColourTarget(fixtureKey)
        "position" -> PositionTarget(fixtureKey)
        else -> {
            val fixture = try {
                state.show.fixtures.untypedFixture(fixtureKey) as? Fixture
            } catch (_: Exception) { null }
            val prop = fixture?.fixtureProperties?.find { it.name == propertyName }
            val propValue = prop?.classProperty?.call(fixture)
            if (propValue is Slider) {
                SliderTarget(fixtureKey, propertyName)
            } else {
                SettingTarget(fixtureKey, propertyName)
            }
        }
    }
}

/** Extract a nullable String from a JsonElement (null / JsonNull → null). */
private fun kotlinx.serialization.json.JsonElement?.nullableString(): String? =
    if (this == null || this is JsonNull) null else jsonPrimitive.content

/** Extract a nullable Long from a JsonElement (null / JsonNull → null). */
private fun kotlinx.serialization.json.JsonElement?.nullableLong(): Long? =
    if (this == null || this is JsonNull) null else jsonPrimitive.long

/**
 * Infer effect category from property name for from-state capture.
 */
internal fun categoryFromPropertyName(propertyName: String): String {
    return when (propertyName.lowercase()) {
        "dimmer" -> "dimmer"
        "colour", "color", "rgbcolour" -> "colour"
        "position" -> "position"
        else -> "controls"
    }
}

/**
 * Create an FxInstance from preset effect data for cue application.
 */
internal fun createInstanceFromPresetForCue(
    presetEffect: FxPresetEffectDto,
    fxTarget: FxTarget,
    presetId: Int?,
    state: State,
    cueId: Int,
): FxInstance {
    val engine = state.show.fxEngine
    val effect = state.show.fxRegistry.createEffect(
        presetEffect.effectType,
        presetEffect.parameters,
        paletteSupplier = { engine.getCuePalette(cueId) ?: engine.getPalette() },
        paletteVersionSupplier = { engine.getCuePaletteVersion(cueId) + engine.paletteVersion },
    )
    val timing = FxTiming(presetEffect.beatDivision)
    val blendMode = try {
        BlendMode.valueOf(presetEffect.blendMode)
    } catch (_: Exception) {
        BlendMode.OVERRIDE
    }
    val distribution = try {
        DistributionStrategy.fromName(presetEffect.distribution)
    } catch (_: Exception) {
        DistributionStrategy.LINEAR
    }
    val elementMode = try {
        presetEffect.elementMode?.let { ElementMode.valueOf(it) } ?: ElementMode.PER_FIXTURE
    } catch (_: Exception) {
        ElementMode.PER_FIXTURE
    }
    val elementFilter = try {
        presetEffect.elementFilter?.let { ElementFilter.fromName(it) } ?: ElementFilter.ALL
    } catch (_: Exception) {
        ElementFilter.ALL
    }

    // Propagate timing source from the effect's registration
    val registration = state.show.fxRegistry.getRegistration(presetEffect.effectType)
    val timingSource = registration?.timingSource ?: uk.me.cormack.lighting7.fx.TimingSource.BEAT

    return FxInstance(effect, fxTarget, timing, blendMode).apply {
        this.presetId = presetId
        phaseOffset = presetEffect.phaseOffset
        distributionStrategy = distribution
        this.elementMode = elementMode
        this.elementFilter = elementFilter
        this.timingSource = timingSource
        presetEffect.stepTiming?.let { this.stepTiming = it }
    }
}
