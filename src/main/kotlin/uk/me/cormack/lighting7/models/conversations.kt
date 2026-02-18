package uk.me.cormack.lighting7.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.json.json

/**
 * A single content block within a conversation message.
 * Mirrors the Anthropic API content block format so conversations
 * can be replayed exactly (including tool_use / tool_result exchanges).
 */
@Serializable
sealed class ContentBlockDto {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlockDto()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
    ) : ContentBlockDto()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id")
        val toolUseId: String,
        val content: String,
        @SerialName("is_error")
        val isError: Boolean = false,
    ) : ContentBlockDto()
}

/**
 * A single message in a conversation (user or assistant turn).
 */
@Serializable
data class ConversationMessageDto(
    val role: String,
    val content: List<ContentBlockDto>,
)

object DaoAiConversations : IntIdTable("ai_conversations") {
    val title = varchar("title", 255).nullable()
    val project = reference("project_id", DaoProjects)
    val messages = json<List<ConversationMessageDto>>("messages", Json)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}

class DaoAiConversation(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoAiConversation>(DaoAiConversations)

    var title by DaoAiConversations.title
    var project by DaoProject referencedOn DaoAiConversations.project
    var messages by DaoAiConversations.messages
    var createdAt by DaoAiConversations.createdAt
    var updatedAt by DaoAiConversations.updatedAt
}
