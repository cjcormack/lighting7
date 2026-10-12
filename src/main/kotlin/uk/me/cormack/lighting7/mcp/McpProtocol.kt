package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.FIXTURE_COMMANDS_REMOTE_REFUSAL
import uk.me.cormack.lighting7.ai.RUN_FIXTURE_COMMAND
import uk.me.cormack.lighting7.ai.RigBriefing
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolDef
import uk.me.cormack.lighting7.ai.ToolImage
import uk.me.cormack.lighting7.ai.readOnlySetupToolNames
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.UserRole
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.update.BuildInfo

/**
 * The MCP server itself: JSON-RPC 2.0 in, JSON-RPC 2.0 out, no transport. `McpServerModule`
 * carries it over streamable HTTP; tests call [handle] directly.
 *
 * The tools are the show-running tools ([AiTools.toolDefs] — there is no script tool), the
 * show-setup tools ([SetupTools]), and `describe_rig`, which answers with the live rig in prose
 * ([RigBriefing]). An MCP client gets nothing it does not ask for, so the `instructions` sent at
 * initialize tell the model to call it first.
 *
 * Stateless: no `Mcp-Session-Id`, no server-initiated messages, no SSE stream. Every tool call
 * reads and writes the desk's *current* project.
 */
class McpProtocol(private val state: State) {
    private val log = LoggerFactory.getLogger(McpProtocol::class.java)
    private val tools = AiTools(state)
    private val setupTools = SetupTools(state, tools, deskUrl = { runCatching { state.remoteAccess.publicUrl() }.getOrNull() })
    private val briefing = RigBriefing(state)

    private val describeRigTool = ToolDef(
        name = DESCRIBE_RIG,
        description = "Describe the rig and the show as they are now: every fixture and group, the effect " +
            "library, running effects, parked channels, speed masters (with the uuids other tools take), looks, colour " +
            "templates, cues and cue stacks. Call this first in a conversation, and again after the " +
            "show changes, because every other tool refers to things by the keys and ids listed here.",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
    )

    /**
     * Tool list in the order a client shows it: the orientation tool first, then the show-running
     * tools ([AiTools]), then the show-setup tools ([SetupTools]).
     */
    val toolDefs: List<ToolDef> = listOf(describeRigTool) + tools.toolDefs + setupTools.toolDefs

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The listener has no error-page handler, so an escape here would reach the client as
            // a bare 500 with no JSON-RPC body to tell it what happened.
            log.warn("MCP {} failed", method, e)
            error(id, INTERNAL_ERROR, "Internal error: ${e.message ?: e::class.simpleName}")
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
        appendLine(SETUP_GUIDE)
        appendLine()
        append(briefing.keyConcepts())
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
        if (name == DESCRIBE_RIG) {
            // The other tools catch their own failures and answer isError; this one reads the
            // whole show, which can change under it (a project switch mid-read).
            return try {
                toolResult(briefing.describeRig(), isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("describe_rig failed", e)
                toolResult("Could not describe the rig: ${e.message ?: e::class.simpleName}", isError = true)
            }
        }

        venueToolRefusal(name, arguments, user)?.let { return toolResult(it, isError = true) }

        // MCP is always remote: a fixture command is held to Remote access's "allow fixture commands"
        // (fixture optics plan session 7), as the REST route's `requireCommandsAccess` holds a remote call.
        if (name == RUN_FIXTURE_COMMAND && !state.remoteAccess.allowRemoteCommands) {
            return toolResult(FIXTURE_COMMANDS_REMOTE_REFUSAL, isError = true)
        }

        val outcome = if (setupTools.handles(name)) setupTools.executeTool(name, arguments, caller = user.displayName)
        else tools.executeTool(name, arguments)
        return toolResult(outcome.result, isError = !outcome.success, images = outcome.images)
    }

