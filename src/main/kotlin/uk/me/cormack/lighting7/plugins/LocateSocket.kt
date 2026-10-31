package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.routes.LocateTargetDto

/**
 * The `locate.*` family: one outbound frame and **no inbound** — the toggle is REST
 * (`routes/lightLocate.kt`) and the surface's `LocateSelection`. It exists because Locate can end
 * without a toggle: an edit to a located fixture makes its Locate the operator's values
 * (`show/LocateCommit.kt`), and every window's Locate button has to hear it.
 */
@Serializable
sealed class LocateOutMessage : OutMessage()

/** Every located target in the live show, as `GET /locate` lists them. The subscription is the connect snapshot. */
@Serializable
@SerialName("locate.state")
data class LocateStateOutMessage(val projectId: Int, val targets: List<LocateTargetDto>) : LocateOutMessage()

/** Follows the live show: a project switch brings a new `LocateManager` (a reload carries the old one). */
fun setupLocateSubscriptions(scope: SocketScope) {
    scope.subscribeShow({ show ->
        val projectId = show.project.id.value
        show.locateManager.activeTargets.map { targets ->
            LocateStateOutMessage(projectId, targets.map { LocateTargetDto(it.discriminator, it.key) })
        }
    }) { scope.send(it) }
}
