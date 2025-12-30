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
import uk.me.cormack.lighting7.scriptSettings.ScriptSetting
import uk.me.cormack.lighting7.scriptSettings.ScriptSettingList
import uk.me.cormack.lighting7.show.ScriptResult
import uk.me.cormack.lighting7.state.State
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.valueOrNull

internal fun Route.routeApiRestProjectScripts(state: State) {
    // GET /{projectId}/scripts - List scripts for a project
    get<ProjectScriptsResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val isCurrentProject = state.isCurrentProject(project)
        val scripts = transaction(state.database) {
            project.scripts
                .orderBy(DaoScripts.name to SortOrder.ASC)
                .map { it.toScriptDetails(isCurrentProject) }
        }
        call.respond(scripts)
    }

    // POST /{projectId}/scripts - Create new script (current project only)
    post<ProjectScriptsResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot create scripts in project '${project.name}' - only the current project can be modified")
            )
            return@post
        }

        val newScript = call.receive<NewScript>()
        val scriptDetails = transaction(state.database) {
            DaoScript.new {
                name = newScript.name
                script = newScript.script
                this.project = project
                settings = ScriptSettingList(newScript.settings.orEmpty())
            }.toScriptDetails(isCurrentProject = true) // Only current project can create
        }
        call.respond(HttpStatusCode.Created, scriptDetails)
    }

    // GET /{projectId}/scripts/{scriptId} - Get script details (any project)
    get<ProjectScriptResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val isCurrentProject = state.isCurrentProject(project)
        val script = transaction(state.database) {
            val script = DaoScript.findById(resource.scriptId)
                ?: return@transaction null

            // Verify script belongs to this project
            if (script.project.id != project.id) {
                return@transaction null
            }

            script.toScriptDetails(isCurrentProject)
        }

        if (script != null) {
            call.respond(script)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Script not found"))
        }
    }

    // PUT /{projectId}/scripts/{scriptId} - Update script (current project only)
    put<ProjectScriptResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@put
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot modify scripts in project '${project.name}' - only the current project can be modified")
            )
            return@put
        }

        val script = transaction(state.database) {
            DaoScript.findById(resource.scriptId)
        }
        if (script == null || script.project.id != project.id) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Script not found"))
            return@put
        }

        val newScriptData = call.receive<NewScript>()
        val scriptDetails = transaction(state.database) {
            script.name = newScriptData.name
            script.script = newScriptData.script
            script.settings = ScriptSettingList(newScriptData.settings.orEmpty())
            script.toScriptDetails(isCurrentProject = true) // Only current project can update
        }
        call.respond(scriptDetails)
    }

    // DELETE /{projectId}/scripts/{scriptId} - Delete script (current project only)
    delete<ProjectScriptResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@delete
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot delete scripts in project '${project.name}' - only the current project can be modified")
            )
            return@delete
        }

        val result = transaction(state.database) {
            val script = DaoScript.findById(resource.scriptId)
                ?: return@transaction ScriptDeleteResult.NOT_FOUND

            // Verify script belongs to this project
            if (script.project.id != project.id) {
                return@transaction ScriptDeleteResult.NOT_FOUND
            }

            // Check if used as loadFixturesScript (required, cannot delete)
            val usedAsLoadFixtures = DaoProject.find {
                DaoProjects.loadFixturesScriptId eq script.id.value
            }.count() > 0

            if (usedAsLoadFixtures) {
                return@transaction ScriptDeleteResult.BLOCKED_REQUIRED_PROPERTY
            }

            // Nullify optional project properties
            DaoProject.find { DaoProjects.trackChangedScriptId eq script.id.value }
                .forEach { it.trackChangedScriptId = null }
            DaoProject.find { DaoProjects.runLoopScriptId eq script.id.value }
                .forEach { it.runLoopScriptId = null }

            // Cascade delete scenes
            script.scenes.forEach { it.delete() }

            // Delete the script
            script.delete()
            ScriptDeleteResult.SUCCESS
        }

        when (result) {
            ScriptDeleteResult.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Script not found"))
            ScriptDeleteResult.BLOCKED_REQUIRED_PROPERTY -> call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Script is used as loadFixturesScript and cannot be deleted")
            )
            ScriptDeleteResult.SUCCESS -> call.respond(HttpStatusCode.OK)
        }
    }

    // POST /{projectId}/scripts/compile - Compile literal script (current project only)
    post<ProjectScriptCompileResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot compile scripts for project '${project.name}' - only the current project can be used")
            )
            return@post
        }

        val literal = call.receive<ScriptLiteral>()
        var response: CompileResult? = null
        GlobalScope.launch {
            response = state.show.compileLiteralScript(literal.script, literal.settings.orEmpty()).toCompileResult()
        }.join()
        call.respond(checkNotNull(response))
    }

    // POST /{projectId}/scripts/run - Run literal script (current project only)
    post<ProjectScriptRunResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (!state.isCurrentProject(project)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("Cannot run scripts for project '${project.name}' - only the current project can be used")
            )
            return@post
        }

        val literal = call.receive<ScriptLiteral>()
        var response: RunResult? = null
        GlobalScope.launch {
            response = state.show.runLiteralScript(literal.script, literal.settings.orEmpty()).toRunResult()
        }.join()
        call.respond(checkNotNull(response))
    }

    // POST /{projectId}/scripts/{scriptId}/copy - Copy a script to another project
    post<CopyScriptResource> { resource ->
        val sourceProject = state.resolveProject(resource.parent.projectId)
        if (sourceProject == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Source project not found"))
            return@post
        }

        val request = call.receive<CopyScriptRequest>()

        val result = transaction(state.database) {
            val sourceScript = DaoScript.findById(resource.scriptId)
                ?: return@transaction null to "Script not found"

            // Verify script belongs to source project
            if (sourceScript.project.id != sourceProject.id) {
                return@transaction null to "Script does not belong to specified project"
            }

            // Find target project
            val targetProject = DaoProject.findById(request.targetProjectId)
                ?: return@transaction null to "Target project not found"

            // Determine script name (use provided name or source name)
            val scriptName = request.newName ?: sourceScript.name

            // Check for name conflict in target project
            val existingScript = DaoScript.find {
                (DaoScripts.project eq targetProject.id) and (DaoScripts.name eq scriptName)
            }.firstOrNull()
            if (existingScript != null) {
                return@transaction null to "A script with name '$scriptName' already exists in target project"
            }

            // Create new script in target project
            val newScript = DaoScript.new {
                name = scriptName
                script = sourceScript.script
                project = targetProject
                settings = sourceScript.settings
            }

            CopyScriptResponse(
                scriptId = newScript.id.value,
                scriptName = newScript.name,
                targetProjectId = targetProject.id.value,
                targetProjectName = targetProject.name,
                message = "Script copied successfully"
            ) to null
        }

        val (response, error) = result
        if (response != null) {
            call.respond(HttpStatusCode.Created, response)
        } else {
            val statusCode = when (error) {
                "Script not found", "Target project not found" -> HttpStatusCode.NotFound
                "Script does not belong to specified project" -> HttpStatusCode.BadRequest
                else -> HttpStatusCode.Conflict
            }
            call.respond(statusCode, ErrorResponse(error ?: "Unknown error"))
        }
    }
}

