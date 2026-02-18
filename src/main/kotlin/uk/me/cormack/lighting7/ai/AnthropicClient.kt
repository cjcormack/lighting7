package uk.me.cormack.lighting7.ai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Thin HTTP client for the Anthropic Messages API.
 *
 * Uses the existing Ktor client stack (CIO engine + kotlinx.serialization).
 * No additional SDK dependency required.
 */
class AnthropicClient(
    private val apiKey: String,
    private val model: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(this@AnthropicClient.json)
        }
    }

    /**
     * Send a messages request to the Anthropic API and return the response.
     */
    suspend fun createMessage(request: AnthropicRequest): AnthropicResponse {
        val response = client.post("https://api.anthropic.com/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(request.copy(model = model))
        }
        return response.body()
    }

    fun close() {
        client.close()
    }
}

// ─── Request / Response DTOs ───────────────────────────────────────────────

@Serializable
data class AnthropicRequest(
    val model: String = "",
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,
    val system: String? = null,
    val messages: List<AnthropicMessage>,
    val tools: List<AnthropicToolDef>? = null,
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: JsonElement,  // Can be a string or array of content blocks
)

@Serializable
data class AnthropicToolDef(
    val name: String,
    val description: String,
    @SerialName("input_schema")
    val inputSchema: JsonObject,
)

@Serializable
data class AnthropicResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<AnthropicContentBlock>,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
data class AnthropicUsage(
    @SerialName("input_tokens")
    val inputTokens: Int = 0,
    @SerialName("output_tokens")
    val outputTokens: Int = 0,
)

/**
 * Content block in an Anthropic response.
 * Uses a discriminated union on the "type" field.
 */
@Serializable
sealed class AnthropicContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : AnthropicContentBlock()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
    ) : AnthropicContentBlock()
}

/**
 * Helper to build a "user" message with tool_result content blocks.
 */
fun toolResultMessage(results: List<ToolResultBlock>): AnthropicMessage {
    val content = buildJsonArray {
        for (result in results) {
            addJsonObject {
                put("type", "tool_result")
                put("tool_use_id", result.toolUseId)
                put("content", result.content)
                if (result.isError) put("is_error", true)
            }
        }
    }
    return AnthropicMessage(role = "user", content = content)
}

/**
 * Helper to build an "assistant" message from response content blocks.
 */
fun assistantMessageFromResponse(blocks: List<AnthropicContentBlock>): AnthropicMessage {
    val content = buildJsonArray {
        for (block in blocks) {
            when (block) {
                is AnthropicContentBlock.Text -> addJsonObject {
                    put("type", "text")
                    put("text", block.text)
                }
                is AnthropicContentBlock.ToolUse -> addJsonObject {
                    put("type", "tool_use")
                    put("id", block.id)
                    put("name", block.name)
                    put("input", block.input)
                }
            }
        }
    }
    return AnthropicMessage(role = "assistant", content = content)
}

data class ToolResultBlock(
    val toolUseId: String,
    val content: String,
    val isError: Boolean = false,
)
