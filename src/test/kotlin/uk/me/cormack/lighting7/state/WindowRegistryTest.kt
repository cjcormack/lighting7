package uk.me.cormack.lighting7.state

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WindowRegistry]'s three rules, each of which the multi-screen plan names a decision for:
 * a row is keyed by **socket** and lives exactly as long as one (§3.4); a re-announce from that
 * socket **replaces** its row in place, because a window announces on every change and the
 * Screens sheet must not reorder itself when anyone navigates; and two sockets sharing a
 * client-minted `windowId` — a duplicated tab, which copies its `sessionStorage` — are two rows
 * (D9), addressable apart because the id that addresses them is the server's.
 *
 * The fourth rule, *a window outlives a project switch*, cannot be shown here: it is a fact about
 * what `State`'s project collector does **not** do, so its guard is over a real `State` in
 * `plugins/WindowsSocketTest`.
 */
class WindowRegistryTest {

    private fun WindowRegistry.announce(
        socketId: String,
        windowId: String = "win-$socketId",
        name: String = "Screen 1",
        view: String = "/projects/1/programmer",
        fullscreen: Boolean = false,
        follows: Boolean = true,
        user: String? = null,
    ) = announce(socketId, windowId, name, view, fullscreen, follows, user)

    @Test
    fun `an announce adds a row carrying both identities, and the socket's id is the row's`() {
        val registry = WindowRegistry()
        assertTrue(registry.windows.value.isEmpty(), "a fresh desk has no windows")

        val window = registry.announce("sock-a", windowId = "tab-1", name = "Screen 1", user = "Chris")

        assertEquals(listOf(window), registry.windows.value)
        assertEquals("sock-a", window.id, "the row's id is the socket's, not the client's")
        assertEquals("tab-1", window.windowId)
        assertEquals("Chris", window.user)
        assertTrue(window.follows, "a window follows the desk by default (D1)")
    }

    @Test
    fun `announces from different sockets keep announce order`() {
        val registry = WindowRegistry()
        registry.announce("sock-a", name = "Screen 1")
        registry.announce("sock-b", name = "Screen 2")
        registry.announce("sock-c", name = "Chris's iPad")

        assertEquals(
            listOf("Screen 1", "Screen 2", "Chris's iPad"),
            registry.windows.value.map { it.name },
        )
    }

    @Test
    fun `a re-announce from the same socket replaces its row in place`() {
        val registry = WindowRegistry()
        registry.announce("sock-a", name = "Screen 1", view = "/projects/1/programmer")
        registry.announce("sock-b", name = "Screen 2", view = "/projects/1/busk")

        // Screen 1 navigates and goes full screen: one row, moved in content, not in position.
        registry.announce("sock-a", name = "Screen 1", view = "/projects/1/show", fullscreen = true)

        val windows = registry.windows.value
        assertEquals(2, windows.size, "a re-announce is a replace, not a second row")
        assertEquals(listOf("sock-a", "sock-b"), windows.map { it.id }, "and it keeps its place in the list")
        assertEquals("/projects/1/show", windows[0].view)
        assertTrue(windows[0].fullscreen)
    }

    @Test
    fun `a duplicated tab announces the same windowId on a second socket and is a second row`() {
        val registry = WindowRegistry()
        registry.announce("sock-a", windowId = "tab-1", name = "Screen 1")
        registry.announce("sock-b", windowId = "tab-1", name = "Screen 1")

        val windows = registry.windows.value
        assertEquals(2, windows.size, "two windows, however they name themselves (D9)")
        assertEquals(listOf("tab-1", "tab-1"), windows.map { it.windowId })
        assertEquals(setOf("sock-a", "sock-b"), windows.map { it.id }.toSet(), "and they are addressable apart")
    }

    @Test
    fun `remove drops that socket's row and leaves the others, and is a no-op for a socket that never announced`() {
        val registry = WindowRegistry()
        registry.announce("sock-a", name = "Screen 1")
        registry.announce("sock-b", name = "Screen 2")

        registry.remove("sock-never-announced")
        assertEquals(2, registry.windows.value.size, "a socket with no row removes nothing")

        registry.remove("sock-a")
        assertEquals(listOf("Screen 2"), registry.windows.value.map { it.name })

        registry.remove("sock-b")
        assertTrue(registry.windows.value.isEmpty())
    }

    @Test
    fun `every command reaches a live subscriber, and none is replayed to one that arrives after`() = runTest {
        val registry = WindowRegistry()

        // A command emitted with nobody collecting is dropped, not queued — the first half of
        // "a command to a disconnected window is lost" (D11).
        registry.command(WindowRegistry.Command.Show("sock-b", "/projects/1/show"))

        val collected = mutableListOf<WindowRegistry.Command>()
        val job = launch { registry.commands.collect { collected += it } }
        yield()

        registry.command(WindowRegistry.Command.Show("sock-b", "/projects/1/busk"))
        registry.command(WindowRegistry.Command.Rename("sock-b", "Screen 2"))
        registry.command(WindowRegistry.Command.Fullscreen("sock-b", on = true))
        registry.command(WindowRegistry.Command.Follow("sock-b", on = false))
        yield()
        job.cancel()

        assertEquals(
            listOf(
                WindowRegistry.Command.Show("sock-b", "/projects/1/busk"),
                WindowRegistry.Command.Rename("sock-b", "Screen 2"),
                WindowRegistry.Command.Fullscreen("sock-b", on = true),
                WindowRegistry.Command.Follow("sock-b", on = false),
            ),
            collected,
            "the four commands arrive in order, and the one sent before the subscription does not",
        )
        assertTrue(registry.commands.replayCache.isEmpty(), "commands are gestures, never replayed")
    }
}
