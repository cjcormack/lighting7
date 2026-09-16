package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.state.WindowRegistry

// ─── Inbound ────────────────────────────────────────────────────────────

/**
 * The `windows.*` family: the desk's registry of signed-in browser windows
 * ([uk.me.cormack.lighting7.state.WindowRegistry]), and the three commands one window sends to
 * move another (multi-screen plan §3.4).
 *
 * Reply convention 3, like `selection.*` and `busk.*`: nothing is answered directly. An announce
 * lands and the `windows.state` broadcast carries the new list to every client including this
 * one; a command is rebroadcast verbatim and its acknowledgement is the target's own next
 * announce, which is what makes *a command to a disconnected window is lost* legible rather than
 * silent — that window's `view` simply does not move.
 *
 * **Machine-scoped**: registered in the pre-warm-up band of [configureSockets], not beside the
 * show-dependent families, because none of it reads `state.show` and a window outlives a project
 * switch. See [WindowRegistry]'s header for why the show band would be a silent failure.
 */
@Serializable
sealed class WindowsInMessage : InMessage()

/**
 * This window, as it stands. Sent on **every** socket open and on every change — a route
 * navigation, going full screen, flipping follow/local, a rename. The one kind of client-side
 * `open` branch the frontend's "where a WS bridge subscribes" rule allows: re-sending what the
 * *server* forgot, because the registry keys by socket and a reconnect is a new socket.
 *
 * [windowId] is the tab's own `sessionStorage` uuid; the row's addressable id is minted here from
 * the socket and comes back on `windows.state`. There is deliberately no `id` and no `user` field:
 * both are the server's to say (D7's rule, applied to the same problem).
 */
@Serializable
@SerialName("windows.announce")
data class WindowsAnnounceInMessage(
    val windowId: String,
    val name: String,
    val view: String,
    val fullscreen: Boolean = false,
    val follows: Boolean = true,
) : WindowsInMessage()

/** Show [view] on the window whose row id is [targetId]. Rebroadcast as-is (D11). */
@Serializable
@SerialName("windows.show")
data class WindowsShowInMessage(val targetId: String, val view: String) : WindowsInMessage()

/**
 * Rename the window whose row id is [targetId]. Rebroadcast as-is: the *target* renames itself
 * and re-announces, so the registry learns the new name the same way it learns every other
 * change. Nothing is written here — a rename applied server-side would be overwritten by that
 * window's next announce anyway.
 */
@Serializable
@SerialName("windows.rename")
data class WindowsRenameInMessage(val targetId: String, val name: String) : WindowsInMessage()

/**
 * Ask the window whose row id is [targetId] to enter or leave full screen. `on: false` is
 * `document.exitFullscreen()` and needs no gesture; `on: true` **cannot** call
 * `requestFullscreen` (MDN: transient user activation is required), so the target raises its
 * *Return to full screen* banner instead. Rebroadcast as-is either way.
 */
@Serializable
@SerialName("windows.fullscreen")
data class WindowsFullscreenInMessage(val targetId: String, val on: Boolean) : WindowsInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class WindowsOutMessage : OutMessage()

/**
 * Every signed-in window, in announce order: the connect snapshot and the broadcast on every
 * change. `StateFlow`-backed, so the subscription *is* the snapshot — it arrives before this
 * window has announced anything, and the list it carries is simply the other windows.
 */
@Serializable
@SerialName("windows.state")
data class WindowsStateOutMessage(val windows: List<WindowRegistry.Window>) : WindowsOutMessage()

/**
 * The three commands, rebroadcast to every socket with the payload the sender wrote. They carry
 * the inbound spelling deliberately — a client reads `targetId` against its own row id and
 * ignores everything else, so a second name for one gesture would buy nothing. (`speedMasters.state`
 * is the existing precedent for one name travelling in both directions.)
 */
@Serializable
@SerialName("windows.show")
data class WindowsShowOutMessage(val targetId: String, val view: String) : WindowsOutMessage()

@Serializable
@SerialName("windows.rename")
data class WindowsRenameOutMessage(val targetId: String, val name: String) : WindowsOutMessage()

@Serializable
@SerialName("windows.fullscreen")
data class WindowsFullscreenOutMessage(val targetId: String, val on: Boolean) : WindowsOutMessage()

internal fun WindowRegistry.Command.toOutMessage(): WindowsOutMessage = when (this) {
    is WindowRegistry.Command.Show -> WindowsShowOutMessage(targetId, view)
    is WindowRegistry.Command.Rename -> WindowsRenameOutMessage(targetId, name)
    is WindowRegistry.Command.Fullscreen -> WindowsFullscreenOutMessage(targetId, on)
}

// ─── Handler ────────────────────────────────────────────────────────────

fun handleWindows(scope: SocketScope, message: WindowsInMessage) {
    val registry = scope.state.windowRegistry
    when (message) {
        is WindowsAnnounceInMessage -> {
            scope.window = registry.announce(
                socketId = scope.id,
                windowId = message.windowId,
                name = message.name,
                view = message.view,
                fullscreen = message.fullscreen,
                follows = message.follows,
                user = scope.user?.displayName,
            )
        }
        is WindowsShowInMessage ->
            registry.command(WindowRegistry.Command.Show(message.targetId, message.view))
        is WindowsRenameInMessage ->
            registry.command(WindowRegistry.Command.Rename(message.targetId, message.name))
        is WindowsFullscreenInMessage ->
            registry.command(WindowRegistry.Command.Fullscreen(message.targetId, message.on))
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

/**
 * Registered in the **machine band** of [configureSockets] — before the warm-up gate, beside
 * [setupMachineSubscriptions] — because nothing here reads `state.show`.
 *
 * A `StateFlow` subscription is the snapshot, exactly as `selection.state` is. The commands ride a
 * replay-0 `SharedFlow`, so nothing is re-delivered on connect.
 */
fun setupWindowsSubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.windowRegistry.windows) { scope.send(WindowsStateOutMessage(it)) }
    scope.subscribe(scope.state.windowRegistry.commands) { scope.send(it.toOutMessage()) }
}
