package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.collectUntilOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `selection.*` family over a real socket: one `selection.state` on connect before any
 * write, one frame per mutation, none for a mutation that changes nothing, and the write's own
 * client learns the result from the broadcast (reply convention 3).
 */
class SelectionSocketTest : RouteIntegrationTest() {

    private val hex1 = CueTargetDto("fixture", "hex-1")
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
            assertTrue(state.deskSelection.targets.value.isEmpty(), "the desk's own selection moved")
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
}
