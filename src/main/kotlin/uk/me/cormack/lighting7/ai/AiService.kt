package uk.me.cormack.lighting7.ai

import io.ktor.server.config.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.detectCapabilities
import uk.me.cormack.lighting7.fx.genericColourRows
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.models.toIsoUtc

/**
 * Thrown when the desk's current project changes part-way through [AiService.chat]. Answered as
 * 409 by the route, matching the "that project isn't current" refusal the rest of the HTTP surface
 * uses — see `docs/api-conventions.md` §"Project scoping".
 */
class ProjectChangedDuringChatException(message: String) : Exception(message)

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
    private val briefing = RigBriefing(state)

    /**
     * Send a user message to Claude and return the response.
     *
     * If [conversationId] is null, a new conversation is created.
     * The conversation is persisted after each exchange.
     *
     * @return The AI response including the conversation ID for continuation.
     */
    suspend fun chat(conversationId: Int?, userMessage: String): AiChatResponse {
        val now = nowUtc()

        // Load or create conversation. Chat is a live-runtime surface — it drives whatever
        // show is loaded — so a conversation from another project is not merely uninteresting,
        // it would silently accumulate this show's history under that project's id. Scope the
        // lookup to the current project rather than trusting the caller's id (see F2).
        val currentProject = state.projectManager.currentProject
        val (convId, existingMessages) = if (conversationId != null) {
            val conv = transaction(state.database) {
                conversationIn(currentProject, conversationId)
            } ?: throw IllegalArgumentException("Conversation not found: $conversationId")
            convId@(conv.id.value) to conv.messages
        } else {
            val conv = transaction(state.database) {
                DaoAiConversation.new {
                    title = null
                    project = currentProject
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

            // Both `buildSystemPrompt()` and every tool in `AiTools` read the *live* show, and
            // each round awaits the Anthropic API — so a concurrent `set-current` would point the
            // remaining tool calls at a different rig while the transcript kept accruing against
            // this conversation's project. Refuse rather than straddle the two. Checking once per
            // round narrows the window to a single round; closing it entirely would mean holding
            // a lock across an outbound HTTP call, which is worse.
            if (state.projectManager.currentProject.id != currentProject.id) {
                persistConversation(convId, existingMessages + newStoredMessages, userMessage)
                throw ProjectChangedDuringChatException(
                    "The current project changed while this reply was being generated. " +
                        "The conversation was stopped part-way; send the message again."
                )
            }

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

        persistConversation(convId, existingMessages + newStoredMessages, userMessage)

        return AiChatResponse(
            conversationId = convId,
            message = finalText,
            actions = actions,
        )
    }

    /**
     * Write the transcript back. Also called on the abort path, so a chat cut short by a project
     * change still leaves the operator the exchange that got as far as it did.
     */
    private fun persistConversation(
        convId: Int,
        allMessages: List<ConversationMessageDto>,
        userMessage: String,
    ) {
        transaction(state.database) {
            val conv = DaoAiConversation.findById(convId)!!
            conv.messages = allMessages
            conv.updatedAt = nowUtc()
            // Auto-title from first user message if not set
            if (conv.title == null) {
                conv.title = userMessage.take(100)
            }
        }
    }

    /**
     * List all conversations belonging to [project], newest first.
     *
     * Conversation history is persisted project data, so every accessor here takes the project
     * explicitly rather than assuming the current one — the routes hang off
     * `/projects/{projectId}/ai/conversations`.
     */
    fun listConversations(project: DaoProject): List<AiConversationSummary> {
        return transaction(state.database) {
            DaoAiConversation.find { DaoAiConversations.project eq project.id }
                .orderBy(DaoAiConversations.updatedAt to SortOrder.DESC)
                .map { conv ->
                    AiConversationSummary(
                        id = conv.id.value,
                        title = conv.title,
                        updatedAt = conv.updatedAt.toIsoUtc(),
                    )
                }
        }
    }

    /**
     * Get a full conversation with display-friendly messages, or null if [conversationId] does
     * not exist or belongs to a different project.
     */
    fun getConversation(project: DaoProject, conversationId: Int): AiConversationDetail? {
        return transaction(state.database) {
            val conv = conversationIn(project, conversationId) ?: return@transaction null
            AiConversationDetail(
                id = conv.id.value,
                title = conv.title,
                messages = conv.messages.toDisplayMessages(),
                updatedAt = conv.updatedAt.toIsoUtc(),
            )
        }
    }

    /**
     * Delete a conversation. Returns false if it does not exist or belongs to a different
     * project — the caller cannot tell the two apart, which is the point.
     */
    fun deleteConversation(project: DaoProject, conversationId: Int): Boolean {
        return transaction(state.database) {
            val conv = conversationIn(project, conversationId) ?: return@transaction false
            conv.delete()
            true
        }
    }

    /**
     * Look a conversation up by id, but only within [project]. Call inside a transaction.
     *
     * Filtered on the FK column rather than `conv.project.id`: dereferencing the `referencedOn`
     * relation would load the whole [DaoProject] row just to compare an id.
     */
    private fun conversationIn(project: DaoProject, conversationId: Int): DaoAiConversation? =
        DaoAiConversation.find {
            (DaoAiConversations.id eq conversationId) and (DaoAiConversations.project eq project.id)
        }.singleOrNull()

    // ─── System Prompt Construction ────────────────────────────────────────

    private fun buildSystemPrompt(): String {
        val sb = StringBuilder()
        sb.appendLine("You are Lux, an AI lighting designer assistant for a DMX lighting controller.")
        sb.appendLine("You control lights by calling tools. Always explain what you're doing to the user.")
        sb.appendLine()
        sb.append(briefing.describeRig())
        sb.append(briefing.fixtureTypeApi())
        sb.append(briefing.keyConcepts(scriptTool = true))
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
    /** ISO-8601 UTC, sortable as text. See `Instant.toIsoUtc`. */
    val updatedAt: String,
)

data class AiConversationDetail(
    val id: Int,
    val title: String?,
    val messages: List<DisplayMessage>,
    /** ISO-8601 UTC, sortable as text. See `Instant.toIsoUtc`. */
    val updatedAt: String,
)

data class DisplayMessage(
    val role: String,
    val content: String,
    val toolCalls: List<DisplayToolCall>? = null,
)

data class DisplayToolCall(
    val tool: String,
)
