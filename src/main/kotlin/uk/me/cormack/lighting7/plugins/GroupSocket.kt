package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithUv
import uk.me.cormack.lighting7.state.State

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class GroupInMessage : InMessage()

@Serializable
@SerialName("groupsState")
data object GroupsStateInMessage : GroupInMessage()

@Serializable
@SerialName("addGroupFx")
data class AddGroupFxInMessage(
    val groupName: String,
    val effectType: String,
    val propertyName: String,
    val beatDivision: Double = 1.0,
    val blendMode: String = "OVERRIDE",
    val distribution: String = "LINEAR",
    val phaseOffset: Double = 0.0,
) : GroupInMessage()

@Serializable
@SerialName("clearGroupFx")
data class ClearGroupFxInMessage(val groupName: String) : GroupInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class GroupOutMessage : OutMessage()

@Serializable
data class GroupSummary(
    val name: String,
    val memberCount: Int,
    val capabilities: List<String>,
)

@Serializable
@SerialName("groupsState")
data class GroupsStateOutMessage(
    val groups: List<GroupSummary>,
) : GroupOutMessage()

@Serializable
@SerialName("groupFxCleared")
data class GroupFxClearedOutMessage(
    val groupName: String,
    val removedCount: Int,
) : GroupOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleGroup(scope: SocketScope, message: GroupInMessage) {
    when (message) {
        is GroupsStateInMessage -> scope.send(buildGroupsStateMessage(scope.state))
        is AddGroupFxInMessage -> {
            // Group effect creation is REST-only; the WS path is intentionally a no-op.
        }
        is ClearGroupFxInMessage -> {
            try {
                val group = scope.state.show.fixtures.untypedGroup(message.groupName)
                val groupCount = scope.state.show.fxEngine.removeEffectsForGroup(message.groupName)
                val fixtureCount = group.sumOf {
                    scope.state.show.fxEngine.removeEffectsForFixture(it.key)
                }
                scope.send(GroupFxClearedOutMessage(message.groupName, groupCount + fixtureCount))
            } catch (_: Exception) {
                // Group not found — silently ignore.
            }
        }
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────

private fun buildGroupsStateMessage(state: State): GroupsStateOutMessage {
    val groups = state.show.fixtures.groups.map { group ->
        val capabilities = mutableListOf<String>()
        if (group.isNotEmpty()) {
            val first = group.first().fixture
            if (first is WithDimmer && group.all { it.fixture is WithDimmer }) capabilities.add("dimmer")
            if (first is WithColour && group.all { it.fixture is WithColour }) capabilities.add("colour")
            if (first is WithPosition && group.all { it.fixture is WithPosition }) capabilities.add("position")
            if (first is WithUv && group.all { it.fixture is WithUv }) capabilities.add("uv")
        }
        GroupSummary(
            name = group.name,
            memberCount = group.size,
            capabilities = capabilities,
        )
    }
    return GroupsStateOutMessage(groups)
}
