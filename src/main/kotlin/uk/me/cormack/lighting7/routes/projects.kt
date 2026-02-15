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

                // Clear FK references first to avoid constraint violations
                project.loadFixturesScriptId = null
                project.trackChangedScriptId = null
                project.runLoopScriptId = null
                project.initialSceneId = null

                // Delete associated scenes and scripts
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

        // Script, Scene, and Preset endpoints are defined in separate files
        routeApiRestProjectScripts(state)
        routeApiRestProjectScenes(state)
        routeApiRestProjectFxPresets(state)

        // POST /current/create-initial-scene - Create initial scene template (script + scene)
        post<CreateInitialSceneResource> {
            val result = transaction(state.database) {
                val project = state.projectManager.currentProject

                // Check if initial scene already exists
                if (project.initialSceneId != null) {
                    val existingScene = DaoScene.findById(project.initialSceneId!!)
                    if (existingScene != null) {
                        return@transaction TemplateCreatedResponse(
                            scriptId = existingScene.script.id.value,
                            scriptName = existingScene.script.name,
                            sceneId = existingScene.id.value,
                            sceneName = existingScene.name,
                            message = "Initial scene already exists"
                        ) to false
                    }
                }

                // Create the script
                val script = DaoScript.new {
                    name = "initial-scene"
                    script = INITIAL_SCENE_SCRIPT_TEMPLATE
                    this.project = project
                    settings = ScriptSettingList(emptyList())
                }

                // Create the scene referencing the script
                val scene = DaoScene.new {
                    name = "Initial"
                    this.script = script
                    this.project = project
                    mode = Mode.SCENE
                    settingsValues = emptyMap()
                }

                // Update project to reference this scene
                project.initialSceneId = scene.id.value

                TemplateCreatedResponse(
                    scriptId = script.id.value,
                    scriptName = script.name,
                    sceneId = scene.id.value,
                    sceneName = scene.name,
                    message = "Initial scene created successfully"
                ) to true
            }

            val (response, created) = result
            if (created) {
                call.respond(HttpStatusCode.Created, response)
            } else {
                call.respond(HttpStatusCode.OK, response)
            }
        }

        // POST /current/create-track-changed-script - Create track changed script template
        post<CreateTrackChangedScriptResource> {
            val result = transaction(state.database) {
                val project = state.projectManager.currentProject

                // Check if track changed script already exists
                if (project.trackChangedScriptId != null) {
                    val existingScript = DaoScript.findById(project.trackChangedScriptId!!)
                    if (existingScript != null) {
                        return@transaction TemplateCreatedResponse(
                            scriptId = existingScript.id.value,
                            scriptName = existingScript.name,
                            message = "Track changed script already exists"
                        ) to false
                    }
                }

                // Create the script
                val script = DaoScript.new {
                    name = "track-changed"
                    script = TRACK_CHANGED_SCRIPT_TEMPLATE
                    this.project = project
                    settings = ScriptSettingList(emptyList())
                }

                // Update project to reference this script
                project.trackChangedScriptId = script.id.value

                TemplateCreatedResponse(
                    scriptId = script.id.value,
                    scriptName = script.name,
                    message = "Track changed script created successfully"
                ) to true
            }

            val (response, created) = result
            if (created) {
                call.respond(HttpStatusCode.Created, response)
            } else {
                call.respond(HttpStatusCode.OK, response)
            }
        }

        // POST /current/create-run-loop-script - Create run loop script template
        post<CreateRunLoopScriptResource> {
            val result = transaction(state.database) {
                val project = state.projectManager.currentProject

                // Check if run loop script already exists
                if (project.runLoopScriptId != null) {
                    val existingScript = DaoScript.findById(project.runLoopScriptId!!)
                    if (existingScript != null) {
                        return@transaction TemplateCreatedResponse(
                            scriptId = existingScript.id.value,
                            scriptName = existingScript.name,
                            message = "Run loop script already exists"
                        ) to false
                    }
                }

                // Create the script
                val script = DaoScript.new {
                    name = "run-loop"
                    script = RUN_LOOP_SCRIPT_TEMPLATE
                    this.project = project
                    settings = ScriptSettingList(emptyList())
                }

                // Update project to reference this script
                project.runLoopScriptId = script.id.value

                TemplateCreatedResponse(
                    scriptId = script.id.value,
                    scriptName = script.name,
                    message = "Run loop script created successfully"
                ) to true
            }

            val (response, created) = result
            if (created) {
                call.respond(HttpStatusCode.Created, response)
            } else {
                call.respond(HttpStatusCode.OK, response)
            }
        }

        // POST /{id}/clone - Clone a project with all scripts and scenes
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
                    runLoopDelayMs = sourceProject.runLoopDelayMs
                }

                // Clone all scripts, maintaining ID mapping
                val scriptIdMapping = mutableMapOf<Int, Int>() // old ID -> new ID
                sourceProject.scripts.forEach { sourceScript ->
                    val newScript = DaoScript.new {
                        name = sourceScript.name
                        script = sourceScript.script
                        project = newProject
                        settings = sourceScript.settings
                    }
                    scriptIdMapping[sourceScript.id.value] = newScript.id.value
                }

                // Clone all scenes, updating script FKs
                val sceneIdMapping = mutableMapOf<Int, Int>() // old ID -> new ID
                sourceProject.scenes.forEach { sourceScene ->
                    val newScriptId = scriptIdMapping[sourceScene.script.id.value]
                    val newScript = newScriptId?.let { DaoScript.findById(it) }
                        ?: return@transaction null to "Failed to map scene script"

                    val newScene = DaoScene.new {
                        name = sourceScene.name
                        script = newScript
                        project = newProject
                        mode = sourceScene.mode
                        settingsValues = sourceScene.settingsValues
                    }
                    sceneIdMapping[sourceScene.id.value] = newScene.id.value
                }

                // Update project FK references to point to cloned entities
                sourceProject.loadFixturesScriptId?.let { oldId ->
                    newProject.loadFixturesScriptId = scriptIdMapping[oldId]
                }
                sourceProject.trackChangedScriptId?.let { oldId ->
                    newProject.trackChangedScriptId = scriptIdMapping[oldId]
                }
                sourceProject.runLoopScriptId?.let { oldId ->
                    newProject.runLoopScriptId = scriptIdMapping[oldId]
                }
                sourceProject.initialSceneId?.let { oldId ->
                    newProject.initialSceneId = sceneIdMapping[oldId]
                }

                CloneProjectResponse(
                    project = newProject.toDetailDto(),
                    scriptsCloned = scriptIdMapping.size,
                    scenesCloned = sceneIdMapping.size,
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

@Resource("/current/create-initial-scene")
class CreateInitialSceneResource

@Resource("/current/create-track-changed-script")
class CreateTrackChangedScriptResource

@Resource("/current/create-run-loop-script")
class CreateRunLoopScriptResource

@Resource("/{id}/clone")
data class CloneProjectResource(val id: Int)

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
    val sceneCount: Int,
    val chaseCount: Int
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
data class TemplateCreatedResponse(
    val scriptId: Int,
    val scriptName: String,
    val sceneId: Int? = null,
    val sceneName: String? = null,
    val message: String
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
    val scenesCloned: Int,
    val message: String
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
    sceneCount = scenes.filter { it.mode == Mode.SCENE }.count(),
    chaseCount = scenes.filter { it.mode == Mode.CHASE }.count()
)

// Template for new projects
private const val LOAD_FIXTURES_TEMPLATE = """// Register your fixtures here
// Example:
//
// fixtures.register {
//     val universe = Universe(0, 1)
//     addController(ArtNetController(universe, "192.168.1.100"))
//
//     // Simple RGB fixture
//     val par1 = addFixture(HexFixture(universe, "par-1", "PAR 1", 1))
//     val par2 = addFixture(HexFixture(universe, "par-2", "PAR 2", 13))
//
//     // Multi-mode fixture (select the mode matching your DIP switch setting)
//     val beamBar = addFixture(SlenderBeamBarQuadFixture.Mode14Ch(
//         universe, "beam-bar-1", "Beam Bar 1", 25
//     ))
//
//     // Create groups for batch control
//     createGroup<HexFixture>("front-pars") {
//         addSpread(listOf(par1, par2), panSpread = 60.0)
//     }
//
//     // Group individual heads from multi-element fixtures
//     createGroup<SlenderBeamBarQuadFixture.BasicHead>("beam-heads") {
//         addElements(beamBar, panSpread = 90.0)
//     }
// }
"""

private const val INITIAL_SCENE_SCRIPT_TEMPLATE = """// Initial scene script - sets fixture states when the project loads
// This script runs once at startup to establish baseline lighting
// Example:
//
// fixtures.all<FixtureWithDimmer>().forEach { fixture ->
//     fixture.dimmer = 0.0  // Start with all lights off
// }
//
// fixtures.byKey<RgbParFixture>("par-1")?.let { par ->
//     par.dimmer = 0.5
//     par.colour = Colour.WARM_WHITE
// }
"""

private const val TRACK_CHANGED_SCRIPT_TEMPLATE = """// Track changed script - runs when the music track changes
// Use this to synchronize lighting with music playback
// Available context:
//   trackName: String? - Name of the current track
//   artistName: String? - Artist name
//   albumName: String? - Album name
//   artworkUrl: String? - URL to album artwork
//   isPlaying: Boolean - Whether music is currently playing
// Example:
//
// if (isPlaying) {
//     // React to new track
//     fixtures.all<FixtureWithDimmer>().forEach { it.dimmer = 0.8 }
// } else {
//     // Music paused/stopped
//     fixtures.all<FixtureWithDimmer>().forEach { it.dimmer = 0.2 }
// }
"""

private const val RUN_LOOP_SCRIPT_TEMPLATE = """// Run loop script - executes continuously during the show
// Use this for ongoing effects or reactive lighting
// The loop runs at the interval configured in runLoopDelayMs (default: 100ms)
// Example:
//
// // Use the FX engine for tempo-synced effects
// val bpm = fxEngine.bpm
//
// // Or implement custom timing logic
// fixtures.all<FixtureWithColour>().forEach { fixture ->
//     // Your effect logic here
// }
"""
