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
import io.ktor.server.routing.post as routingPost
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

/**
 * Resolves a project ID string to a DaoProject.
 * Supports both numeric IDs and the "current" keyword.
 *
 * @return The resolved DaoProject or null if not found
 */
internal fun State.resolveProject(projectIdStr: String): DaoProject? {
    return transaction(database) {
        if (projectIdStr.equals("current", ignoreCase = true)) {
            projectManager.currentProject
        } else {
            projectIdStr.toIntOrNull()?.let { DaoProject.findById(it) }
        }
    }
}

/**
 * Checks if the given project is the current active project.
 */
internal fun State.isCurrentProject(project: DaoProject): Boolean {
    return transaction(database) {
        project.id == projectManager.currentProject.id
    }
}

internal fun Route.routeApiRestProjects(state: State) {
    route("/project") {
        // GET /list - List all projects
        get("/list") {
            val projects = transaction(state.database) {
                DaoProject.all().orderBy(DaoProjects.name to SortOrder.ASC).map { it.toListDto() }
            }
            call.respond(projects)
        }

        // GET /current - Get current project
        get("/current") {
            val project = transaction(state.database) {
                state.projectManager.currentProject.toDetailDto()
            }
            call.respond(project)
        }

        // GET /{id} - Get project by ID
        get<ProjectIdResource> { resource ->
            val project = transaction(state.database) {
                DaoProject.findById(resource.id)?.toDetailDto()
            }
            if (project != null) {
                call.respond(project)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            }
        }

        // POST / - Create new project
        routingPost {
            val request = call.receive<CreateProjectRequest>()
            val project = transaction(state.database) {
                val newProject = DaoProject.new {
                    name = request.name
                    description = request.description
                    isCurrent = false
                }

                newProject.toDetailDto()
            }
            call.respond(HttpStatusCode.Created, project)
        }

        // PUT /{id} - Update project
        put<ProjectIdResource> { resource ->
            val request = call.receive<UpdateProjectRequest>()
            val project = transaction(state.database) {
                val project = DaoProject.findById(resource.id)
                    ?: return@transaction null

                request.name?.let { project.name = it }
                request.description?.let { project.description = it }

                project.toDetailDto()
            }

            if (project != null) {
                call.respond(project)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            }
        }

        // DELETE /{id} - Delete project
        delete<ProjectIdResource> { resource ->
            val result = transaction(state.database) {
                val project = DaoProject.findById(resource.id)
                    ?: return@transaction DeleteResult.NOT_FOUND

                if (project.isCurrent) {
                    return@transaction DeleteResult.IS_CURRENT
                }

                // Delete associated records in FK-safe order
                project.cues.forEach { cue ->
                    deleteCueChildren(cue)
                    cue.delete()
                }
                project.cueStacks.forEach { it.delete() }
                project.cueSlots.forEach { it.delete() }
                project.fxPresets.forEach { it.delete() }
                project.fixtureGroups.forEach { group ->
                    group.members.forEach { it.delete() }
                    group.delete()
                }
                project.fixturePatches.forEach { it.delete() }
                project.universeConfigs.forEach { it.delete() }
                project.parkedChannels.forEach { it.delete() }
                project.aiConversations.forEach { it.delete() }
                project.fxDefinitions.forEach { it.delete() }
                project.scripts.forEach { it.delete() }
                project.delete()

                DeleteResult.SUCCESS
            }

            when (result) {
                DeleteResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
                DeleteResult.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
                DeleteResult.IS_CURRENT -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("Cannot delete current project")
                )
            }
        }

        // POST /{id}/set-current - Set project as current (triggers switch)
        post<SetCurrentProjectResource> { resource ->
            try {
                state.projectManager.switchProject(resource.id)
                val project = transaction(state.database) {
                    state.projectManager.currentProject.toDetailDto()
                }
                call.respond(project)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Project not found"))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Failed to switch project: ${e.message}")
                )
            }
        }

        // Script, Preset, Cue, and Cue Stack endpoints are defined in separate files
        routeApiRestProjectScripts(state)
        routeApiRestProjectFxPresets(state)
        routeApiRestProjectCues(state)
        routeApiRestProjectCueStacks(state)
        routeApiRestProjectCueSlots(state)
        routeApiRestProjectPatches(state)
        routeApiRestProjectUniverseConfigs(state)
        routeApiRestProjectPatchGroups(state)

        // POST /{id}/clone - Clone a project with all scripts
        post<CloneProjectResource> { resource ->
            val request = call.receive<CloneProjectRequest>()

            val result = transaction(state.database) {
                val sourceProject = DaoProject.findById(resource.id)
                    ?: return@transaction null to "Source project not found"

                // Check if name is already taken
                val existingProject = DaoProject.find { DaoProjects.name eq request.name }.firstOrNull()
                if (existingProject != null) {
                    return@transaction null to "A project with name '${request.name}' already exists"
                }

                // Create new project
                val newProject = DaoProject.new {
                    name = request.name
                    description = request.description ?: sourceProject.description
                    isCurrent = false
                }

                // Clone all scripts, maintaining ID mapping
                val scriptIdMapping = mutableMapOf<Int, Int>() // old ID -> new ID
                sourceProject.scripts.forEach { sourceScript ->
                    val newScript = DaoScript.new {
                        name = sourceScript.name
                        script = sourceScript.script
                        project = newProject
                    }
                    scriptIdMapping[sourceScript.id.value] = newScript.id.value
                }

                // Clone all FX presets, maintaining ID mapping
                val presetIdMapping = mutableMapOf<Int, Int>() // old ID -> new ID
                sourceProject.fxPresets.forEach { sourcePreset ->
                    val newPreset = DaoFxPreset.new {
                        name = sourcePreset.name
                        description = sourcePreset.description
                        fixtureType = sourcePreset.fixtureType
                        project = newProject
                        effects = sourcePreset.effects
                    }
                    presetIdMapping[sourcePreset.id.value] = newPreset.id.value
                }

                // Clone all cue stacks
                val cueStackIdMapping = mutableMapOf<Int, Int>()
                sourceProject.cueStacks.forEach { sourceStack ->
                    val newStack = DaoCueStack.new {
                        name = sourceStack.name
                        project = newProject
                        palette = sourceStack.palette
                        loop = sourceStack.loop
                    }
                    cueStackIdMapping[sourceStack.id.value] = newStack.id.value
                }

                // Clone all cues, remapping preset IDs and cue stack IDs
                var cuesCloned = 0
                sourceProject.cues.forEach { sourceCue ->
                    val newStackId = sourceCue.cueStack?.id?.value?.let { cueStackIdMapping[it] }
                    val newCue = DaoCue.new {
                        name = sourceCue.name
                        project = newProject
                        palette = sourceCue.palette
                        updateGlobalPalette = sourceCue.updateGlobalPalette
                        autoAdvance = sourceCue.autoAdvance
                        autoAdvanceDelayMs = sourceCue.autoAdvanceDelayMs
                        fadeDurationMs = sourceCue.fadeDurationMs
                        fadeCurve = sourceCue.fadeCurve
                        if (newStackId != null) {
                            cueStack = DaoCueStack.findById(newStackId)
                            sortOrder = sourceCue.sortOrder
                        }
                    }
                    // Clone preset applications with remapped preset IDs
                    for (app in sourceCue.presetApplications) {
                        val newPresetId = presetIdMapping[app.preset.id.value] ?: continue
                        val newPreset = DaoFxPreset.findById(newPresetId) ?: continue
                        DaoCuePresetApplication.new {
                            cue = newCue
                            preset = newPreset
                            targets = app.targets
                        }
                    }
                    // Clone ad-hoc effects
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
                        }
                    }
                    cuesCloned++
                }

                CloneProjectResponse(
                    project = newProject.toDetailDto(),
                    scriptsCloned = scriptIdMapping.size,
                    presetsCloned = presetIdMapping.size,
                    cuesCloned = cuesCloned,
                    cueStacksCloned = cueStackIdMapping.size,
                    message = "Project cloned successfully"
                ) to null
            }

            val (response, error) = result
            if (response != null) {
                call.respond(HttpStatusCode.Created, response)
            } else {
                val statusCode = if (error == "Source project not found") {
                    HttpStatusCode.NotFound
                } else {
                    HttpStatusCode.Conflict
                }
                call.respond(statusCode, ErrorResponse(error ?: "Unknown error"))
            }
        }
    }
}

