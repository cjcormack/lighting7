package uk.me.cormack.lighting7.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoCueTrigger
import uk.me.cormack.lighting7.models.DaoCueTriggers
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.DaoScripts
import uk.me.cormack.lighting7.routes.ScriptRunMessage
import uk.me.cormack.lighting7.routes.toCompileResult
import uk.me.cormack.lighting7.routes.toRunResult
import uk.me.cormack.lighting7.scripts.ScriptType
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.state.State

/** The project-script tools that compile, store or run code, refused while *Allow scripts* is off. */
internal val PROJECT_SCRIPT_GATED_TOOLS = setOf("save_script", "run_script")

/** The script types a project script may be: the FX calc bodies are [FxScriptTools]' FX definitions. */
private val PROJECT_SCRIPT_TYPES = listOf(ScriptType.GENERAL, ScriptType.FX_APPLICATION, ScriptType.FX_DEFINITION)

private fun JsonObjectBuilder.stringProperty(name: String, description: String) =
    put(name, buildJsonObject { put("type", "string"); put("description", description) })

private fun JsonObjectBuilder.scriptTypeProperty(description: String) =
    put("scriptType", buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { PROJECT_SCRIPT_TYPES.forEach { add(it.name) } })
        put("description", description)
    })

internal val getScriptGuideTool = ToolDef(
    name = "get_script_guide",
    description = "How to write a project script — GENERAL, FX_APPLICATION or FX_DEFINITION Kotlin stored with the show " +
        "and run on demand: each type's scope, worked examples, and the fixture classes in this rig that fixture<T> and " +
        "group<T> are typed by. Read it before save_script or run_script.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    },
)

internal val getScriptTool = ToolDef(
    name = "get_script",
    description = "Without a name: the current show's project scripts, each with its name and type. With one: that " +
        "script's type and source.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { stringProperty("name", "The script's name. Omit to list them.") }
    },
)

internal val saveScriptTool = ToolDef(
    name = "save_script",
    description = "Create a project script in the current show, or replace the one with this name. It is compiled " +
        "first: a script that does not compile is not saved, and the answer names each error at line:column. Saving " +
        "does not run it — run_script does. Needs Remote access's Allow scripts. get_script_guide explains the types.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("name", "The script's name, unique in the show.")
            stringProperty("newName", "Rename it as it is saved. Omit to keep the name.")
            scriptTypeProperty("GENERAL, FX_APPLICATION or FX_DEFINITION. Default FX_APPLICATION.")
            stringProperty("script", "The script's Kotlin source.")
        }
        put("required", buildJsonArray { add("name"); add("script") })
    },
)

internal val deleteScriptTool = ToolDef(
    name = "delete_script",
    description = "Delete a project script from the current show. What it did when it last ran stays as it is.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { stringProperty("name", "The script's name.") }
        put("required", buildJsonArray { add("name") })
    },
)

internal val runScriptTool = ToolDef(
    name = "run_script",
    description = "Run a project script on the live rig and wait for it: a saved one by name, or one given as script " +
        "(and scriptType) without saving it. It acts on the rig directly, not through the programmer. Answers its " +
        "status — success, compileError, exception or timedOut (still running after 30 s, and cancelled) — its messages at line:column, and its result (the last " +
        "expression's value, or the exception). Needs Remote access's Allow scripts.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("name", "A saved script to run. Not with script.")
            stringProperty("script", "Kotlin source to run once, unsaved. Not with name.")
            scriptTypeProperty("The type of an unsaved script. Default FX_APPLICATION.")
            put("compileOnly", buildJsonObject {
                put("type", "boolean")
                put("description", "Compile it and answer its errors, without running it. Default false.")
            })
        }
    },
)

/**
 * The project-script tools: the show's `scripts` rows of the three runnable types, the rows the desk's
 * Scripts view edits (`routes/projectScripts.kt`), compiled and run by the same [uk.me.cormack.lighting7.show.Show]
 * calls. Saving and running are refused by `McpProtocol` while *Allow scripts* is off
 * ([PROJECT_SCRIPT_GATED_TOOLS]); reading, the guide and deleting are not, as their routes are not.
 */
