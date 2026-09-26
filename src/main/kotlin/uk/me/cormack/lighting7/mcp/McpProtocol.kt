package uk.me.cormack.lighting7.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.AnthropicToolDef
import uk.me.cormack.lighting7.ai.RigBriefing
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.update.BuildInfo

/**
 * The MCP server itself: JSON-RPC 2.0 in, JSON-RPC 2.0 out, no transport. `McpServerModule`
 * carries it over streamable HTTP; tests call [handle] directly.
 *
 * The tools are the in-app chat's ([AiTools.mcpTools] — every one but `run_lighting_script`)
 * plus `describe_rig`, which answers with what the chat puts in its system prompt each turn.
 * The chat gets that context for free; an MCP client gets nothing it does not ask for, so the
 * `instructions` sent at initialize tell the model to call it first.
 *
 * Stateless: no `Mcp-Session-Id`, no server-initiated messages, no SSE stream. Every tool call
 * reads and writes the desk's *current* project, exactly as the chat does.
 */
class McpProtocol(private val state: State) {
    private val log = LoggerFactory.getLogger(McpProtocol::class.java)
    private val tools = AiTools(state)
    private val briefing = RigBriefing(state)

    private val describeRigTool = AnthropicToolDef(
        name = DESCRIBE_RIG,
        description = "Describe the rig and the show as they are now: every fixture and group, the effect " +
            "library, running effects, speed masters (with the uuids other tools take), looks, colour " +
            "templates, cues and cue stacks. Call this first in a conversation, and again after the " +
            "show changes, because every other tool refers to things by the keys and ids listed here.",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
    )

    /** Tool list in the order a client shows it: the orientation tool first. */
    val toolDefs: List<AnthropicToolDef> = listOf(describeRigTool) + tools.mcpTools

    /**
     * Handle one JSON-RPC message. Returns the response, or null for a notification (and for a
     * client's response to a request we never send), which the transport answers with 202.
     */
    suspend fun handle(message: JsonElement, user: AuthenticatedUser): JsonObject? {
        if (message is JsonArray) {
            // Batching was removed from the protocol in 2025-06-18; answer it as invalid rather
            // than half-supporting it.
            return error(JsonNull, INVALID_REQUEST, "Batched requests are not supported")
        }
        val obj = message as? JsonObject ?: return error(JsonNull, INVALID_REQUEST, "Expected a JSON-RPC object")
        val id = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
        if (method == null) {
            // A response or an error from the client — nothing we sent is awaiting one.
            return if (id == null || "result" in obj || "error" in obj) null
            else error(id, INVALID_REQUEST, "Missing method")
        }
        if (id == null) return null // notification: initialized, cancelled, …
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())

        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, buildJsonObject {
                    put("tools", buildJsonArray { toolDefs.forEach { add(it.toMcp()) } })
                })
                "tools/call" -> result(id, callTool(params, user))
                // Declared absent in capabilities, but answering empty is kinder to clients that
                // probe anyway than a method-not-found error in their logs.
                "resources/list" -> result(id, buildJsonObject { put("resources", JsonArray(emptyList())) })
                "prompts/list" -> result(id, buildJsonObject { put("prompts", JsonArray(emptyList())) })
                else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
            }
        } catch (e: InvalidParams) {
            error(id, INVALID_PARAMS, e.message ?: "Invalid params")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        val version = if (requested in SUPPORTED_VERSIONS) requested!! else SUPPORTED_VERSIONS.first()
        return buildJsonObject {
            put("protocolVersion", version)
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", "lighting7")
                put("title", "Lighting desk")
                put("version", runCatching { BuildInfo.current.version }.getOrDefault("dev"))
            }
            put("instructions", instructions())
        }
    }

    /**
     * Sent once per connection. The composition model rather than the rig: the rig changes under
     * a long conversation, the rules do not, and `describe_rig` answers for the rig when asked.
     */
    private fun instructions(): String = buildString {
        appendLine("You are driving a live stage-lighting desk (lighting7). Changes reach the rig immediately.")
        appendLine("Call describe_rig before anything else: every other tool names fixtures, groups, looks, cues and speed masters by the keys and ids it lists.")
        appendLine("Prefer get_current_state to check what is running before changing it.")
        appendLine()
        append(briefing.keyConcepts(scriptTool = false))
    }

    private suspend fun callTool(params: JsonObject, user: AuthenticatedUser): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull ?: throw InvalidParams("Missing tool name")
        if (toolDefs.none { it.name == name }) throw InvalidParams("Unknown tool: $name")
        val arguments = when (val a = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> a
            else -> throw InvalidParams("arguments must be an object")
        }

        if (!state.isShowReady) {
            return toolResult("The desk is still starting up; try again in a moment.", isError = true)
        }

        log.info("MCP tool {} called by {}", name, user.username)
        if (name == DESCRIBE_RIG) return toolResult(briefing.describeRig(), isError = false)

        val outcome = tools.executeTool(name, arguments)
        return toolResult(outcome.result, isError = !outcome.success)
    }

    private fun toolResult(text: String, isError: Boolean) = buildJsonObject {
        put("content", buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        })
        put("isError", isError)
    }

    private fun AnthropicToolDef.toMcp() = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
        if (name in READ_ONLY_TOOLS) {
            putJsonObject("annotations") {
                put("readOnlyHint", true)
            }
        }
    }

    private class InvalidParams(message: String) : Exception(message)

    companion object {
        const val DESCRIBE_RIG = "describe_rig"

        /** Newest first: an unknown requested version is answered with the newest we speak. */
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        private val READ_ONLY_TOOLS = setOf(DESCRIBE_RIG, "get_current_state")

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

        fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
    }
}
