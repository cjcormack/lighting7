package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class ProjectInMessage : InMessage()

@Serializable
@SerialName("projectState")
data object ProjectStateInMessage : ProjectInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class ProjectOutMessage : OutMessage()

@Serializable
@SerialName("projectState")
data class ProjectStateOutMessage(
    val projectId: Int,
    val projectName: String,
    val description: String?,
) : ProjectOutMessage()

@Serializable
@SerialName("projectChanged")
data class ProjectChangedOutMessage(
    val previousProjectId: Int?,
    val newProjectId: Int,
    val newProjectName: String,
) : ProjectOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleProject(scope: SocketScope, message: ProjectInMessage) {
    when (message) {
        is ProjectStateInMessage -> {
            val project = scope.state.projectManager.currentProject
            scope.send(ProjectStateOutMessage(
                projectId = project.id.value,
                projectName = project.name,
                description = project.description,
            ))
        }
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupProjectSubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.projectManager.projectChangedFlow) { event ->
        scope.send(ProjectChangedOutMessage(
            previousProjectId = event.previousProjectId,
            newProjectId = event.newProjectId,
            newProjectName = event.newProjectName,
        ))
    }
}
