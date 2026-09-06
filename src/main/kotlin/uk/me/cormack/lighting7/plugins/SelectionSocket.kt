package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.models.CueTargetDto

// ─── Inbound ────────────────────────────────────────────────────────────

/**
 * The `selection.*` family: the desk's one shared selection
 * ([uk.me.cormack.lighting7.state.DeskSelection]). Every write lands and the `selection.state`
 * broadcast carries it back to this client along with every other (reply convention 3 in
 * `docs/websocket-engineering.md`); a client renders from the frame, never from its own echo.
 */
@Serializable
sealed class SelectionInMessage : InMessage()

/** Replace the selection. Order is the client's; duplicates collapse. */
@Serializable
@SerialName("selection.set")
data class SelectionSetInMessage(val targets: List<CueTargetDto>) : SelectionInMessage()

/**
 * Toggle one group or fixture, head by head — the press a busk pad's target band and an X-Touch
 * select button both make. A group all of whose members are selected is "in", and toggling it
 * off narrows the entries that covered it.
 */
@Serializable
@SerialName("selection.toggle")
data class SelectionToggleInMessage(val target: CueTargetDto) : SelectionInMessage()

@Serializable
@SerialName("selection.clear")
data object SelectionClearInMessage : SelectionInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class SelectionOutMessage : OutMessage()

/** The whole selection: the connect snapshot and the broadcast on every change. */
@Serializable
@SerialName("selection.state")
data class SelectionStateOutMessage(val targets: List<CueTargetDto>) : SelectionOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleSelection(scope: SocketScope, message: SelectionInMessage) {
    val selection = scope.state.deskSelection
    when (message) {
        is SelectionSetInMessage -> selection.set(message.targets)
        is SelectionToggleInMessage -> selection.toggle(message.target)
        is SelectionClearInMessage -> selection.clear()
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

/**
 * A `StateFlow` subscription is the snapshot: it delivers the current selection on connect and
 * every change after, with no gap a one-shot read plus a delta stream could fall into.
 */
fun setupSelectionSubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.deskSelection.targets) { scope.send(SelectionStateOutMessage(it)) }
}
