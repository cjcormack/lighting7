package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.parseMaskGroupsLenient
import uk.me.cormack.lighting7.fx.toMaskNames
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.state.DeskSelection
import uk.me.cormack.lighting7.state.SelectionSource
import uk.me.cormack.lighting7.state.SubselectMode
import org.slf4j.LoggerFactory

private val selectionLogger = LoggerFactory.getLogger("selectionSocket")

// ─── Inbound ────────────────────────────────────────────────────────────

/**
 * The `selection.*` family: the desk's one shared selection
 * ([uk.me.cormack.lighting7.state.DeskSelection]). Every write lands and the `selection.state`
 * broadcast carries it back to this client along with every other (reply convention 3 in
 * `docs/websocket-engineering.md`); a client renders from the frame, never from its own echo.
 *
 * **`source` is stamped by the handler, never carried by the payload** (multi-screen plan D7).
 * A frame names no `source`: the handler reads the socket's own announced window
 * ([SocketScope.window], set by `windows.announce`) and stamps its name and row id. A socket that
 * has not announced falls back to session 1's name-only stub — the `sourceName` a pre-registry
 * client puts on the frame, remembered on the socket so a later write carries it without
 * repeating it. Neither path lets a window claim to be another: the id is the socket's, minted
 * server-side, and a `sourceName` carries none at all.
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

/**
 * Rewrite the selection's targets by a [uk.me.cormack.lighting7.state.SubselectMode] name — the
 * Cells chip and the `SelectionCells` / `SelectionNext` / `SelectionPrev` buttons, one rule on the
 * desk (busk-further plan D12). The mask is kept; `source` is stamped as for any write. An unknown
 * mode is dropped with a log line, as an unknown family name is.
 */
@Serializable
@SerialName("selection.subselect")
data class SelectionSubselectInMessage(
    val mode: String,
    val sourceName: String? = null,
) : SelectionInMessage()

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
        is SelectionSubselectInMessage -> {
            val mode = SubselectMode.byName(message.mode)
            if (mode == null) {
                selectionLogger.warn("selection.subselect dropped: '{}' is not a mode", message.mode)
            } else {
                selection.subselect(mode, scope.selectionSource(message.sourceName))
            }
        }
    }
}

/**
 * The mover to stamp.
 *
 * Shared with `hand.pickUp`, which stamps `pickedUpOn` from it: "who did this" is one fact about a
 * socket, and two copies of the fallback order would be two chances to read a payload the rule says
 * must never be read.
 *
 * The announced window wins: its name *and* its row id, so the chip can say *from Screen 1* and a
 * client can tell its own write from another window's. The id stamped is the registry row's
 * socket-minted [uk.me.cormack.lighting7.state.WindowRegistry.Window.id], not the client-minted
 * `windowId` D7's sketch names — `windowId` is not unique (a duplicated tab shares one, D9), and
 * the unique id is the one `windows.show` addresses, so the two agree on what "that window" means.
 *
 * Failing that, session 1's `sourceName` stub, remembered on the socket. Null for a socket that
 * has done neither — an unnamed mover is recorded as no mover, not as the previous one, because
 * the source is *who moved it last*.
 */
internal fun SocketScope.selectionSource(sourceName: String?): SelectionSource? {
    window?.let { return SelectionSource.window(it.name, it.id) }
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
