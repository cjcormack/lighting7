package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonObject

/**
 * One tool as the MCP server lists it: a name, the description the model reads, and the JSON
 * Schema of its arguments. `McpProtocol` renders it as an MCP `Tool`.
 */
data class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)
