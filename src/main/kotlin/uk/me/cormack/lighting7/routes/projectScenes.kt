@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.scriptSettings.IntValue
import uk.me.cormack.lighting7.scriptSettings.ScriptSettingValue
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectScenes(state: State) {
    // GET /{projectId}/scenes - List scenes for a project (with optional mode filter)
    get<ProjectScenesResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val isCurrentProject = state.isCurrentProject(project)
        val scenes = transaction(state.database) {
            val query = if (resource.mode != null) {
                DaoScene.find { (DaoScenes.project eq project.id) and (DaoScenes.mode eq resource.mode) }
            } else {
                project.scenes
            }
            query.orderBy(DaoScenes.id to SortOrder.DESC).toList().map { it.details(state.show, isCurrentProject) }
        }
        call.respond(scenes)
    }

    // POST /{projectId}/scenes - Create new scene (current project only)
    post<ProjectScenesResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot create scenes in project '${project.name}' - only the current project can be modified")
            )
            return@post
        }

        val newScene = call.receive<NewScene>()
        val sceneDetails = transaction(state.database) {
            val sceneScript = DaoScript.findById(newScene.scriptId)
                ?: throw Error("Script not found")
            if (sceneScript.project.id != project.id) {
                throw Error("Script does not belong to this project")
            }
            DaoScene.new {
                mode = newScene.mode
                name = newScene.name
                script = sceneScript
                this.project = project
                settingsValues = newScene.settingsValues
            }.details(state.show, isCurrentProject = true) // Only current project can create
        }
        state.show.fixtures.sceneListChanged()
        call.respond(HttpStatusCode.Created, sceneDetails)
    }

    // GET /{projectId}/scenes/{sceneId} - Get scene details (any project)
    get<ProjectSceneResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val isCurrentProject = state.isCurrentProject(project)
        val scene = transaction(state.database) {
            val scene = DaoScene.findById(resource.sceneId)
                ?: return@transaction null

            // Verify scene belongs to this project
            if (scene.project.id != project.id) {
                return@transaction null
            }

            scene.details(state.show, isCurrentProject)
        }

        if (scene != null) {
            call.respond(scene)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Scene not found"))
        }
    }

    // PUT /{projectId}/scenes/{sceneId} - Update scene (current project only)
    put<ProjectSceneResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@put
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot modify scenes in project '${project.name}' - only the current project can be modified")
            )
            return@put
        }

        val newSceneDetails = call.receive<NewScene>()
        val sceneDetails = transaction(state.database) {
            val scene = DaoScene.findById(resource.sceneId)
                ?: throw Error("Scene not found")

            // Verify scene belongs to this project
            if (scene.project.id != project.id) {
                throw Error("Scene not found")
            }

            val sceneScript = DaoScript.findById(newSceneDetails.scriptId)
                ?: throw Error("Script not found")
            if (sceneScript.project.id != project.id) {
                throw Error("Script does not belong to this project")
            }

            scene.mode = newSceneDetails.mode
            scene.name = newSceneDetails.name
            scene.script = sceneScript
            scene.settingsValues = newSceneDetails.settingsValues

            scene.details(state.show, isCurrentProject = true) // Only current project can update
        }
        state.show.fixtures.sceneChanged(sceneDetails.id)
        call.respond(sceneDetails)
    }

    // DELETE /{projectId}/scenes/{sceneId} - Delete scene (current project only)
    delete<ProjectSceneResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@delete
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot delete scenes in project '${project.name}' - only the current project can be modified")
            )
            return@delete
        }

        val deleted = transaction(state.database) {
            val scene = DaoScene.findById(resource.sceneId)
                ?: return@transaction false

            // Verify scene belongs to this project
            if (scene.project.id != project.id) {
                return@transaction false
            }

            scene.delete()
            true
        }

        if (deleted) {
            state.show.fixtures.sceneListChanged()
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Scene not found"))
        }
    }

    // POST /{projectId}/scenes/{sceneId}/run - Run scene (current project only)
    post<ProjectSceneRunResource> { resource ->
        val project = state.resolveProject(resource.parent.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot run scenes from project '${project.name}' - only the current project can be used")
            )
            return@post
        }

        // Verify scene belongs to this project
        val sceneExists = transaction(state.database) {
            val scene = DaoScene.findById(resource.parent.sceneId) ?: return@transaction false
            scene.project.id == project.id
        }

        if (!sceneExists) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Scene not found"))
            return@post
        }

        var response: RunResult? = null
        GlobalScope.launch {
            response = state.show.runScene(resource.parent.sceneId).toRunResult()
        }.join()
        call.respond(checkNotNull(response))
    }
}

// Resource classes
@Resource("/{projectId}/scenes")
data class ProjectScenesResource(val projectId: String, val mode: Mode? = null)

@Resource("/{sceneId}")
data class ProjectSceneResource(val parent: ProjectScenesResource, val sceneId: Int)

@Resource("/run")
data class ProjectSceneRunResource(val parent: ProjectSceneResource)

// DTOs
@Serializable
data class NewScene(
    val mode: Mode,
    val name: String,
    val scriptId: Int,
    val settingsValues: Map<String, IntValue>,
)

@Serializable
data class SceneDetails(
    val id: Int,
    val mode: Mode,
    val name: String,
    val scriptId: Int,
    val isActive: Boolean,
    val settingsValues: Map<String, ScriptSettingValue>,
    val canEdit: Boolean,
    val cannotEditReason: String? = null,
    val canDelete: Boolean,
    val cannotDeleteReason: String? = null,
)

@Serializable
data class SceneSummaryDto(
    val id: Int,
    val name: String,
    val mode: String,
    val scriptName: String
)

// Helper functions
internal fun DaoScene.details(show: Show, isCurrentProject: Boolean): SceneDetails {
    val canEdit = isCurrentProject
    val cannotEditReason = if (!isCurrentProject) "Cannot edit scenes from a non-current project" else null
    val canDelete = isCurrentProject
    val cannotDeleteReason = if (!isCurrentProject) "Cannot delete scenes from a non-current project" else null

    return SceneDetails(
        this.id.value,
        this.mode,
        this.name,
        this.script.id.value,
        show.fixtures.isSceneActive(this.id.value),
        this.settingsValues.orEmpty(),
        canEdit,
        cannotEditReason,
        canDelete,
        cannotDeleteReason,
    )
}
