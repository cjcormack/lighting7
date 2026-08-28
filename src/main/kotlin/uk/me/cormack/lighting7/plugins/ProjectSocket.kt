package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.state.State

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
        is ProjectStateInMessage -> scope.send(buildProjectStateMessage(scope.state))
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupProjectSubscriptions(scope: SocketScope) {
    // The connect snapshot is `projectState`. It used to be the replay-1 `projectChanged` below,
    // which only arrived if a switch happened to have occurred since boot — so a client had no
    // choice but to ask. `projectChanged` is now purely an event: it fires on switches only.
    scope.sendSnapshot { send(buildProjectStateMessage(state)) }

    scope.subscribe(scope.state.projectManager.projectChangedFlow) { event ->
        scope.send(ProjectChangedOutMessage(
            previousProjectId = event.previousProjectId,
            newProjectId = event.newProjectId,
            newProjectName = event.newProjectName,
        ))
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────

private fun buildProjectStateMessage(state: State): ProjectStateOutMessage {
    val project = state.projectManager.currentProject
    return ProjectStateOutMessage(
        projectId = project.id.value,
        projectName = project.name,
        description = project.description,
    )
}