    /**
     * Why [name] may not run on the project it writes when that is a venue, or null (venue plan D3,
     * D11) — the tools' copy of the REST routes' rules, since a tool writes with no route in between.
     * The project is the one a project-scoped tool names, else the live one. Record and Update are off
     * in a venue for everyone; any other stored write is an admin's, and so are making and publishing
     * a venue. A venue version is refused by the tools themselves, as absent.
     */
    private fun venueToolRefusal(name: String, arguments: JsonObject, user: AuthenticatedUser): String? {
        if (name in VENUE_ADMIN_TOOLS && user.role != UserRole.ADMIN) return "Only an admin can make or publish a venue."
        val field = PROJECT_SCOPED_TOOLS[name]
        val named = field?.let { (arguments[it] as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull }
        val kind = transaction(state.database) {
            if (named != null) DaoProject.findById(named)?.kind else state.projectManager.currentProject.kind
        }
        if (kind != ProjectKind.VENUE) return null
        // `aim_fixtures` acts on the rig as it stands, unless it is told to keep the aims as a template.
        val storesTemplate = name == "aim_fixtures" &&
            (arguments["saveAsTemplate"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
        return when {
            name in VENUE_RECORD_TOOLS -> "Record is off in a venue: the programmer here is for testing and focus."
            (name in VENUE_STORED_WRITE_TOOLS || storesTemplate) && user.role != UserRole.ADMIN ->
                "Only an admin can change a venue."
            else -> null
        }
    }

    /**
     * A `CallToolResult`: the text, then any images as `image` content (`{type, data, mimeType}`,
     * base64 — the same shape in all three protocol versions this server speaks).
     */
    private fun toolResult(text: String, isError: Boolean, images: List<ToolImage> = emptyList()) = buildJsonObject {
        put("content", buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
            images.forEach { image ->
                addJsonObject {
                    put("type", "image")
                    put("data", image.base64)
                    put("mimeType", image.mimeType)
                }
            }
        })
        put("isError", isError)
    }

    private fun ToolDef.toMcp() = buildJsonObject {
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

        private val READ_ONLY_TOOLS = setOf(DESCRIBE_RIG, "get_current_state") + readOnlySetupToolNames

        /** Making and publishing a venue (venue plan D3): an admin's, as their REST routes are. */
        private val VENUE_ADMIN_TOOLS = setOf("extract_venue", "publish_venue")

        /**
         * The tools that act on the project an argument names rather than the live one, by that
         * argument. Any other tool's `projectId` is not read, so it cannot steer the guard.
         */
        private val PROJECT_SCOPED_TOOLS: Map<String, String> = listOf(
            "get_patch", "patch_fixtures", "delete_groups", "set_stage", "set_scene", "get_scene", "upload_scene_image",
            "place_fixtures", "get_prompt_book", "build_cue_stack", "delete_cue_stacks", "set_scenery",
        ).associateWith { "projectId" } + ("copy_to_project" to "toProjectId")

        /** The programmer's Record and Update, off in a venue (venue plan D11). */
        private val VENUE_RECORD_TOOLS = setOf("record_cue", "record_look", "update_from_programmer")

        /**
         * The tools that store something in the live project — an operator's are refused on a venue
         * (D3). The rest act on the rig as it stands (apply, GO, aim, tempo, the programmer) or only read.
         */
        private val VENUE_STORED_WRITE_TOOLS = setOf(
            "create_look", "update_look", "delete_look", "create_speed_master", "park_channel", "unpark_channel", "create_cue",
            "create_cue_stack", "add_cue_to_stack", "create_template", "set_cue_events", "set_scenery",
            "patch_fixtures", "delete_groups", "set_stage", "set_scene", "upload_scene_image",
            "place_fixtures", "build_cue_stack", "mark_up_prompt_book", "copy_to_project", "delete_cue_stacks",
        )

        /**
         * How the show-setup tools fit together. They are driven from documents the operator
         * attaches to the conversation, which the model reads itself, so the guide is mostly about
         * order and about the one file the desk has to hold.
         */
        private val SETUP_GUIDE = """
            ## Setting up a show from documents
            The operator may attach another console's patch export, a lighting plot, photos of the rig, a script and lighting notes. Read them yourself and pass the tools structured data; the one tool that takes a file is upload_scene_image, an image to paint on a cloth or a model.
            - Tools act on the current project. The setup tools that read or store data also take a projectId, to work on a project that is not current without switching; switch_project stops the live output, so confirm first unless the operator asked for it. Tools that drive output (looks, cues, the programmer, render_view) and describe_rig stay on the current project.
            - Venues: a venue is a hall — room, masking, seating, viewpoints and optionally the house rig — that shows are built on (list_venues). extract_venue turns an existing show's room and rig into one and puts that show on it; create_project with venueId starts a new show on it, holding only its own set, cues and prompt book. A show on a venue sees the venue's records read-only (owner=venue) but can hide a venue element for its run (set_scene). To change the venue for every show, edit it with projectId = the venue's id and publish_venue; each show takes the new version when the operator updates it on the desk. copy_to_project moves set pieces, cue stacks and Looks between projects by name; delete_cue_stacks removes stacks.
            - Patch: list_fixture_types to match each fixture to a typeKey (conventional lanterns are 'generic-dimmer'), then patch_fixtures with the whole list, using dryRun first. Report fixtures with no matching type rather than guessing. Put fixtures in groups by position and role; groups are what looks and cues address. delete_groups removes groups an old patch left behind.
            - Stage: set_stage for the stage size, regions and riggings (named as the plot names them), then place_fixtures to hang fixtures on them. get_patch shows the result. A long body (a lightstrip, a bar) stands on end with rollDeg 90. aim_fixtures points moving heads at a stage coordinate (a region's centre at head height, a mark on the plot) through the programmer, which the operator clears — so keep an aim with record_cue, or pass saveAsTemplate to save it as a position template.
            - Venue: set_scene models the room, the proscenium, the seating and the set, so the Stage view shows the real hall. Start from template 'proscenium-hall' with the hall's numbers read off a plan or a photo, correct the named elements it makes, add set pieces, then get_scene to check. Save the views the operator will want — the desk's position, a seat — as viewpoints. render_view draws a viewpoint as a PNG (a desk window must be open), so you can compare the model with the operator's photo from the same place and correct it. A rigging the units stand on (a balcony front) is kind LEDGE. A painted cloth — a backcloth, a frontcloth, a cut cloth — is an image: upload_scene_image, then a DRAPE (or a FLAT) whose params.paint names its hash, sized to the image's aspect; a gauze is a DRAPE with fabric SHARKSTOOTH or BOBBINET. A set piece no primitive draws — an arch, a tree, a column, a statue — is an OBJECT with shape MODEL built from parts (boxes, cylinders and cones, spheres, extruded outlines, lathed profiles; set_scene's params says how), each with its own colour or a texture from upload_scene_image; render_view to check it, and modelFrom to place copies.
            - Show: the operator imports the script PDF through the desk's Prompt Book view (get_prompt_book says where); then build_cue_stack creates the stack and cues in running order with numbers, notes and timings, anchoring each at the line it is called on, and mark_up_prompt_book adds notes and moves anchors. PDF pages count from 1 at the file's first page.
            - These tools validate a whole request and write nothing if any row is wrong; fix every listed problem and resend.
        """.trimIndent()

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

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
