package uk.me.cormack.lighting7.ai

import io.ktor.server.config.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.detectCapabilities
import uk.me.cormack.lighting7.routes.effectLibrary
import uk.me.cormack.lighting7.state.State

/**
 * Orchestrates AI conversations: manages context, calls Claude, executes tools,
 * and persists conversation history to the database.
 */
class AiService(
    private val state: State,
    config: ApplicationConfig,
) {
    private val apiKey = config.property("anthropic.apiKey").getString()
    private val model = config.propertyOrNull("anthropic.model")?.getString() ?: "claude-sonnet-4-20250514"
    private val client = AnthropicClient(apiKey, model)
    private val tools = AiTools(state)

    /**
     * Send a user message to Claude and return the response.
     *
     * If [conversationId] is null, a new conversation is created.
     * The conversation is persisted after each exchange.
     *
     * @return The AI response including the conversation ID for continuation.
     */
    suspend fun chat(conversationId: Int?, userMessage: String): AiChatResponse {
        val now = System.currentTimeMillis()

        // Load or create conversation
        val (convId, existingMessages) = if (conversationId != null) {
            val conv = transaction(state.database) {
                DaoAiConversation.findById(conversationId)
            } ?: throw IllegalArgumentException("Conversation not found: $conversationId")
            convId@(conv.id.value) to conv.messages
        } else {
            val conv = transaction(state.database) {
                DaoAiConversation.new {
                    title = null
                    project = state.projectManager.currentProject
                    messages = emptyList()
                    createdAt = now
                    updatedAt = now
                }
            }
            conv.id.value to emptyList()
        }

        // Build Anthropic messages from stored conversation + new user message
        val anthropicMessages = existingMessages.map { it.toAnthropicMessage() }.toMutableList()
        anthropicMessages.add(AnthropicMessage(
            role = "user",
            content = JsonPrimitive(userMessage)
        ))

        // Track which messages are new (for persisting)
        val newStoredMessages = mutableListOf<ConversationMessageDto>()
        newStoredMessages.add(ConversationMessageDto(
            role = "user",
            content = listOf(ContentBlockDto.Text(userMessage))
        ))

        // Collect actions from tool calls
        val actions = mutableListOf<AiAction>()

        // Tool-use loop: keep calling Claude until we get a text-only response
        var loopCount = 0
        val maxLoops = 10  // Safety limit
        var finalText = ""

        while (loopCount < maxLoops) {
            loopCount++

            val request = AnthropicRequest(
                system = buildSystemPrompt(),
                messages = anthropicMessages.toList(),
                tools = tools.allTools,
            )

            val response = client.createMessage(request)

            // Check for tool_use blocks
            val toolUseBlocks = response.content.filterIsInstance<AnthropicContentBlock.ToolUse>()
            val textBlocks = response.content.filterIsInstance<AnthropicContentBlock.Text>()

            // Store assistant message
            val assistantStoredContent = response.content.map { block ->
                when (block) {
                    is AnthropicContentBlock.Text -> ContentBlockDto.Text(block.text)
                    is AnthropicContentBlock.ToolUse -> ContentBlockDto.ToolUse(block.id, block.name, block.input)
                }
            }
            newStoredMessages.add(ConversationMessageDto(
                role = "assistant",
                content = assistantStoredContent
            ))

            // Add assistant message to Anthropic conversation
            anthropicMessages.add(assistantMessageFromResponse(response.content))

            if (toolUseBlocks.isEmpty()) {
                // No tool calls — we're done
                finalText = textBlocks.joinToString("\n") { it.text }
                break
            }

            // Execute each tool call
            val toolResults = mutableListOf<ToolResultBlock>()
            for (toolUse in toolUseBlocks) {
                val result = tools.executeTool(toolUse.name, toolUse.input)
                actions.add(AiAction(
                    tool = toolUse.name,
                    description = result.description,
                    success = result.success,
                ))
                toolResults.add(ToolResultBlock(
                    toolUseId = toolUse.id,
                    content = result.result,
                    isError = !result.success,
                ))
            }

            // Store tool results as a user message
            val toolResultStoredContent = toolResults.map { tr ->
                ContentBlockDto.ToolResult(
                    toolUseId = tr.toolUseId,
                    content = tr.content,
                    isError = tr.isError,
                )
            }
            newStoredMessages.add(ConversationMessageDto(
                role = "user",
                content = toolResultStoredContent
            ))

            // Add tool results to Anthropic conversation
            anthropicMessages.add(toolResultMessage(toolResults))

            // Collect any text from this turn too
            if (textBlocks.isNotEmpty()) {
                finalText = textBlocks.joinToString("\n") { it.text }
            }
        }

        // Persist conversation
        val allMessages = existingMessages + newStoredMessages
        transaction(state.database) {
            val conv = DaoAiConversation.findById(convId)!!
            conv.messages = allMessages
            conv.updatedAt = System.currentTimeMillis()
            // Auto-title from first user message if not set
            if (conv.title == null) {
                conv.title = userMessage.take(100)
            }
        }

        return AiChatResponse(
            conversationId = convId,
            message = finalText,
            actions = actions,
        )
    }

    /**
     * List all conversations for the current project.
     */
    fun listConversations(): List<AiConversationSummary> {
        val project = state.projectManager.currentProject
        return transaction(state.database) {
            DaoAiConversation.find { DaoAiConversations.project eq project.id }
                .orderBy(DaoAiConversations.updatedAt to SortOrder.DESC)
                .map { conv ->
                    AiConversationSummary(
                        id = conv.id.value,
                        title = conv.title,
                        updatedAt = conv.updatedAt,
                    )
                }
        }
    }

    /**
     * Get a full conversation with display-friendly messages.
     */
    fun getConversation(conversationId: Int): AiConversationDetail? {
        return transaction(state.database) {
            val conv = DaoAiConversation.findById(conversationId) ?: return@transaction null
            AiConversationDetail(
                id = conv.id.value,
                title = conv.title,
                messages = conv.messages.toDisplayMessages(),
                updatedAt = conv.updatedAt,
            )
        }
    }

    /**
     * Delete a conversation.
     */
    fun deleteConversation(conversationId: Int): Boolean {
        return transaction(state.database) {
            val conv = DaoAiConversation.findById(conversationId) ?: return@transaction false
            conv.delete()
            true
        }
    }

    // ─── System Prompt Construction ────────────────────────────────────────

    private fun buildSystemPrompt(): String {
        val sb = StringBuilder()
        sb.appendLine("You are Lux, an AI lighting designer assistant for a DMX lighting controller.")
        sb.appendLine("You control lights by calling tools. Always explain what you're doing to the user.")
        sb.appendLine()

        // Fixtures
        sb.appendLine("## Available Fixtures")
        for (fixture in state.show.fixtures.fixtures) {
            val groups = state.show.fixtures.groupsForFixture(fixture.key)
            sb.appendLine("- **${fixture.fixtureName}** (key=`${fixture.key}`, type=`${fixture.typeKey}`" +
                    if (groups.isNotEmpty()) ", groups=${groups.joinToString(",")}" else "" +
                    ")")
        }
        sb.appendLine()

        // Fixture type API (for scripts)
        sb.appendLine("## Fixture Type API (for run_lighting_script)")
        sb.appendLine("When writing scripts, use `fixture<TypeName>(\"key\")` to access fixtures.")
        val fixturesByType = state.show.fixtures.fixtures.groupBy { it::class }
        for ((klass, fixtures) in fixturesByType) {
            val sample = fixtures.first()
            val typeName = klass.simpleName ?: continue
            sb.appendLine("### $typeName")
            sb.appendLine("Keys: ${fixtures.joinToString(", ") { "`${it.key}`" }}")
            sb.appendLine("Properties:")
            for (prop in sample.fixtureProperties) {
                val propValue = prop.classProperty.call(sample)
                val propType = propValue?.javaClass?.simpleName ?: "Unknown"
                sb.appendLine("  - `${prop.name}` ($propType, category=${prop.category})")
            }
            sb.appendLine()
        }

        // Groups
        sb.appendLine("## Available Groups")
        for (group in state.show.fixtures.groups) {
            val caps = group.detectCapabilities()
            sb.appendLine("- **${group.name}** (${group.allMembers.size} members, capabilities=${caps.joinToString(",")})")
        }
        sb.appendLine()

        // Effect library summary
        sb.appendLine("## Effect Library (for create_fx_preset)")
        for (effect in effectLibrary) {
            val params = effect.parameters.joinToString(", ") { "${it.name}:${it.type}=${it.defaultValue}" }
            sb.appendLine("- **${effect.name}** (category=${effect.category}, output=${effect.outputType}) params: $params")
        }
        sb.appendLine()

        // Current state
        sb.appendLine("## Current State")
        sb.appendLine("BPM: ${state.show.fxEngine.masterClock.bpm.value}")
        val palette = state.show.fxEngine.getPalette()
        if (palette.isNotEmpty()) {
            val paletteStr = palette.mapIndexed { i, c -> "P${i + 1}=${c.toSerializedString()}" }.joinToString(", ")
            sb.appendLine("Palette: $paletteStr")
        } else {
            sb.appendLine("Palette: (empty)")
        }
        val activeEffects = state.show.fxEngine.getActiveEffects()
        if (activeEffects.isNotEmpty()) {
            sb.appendLine("Active effects: ${activeEffects.size}")
            for (effect in activeEffects.take(20)) {
                sb.appendLine("  - ${effect.effect.name} on ${effect.target.targetKey}.${effect.target.propertyName}" +
                        " (beat=${effect.timing.beatDivision}, blend=${effect.blendMode})")
            }
        } else {
            sb.appendLine("No active effects.")
        }
        sb.appendLine()

        // Existing presets
        val project = state.projectManager.currentProject
        val presets = transaction(state.database) {
            DaoFxPreset.find { DaoFxPresets.project eq project.id }
                .map { "${it.name} (id=${it.id.value}, ${it.effects.size} effects)" }
        }
        if (presets.isNotEmpty()) {
            sb.appendLine("## Existing Presets")
            presets.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        // Key concepts
        sb.appendLine("## Key Concepts")
        sb.appendLine("- Beat divisions: 0.125 (1/32), 0.25 (16th), 0.5 (8th), 1.0 (quarter), 2.0 (half), 4.0 (1 bar), 8.0 (2 bars)")
        sb.appendLine("- Blend modes: OVERRIDE (replace), ADDITIVE (add), MULTIPLY, MAX, MIN")
        sb.appendLine("- Distributions: LINEAR (sequential chase), UNIFIED (all same), CENTER_OUT, EDGES_IN, PING_PONG, REVERSE, SPLIT, RANDOM")
        sb.appendLine("- Colour format: hex '#FF0000', names 'red', extended '#ff0000;w128;a64;uv200', or palette refs 'P1', 'P2', etc.")
        sb.appendLine("- **Palette references**: Use P1, P2, P3 etc. in colour parameters to reference the shared palette. 1-indexed, wraps if index exceeds palette size (e.g. P4 with 3 colours = P1). Default colour effect params use palette refs (e.g. ColourCycle defaults to P1,P2,P3).")
        sb.appendLine("- **P* wildcard**: In ColourCycle, use 'P*' as the colours parameter to automatically use ALL palette colours. This is the easiest way to create a colour cycle from the palette.")
        sb.appendLine("- Palette colours are shared across all running effects and update in real-time when changed via the set_palette tool.")
        sb.appendLine("- For group effects, use distribution=LINEAR for chases, UNIFIED for all-together")
        sb.appendLine("- **Step timing**: Controls whether beat division means per-step time or total cycle time. When stepTiming=true, each step gets one full beat-division (total cycle = beatDivision × steps). When false, the entire cycle completes in one beat-division. Static effects default to stepTiming=true (chase), continuous effects default to false. You can override per-effect in the preset.")
        sb.appendLine("- UByte values range 0-255 (use 'u' suffix in scripts: 128u)")

        return sb.toString()
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun ConversationMessageDto.toAnthropicMessage(): AnthropicMessage {
        val jsonContent = buildJsonArray {
            for (block in content) {
                when (block) {
                    is ContentBlockDto.Text -> addJsonObject {
                        put("type", "text")
                        put("text", block.text)
                    }
                    is ContentBlockDto.ToolUse -> addJsonObject {
                        put("type", "tool_use")
                        put("id", block.id)
                        put("name", block.name)
                        put("input", block.input)
                    }
                    is ContentBlockDto.ToolResult -> addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", block.toolUseId)
                        put("content", block.content)
                        if (block.isError) put("is_error", true)
                    }
                }
            }
        }
        return AnthropicMessage(role = role, content = jsonContent)
    }

    /**
     * Convert stored messages into a display-friendly format for the frontend.
     * Filters out tool_use/tool_result noise, keeping only user text and assistant text + action summaries.
     */
    private fun List<ConversationMessageDto>.toDisplayMessages(): List<DisplayMessage> {
        val result = mutableListOf<DisplayMessage>()

        for (msg in this) {
            when (msg.role) {
                "user" -> {
                    // Only include text content (skip tool_result messages)
                    val text = msg.content.filterIsInstance<ContentBlockDto.Text>()
                        .joinToString("\n") { it.text }
                    if (text.isNotEmpty()) {
                        result.add(DisplayMessage(role = "user", content = text))
                    }
                }
                "assistant" -> {
                    val text = msg.content.filterIsInstance<ContentBlockDto.Text>()
                        .joinToString("\n") { it.text }
                    val toolCalls = msg.content.filterIsInstance<ContentBlockDto.ToolUse>()
                        .map { DisplayToolCall(tool = it.name) }
                    // Only add if there's text content (skip tool-only assistant turns)
                    if (text.isNotEmpty()) {
                        result.add(DisplayMessage(
                            role = "assistant",
                            content = text,
                            toolCalls = toolCalls.ifEmpty { null }
                        ))
                    }
                }
            }
        }

        return result
    }
}

// ─── Response DTOs ─────────────────────────────────────────────────────────

data class AiChatResponse(
    val conversationId: Int,
    val message: String,
    val actions: List<AiAction>,
)

data class AiAction(
    val tool: String,
    val description: String,
    val success: Boolean,
)

data class AiConversationSummary(
    val id: Int,
    val title: String?,
    val updatedAt: Long,
)

data class AiConversationDetail(
    val id: Int,
    val title: String?,
    val messages: List<DisplayMessage>,
    val updatedAt: Long,
)

data class DisplayMessage(
    val role: String,
    val content: String,
    val toolCalls: List<DisplayToolCall>? = null,
)

data class DisplayToolCall(
    val tool: String,
)
