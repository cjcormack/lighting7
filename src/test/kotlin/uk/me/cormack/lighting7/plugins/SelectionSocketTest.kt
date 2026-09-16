package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.state.SelectionSource
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.collectUntilOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `selection.*` family over a real socket: one `selection.state` on connect before any
 * write, one frame per mutation, none for a mutation that changes nothing, and the write's own
 * client learns the result from the broadcast (reply convention 3). The frame carries the whole
 * fact — targets, families, source — and `source` is stamped from the socket's own name, never
 * read from a payload (multi-screen plan D2, D7).
 */
class SelectionSocketTest : RouteIntegrationTest() {

    private val hex1 = CueTargetDto("fixture", "hex-1")
    private val hex2 = CueTargetDto("fixture", "hex-2")
    private val wash = CueTargetDto("group", "front-wash")

    @Test
    fun `connect burst carries the current selection and every write is one frame`() = testApplication {
        mountTestApp(state)
        state.deskSelection.set(listOf(hex1))
        val client = createWsClient()

        client.webSocket("/api") {
            val connect = awaitOfType<SelectionStateOutMessage>()
            assertEquals(listOf(hex1), connect.targets, "the connect frame is the selection as it stands")

            sendSerialized<InMessage>(SelectionToggleInMessage(wash))
            val toggled = awaitOfType<SelectionStateOutMessage>()
            assertEquals(listOf(hex1, wash), toggled.targets)

            sendSerialized<InMessage>(SelectionSetInMessage(listOf(wash)))
            val set = awaitOfType<SelectionStateOutMessage>()
            assertEquals(listOf(wash), set.targets)

            sendSerialized<InMessage>(SelectionClearInMessage)
            val cleared = awaitOfType<SelectionStateOutMessage>()
            assertTrue(cleared.targets.isEmpty())
            assertTrue(state.deskSelection.state.value.targets.isEmpty(), "the desk's own selection moved")
        }
    }

    @Test
    fun `a write that changes nothing sends no frame`() = testApplication {
        mountTestApp(state)
        state.deskSelection.set(listOf(hex1))
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<SelectionStateOutMessage>()
            sendSerialized<InMessage>(SelectionSetInMessage(listOf(hex1)))
            // A request with a guaranteed reply fences the socket: everything the repeat set
            // could have sent arrives before the bank state does.
            sendSerialized<InMessage>(SurfaceBankStateInMessage)
            val frames = collectUntilOfType<SurfaceBankStateOutMessage>()
            assertTrue(
                frames.none { it is SelectionStateOutMessage },
                "a no-op set must not re-broadcast the selection, got $frames",
            )
        }
    }

    @Test
    fun `the frame carries the mask and the mover, and set replaces both while toggle keeps the mask`() = testApplication {
        mountTestApp(state)
        state.deskSelection.set(listOf(hex1), setOf(PropertyMaskGroup.POSITION), SelectionSource.SURFACE)
        val client = createWsClient()

        client.webSocket("/api") {
            val connect = awaitOfType<SelectionStateOutMessage>()
            assertEquals(listOf("POSITION"), connect.families, "the connect snapshot carries the standing mask")
            assertEquals(SelectionSource.SURFACE, connect.source)

            // A marquee: both halves, named by the window. An unknown family name is dropped, as
            // the client's `parsePropertyMask` drops one, rather than refused — there is no reply.
            sendSerialized<InMessage>(
                SelectionSetInMessage(listOf(hex1, hex2), families = listOf("colour", "bogus"), sourceName = "Screen 1"),
            )
            val set = awaitOfType<SelectionStateOutMessage>()
            assertEquals(listOf(hex1, hex2), set.targets)
            assertEquals(listOf("COLOUR"), set.families)
            assertEquals(SelectionSource.window("Screen 1"), set.source)
            assertEquals(
                setOf(PropertyMaskGroup.COLOUR), state.deskSelection.state.value.families,
                "the desk holds the parsed vocabulary, not the strings",
            )

            // A tap on the band: the head joins under the standing mask.
            sendSerialized<InMessage>(SelectionToggleInMessage(wash))
            val toggled = awaitOfType<SelectionStateOutMessage>()
            assertEquals(listOf(hex1, hex2, wash), toggled.targets)
            assertEquals(listOf("COLOUR"), toggled.families, "toggle keeps the mask")
            assertEquals(SelectionSource.window("Screen 1"), toggled.source, "the socket's name is remembered")

            // A replace with no mask is every attribute.
            sendSerialized<InMessage>(SelectionSetInMessage(listOf(hex2)))
            val replaced = awaitOfType<SelectionStateOutMessage>()
            assertNull(replaced.families, "set with no families clears the mask")

            sendSerialized<InMessage>(SelectionClearInMessage)
            val cleared = awaitOfType<SelectionStateOutMessage>()
            assertTrue(cleared.targets.isEmpty())
            assertNull(cleared.families)
            assertNull(cleared.source, "clear drops the mover with the mask")
        }
    }

    @Test
    fun `source is the writing socket's own name — a socket that never named itself stamps none`() = testApplication {
        mountTestApp(state)
        val named = createWsClient()
        val unnamed = createWsClient()

        named.webSocket("/api") {
            awaitOfType<SelectionStateOutMessage>()
            sendSerialized<InMessage>(SelectionSetInMessage(listOf(hex1), sourceName = "Screen 1"))
            assertEquals(SelectionSource.window("Screen 1"), awaitOfType<SelectionStateOutMessage>().source)

            unnamed.webSocket("/api") {
                awaitOfType<SelectionStateOutMessage>()
                // The other window moves it without a name: it moved it last, and it is nobody —
                // not the previous mover, whose name is on *its* socket, not on the desk.
                sendSerialized<InMessage>(SelectionToggleInMessage(hex2))
                val moved = awaitOfType<SelectionStateOutMessage>()
                assertEquals(listOf(hex1, hex2), moved.targets)
                assertNull(moved.source, "an unnamed socket is no mover, not the last one")
            }

            // Back on the named socket, still without repeating the name: its own is stamped.
            awaitOfType<SelectionStateOutMessage>()
            sendSerialized<InMessage>(SelectionToggleInMessage(wash))
            val moved = awaitOfType<SelectionStateOutMessage>()
            assertEquals(SelectionSource.window("Screen 1"), moved.source)
            assertNull(moved.source?.id, "name-only until the windows registry mints an id (session 2)")
        }
    }
}
