package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.delete
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.ai.ProjectChangedDuringChatException
import uk.me.cormack.lighting7.state.State

/**
 * REST API routes for AI chat.
 *
 * Chat itself is a **live-runtime** surface: the tools Claude calls drive whatever show is
 * loaded, so the endpoint carries no `{projectId}` and always means the current project — see
 * `docs/api-conventions.md` §"Project scoping". The conversation *history* it writes is
 * persisted project data and lives under `/projects/{projectId}/ai/conversations`
 * ([routeApiRestProjectAiConversations]).
 */
internal fun Route.routeApiRestAiChat(state: State) {
    route("/ai") {
        // POST /ai/chat - Send a message to Claude
        post("/chat") {
            val aiService = state.aiService ?: run {
                call.respondAiUnavailable()
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
            } catch (e: ProjectChangedDuringChatException) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Project changed"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Not found"))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("AI error: ${e.message ?: "Unknown error"}")
                )
            }
        }
    }
}

/**
 * Conversation history for one project. Mounted under `/projects` alongside the other persisted
 * project data. Reads and the delete both take any project id — nothing here touches the running
 * show, so there is no live state for a non-current project to be incoherent with.
 */
internal fun Route.routeApiRestProjectAiConversations(state: State) {
    // GET /{projectId}/ai/conversations
    get<ProjectAiConversationsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val aiService = state.aiService ?: run {
                call.respondAiUnavailable()
                return@withProject
            }
            call.respond(aiService.listConversations(project).map {
                AiConversationSummaryDto(id = it.id, title = it.title, updatedAt = it.updatedAt)
            })
        }
    }

    // GET /{projectId}/ai/conversations/{conversationId}
    get<ProjectAiConversationResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val aiService = state.aiService ?: run {
                call.respondAiUnavailable()
                return@withProject
            }
            val conversation = aiService.getConversation(project, resource.conversationId)
            if (conversation == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(CONVERSATION_NOT_FOUND))
                return@withProject
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
    }

    // DELETE /{projectId}/ai/conversations/{conversationId}
    delete<ProjectAiConversationResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val aiService = state.aiService ?: run {
                call.respondAiUnavailable()
                return@withProject
            }
            if (aiService.deleteConversation(project, resource.conversationId)) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(CONVERSATION_NOT_FOUND))
            }
        }
    }
}

private const val CONVERSATION_NOT_FOUND = "Conversation not found"

/**
 * `State.aiService` is absent whenever no API key is configured, which is the normal state of a
 * desk that doesn't use the assistant — so this is a 503 about the deployment, not a 404 about
 * the URL.
 */
private suspend fun ApplicationCall.respondAiUnavailable() {
    respond(
        HttpStatusCode.ServiceUnavailable,
        ErrorResponse("AI service not available. Set ANTHROPIC_API_KEY to enable."),
    )
}

@Resource("/{projectId}/ai/conversations")
data class ProjectAiConversationsResource(val projectId: String)

@Resource("/{conversationId}")
data class ProjectAiConversationResource(
    val parent: ProjectAiConversationsResource,
    val conversationId: Int,
)

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
