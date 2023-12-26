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
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.show.ScriptResult
import uk.me.cormack.lighting7.state.State
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.valueOrNull

@OptIn(DelicateCoroutinesApi::class)
internal fun Route.routeApiRestScript(state: State) {
    route("/script") {
        get("/list") {
            val scripts = transaction(state.database) {
                state.show.project.scripts.toList().map {
                    ScriptDetails(it.id.value, it.name, it.script)
                }
            }
            call.respond(scripts)
        }

        post("/compile") {
            val script = call.receive<String>()

            var response: CompileResult? = null

            GlobalScope.launch {
                response = state.show.compileLiteralScript(script).toCompileResult()
            }.join()

            call.respond(checkNotNull(response))
        }

        post("/run") {
            val script = call.receive<String>()

            var response: RunResult? = null

            GlobalScope.launch {
                response = state.show.runLiteralScript(script).toRunResult()
            }.join()

            call.respond(checkNotNull(response))
        }

        post {
            val newScript = call.receive<NewScript>()
            val script = transaction(state.database) {
                DaoScript.new {
                    name = newScript.name
                    script = newScript.script
                    project = state.show.project
                }
            }
            call.respond(ScriptDetails(script.id.value, script.name, script.script))
        }

        get<ScriptResource> {
            val script = transaction(state.database) {
                DaoScript.findById(it.id)
            }
            if (script != null) {
                call.respond(ScriptDetails(script.id.value, script.name, script.script))
            }
        }

        put<ScriptResource> {
            val newScriptDetails = call.receive<NewScript>()
            val script = transaction(state.database) {
                val script = DaoScript.findById(it.id) ?: throw Error("Script not found")
                script.name = newScriptDetails.name
                script.script = newScriptDetails.script

                script
            }
            call.respond(ScriptDetails(script.id.value, script.name, script.script))
        }

        delete<ScriptResource> {
            transaction(state.database) {
                DaoScript.findById(it.id)?.delete()
            }
            call.respond("")
        }
    }
}

@Resource("/{id}")
data class ScriptResource(val id: Int)


@Serializable
data class NewScript(val name: String, val script: String)

@Serializable
data class ScriptDetails(val id: Int, val name: String, val script: String)

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
    return this.valueOrNull() == true
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
            RunResult(
                "success",
                compileResult.reports.toMessages(),
                evalResult.returnValue.toString(),
            )
        }
    }
}

@Serializable
data class RunResult(val status: String, val messages: List<ScriptRunMessage>, val result: String? = null)

@Serializable
data class CompileResult(val success: Boolean, val messages: List<ScriptRunMessage>)
