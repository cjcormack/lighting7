package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import org.junit.Test
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.state.SelectionSource
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `windows.*` family over a real socket: the connect snapshot arrives before any announce,
 * an announce is one row keyed by the socket, the five commands are rebroadcast verbatim to
 * every socket (D11), a closed socket takes its row with it, and a `selection.*` write is stamped
 * from the announced window rather than from the payload (D7).
 *
 * Driven over a socket rather than against [uk.me.cormack.lighting7.state.WindowRegistry] for the
 * reason `CueRunStateBroadcastTest` is: a message missing from the `OutMessage` polymorphic scope
 * serializes fine in isolation and closes the socket in production.
 */
class WindowsSocketTest : RouteIntegrationTest() {

    private val hex1 = CueTargetDto("fixture", "hex-1")
    private val hex2 = CueTargetDto("fixture", "hex-2")

    private fun announce(
        windowId: String = "tab-1",
        name: String = "Screen 1",
        view: String = "/programmer",
        fullscreen: Boolean = false,
        follows: Boolean = true,
    ) = WindowsAnnounceInMessage(windowId, name, view, fullscreen, follows)

    @Test
    fun `the connect snapshot arrives before any announce and lists the windows already signed in`() =
        testApplication {
            mountTestApp(state)
            val screen1 = createWsClient()
            val screen2 = createWsClient()

            screen1.webSocket("/api") {
                // Unasked, and empty: this socket has announced nothing and nobody else has either.
                assertTrue(awaitOfType<WindowsStateOutMessage>().windows.isEmpty())

                sendSerialized<InMessage>(announce(name = "Screen 1", view = "/programmer"))
                val mine = awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }
                assertEquals(listOf("Screen 1"), mine.windows.map { w -> w.name })
                assertEquals("/programmer", mine.windows.single().view)

                screen2.webSocket("/api") {
                    // The second window's own snapshot already carries the first — a client
                    // renders the sheet from the connect burst alone.
                    val snapshot = awaitOfType<WindowsStateOutMessage>()
                    assertEquals(listOf("Screen 1"), snapshot.windows.map { w -> w.name })

                    sendSerialized<InMessage>(announce(windowId = "tab-2", name = "Screen 2", view = "/busk"))
                    val both = awaitOfType<WindowsStateOutMessage> { it.windows.size == 2 }
                    assertEquals(listOf("Screen 1", "Screen 2"), both.windows.map { w -> w.name })
                }

                // …and the first learns of both the arrival and the departure.
                val gone = awaitOfType<WindowsStateOutMessage> { it.windows.size == 1 }
                assertEquals(listOf("Screen 1"), gone.windows.map { w -> w.name })
            }
        }

    @Test
    fun `a re-announce replaces this socket's row rather than adding one`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<WindowsStateOutMessage>()
            sendSerialized<InMessage>(announce(view = "/programmer"))
            val first = awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }
            val id = first.windows.single().id

            sendSerialized<InMessage>(announce(view = "/busk", fullscreen = true, follows = false))
            val moved = awaitOfType<WindowsStateOutMessage> { it.windows.singleOrNull()?.view == "/busk" }
            val row = moved.windows.single()
            assertEquals(id, row.id, "the row's id is the socket's, so it survives a re-announce")
            assertTrue(row.fullscreen)
            assertTrue(!row.follows, "an unlinked window reports it (§3.1)")
        }
    }

    @Test
    fun `show, rename, fullscreen and follow are rebroadcast as-is to every socket, sender included`() =
        testApplication {
            mountTestApp(state)
            val screen1 = createWsClient()
            val screen2 = createWsClient()

            screen1.webSocket("/api") {
                awaitOfType<WindowsStateOutMessage>()
                sendSerialized<InMessage>(announce(name = "Screen 1"))
                val target = awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }.windows.single().id

                screen2.webSocket("/api") {
                    awaitOfType<WindowsStateOutMessage>()
                    sendSerialized<InMessage>(announce(windowId = "tab-2", name = "Screen 2", view = "/busk"))
                    awaitOfType<WindowsStateOutMessage> { it.windows.size == 2 }

                    // Screen 2 moves Screen 1. No session lookup: the frame goes to everyone and
                    // the window whose id it names is the one that acts.
                    sendSerialized<InMessage>(WindowsShowInMessage(target, "/busk"))
                    assertEquals(WindowsShowOutMessage(target, "/busk"), awaitOfType<WindowsShowOutMessage>())

                    sendSerialized<InMessage>(WindowsRenameInMessage(target, "Front of house"))
                    assertEquals(
                        WindowsRenameOutMessage(target, "Front of house"),
                        awaitOfType<WindowsRenameOutMessage>(),
                        "the sender sees its own gesture — the Screens sheet on every window does",
                    )

                    sendSerialized<InMessage>(WindowsFullscreenInMessage(target, on = true))
                    assertEquals(
                        WindowsFullscreenOutMessage(target, on = true),
                        awaitOfType<WindowsFullscreenOutMessage>(),
                    )

                    sendSerialized<InMessage>(WindowsFollowInMessage(target, on = false))
                    assertEquals(
                        WindowsFollowOutMessage(target, on = false),
                        awaitOfType<WindowsFollowOutMessage>(),
                    )

                    // A command naming nobody is not refused and not looked up — it is simply
                    // broadcast and matches no window (D11).
                    sendSerialized<InMessage>(WindowsShowInMessage("not-a-window", "/show"))
                    assertEquals(
                        WindowsShowOutMessage("not-a-window", "/show"),
                        awaitOfType<WindowsShowOutMessage>(),
                    )
                }

                // The target's own socket received all four, in order, and the registry never
                // moved on its own: a rename or a follow is the target's job, on its next announce.
                assertEquals(WindowsShowOutMessage(target, "/busk"), awaitOfType<WindowsShowOutMessage>())
                assertEquals(
                    WindowsRenameOutMessage(target, "Front of house"),
                    awaitOfType<WindowsRenameOutMessage>(),
                )
                assertEquals(WindowsFullscreenOutMessage(target, on = true), awaitOfType<WindowsFullscreenOutMessage>())
                assertEquals(WindowsFollowOutMessage(target, on = false), awaitOfType<WindowsFollowOutMessage>())
                val row = state.windowRegistry.windows.value.first { it.id == target }
                assertEquals(
                    "Screen 1",
                    row.name,
                    "a rename is the target window's job, on its next announce — the registry does not guess",
                )
                assertTrue(row.follows, "and so is a follow: nothing is written until the target re-announces `follows`")
            }
        }

    @Test
    fun `a follow aimed at a disconnected window is lost exactly as a show is`() =
        testApplication {
            mountTestApp(state)
            val screen1 = createWsClient()
            val screen2 = createWsClient()

            screen1.webSocket("/api") {
                awaitOfType<WindowsStateOutMessage>()
                sendSerialized<InMessage>(announce(name = "Screen 1"))
                awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }

                lateinit var gone: String
                screen2.webSocket("/api") {
                    awaitOfType<WindowsStateOutMessage>()
                    sendSerialized<InMessage>(announce(windowId = "tab-2", name = "Screen 2", view = "/busk"))
                    gone = awaitOfType<WindowsStateOutMessage> { it.windows.size == 2 }.windows.last().id
                }
                // Screen 2's socket closed and took its row with it.
                awaitOfType<WindowsStateOutMessage> { it.windows.size == 1 }

                // Screen 1's Screens sheet still names the old row id. Neither command is refused
                // or queued: each is rebroadcast, matches nobody, and the registry is unchanged —
                // FU-WINDOWS-SHOW-OFFLINE's behaviour, shared by follow rather than a new one.
                sendSerialized<InMessage>(WindowsShowInMessage(gone, "/show"))
                assertEquals(WindowsShowOutMessage(gone, "/show"), awaitOfType<WindowsShowOutMessage>())
                sendSerialized<InMessage>(WindowsFollowInMessage(gone, on = false))
                assertEquals(WindowsFollowOutMessage(gone, on = false), awaitOfType<WindowsFollowOutMessage>())

                assertEquals(
                    listOf("Screen 1"),
                    state.windowRegistry.windows.value.map { it.name },
                    "a command to a disconnected window resurrects no row",
                )
            }
        }

    @Test
    fun `a selection write is stamped with the announcing window's name and id`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<WindowsStateOutMessage>()
            awaitOfType<SelectionStateOutMessage>()

            sendSerialized<InMessage>(announce(name = "Screen 2"))
            val row = awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }.windows.single()

            sendSerialized<InMessage>(SelectionSetInMessage(listOf(hex1)))
            val stamped = awaitOfType<SelectionStateOutMessage>()
            assertEquals(SelectionSource.KIND_WINDOW, stamped.source?.kind)
            assertEquals("Screen 2", stamped.source?.name)
            assertEquals(row.id, stamped.source?.id, "the id is the registry row's, so the chip can match it")

            // A payload `sourceName` does not override the announced identity — a window cannot
            // claim to be another (D7).
            sendSerialized<InMessage>(SelectionToggleInMessage(hex2, sourceName = "Screen 1"))
            val second = awaitOfType<SelectionStateOutMessage>()
            assertEquals("Screen 2", second.source?.name)
            assertEquals(row.id, second.source?.id)
        }
    }

    @Test
    fun `a socket that never announced still stamps session 1's sourceName, with no id`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<SelectionStateOutMessage>()
            sendSerialized<InMessage>(SelectionSetInMessage(listOf(hex1), sourceName = "Screen 1"))
            val stamped = awaitOfType<SelectionStateOutMessage>()
            assertEquals(SelectionSource.window("Screen 1"), stamped.source)
            assertNull(stamped.source?.id, "the stub carries no id — there is no row to point at")
        }
    }

    /**
     * Busk-further plan D13: one generic command for a window's per-view options, rebroadcast like
     * the other three; and the options a window announces come back on `windows.state` verbatim,
     * so the Screens sheet reads them without learning the word busk.
     */
    @Test
    fun `viewOptions is rebroadcast as-is and an announced options map comes back on state`() = testApplication {
        mountTestApp(state)
        val screen1 = createWsClient()
        val screen2 = createWsClient()

        screen1.webSocket("/api") {
            awaitOfType<WindowsStateOutMessage>()
            sendSerialized<InMessage>(announce(name = "Screen 1", view = "/busk").copy(viewOptions = mapOf("focus" to "rig", "sheet" to "colour")))
            val row = awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }.windows.single()
            assertEquals(mapOf("focus" to "rig", "sheet" to "colour"), row.viewOptions)

            screen2.webSocket("/api") {
                val snapshot = awaitOfType<WindowsStateOutMessage>()
                assertEquals(mapOf("focus" to "rig", "sheet" to "colour"), snapshot.windows.single().viewOptions)

                sendSerialized<InMessage>(WindowsViewOptionsInMessage(row.id, "/busk", mapOf("focus" to "pads")))
                assertEquals(
                    WindowsViewOptionsOutMessage(row.id, "/busk", mapOf("focus" to "pads")),
                    awaitOfType<WindowsViewOptionsOutMessage>(),
                )
            }
            assertEquals(
                WindowsViewOptionsOutMessage(row.id, "/busk", mapOf("focus" to "pads")),
                awaitOfType<WindowsViewOptionsOutMessage>(),
                "the target's own socket receives it; the registry never writes options itself",
            )
            assertEquals(
                mapOf("focus" to "rig", "sheet" to "colour"),
                state.windowRegistry.windows.value.single().viewOptions,
                "options move on the target's next announce, not on the command",
            )
        }
    }

    /** A client from before `viewOptions` existed omits the key entirely, and must still announce. */
    @Test
    fun `an old client that omits viewOptions still announces, with none recorded`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<WindowsStateOutMessage>()
            send(Frame.Text("""{"type":"windows.announce","windowId":"tab-1","name":"Screen 1","view":"/programmer"}"""))
            val row = awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }.windows.single()
            assertEquals("Screen 1", row.name)
            assertNull(row.viewOptions)
        }
    }

    /**
     * The machine-scoped half of §3.4, and the failure §10 names: a registry cleared with the
     * project would leave every window unnamed after a switch, and the desk chip would read
     * *Desk* because nobody could be named rather than because this window moved the selection.
     * The selection beside it is the control — that one *is* project-scoped and does clear.
     */
    @Test
    fun `a window outlives a project switch, where the selection does not`() = testApplication {
        mountTestApp(state)
        val otherProjectId = seedMinimalProject(state, projectName = "Second project", universe = 1)
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<WindowsStateOutMessage>()
            sendSerialized<InMessage>(announce(name = "Screen 1"))
            awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }

            sendSerialized<InMessage>(SelectionSetInMessage(listOf(hex1)))
            awaitOfType<SelectionStateOutMessage> { it.targets.isNotEmpty() }

            state.projectManager.switchProject(otherProjectId)

            val cleared = awaitOfType<SelectionStateOutMessage> { it.targets.isEmpty() }
            assertTrue(cleared.targets.isEmpty(), "the selection names the old rig's heads and goes")
            val survivor = state.windowRegistry.windows.value.singleOrNull()
            assertNotNull(survivor, "the window is a fact about the machine, not about the project")
            assertEquals("Screen 1", survivor.name)
        }
    }
}
