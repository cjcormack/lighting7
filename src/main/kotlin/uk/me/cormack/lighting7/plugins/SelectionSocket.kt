package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.parseMaskGroupsLenient
import uk.me.cormack.lighting7.fx.toMaskNames
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.state.DeskSelection
import uk.me.cormack.lighting7.state.SelectionSource

// ─── Inbound ────────────────────────────────────────────────────────────

/**
 * The `selection.*` family: the desk's one shared selection
 * ([uk.me.cormack.lighting7.state.DeskSelection]). Every write lands and the `selection.state`
 * broadcast carries it back to this client along with every other (reply convention 3 in
 * `docs/websocket-engineering.md`); a client renders from the frame, never from its own echo.
 *
 * **`source` is stamped by the handler, never carried by the payload** (multi-screen plan D7).
 * A frame names no `source`; it may name a `sourceName`, which this session stands in for the
 * window identity the windows registry will announce in session 2 ([SocketScope.windowName]).
 * The handler remembers it on the socket, so a later write from the same socket is stamped with
 * the same name whether or not it repeats it.
 */
@Serializable
sealed class SelectionInMessage : InMessage()

/**
 * Replace the selection — the whole fact (D2). Order is the client's; duplicates collapse.
 * [families] absent or empty is every attribute; a name outside `PropertyMaskGroup` is dropped.
 */
@Serializable
@SerialName("selection.set")
data class SelectionSetInMessage(
    val targets: List<CueTargetDto>,
    val families: List<String>? = null,
    val sourceName: String? = null,
) : SelectionInMessage()

/**
 * Toggle one group or fixture, head by head, under the standing mask — the press a busk pad's
 * target band and an X-Touch select button both make. A group all of whose members are selected
 * is "in", and toggling it off narrows the entries that covered it.
 */
@Serializable
@SerialName("selection.toggle")
data class SelectionToggleInMessage(
    val target: CueTargetDto,
    val sourceName: String? = null,
) : SelectionInMessage()

/** Nothing selected, no mask, no mover. */
@Serializable
@SerialName("selection.clear")
data object SelectionClearInMessage : SelectionInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class SelectionOutMessage : OutMessage()

/**
 * The whole selection: the connect snapshot and the broadcast on every change. [families] absent
 * is every attribute; [source] absent means nobody has moved it since the last clear.
 */
@Serializable
@SerialName("selection.state")
data class SelectionStateOutMessage(
    val targets: List<CueTargetDto>,
    val families: List<String>? = null,
    val source: SelectionSource? = null,
) : SelectionOutMessage()

internal fun DeskSelection.Snapshot.toOutMessage() =
    SelectionStateOutMessage(targets, families?.toMaskNames(), source)

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleSelection(scope: SocketScope, message: SelectionInMessage) {
    val selection = scope.state.deskSelection
    when (message) {
        is SelectionSetInMessage -> selection.set(
            message.targets,
            parseMaskGroupsLenient(message.families),
            scope.selectionSource(message.sourceName),
        )
        is SelectionToggleInMessage -> selection.toggle(message.target, scope.selectionSource(message.sourceName))
        is SelectionClearInMessage -> selection.clear()
    }
}

/**
 * The mover to stamp: the socket's window name, refreshed by a frame that carries one. Null for
 * a socket that has never named itself — an unnamed mover is recorded as no mover, not as the
 * previous one, because the source is *who moved it last*.
 */
private fun SocketScope.selectionSource(sourceName: String?): SelectionSource? {
    sourceName?.trim()?.takeIf { it.isNotEmpty() }?.let { windowName = it }
    return windowName?.let { SelectionSource.window(it) }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

/**
 * A `StateFlow` subscription is the snapshot: it delivers the current selection on connect and
 * every change after, with no gap a one-shot read plus a delta stream could fall into.
 */
fun setupSelectionSubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.deskSelection.state) { scope.send(it.toOutMessage()) }
}
