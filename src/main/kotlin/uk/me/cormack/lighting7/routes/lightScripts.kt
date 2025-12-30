package uk.me.cormack.lighting7.routes

import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import io.ktor.http.*
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.Mode
import uk.me.cormack.lighting7.scriptSettings.ScriptSetting
import uk.me.cormack.lighting7.scriptSettings.ScriptSettingList
import uk.me.cormack.lighting7.show.ScriptResult
import uk.me.cormack.lighting7.state.State
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.valueOrNull

@OptIn(DelicateCoroutinesApi::class)
internal fun Route.routeApiRestLightsScript(state: State) {
    route("/script") {
        get("/list") {
            val scripts = transaction(state.database) {
                state.show.project.scripts.toList().map { it.toScriptDetails() }
            }
            call.respond(scripts)
        }

        post("/compile") {
            val literal = call.receive<ScriptLiteral>()

            var response: CompileResult? = null

            GlobalScope.launch {
                response = state.show.compileLiteralScript(literal.script, literal.settings.orEmpty()).toCompileResult()
            }.join()

            call.respond(checkNotNull(response))
        }

        post("/run") {
            val literal = call.receive<ScriptLiteral>()

            var response: RunResult? = null

            GlobalScope.launch {
                response = state.show.runLiteralScript(literal.script, literal.settings.orEmpty()).toRunResult()
            }.join()

            call.respond(checkNotNull(response))
        }

        post {
            val newScript = call.receive<NewScript>()
            val scriptDetails = transaction(state.database) {
                DaoScript.new {
                    name = newScript.name
                    script = newScript.script
                    project = state.show.project
                    settings = ScriptSettingList(newScript.settings.orEmpty())
                }.toScriptDetails()
            }
            call.respond(scriptDetails)
        }

        get<ScriptResource> {
            val scriptDetails = transaction(state.database) {
                DaoScript.findById(it.id)?.toScriptDetails()
            }
            if (scriptDetails != null) {
                call.respond(scriptDetails)
            }
        }

        put<ScriptResource> {
            val newScriptData = call.receive<NewScript>()
            val scriptDetails = transaction(state.database) {
                val script = DaoScript.findById(it.id) ?: throw Error("Script not found")
                script.name = newScriptData.name
                script.script = newScriptData.script
                script.settings = ScriptSettingList(newScriptData.settings.orEmpty())
                script.toScriptDetails()
            }
            call.respond(scriptDetails)
        }

        delete<ScriptResource> {
            val result = transaction(state.database) {
                val script = DaoScript.findById(it.id)
                    ?: return@transaction ScriptDeleteResult.NOT_FOUND

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
                ScriptDeleteResult.NOT_FOUND -> call.respond(HttpStatusCode.NotFound)
                ScriptDeleteResult.BLOCKED_REQUIRED_PROPERTY -> call.respond(
                    HttpStatusCode.Conflict,
                    DeleteErrorResponse("Script is used as loadFixturesScript and cannot be deleted")
                )
                ScriptDeleteResult.SUCCESS -> call.respond(HttpStatusCode.OK)
            }
        }
    }
}

@Resource("/{id}")
data class ScriptResource(val id: Int)

internal fun DaoScript.toScriptDetails(): ScriptDetails {
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

    val canDelete = !isLoadFixtures
    val cannotDeleteReason = if (isLoadFixtures) "Script is used as loadFixturesScript (required)" else null

    return ScriptDetails(
        id = this.id.value,
        name = this.name,
        script = this.script,
        settings = this.settings?.list.orEmpty(),
        sceneNames = sceneNames,
        chaseNames = chaseNames,
        usedByProperties = usedByProperties,
        canDelete = canDelete,
        cannotDeleteReason = cannotDeleteReason,
    )
}

private enum class ScriptDeleteResult {
    SUCCESS, NOT_FOUND, BLOCKED_REQUIRED_PROPERTY
}

@Serializable
data class DeleteErrorResponse(val error: String)

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
    val canDelete: Boolean,
    val cannotDeleteReason: String? = null,
)

@Serializable
data class ScriptRunMessage(val severity: String,
                            val message: String,
                            val sourcePath: String?,
                            val location: String?)

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

@Serializable
data class RunResult(val status: String, val messages: List<ScriptRunMessage>, val result: String? = null)

@Serializable
data class CompileResult(val success: Boolean, val messages: List<ScriptRunMessage>)
