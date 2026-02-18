package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.state.State

/**
 * REST API routes for AI chat and conversation management.
 */
internal fun Route.routeApiRestAiChat(state: State) {
    route("/ai") {
        // POST /ai/chat - Send a message to Claude
        post("/chat") {
            val aiService = state.aiService
            if (aiService == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("AI service not available. Set ANTHROPIC_API_KEY to enable.")
                )
                return@post
            }

            val request = call.receive<AiChatRequest>()
            try {
                val response = aiService.chat(request.conversationId, request.message)
                call.respond(AiChatResponseDto(
                    conversationId = response.conversationId,
                    message = response.message,
                    actions = response.actions.map {
                        AiActionDto(tool = it.tool, description = it.description, success = it.success)
                    }
                ))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Not found"))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("AI error: ${e.message ?: "Unknown error"}")
                )
            }
        }

        // GET /ai/conversations - List conversations for current project
        get("/conversations") {
            val aiService = state.aiService
            if (aiService == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI service not available"))
                return@get
            }

            val conversations = aiService.listConversations()
            call.respond(conversations.map {
                AiConversationSummaryDto(id = it.id, title = it.title, updatedAt = it.updatedAt)
            })
        }

        // GET /ai/conversations/{id} - Get full conversation
        get("/conversations/{id}") {
            val aiService = state.aiService
            if (aiService == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI service not available"))
                return@get
            }

            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid conversation ID"))
                return@get
            }

            val conversation = aiService.getConversation(id)
            if (conversation == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Conversation not found"))
                return@get
            }

            call.respond(AiConversationDetailDto(
                id = conversation.id,
                title = conversation.title,
                messages = conversation.messages.map {
                    DisplayMessageDto(
                        role = it.role,
                        content = it.content,
                        toolCalls = it.toolCalls?.map { tc -> DisplayToolCallDto(tool = tc.tool) }
                    )
                },
                updatedAt = conversation.updatedAt,
            ))
        }

        // DELETE /ai/conversations/{id} - Delete a conversation
        delete("/conversations/{id}") {
            val aiService = state.aiService
            if (aiService == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI service not available"))
                return@delete
            }

            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid conversation ID"))
                return@delete
            }

            if (aiService.deleteConversation(id)) {
                call.respond(HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Conversation not found"))
            }
        }
    }
}

// ─── DTOs ──────────────────────────────────────────────────────────────────

@Serializable
data class AiChatRequest(
    val conversationId: Int? = null,
    val message: String,
)

@Serializable
data class AiChatResponseDto(
    val conversationId: Int,
    val message: String,
    val actions: List<AiActionDto>,
)

@Serializable
data class AiActionDto(
    val tool: String,
    val description: String,
    val success: Boolean,
)

@Serializable
data class AiConversationSummaryDto(
    val id: Int,
    val title: String?,
    val updatedAt: Long,
)

@Serializable
data class AiConversationDetailDto(
    val id: Int,
    val title: String?,
    val messages: List<DisplayMessageDto>,
    val updatedAt: Long,
)

@Serializable
data class DisplayMessageDto(
    val role: String,
    val content: String,
    val toolCalls: List<DisplayToolCallDto>? = null,
)

@Serializable
data class DisplayToolCallDto(
    val tool: String,
)
