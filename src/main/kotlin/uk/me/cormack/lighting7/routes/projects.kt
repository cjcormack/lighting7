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
import uk.me.cormack.lighting7.scriptSettings.ScriptSettingList
import uk.me.cormack.lighting7.state.State

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

        // POST / - Create new project with template scripts
        routingPost {
            val request = call.receive<CreateProjectRequest>()
            val project = transaction(state.database) {
                val newProject = DaoProject.new {
                    name = request.name
                    description = request.description
                    isCurrent = false
                }

                // Create template load-fixtures script
                val loadFixturesScript = DaoScript.new {
                    name = "load-fixtures"
                    script = LOAD_FIXTURES_TEMPLATE
                    project = newProject
                    settings = ScriptSettingList(emptyList())
                }
                newProject.loadFixturesScriptId = loadFixturesScript.id.value

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
                request.runLoopDelayMs?.let { project.runLoopDelayMs = it }

                // Update FK references if provided (use ID directly)
                if (request.loadFixturesScriptId != null && request.loadFixturesScriptId > 0) {
                    val script = DaoScript.findById(request.loadFixturesScriptId)
                    if (script != null && script.project.id == project.id) {
                        project.loadFixturesScriptId = script.id.value
                    }
                }
                if (request.initialSceneId != null && request.initialSceneId > 0) {
                    val scene = DaoScene.findById(request.initialSceneId)
                    if (scene != null && scene.project.id == project.id) {
                        project.initialSceneId = scene.id.value
                    }
                }
                if (request.trackChangedScriptId != null && request.trackChangedScriptId > 0) {
                    val script = DaoScript.findById(request.trackChangedScriptId)
                    if (script != null && script.project.id == project.id) {
                        project.trackChangedScriptId = script.id.value
                    }
                }
                if (request.runLoopScriptId != null && request.runLoopScriptId > 0) {
                    val script = DaoScript.findById(request.runLoopScriptId)
                    if (script != null && script.project.id == project.id) {
                        project.runLoopScriptId = script.id.value
                    }
                }

                // Handle clearing FK references (pass 0 to clear)
                if (request.loadFixturesScriptId == 0) project.loadFixturesScriptId = null
                if (request.initialSceneId == 0) project.initialSceneId = null
                if (request.trackChangedScriptId == 0) project.trackChangedScriptId = null
                if (request.runLoopScriptId == 0) project.runLoopScriptId = null

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

                // Delete associated scenes and scripts first
                project.scenes.forEach { it.delete() }
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

        // GET /{id}/scripts - List scripts for any project (read-only)
        get<ProjectScriptsResource> { resource ->
            val scripts = transaction(state.database) {
                val project = DaoProject.findById(resource.id)
                    ?: return@transaction null

                project.scripts.toList().map { script ->
                    ScriptSummaryDto(
                        id = script.id.value,
                        name = script.name,
                        settingsCount = script.settings?.list?.size ?: 0
                    )
                }
            }

            if (scripts != null) {
                call.respond(scripts)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            }
        }

        // GET /{id}/scripts/{scriptId} - Get specific script from any project (read-only)
        get<ProjectScriptResource> { resource ->
            val script = transaction(state.database) {
                val project = DaoProject.findById(resource.parent.id)
                    ?: return@transaction null

                val script = DaoScript.findById(resource.scriptId)
                    ?: return@transaction null

                // Verify script belongs to this project
                if (script.project.id != project.id) {
                    return@transaction null
                }

                ScriptDetails(
                    id = script.id.value,
                    name = script.name,
                    script = script.script,
                    settings = script.settings?.list.orEmpty()
                )
            }

            if (script != null) {
                call.respond(script)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Script not found"))
            }
        }

        // GET /{id}/scenes - List scenes for any project (read-only)
        get<ProjectScenesResource> { resource ->
            val scenes = transaction(state.database) {
                val project = DaoProject.findById(resource.id)
                    ?: return@transaction null

                project.scenes.toList().map { scene ->
                    SceneSummaryDto(
                        id = scene.id.value,
                        name = scene.name,
                        mode = scene.mode.name,
                        scriptName = scene.script.name
                    )
                }
            }

            if (scenes != null) {
                call.respond(scenes)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            }
        }
    }
}

