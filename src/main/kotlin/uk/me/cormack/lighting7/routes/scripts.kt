package uk.me.cormack.lighting7.routes

import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.Script
import uk.me.cormack.lighting7.state.State
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
                val compiledResultWithDiagnostics = state.show.compileLiteralScript(script)

                val compiledScript = compiledResultWithDiagnostics.valueOrNull()

                response = CompileResult(
                    compiledScript != null,
                    compiledResultWithDiagnostics.reports.filter {
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
                )
            }.join()

            call.respond(checkNotNull(response))
        }

        post("/run") {
            val script = call.receive<String>()

            var response: RunResult? = null

            GlobalScope.launch {
                val compiledResultWithDiagnostics = state.show.compileLiteralScript(script)

                val compiledScript = compiledResultWithDiagnostics.valueOrNull()

                if (compiledScript == null) {
                    response = RunResult(
                        "compileError",
                        compiledResultWithDiagnostics.reports.filter {
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
                        },
                        null
                    )

                    return@launch
                }

                val evalResultWithDiagnostics = state.show.runLiteralScript(compiledScript)

                val evalResult = evalResultWithDiagnostics.valueOrNull()
                if (evalResult == null) {
                    response = RunResult(
                        "exception",
                        evalResultWithDiagnostics.reports.filter {
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
                        },
                        null
                    )

                    return@launch
                }

                response = RunResult(
                    "success",
                    evalResultWithDiagnostics.reports.filter {
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
                    },
                    evalResult.returnValue.toString()
                )

            }.join()

            call.respond(checkNotNull(response))
        }

        post {
            val newScript = call.receive<NewScript>()
            val script = transaction(state.database) {
                Script.new {
                    name = newScript.name
                    script = newScript.script
                    project = state.show.project
                }
            }
            call.respond(ScriptDetails(script.id.value, script.name, script.script))
        }

        get<ScriptResource> {
            val script = transaction(state.database) {
                Script.findById(it.id)
            }
            if (script != null) {
                call.respond(ScriptDetails(script.id.value, script.name, script.script))
            }
        }

        put<ScriptResource> {
            val newScriptDetails = call.receive<NewScript>()
            val script = transaction(state.database) {
                val script = Script.findById(it.id) ?: throw Error("Script not found")
                script.name = newScriptDetails.name
                script.script = newScriptDetails.script

                script
            }
            call.respond(ScriptDetails(script.id.value, script.name, script.script))
        }

        delete<ScriptResource> {
            transaction(state.database) {
                Script.findById(it.id)?.delete()
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

@Serializable
data class RunResult(val status: String, val messages: List<ScriptRunMessage>, val result: String?)

@Serializable
data class CompileResult(val success: Boolean, val messages: List<ScriptRunMessage>)
