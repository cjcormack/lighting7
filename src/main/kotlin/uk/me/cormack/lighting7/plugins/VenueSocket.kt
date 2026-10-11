package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.state.VenueHoldService

/**
 * The `venue.*` family (venue plan §3.5, session 6): one outbound frame and **no inbound**. *Run
 * against* and Update are REST (`routes/venueUpdate.kt`), so the socket gains no operation and
 * `FU-AUTH-WS-PER-MESSAGE` stays unfired.
 */
@Serializable
sealed class VenueOutMessage : OutMessage()

/**
 * The current show's hold (venue plan P5, D7): [held] when it is behind its venue on output and sends
 * zero on every channel, [runAgainst] when the operator has lifted that for the session, [pinned] the
 * version the show uses and [installed] the venue's latest. All null / false for a standalone show,
 * a venue, and a show on its installed version. `StateFlow`-backed, so the subscription is the connect
 * snapshot and every change is a whole frame.
 */
@Serializable
@SerialName("venue.hold")
data class VenueHoldOutMessage(
    val projectId: Int? = null,
    val held: Boolean = false,
    val pinned: Int? = null,
    val installed: Int? = null,
    val runAgainst: Boolean = false,
) : VenueOutMessage()

internal fun VenueHoldService.Status.toMessage() = VenueHoldOutMessage(
    projectId = projectId,
    held = held,
    pinned = pinned,
    installed = installed,
    runAgainst = runAgainst,
)

/** Registered in the **show band** of `configureSockets`: the hold is the current project's. */
fun setupVenueSubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.venueHold.status) { scope.send(it.toMessage()) }
}