// Resource classes
@Resource("/{projectId}/scripts")
data class ProjectScriptsResource(val projectId: String)

@Resource("/{scriptId}")
data class ProjectScriptResource(val parent: ProjectScriptsResource, val scriptId: Int)

@Resource("/compile")
data class ProjectScriptCompileResource(val parent: ProjectScriptsResource)

@Resource("/run")
data class ProjectScriptRunResource(val parent: ProjectScriptsResource)

@Resource("/{scriptId}/copy")
data class CopyScriptResource(val parent: ProjectScriptsResource, val scriptId: Int)

// DTOs
@Serializable
data class ScriptLiteral(val script: String, val settings: List<ScriptSetting<*>>? = null)

@Serializable
data class NewScript(val name: String, val script: String, val settings: List<ScriptSetting<*>>?)

@Serializable
data class ScriptDetails(
    val id: Int,
    val name: String,
    val script: String,
    val settings: List<ScriptSetting<*>>,
    val sceneNames: List<String>,
    val chaseNames: List<String>,
    val usedByProperties: List<String>,
    val canEdit: Boolean,
    val cannotEditReason: String? = null,
    val canDelete: Boolean,
    val cannotDeleteReason: String? = null,
)

@Serializable
data class ScriptRunMessage(
    val severity: String,
    val message: String,
    val sourcePath: String?,
    val location: String?
)