// Resource definitions
@Resource("/{id}")
data class ProjectIdResource(val id: Int)

@Resource("/{id}/set-current")
data class SetCurrentProjectResource(val id: Int)

@Resource("/{id}/scripts")
data class ProjectScriptsResource(val id: Int)

@Resource("/{scriptId}")
data class ProjectScriptResource(val parent: ProjectScriptsResource, val scriptId: Int)

@Resource("/{id}/scenes")
data class ProjectScenesResource(val id: Int)

// DTOs
@Serializable
data class ProjectListDto(
    val id: Int,
    val name: String,
    val description: String?,
    val isCurrent: Boolean
)

@Serializable
data class ProjectDetailDto(
    val id: Int,
    val name: String,
    val description: String?,
    val isCurrent: Boolean,
    val loadFixturesScriptId: Int?,
    val loadFixturesScriptName: String?,
    val initialSceneId: Int?,
    val initialSceneName: String?,
    val trackChangedScriptId: Int?,
    val trackChangedScriptName: String?,
    val runLoopScriptId: Int?,
    val runLoopScriptName: String?,
    val runLoopDelayMs: Long,
    val scriptCount: Int,
    val sceneCount: Int
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val description: String? = null
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val description: String? = null,
    val loadFixturesScriptId: Int? = null,
    val initialSceneId: Int? = null,
    val trackChangedScriptId: Int? = null,
    val runLoopScriptId: Int? = null,
    val runLoopDelayMs: Long? = null
)

@Serializable
data class ScriptSummaryDto(
    val id: Int,
    val name: String,
    val settingsCount: Int
)

@Serializable
data class SceneSummaryDto(
    val id: Int,
    val name: String,
    val mode: String,
    val scriptName: String
)

// ErrorResponse is defined in lightFx.kt

private enum class DeleteResult { SUCCESS, NOT_FOUND, IS_CURRENT }

// Helper extensions
private fun DaoProject.toListDto() = ProjectListDto(
    id = id.value,
    name = name,
    description = description,
    isCurrent = isCurrent
)

private fun DaoProject.toDetailDto() = ProjectDetailDto(
    id = id.value,
    name = name,
    description = description,
    isCurrent = isCurrent,
    loadFixturesScriptId = loadFixturesScriptId,
    loadFixturesScriptName = loadFixturesScriptId?.let { DaoScript.findById(it)?.name },
    initialSceneId = initialSceneId,
    initialSceneName = initialSceneId?.let { DaoScene.findById(it)?.name },
    trackChangedScriptId = trackChangedScriptId,
    trackChangedScriptName = trackChangedScriptId?.let { DaoScript.findById(it)?.name },
    runLoopScriptId = runLoopScriptId,
    runLoopScriptName = runLoopScriptId?.let { DaoScript.findById(it)?.name },
    runLoopDelayMs = runLoopDelayMs,
    scriptCount = scripts.count().toInt(),
    sceneCount = scenes.count().toInt()
)

// Template for new projects
private const val LOAD_FIXTURES_TEMPLATE = """// Register your fixtures here
// Example:
//
// fixtures.register {
//     val universe = Universe(0, 1)
//     addController(ArtNetController(universe, "192.168.1.100"))
//
//     val par1 = addFixture(RgbParFixture(universe, "par-1", "PAR 1", 1, 1))
//     val par2 = addFixture(RgbParFixture(universe, "par-2", "PAR 2", 8, 2))
//
//     createGroup<RgbParFixture>("front-pars") {
//         addSpread(listOf(par1, par2), panSpread = 60.0)
//     }
// }
"""
