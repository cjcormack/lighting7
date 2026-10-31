package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.moveInDarkSettings
import uk.me.cormack.lighting7.state.State

internal val setMoveInDarkTool = ToolDef(
    name = "set_move_in_dark",
    description = "Set Move in dark on a cue stack or on a cue. When a head goes dark, the desk moves its position, colour and beam " +
        "to the next cue that lights it, so the GO only brings it up. A stack's setting: on, when (EARLY: the first cue ahead " +
        "that lights the head; LATE: the next GO's only), delayMs (how long it must be dark first), timeMs (how long the move " +
        "takes) and families. A cue's, on the cue that lights the heads: mode (STACK follows the stack; EARLY or LATE apply on " +
        "a stack that is off; OFF moves its heads in its fade, lit), its own delayMs and timeMs (null is the stack's), and live " +
        "(heads kept lit as they move — a fly-in). Fields left out keep what is stored. A stored write: nothing on stage changes " +
        "but which dark heads move. get_current_state's cue_stacks reports each stack's setting and what its list will do.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "The stack to set. Give this or cueId.") })
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "The cue to set. Give this or stackId.") })
            put("moveInDark", buildJsonObject {
                put("type", "object")
                put("description", "With stackId, the stack's fields (on, when, delayMs, timeMs, families); with cueId, the cue's (mode, delayMs, timeMs, live).")
                put("properties", buildJsonObject {
                    val stack = moveInDarkSchema["properties"] as JsonObject
                    val cue = cueMoveInDarkSchema["properties"] as JsonObject
                    put("on", stack.getValue("on"))
                    put("when", stack.getValue("when"))
                    put("families", stack.getValue("families"))
                    put("mode", cue.getValue("mode"))
                    put("live", cue.getValue("live"))
                    put("delayMs", buildJsonObject {
                        put("type", JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("null"))))
                        put("description", "How long a head must be dark first, 0–30000 (a stack's default 1000). On a cue, null is the stack's.")
                    })
                    put("timeMs", buildJsonObject {
                        put("type", JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("null"))))
                        put("description", "How long its move takes, 0–30000 (a stack's default 2000). On a cue, null is the stack's.")
                    })
                })
            })
            put("projectId", buildJsonObject { put("type", "integer"); put("description", PROJECT_ID_DESCRIPTION) })
        })
        put("required", buildJsonArray { add("moveInDark") })
    },
)

/** `set_move_in_dark`: a stack's or a cue's stored Move in dark setting (move-in-dark plan D2, D3). */
internal class MoveInDarkSetupTool(private val state: State) {

    fun setMoveInDark(input: JsonObject, target: ToolProject): ToolExecutionResult {
        val stackId = (input["stackId"] as? JsonPrimitive)?.intOrNull
        val cueId = (input["cueId"] as? JsonPrimitive)?.intOrNull
        val raw = input["moveInDark"]?.takeIf { it !is JsonNull } ?: return failure("Missing 'moveInDark'")
        if (stackId == null && cueId == null) return failure("give stackId for a stack's setting, or cueId for a cue's")
        if (stackId != null && cueId != null) return failure("give stackId or cueId, not both")
        if (stackId != null) return setStack(stackId, raw as? JsonObject ?: return failure("moveInDark must be an object"), target)
        return setCue(cueId!!, raw, target).also { if (it.success && target.isCurrent) state.show.fixtures.cueListChanged() }
    }

    private fun setStack(stackId: Int, raw: JsonObject, target: ToolProject): ToolExecutionResult {
        var problem: String? = null
        val written = transaction(state.database) {
            val stack = DaoCueStack.findById(stackId)?.takeIf { it.project.id == target.project.id && it.type == CueStackType.STACK.name }
                ?: return@transaction null.also { problem = "No cue stack $stackId in this project" }
            val settings = parseToolMoveInDark(raw, stack.moveInDarkSettings).getOrElse {
                problem = it.message
                return@transaction null
            }
            stack.moveInDarkSettings = settings
            stack.name to settings
        } ?: return failure(problem ?: "Invalid moveInDark")
        if (target.isCurrent) state.show.moveInDark.onStackSettingsChanged(stackId)
        state.show.fixtures.cueStackListChanged()
        val (name, settings) = written
        return success(
            "Move in dark on stack '$name' (id=$stackId) is ${if (settings.on) "on, ${settings.whenMode.name.lowercase()}" else "off"}",
            buildJsonObject {
                put("stackId", stackId)
                put("moveInDark", moveInDarkJson.encodeToJsonElement(uk.me.cormack.lighting7.models.MoveInDarkDto.serializer(), settings.toDto()))
            },
        )
    }

    private fun setCue(cueId: Int, raw: kotlinx.serialization.json.JsonElement, target: ToolProject): ToolExecutionResult {
        var problem: String? = null
        val written = transaction(state.database) {
            val cue = DaoCue.findById(cueId)?.takeIf { it.project.id == target.project.id }
                ?: return@transaction null.also { problem = "No cue $cueId in this project" }
            val settings = parseToolCueMoveInDark(raw, cue.moveInDarkSettings).getOrElse {
                problem = it.message
                return@transaction null
            }
            cue.moveInDarkSettings = settings
            cue.name to settings
        } ?: return failure(problem ?: "Invalid moveInDark")
        val (name, settings) = written
        return success(
            "Move in dark on cue '$name' (id=$cueId) is ${settings.mode.name.lowercase()}" +
                if (settings.live.isEmpty()) "" else ", ${settings.live.size} head(s) kept live",
            buildJsonObject {
                put("cueId", cueId)
                put("moveInDark", buildJsonObject {
                    put("mode", settings.mode.name)
                    put("delayMs", settings.delay?.toMillis()?.let(::JsonPrimitive) ?: JsonNull)
                    put("timeMs", settings.time?.toMillis()?.let(::JsonPrimitive) ?: JsonNull)
                    putJsonArray("live") { settings.live.forEach { add(it) } }
                })
            },
        )
    }

    private fun success(description: String, result: JsonObject) =
        ToolExecutionResult(success = true, description = description, result = result.toString())

    private fun failure(message: String) = ToolExecutionResult(
        success = false,
        description = message,
        result = buildJsonObject { put("error", message) }.toString(),
    )

    private companion object {
        val moveInDarkJson = kotlinx.serialization.json.Json { encodeDefaults = true }
    }
}