@Serializable
data class RunResult(val status: String, val messages: List<ScriptRunMessage>, val result: String? = null)

@Serializable
data class CompileResult(val success: Boolean, val messages: List<ScriptRunMessage>)

@Serializable
data class ScriptSummaryDto(
    val id: Int,
    val name: String,
    val settingsCount: Int
)

@Serializable
data class CopyScriptRequest(
    val targetProjectId: Int,
    val newName: String? = null
)

@Serializable
data class CopyScriptResponse(
    val scriptId: Int,
    val scriptName: String,
    val targetProjectId: Int,
    val targetProjectName: String,
    val message: String
)

// Enums
private enum class ScriptDeleteResult { SUCCESS, NOT_FOUND, BLOCKED_REQUIRED_PROPERTY }

// Helper functions
internal fun DaoScript.toScriptDetails(isCurrentProject: Boolean): ScriptDetails {
    val allScenes = this.scenes.toList()
    val sceneNames = allScenes.filter { it.mode == Mode.SCENE }.map { it.name }
    val chaseNames = allScenes.filter { it.mode == Mode.CHASE }.map { it.name }

    val usedByProperties = mutableListOf<String>()
    val isLoadFixtures = DaoProject.find { DaoProjects.loadFixturesScriptId eq this@toScriptDetails.id.value }.count() > 0
    if (isLoadFixtures) usedByProperties.add("loadFixturesScript")
    if (DaoProject.find { DaoProjects.trackChangedScriptId eq this@toScriptDetails.id.value }.count() > 0) {
        usedByProperties.add("trackChangedScript")
    }
    if (DaoProject.find { DaoProjects.runLoopScriptId eq this@toScriptDetails.id.value }.count() > 0) {
        usedByProperties.add("runLoopScript")
    }

    val canEdit = isCurrentProject
    val cannotEditReason = if (!isCurrentProject) "Cannot edit scripts from a non-current project" else null

    val canDelete: Boolean
    val cannotDeleteReason: String?
    when {
        !isCurrentProject -> {
            canDelete = false
            cannotDeleteReason = "Cannot delete scripts from a non-current project"
        }
        isLoadFixtures -> {
            canDelete = false
            cannotDeleteReason = "Script is used as loadFixturesScript (required)"
        }
        else -> {
            canDelete = true
            cannotDeleteReason = null
        }
    }

    return ScriptDetails(
        id = this.id.value,
        name = this.name,
        script = this.script,
        settings = this.settings?.list.orEmpty(),
        sceneNames = sceneNames,
        chaseNames = chaseNames,
        usedByProperties = usedByProperties,
        canEdit = canEdit,
        cannotEditReason = cannotEditReason,
        canDelete = canDelete,
        cannotDeleteReason = cannotDeleteReason,
    )
}

internal fun List<ScriptDiagnostic>.toMessages(): List<ScriptRunMessage> = filter {
    it.severity != ScriptDiagnostic.Severity.DEBUG
}.map {
    ScriptRunMessage(
        it.severity.name,
        it.message,
        it.sourcePath,
        it.location?.let { location ->
            "${location.start.line}:${location.start.col}"
        }
    )
}

internal fun ResultWithDiagnostics<*>.isSuccess(): Boolean {
    return this.valueOrNull() != null
}

fun ScriptResult.toCompileResult(): CompileResult {
    return CompileResult(
        this.compileResult.isSuccess(),
        compileResult.reports.toMessages(),
    )
}

fun ScriptResult.toRunResult(): RunResult {
    return if (runResult == null) {
        RunResult(
            "compileError",
            compileResult.reports.toMessages(),
        )
    } else {
        val evalResult = runResult.valueOrNull()
        if (evalResult == null) {
            RunResult(
                "exception",
                compileResult.reports.toMessages(),
            )
        } else {
            val returnValue = evalResult.returnValue
            if (returnValue is ResultValue.Error) {
                RunResult(
                    "exception",
                    compileResult.reports.toMessages(),
                    "${returnValue.error}\n${returnValue.error.stackTraceToString()}"
                )
            } else {
                RunResult(
                    "success",
                    compileResult.reports.toMessages(),
                    returnValue.toString(),
                )
            }
        }
    }
}