// Resource definitions
@Resource("/{id}")
data class ProjectIdResource(val id: Int)

@Resource("/{id}/set-current")
data class SetCurrentProjectResource(val id: Int)

@Resource("/{id}/clone")
data class CloneProjectResource(val id: Int)

// DTOs
@Serializable
data class ProjectListDto(
    val id: Int,
    val name: String,
    val description: String?,
    val isCurrent: Boolean,
)

@Serializable
data class ProjectDetailDto(
    val id: Int,
    val name: String,
    val description: String?,
    val isCurrent: Boolean,
    val scriptCount: Int,
    val fxPresetCount: Int,
    val cueCount: Int,
    val cueStackCount: Int,
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val description: String? = null,
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class CloneProjectRequest(
    val name: String,
    val description: String? = null
)

@Serializable
data class CloneProjectResponse(
    val project: ProjectDetailDto,
    val scriptsCloned: Int,
    val presetsCloned: Int,
    val cuesCloned: Int,
    val cueStacksCloned: Int = 0,
    val message: String
)

// ErrorResponse is defined in lightFx.kt

private enum class DeleteResult { SUCCESS, NOT_FOUND, IS_CURRENT }

// Helper extensions
private fun DaoProject.toListDto() = ProjectListDto(
    id = id.value,
    name = name,
    description = description,
    isCurrent = isCurrent,
)

private fun DaoProject.toDetailDto() = ProjectDetailDto(
    id = id.value,
    name = name,
    description = description,
    isCurrent = isCurrent,
    scriptCount = scripts.count().toInt(),
    fxPresetCount = fxPresets.count().toInt(),
    cueCount = cues.count().toInt(),
    cueStackCount = cueStacks.count().toInt(),
)

