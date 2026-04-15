package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
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
                    .map { it.toCueDetails(isCurrentProject) }
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
                    if (stack != null) {
                        cueStack = stack
                        sortOrder = newCue.sortOrder ?: stack.cues.count().toInt()
                    }
                }
                createCueChildren(cue, newCue.presetApplications, newCue.adHocEffects, newCue.triggers)
                cue.toCueDetails(isCurrentProject = true)
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
                cue.toCueDetails(isCurrentProject)
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

                // Replace children: delete existing, create new
                deleteCueChildren(cue)
                createCueChildren(cue, updatedData.presetApplications, updatedData.adHocEffects, updatedData.triggers)

                cue.toCueDetails(isCurrentProject = true)
            }

            if (cueDetails != null) {
                state.show.fixtures.cueListChanged()
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
                val stackId = cue.cueStack?.id?.value
                val cueData = CueApplyData(
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
                )
                Pair(cueData, stackId)
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
                    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                    val stackResult = state.show.cueStackManager.activateCueInStack(
                        state, cueStackId, resource.cueId, kotlinx.coroutines.GlobalScope
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
                        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                        state.cueTriggerManager.activateTimedEffectsForCue(
                            cueId = resource.cueId,
                            cueStackId = null,
                            timedPresets = timedPresets,
                            timedAdHocEffects = timedAdHoc,
                            scope = kotlinx.coroutines.GlobalScope,
                        )
                    }

                    // Activate script triggers after effects are applied
                    if (cueData.triggers.isNotEmpty()) {
                        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
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

@Resource("/current-state")
data class CueCurrentStateResource(val parent: ProjectCuesResource)

// DTOs
@Serializable
data class NewCue(
    val name: String,
    val palette: List<String> = emptyList(),
    val presetApplications: List<CuePresetApplicationDto> = emptyList(),
    val adHocEffects: List<CueAdHocEffectDto> = emptyList(),
    val triggers: List<CueTriggerDto> = emptyList(),
    val updateGlobalPalette: Boolean = false,
    val cueStackId: Int? = null,
    val sortOrder: Int? = null,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
)

@Serializable
data class CueDetails(
    val id: Int,
    val name: String,
    val palette: List<String>,
    val presetApplications: List<CuePresetApplicationDetail>,
    val adHocEffects: List<CueAdHocEffectDto>,
    val triggers: List<CueTriggerDetailDto> = emptyList(),
    val updateGlobalPalette: Boolean = false,
    val cueStackId: Int? = null,
    val cueStackName: String? = null,
    val sortOrder: Int = 0,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
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
    val triggers: List<CueTriggerDto> = emptyList(),
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
)

// ─── State capture ──────────────────────────────────────────────────────

private data class CapturedState(
    val palette: List<String>,
    val presetApplications: List<CuePresetApplicationDto>,
    val adHocEffects: List<CueAdHocEffectDto>,
)

/** Capture the current palette and active effects from the FX engine. */
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

    return CapturedState(
        palette = currentPalette,
        presetApplications = presetAppDtos,
        adHocEffects = adHocEffects,
    )
}

// ─── Entity helpers ─────────────────────────────────────────────────────

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

/** Convert a DaoCue entity to CueDetails API response. */
internal fun DaoCue.toCueDetails(isCurrentProject: Boolean): CueDetails {
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
    return CueDetails(
        id = this.id.value,
        name = this.name,
        palette = this.palette,
        presetApplications = presetDetails,
        adHocEffects = this.adHocEffects.map { it.toDto() },
        triggers = triggerDetails,
        updateGlobalPalette = this.updateGlobalPalette,
        cueStackId = this.cueStack?.id?.value,
        cueStackName = this.cueStack?.name,
        sortOrder = this.sortOrder,
        autoAdvance = this.autoAdvance,
        autoAdvanceDelayMs = this.autoAdvanceDelayMs,
        fadeDurationMs = this.fadeDurationMs,
        fadeCurve = this.fadeCurve,
        canEdit = isCurrentProject,
        canDelete = isCurrentProject,
    )
}

/** Create child preset application, ad-hoc effect, and trigger entities for a cue. */
internal fun createCueChildren(
    cue: DaoCue,
    presetApplications: List<CuePresetApplicationDto>,
    adHocEffects: List<CueAdHocEffectDto>,
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

/** Delete all child entities (preset applications, ad-hoc effects, and triggers) for a cue. */
internal fun deleteCueChildren(cue: DaoCue) {
    cue.presetApplications.forEach { it.delete() }
    cue.adHocEffects.forEach { it.delete() }
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

    // 2. Set per-cue palette (isolated from global palette)
    if (cueData.palette.isNotEmpty()) {
        val colours = cueData.palette.map { parseExtendedColour(it) }
        engine.setCuePalette(cueData.cueId, colours)
        if (cueData.updateGlobalPalette) {
            engine.setPalette(colours)
        }
    }

    // 3. Apply immediate preset effects — read each preset fresh from DB
    // (Timed presets with delayMs/intervalMs are handled by CueTriggerManager)
    for (presetApp in cueData.presetApplications.filter { it.delayMs == null && it.intervalMs == null }) {
        val presetEffects = transaction(state.database) {
            DaoFxPreset.findById(presetApp.presetId)?.effects
        } ?: continue // Skip if preset was deleted

        for (target in presetApp.targets) {
            val toggleTarget = TogglePresetTarget(type = target.type, key = target.key)
            for (presetEffect in presetEffects) {
                val fxTarget = try {
                    resolveTargetForCue(state, toggleTarget, presetEffect)
                } catch (_: Exception) { null } ?: continue

                val instance = createInstanceFromPresetForCue(
                    presetEffect, fxTarget, presetApp.presetId, state, cueData.cueId
                )
                instance.cueId = cueData.cueId
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
        engine.addEffect(instance)
        effectCount++
    }

    return ApplyCueResponse(effectCount = effectCount, cueName = cueData.cueName)
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
