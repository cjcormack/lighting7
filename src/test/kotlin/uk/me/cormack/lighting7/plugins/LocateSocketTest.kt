package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.routes.LocateTargetDto
import uk.me.cormack.lighting7.routes.toggleLocate
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `locate.state` over a real socket: the connect frame, a frame when a toggle locates a target, and a
 * frame when an edit ends the Locate with no toggle — the one the frame exists for.
 */
class LocateSocketTest : RouteIntegrationTest() {

    @Test
    fun `an edit that ends a locate reaches every socket`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-a", 1)
        val client = createWsClient()

        client.webSocket("/api") {
            val connect = awaitOfType<LocateStateOutMessage>()
            assertTrue(connect.targets.isEmpty(), "nothing is located on a fresh desk")
            assertEquals(projectId, connect.projectId)

            assertTrue(toggleLocate(state, TargetRef.Fixture("hex-a")).outcome.active)
            val located = awaitOfType<LocateStateOutMessage>()
            assertEquals(listOf(LocateTargetDto("fixture", "hex-a")), located.targets)

            state.show.fxEngine.programmer.writeAttribute(
                ProgrammerOwner.WEB,
                state.show.fixtures.untypedGroupableFixture("hex-a"),
                "dimmer",
                AttributeValue.Slider(40u),
            )
            val committed = awaitOfType<LocateStateOutMessage>()
            assertTrue(committed.targets.isEmpty(), "the edit ended the locate, and the socket heard it")
        }
    }
}