internal class ProjectScriptTools(
    private val state: State,
    private val runDeadlineMs: Long = RUN_DEADLINE_MS,
) {

    val toolDefs: List<ToolDef> = listOf(getScriptGuideTool, getScriptTool, saveScriptTool, deleteScriptTool, runScriptTool)

    fun handles(name: String): Boolean = toolDefs.any { it.name == name }

    fun executeTool(name: String, input: JsonObject): ToolExecutionResult {
        toolDefs.firstOrNull { it.name == name }?.let { def -> undeclaredArgumentRefusal(def, input)?.let { return failure(it) } }
        return try {
            when (name) {
                getScriptGuideTool.name -> ToolExecutionResult(
                    true, "The project script guide",
                    ProjectScriptGuide.text(state.show.fixtures.fixtures.map { it.key to it.javaClass }),
                )
                getScriptTool.name -> getScript(input)
                saveScriptTool.name -> saveScript(input)
                deleteScriptTool.name -> deleteScript(input)
                runScriptTool.name -> runScript(input)
                else -> failure("Unknown tool: $name")
            }
        } catch (e: IllegalArgumentException) {
            failure(e.message ?: "Bad arguments")
        }
    }

    private fun findScript(name: String): DaoScript? =
        DaoScript.find { (DaoScripts.project eq state.show.project.id) and (DaoScripts.name eq name) }.singleOrNull()

    private fun getScript(input: JsonObject): ToolExecutionResult {
        val name = input.text("name")
        return transaction(state.database) {
            if (name == null) {
                val scripts = DaoScript.find { DaoScripts.project eq state.show.project.id }.orderBy(DaoScripts.name to SortOrder.ASC).toList()
                success("${scripts.size} script(s)", buildJsonObject {
                    putJsonArray("scripts") {
                        scripts.forEach { addJsonObject { put("name", it.name); put("scriptType", it.scriptType.name) } }
                    }
                })
            } else {
                val script = findScript(name) ?: return@transaction failure("No script '$name' in this show")
                success("Script $name", buildJsonObject {
                    put("name", script.name)
                    put("scriptType", script.scriptType.name)
                    put("script", script.script)
                })
            }
        }
    }

    private fun saveScript(input: JsonObject): ToolExecutionResult {
        val name = input.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return failure("Missing 'name'")
        val newName = input.text("newName")?.trim()?.takeIf { it.isNotEmpty() }
        val source = input.text("script")?.takeIf { it.isNotBlank() } ?: return failure("Missing 'script'")
        val type = scriptTypeOf(input) ?: ScriptType.FX_APPLICATION
        if (newName != null && newName != name && transaction(state.database) { findScript(newName) } != null) {
            return failure("A script named '$newName' is already in this show")
        }

        val compiled = state.show.compileLiteralScript(source, type).toCompileResult()
        if (!compiled.success) {
            return ToolExecutionResult(false, "The script does not compile", buildJsonObject {
                put("error", "The script does not compile; nothing was saved")
                putMessages(compiled.messages)
            }.toString())
        }
        val created = transaction(state.database) {
            val existing = findScript(name)
            val row = existing ?: DaoScript.new {
                this.name = name
                this.script = source
                this.project = state.show.project
            }
            row.name = newName ?: name
            row.script = source
            row.scriptType = type
            existing == null
        }
        state.show.fixtures.scriptListChanged()
        return success("${if (created) "Created" else "Replaced"} script ${newName ?: name}", buildJsonObject {
            put("name", newName ?: name)
            put("scriptType", type.name)
            put("created", created)
            compiled.messages.takeIf { it.isNotEmpty() }?.let { putMessages(it) }
        })
    }

    private fun deleteScript(input: JsonObject): ToolExecutionResult {
        val name = input.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return failure("Missing 'name'")
        val refusal = transaction(state.database) {
            val script = findScript(name) ?: return@transaction "No script '$name' in this show"
            val cues = DaoCueTrigger.find { DaoCueTriggers.script eq script.id }.map { it.cue.name }.distinct().sorted()
            if (cues.isNotEmpty()) return@transaction "Script '$name' runs from cue triggers on ${cues.joinToString { "'$it'" }}; take it off those cues first"
            script.delete()
            null
        }
        if (refusal != null) return failure(refusal)
        state.show.fixtures.scriptListChanged()
        return success("Deleted script $name", buildJsonObject { put("deleted", name) })
    }

    private fun runScript(input: JsonObject): ToolExecutionResult {
        val name = input.text("name")?.trim()?.takeIf { it.isNotEmpty() }
        val literal = input.text("script")?.takeIf { it.isNotBlank() }
        if ((name == null) == (literal == null)) return failure("Give the name of a saved script, or a script to run unsaved — one of them")
        val compileOnly = input.present("compileOnly")?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toBooleanStrictOrNull() ?: throw IllegalArgumentException("compileOnly must be true or false")
        } ?: false

        data class Target(val source: String, val type: ScriptType, val id: Int?)
        val target = if (name != null) {
            if (input.present("scriptType") != null) return failure("A saved script runs as its own type; leave out scriptType")
            transaction(state.database) { findScript(name)?.let { Target(it.script, it.scriptType, it.id.value) } }
                ?: return failure("No script '$name' in this show")
        } else {
            Target(literal!!, scriptTypeOf(input) ?: ScriptType.FX_APPLICATION, null)
        }
        if (target.type !in PROJECT_SCRIPT_TYPES) return failure("A ${target.type} script is an FX's body; save_fx_script runs those")

        val script = state.show.script(name ?: "", target.source, target.type)
        val compiled = script.compileStatus.toCompileResult()
        if (compileOnly) {
            return ToolExecutionResult(compiled.success, if (compiled.success) "Compiles" else "Does not compile", buildJsonObject {
                put("compiles", compiled.success)
                putMessages(compiled.messages)
            }.toString())
        }
        if (!compiled.success) {
            return ToolExecutionResult(false, "Script compileError", buildJsonObject {
                put("status", "compileError")
                putMessages(compiled.messages)
            }.toString())
        }
        val runner = Show.ScriptRunner(state.show, script, scriptId = target.id)
        if (runBlocking { withTimeoutOrNull(runDeadlineMs) { runner.job.join() } } == null) {
            runner.stop()
            return ToolExecutionResult(false, "Script timedOut", buildJsonObject {
                put("status", "timedOut")
                put("error", "The script was still running after ${runDeadlineMs / 1000} s, so it was cancelled")
            }.toString())
        }
        val run = runner.result().toRunResult()
        return ToolExecutionResult(run.status == "success", "Script ${run.status}", buildJsonObject {
            put("status", run.status)
            putMessages(run.messages)
            run.result?.let { put("result", it.take(MAX_RESULT_CHARS)) }
        }.toString())
    }

    private fun scriptTypeOf(input: JsonObject): ScriptType? = input.text("scriptType")?.let { raw ->
        PROJECT_SCRIPT_TYPES.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("scriptType '$raw' is not one of ${PROJECT_SCRIPT_TYPES.joinToString()}")
    }

    private fun JsonObjectBuilder.putMessages(messages: List<ScriptRunMessage>) {
        putJsonArray("messages") {
            messages.forEach { m ->
                addJsonObject {
                    put("severity", m.severity)
                    put("message", m.message)
                    m.location?.let { put("at", it) }
                }
            }
        }
    }

    private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

    private fun JsonObject.text(key: String): String? = present(key)?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("'$key' must be a string")
    }

    private fun success(description: String, result: JsonObject) = ToolExecutionResult(true, description, result.toString())

    private fun failure(message: String) = ToolExecutionResult(
        success = false,
        description = message,
        result = buildJsonObject { put("error", message) }.toString(),
    )

    private companion object {
        /** A result is a value's `toString` or a stack trace; past this it is cut. */
        const val MAX_RESULT_CHARS = 8_000

        const val RUN_DEADLINE_MS = 30_000L
    }
}
