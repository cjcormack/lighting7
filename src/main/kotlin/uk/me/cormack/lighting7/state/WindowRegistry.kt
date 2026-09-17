package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * The desk's **windows**: every browser window signed in to it, what each is showing, and whether
 * it follows the desk selection. What makes *Show Busk on Screen 2* possible — the console gesture
 * of moving a window to the external monitor, made from either side (multi-screen plan §3.4).
 *
 * `DeskSelection`'s and `BuskPageState`'s shape, with one difference that is the whole design:
 * **this is machine-scoped, not project-scoped.** A window outlives a project switch — its `view`
 * carries the project id, so the route it is on is still meaningful afterwards — so it is *not*
 * cleared in `State`'s `projectChangedFlow` collector, and `setupWindowsSubscriptions` registers
 * in the machine band of `plugins/Sockets.kt` beside `setupMachineSubscriptions`, not in the
 * show band. The plan's §10 names the show-band alternative as the silent failure: a registry
 * that announced nothing until the show was warm would leave the desk chip reading *Desk* for
 * the wrong reason.
 *
 * **Keyed by socket, not by [Window.windowId].** A row lives exactly as long as its connection
 * ([announce] on the way in, [remove] in the connection's `finally`, beside `ownedLearnSessions`),
 * which is what makes a closed window disappear without a heartbeat. A duplicated tab copies its
 * `sessionStorage` and so announces the *same* `windowId` on a second socket: that is two rows,
 * deliberately (D9) — they are two windows, the sheet shows two, and each is addressable on its
 * own [Window.id].
 *
 * Nothing here is persisted and nothing is a table, so the sync decision tree's transient branch
 * applies and `SyncCoverageTest` gains no row (§3.7).
 */
class WindowRegistry {

    /**
     * One signed-in window.
     *
     * [id] is **socket-minted** — `SocketScope.id`, stable for the life of the connection and
     * unique across every window, including two tabs sharing a [windowId]. It is what
     * [Command.targetId] addresses and what a `selection.state` `source` carries, for exactly
     * that reason: [windowId] is not unique and could not address a duplicated tab.
     *
     * [windowId] is **client-minted** — a uuid in that tab's `sessionStorage`, so it survives a
     * reload and a reconnect and the client can recognise its own row across them.
     *
     * [user] is the authenticated caller's display name, taken from the socket rather than the
     * payload, so a window cannot claim to be someone else. Null on a bootstrap-open desk.
     */
    @Serializable
    data class Window(
        val id: String,
        val windowId: String,
        val name: String,
        val view: String,
        val fullscreen: Boolean = false,
        val follows: Boolean = true,
        val user: String? = null,
        /**
         * The window's **per-view options** as it last announced them — for the busk view, its
         * focus, split rows and sheet tab (busk-further plan D13). Free `String → String` so the
         * registry never learns a view's vocabulary: each entry in the client's `windowViews.ts`
         * says what its view contributes, and the Screens sheet renders whatever that is. Null from
         * a client that predates the field; absent from the frame then, exactly as it was sent.
         */
        val viewOptions: Map<String, String>? = null,
    )

    /**
     * A command aimed at one window by [targetId]. Rebroadcast to **every** socket rather than
     * looked up and unicast (D11), the pattern `busk.layoutChanged {pageIds}` uses: an id that is
     * not this window's matches nothing, the handler needs no session lookup, and the Screens
     * sheet on every window sees the gesture. A command whose target is not connected simply
     * matches nobody — and that is visible, because that window's `view` in [windows] does not
     * move (`FU-WINDOWS-SHOW-OFFLINE` is the item for when that stops being good enough).
     */
    sealed interface Command {
        val targetId: String

        data class Show(override val targetId: String, val view: String) : Command
        data class Rename(override val targetId: String, val name: String) : Command
        data class Fullscreen(override val targetId: String, val on: Boolean) : Command

        /**
         * Set [options] on the window whose row id is [targetId], **for [view] only** (busk-further
         * plan D13): the target applies them to its own per-tab facts if it is showing that view
         * and ignores the frame otherwise, then re-announces — so a busk `focus` arriving at a
         * window on the Prompt Book changes nothing there. One generic command rather than one per
         * option, so the registry, this file and the Screens sheet never learn the word *busk*.
         */
        data class ViewOptions(
            override val targetId: String,
            val view: String,
            val options: Map<String, String>,
        ) : Command
    }

    private val _windows = MutableStateFlow<List<Window>>(emptyList())

    /** Every signed-in window, in the order each first announced. */
    val windows: StateFlow<List<Window>> = _windows.asStateFlow()

    private val _commands = MutableSharedFlow<Command>(replay = 0, extraBufferCapacity = 64)

    /**
     * The command stream every connection rebroadcasts. `replay = 0`: a command is a gesture, not
     * a fact, so a window that connects afterwards must not replay someone else's *show*.
     */
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    /**
     * Record what this socket's window is showing. A **re-announce from the same socket replaces
     * its row in place**, keeping its position: a window announces on every open *and* on every
     * change (route, fullscreen, follow, rename), so replacement is the common case and a move to
     * the end of the list would make the sheet reorder itself every time anyone navigated.
     *
     * The two server-owned fields are not arguments: [Window.id] is the socket's own identity and
     * [Window.user] is the authenticated session's, for the reason `selection.state`'s `source` is
     * stamped rather than carried (D7) — a window that could send either could claim to be
     * another window or another operator.
     */
    fun announce(
        socketId: String,
        windowId: String,
        name: String,
        view: String,
        fullscreen: Boolean,
        follows: Boolean,
        user: String?,
        viewOptions: Map<String, String>? = null,
    ): Window {
        val window = Window(socketId, windowId, name, view, fullscreen, follows, user, viewOptions)
        _windows.update { current ->
            val at = current.indexOfFirst { it.id == socketId }
            if (at < 0) current + window else current.toMutableList().also { it[at] = window }
        }
        return window
    }

    /** Drop this socket's row, if it ever announced one. Idempotent — a socket that never did is a no-op. */
    fun remove(socketId: String) {
        _windows.update { current ->
            if (current.none { it.id == socketId }) current else current.filterNot { it.id == socketId }
        }
    }

    /** Publish a command to every connection. Dropped rather than queued if the buffer is full. */
    fun command(command: Command) {
        _commands.tryEmit(command)
    }
}
