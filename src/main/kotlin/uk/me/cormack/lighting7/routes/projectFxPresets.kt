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
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectFxPresets(state: State) {
    // GET /{projectId}/fx-presets - List presets for a project
    get<ProjectFxPresetsResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val isCurrentProject = state.isCurrentProject(project)
        val presets = transaction(state.database) {
            DaoFxPreset.find { DaoFxPresets.project eq project.id }
                .orderBy(DaoFxPresets.name to SortOrder.ASC)
                .map { it.toPresetDetails(isCurrentProject) }
        }
        call.respond(presets)
    }

    // POST /{projectId}/fx-presets - Create new preset (current project only)
    post<ProjectFxPresetsResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot create presets in project '${project.name}' - only the current project can be modified")
            )
            return@post
        }

        val newPreset = call.receive<NewFxPreset>()
        val presetDetails = transaction(state.database) {
            DaoFxPreset.new {
                name = newPreset.name
                description = newPreset.description
                fixtureType = newPreset.fixtureType
                this.project = project
                effects = newPreset.effects
            }.toPresetDetails(isCurrentProject = true)
        }
        state.show.fixtures.presetListChanged()
        call.respond(HttpStatusCode.Created, presetDetails)
    }

    // GET /{projectId}/fx-presets/{presetId} - Get preset details (any project)
    get<ProjectFxPresetResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val isCurrentProject = state.isCurrentProject(project)
        val preset = transaction(state.database) {
            val preset = DaoFxPreset.findById(resource.presetId)
                ?: return@transaction null

            if (preset.project.id != project.id) {
                return@transaction null
            }

            preset.toPresetDetails(isCurrentProject)
        }

        if (preset != null) {
            call.respond(preset)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Preset not found"))
        }
    }

    // PUT /{projectId}/fx-presets/{presetId} - Update preset (current project only)
    put<ProjectFxPresetResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@put
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot modify presets in project '${project.name}' - only the current project can be modified")
            )
            return@put
        }

        val updatedData = call.receive<NewFxPreset>()
        val presetDetails = transaction(state.database) {
            val preset = DaoFxPreset.findById(resource.presetId)
                ?: return@transaction null

            if (preset.project.id != project.id) {
                return@transaction null
            }

            preset.name = updatedData.name
            preset.description = updatedData.description
            preset.fixtureType = updatedData.fixtureType
            preset.effects = updatedData.effects
            preset.toPresetDetails(isCurrentProject = true)
        }

        if (presetDetails != null) {
            state.show.fixtures.presetListChanged()
            call.respond(presetDetails)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Preset not found"))
        }
    }

    // DELETE /{projectId}/fx-presets/{presetId} - Delete preset (current project only)
    delete<ProjectFxPresetResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@delete
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot delete presets in project '${project.name}' - only the current project can be modified")
            )
            return@delete
        }

        val found = transaction(state.database) {
            val preset = DaoFxPreset.findById(resource.presetId)
                ?: return@transaction false

            if (preset.project.id != project.id) {
                return@transaction false
            }

            preset.delete()
            true
        }

        if (found) {
            state.show.fixtures.presetListChanged()
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Preset not found"))
        }
    }

    // POST /{projectId}/fx-presets/{presetId}/copy - Copy preset to another project
    post<CopyFxPresetResource> { resource ->
        val sourceProject = state.resolveProject(resource.parent.projectId)
        if (sourceProject == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Source project not found"))
            return@post
        }

        val request = call.receive<CopyPresetRequest>()

        val result = transaction(state.database) {
            val sourcePreset = DaoFxPreset.findById(resource.presetId)
                ?: return@transaction null to "Preset not found"

            if (sourcePreset.project.id != sourceProject.id) {
                return@transaction null to "Preset does not belong to specified project"
            }

            val targetProject = DaoProject.findById(request.targetProjectId)
                ?: return@transaction null to "Target project not found"

            val presetName = request.newName ?: sourcePreset.name

            val sourceFixtureType = sourcePreset.fixtureType
            val existingPreset = DaoFxPreset.find {
                (DaoFxPresets.project eq targetProject.id) and
                (DaoFxPresets.name eq presetName) and
                (DaoFxPresets.fixtureType eq sourceFixtureType)
            }.firstOrNull()
            if (existingPreset != null) {
                return@transaction null to "A preset with name '$presetName' already exists in target project"
            }

            val newPreset = DaoFxPreset.new {
                name = presetName
                description = sourcePreset.description
                fixtureType = sourceFixtureType
                project = targetProject
                effects = sourcePreset.effects
            }

            CopyPresetResponse(
                presetId = newPreset.id.value,
                presetName = newPreset.name,
                targetProjectId = targetProject.id.value,
                targetProjectName = targetProject.name,
                message = "Preset copied successfully"
            ) to null
        }

        val (response, error) = result
        if (response != null) {
            state.show.fixtures.presetListChanged()
            call.respond(HttpStatusCode.Created, response)
        } else {
            val statusCode = when (error) {
                "Preset not found", "Target project not found" -> HttpStatusCode.NotFound
                "Preset does not belong to specified project" -> HttpStatusCode.BadRequest
                else -> HttpStatusCode.Conflict
            }
            call.respond(statusCode, ErrorResponse(error ?: "Unknown error"))
        }
    }
}

// Resource classes
@Resource("/{projectId}/fx-presets")
data class ProjectFxPresetsResource(val projectId: String)

@Resource("/{presetId}")
data class ProjectFxPresetResource(val parent: ProjectFxPresetsResource, val presetId: Int)

@Resource("/{presetId}/copy")
data class CopyFxPresetResource(val parent: ProjectFxPresetsResource, val presetId: Int)

// DTOs
@Serializable
data class NewFxPreset(
    val name: String,
    val description: String? = null,
    val fixtureType: String? = null,
    val effects: List<FxPresetEffectDto>,
)

@Serializable
data class FxPresetDetails(
    val id: Int,
    val name: String,
    val description: String?,
    val fixtureType: String?,
    val effects: List<FxPresetEffectDto>,
    val canEdit: Boolean,
    val canDelete: Boolean,
)

@Serializable
data class CopyPresetRequest(
    val targetProjectId: Int,
    val newName: String? = null,
)

@Serializable
data class CopyPresetResponse(
    val presetId: Int,
    val presetName: String,
    val targetProjectId: Int,
    val targetProjectName: String,
    val message: String,
)

// Helper
internal fun DaoFxPreset.toPresetDetails(isCurrentProject: Boolean): FxPresetDetails {
    return FxPresetDetails(
        id = this.id.value,
        name = this.name,
        description = this.description,
        fixtureType = this.fixtureType,
        effects = this.effects,
        canEdit = isCurrentProject,
        canDelete = isCurrentProject,
    )
}
