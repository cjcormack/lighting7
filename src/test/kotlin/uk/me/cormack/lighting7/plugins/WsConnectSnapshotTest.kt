package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.reflect.KClass
import kotlin.test.assertTrue

/**
 * The one-snapshot rule (backend sweep F5, documented in `docs/websocket-engineering.md`
 * §"Snapshot rule"): a client should be able to render the desk from the connect burst alone,
 * without sending a single request message.
 *
 * This is a guard test, not a coverage test. The rule keeps getting broken in the same two
 * silent ways, and neither is visible in a unit test:
 *
 *  - a family whose snapshot rides a **replay-1 `MutableSharedFlow`**, which has nothing to
 *    replay on a desk where the thing has never happened — no effect ever added, nothing ever
 *    parked, no project ever switched. This test runs on exactly that desk: a freshly seeded
 *    project where none of those has happened.
 *  - a family whose connect frame was suppressed by a `.drop(1)` meant for a replayed event.
 *
 * Driven over a real socket rather than asserted on the setup functions, for the same reason
 * `CueRunStateBroadcastTest` is: a message missing from the `OutMessage` polymorphic scope
 * serializes fine in isolation and closes the socket in production.
 */
class WsConnectSnapshotTest : RouteIntegrationTest() {

    private val required: List<KClass<out OutMessage>> = listOf(
        ChannelStateOutMessage::class,
        UniversesStateOutMessage::class,
        ChannelMappingStateOutMessage::class,
        ParkStateOutMessage::class,
        FxStateOutMessage::class,
        ProjectStateOutMessage::class,
        ProgrammerStateOutMessage::class,
        // Provenance is the one family whose live flow deliberately stays a replay-1 SharedFlow
        // (content-equal frames are a real signal there), so its connect frame is an explicit
        // push and nothing else would catch its loss.
        ProvenanceStateOutMessage::class,
        SpeedMastersStateOutMessage::class,
        SurfaceBankStateOutMessage::class,
        SurfaceScalerStateOutMessage::class,
        SurfaceDevicesStateOutMessage::class,
    )

    @Test
    fun `every stateful family pushes its snapshot on connect, unasked`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        client.webSocket("/api") {
            val seen = mutableListOf<OutMessage>()
            // Each family's snapshot is launched in its own coroutine, so the burst has no
            // fixed order and no fixed tail frame to stop on. Read until every family has been
            // seen, bounded by a timeout rather than a frame count: a missing snapshot means the
            // read blocks, and the timeout is what turns that into a legible assertion failure
            // instead of the 60 s per-test cap.
            withTimeoutOrNull(20_000) {
                while (required.any { cls -> seen.none(cls::isInstance) }) {
                    seen += receiveDeserialized<OutMessage>()
                }
            }

            val missing = required.filterNot { cls -> seen.any(cls::isInstance) }
            assertTrue(
                missing.isEmpty(),
                "no connect snapshot for ${missing.map { it.simpleName }} — a client would have " +
                    "to ask for it, which the one-snapshot rule says it must not have to. " +
                    "Saw: ${seen.map { it::class.simpleName }.distinct()}",
            )
        }
    }
}
