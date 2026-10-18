package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.dmx.ParkSnapshot
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.routes.CODE_VENUE_ADMIN_ONLY
import uk.me.cormack.lighting7.state.State

private val parkLogger = org.slf4j.LoggerFactory.getLogger("ParkSocket")

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class ParkInMessage : InMessage()

@Serializable
@SerialName("parkState")
data object ParkStateInMessage : ParkInMessage()

@Serializable
@SerialName("parkChannel")
data class ParkChannelInMessage(
    val universe: Int,
    val channel: Int,
    val value: UByte,
) : ParkInMessage()

/**
 * Unpark a channel. On a show on a venue, a venue park is **released** for this show (stored as the
 * show's release, the version untouched), and the show's own park over a venue park brings the venue's
 * back (`ParkManager.unpark`).
 */
@Serializable
@SerialName("unparkChannel")
data class UnparkChannelInMessage(
    val universe: Int,
    val channel: Int,
) : ParkInMessage()

/**
 * Take the venue's park back on a channel, in a show on a venue: drops the show's release of it, and
 * the show's own park over it (`ParkManager.restoreVenuePark`).
 */
@Serializable
@SerialName("restoreChannelPark")
data class RestoreChannelParkInMessage(
    val universe: Int,
    val channel: Int,
) : ParkInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class ParkOutMessage : OutMessage()

/**
 * One parked channel. [owner] is whose park it is — `SHOW`, the project's own (in a venue, the venue's
 * draft), or `VENUE`, the pinned venue version's, which a show on that venue inherits — and is always
 * sent (no default: the WS `Json` drops defaults). [venueValue] is the venue's park under a show's own
 * on the same channel, which unparking the show's brings back; absent when there is none.
 */
@Serializable
data class ParkedChannelState(
    val universe: Int,
    val channel: Int,
    val value: UByte,
    val owner: RigOwner,
    val venueValue: UByte? = null,
)

/** A venue park the show has released; [venueValue] absent when the pinned version parks nothing there (dormant). */
@Serializable
data class ReleasedParkState(
    val universe: Int,
    val channel: Int,
    val venueValue: UByte? = null,
)

/** The whole park list. [released] has no default, so an empty list is still sent. */
@Serializable
@SerialName("parkState")
data class ParkStateOutMessage(
    val channels: List<ParkedChannelState>,
    val released: List<ReleasedParkState>,
) : ParkOutMessage()

/** A park write refused, to the socket that asked: `VENUE_ADMIN_ONLY` (an operator's on a venue), or `PARK_REFUSED`. */
@Serializable
@SerialName("parkError")
data class ParkErrorOutMessage(
    val code: String,
    val message: String,
) : ParkOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handlePark(scope: SocketScope, message: ParkInMessage) {
    val parkManager = scope.state.show.parkManager
    if (message !is ParkStateInMessage) {
        // A venue's parks are its rig (venue plan D3): an operator's park write on a venue is refused,
        // as the REST write guard and MCP refuse an operator's stored write to one. In a show either
        // role parks; a zero-user desk's socket counts as an admin.
        parkWriteRefusal(scope)?.let {
            parkLogger.warn("park write refused: {}", it.message)
            scope.send(it)
            return
        }
    }
    when (message) {
        is ParkStateInMessage -> scope.send(buildParkStateMessage(scope.state))
        is ParkChannelInMessage -> {
            // A park at a firing level on a one-shot trigger or its arm would be a held fire (stage-view
            // plan session 9). Refused with a log and a `parkError`, and the park list the client already
            // holds stays as it was.
            // Likewise a park that would hold a fixture command (fixture optics plan session 7).
            (scope.state.show.triggerOutput.parkRefusal(message.universe, message.channel, message.value)
                ?: scope.state.show.commandOutput.parkRefusal(message.universe, message.channel, message.value))?.let {
                parkLogger.warn("parkChannel refused: {}", it)
                scope.send(ParkErrorOutMessage(CODE_PARK_REFUSED, it))
                return
            }
            // Nothing to nudge: controllers consult the ParkSource at transmit time, so the
            // park lands on the next frame of the affected universe.
            parkManager.park(message.universe, message.channel, message.value)
            scope.state.show.fxEngine.provenance.emitUpdate()
        }
        is UnparkChannelInMessage -> {
            parkManager.unpark(message.universe, message.channel)
            scope.state.show.fxEngine.provenance.emitUpdate()
        }
        is RestoreChannelParkInMessage -> {
            if (parkManager.restoreVenuePark(message.universe, message.channel)) {
                scope.state.show.fxEngine.provenance.emitUpdate()
            }
        }
    }
}

internal const val CODE_PARK_REFUSED = "PARK_REFUSED"

/** Why this socket may not change the live project's parks, or null when it may. */
private fun parkWriteRefusal(scope: SocketScope): ParkErrorOutMessage? {
    val user = scope.user ?: return null
    if (user.role == UserRole.ADMIN) return null
    val kind = transaction(scope.state.database) { scope.state.projectManager.currentProject.kind }
    if (kind != ProjectKind.VENUE) return null
    return ParkErrorOutMessage(CODE_VENUE_ADMIN_ONLY, "Only an admin can change a venue's parks.")
}

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupParkSubscriptions(scope: SocketScope) {
    scope.subscribeShow({ it.parkManager.parkStateFlow }) { snapshot ->
        scope.send(snapshot.toMessage())
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────

private fun buildParkStateMessage(state: State): ParkStateOutMessage = state.show.parkManager.snapshot().toMessage()

private fun ParkSnapshot.toMessage() = ParkStateOutMessage(
    channels = parked.map { ParkedChannelState(it.universe, it.channel, it.value, it.owner, it.venueValue) },
    released = released.map { ReleasedParkState(it.universe, it.channel, it.venueValue) },
)
