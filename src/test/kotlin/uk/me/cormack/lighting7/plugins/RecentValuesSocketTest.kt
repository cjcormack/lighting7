package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `recentValues.*` over real sockets: the connect frame is the live project's empty lists, a record
 * lands on the live project, and a second socket hears it.
 */
class RecentValuesSocketTest : RouteIntegrationTest() {

    @Test
    fun `a record reaches the live project's lists and every socket`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-a", 1)
        val client = createWsClient()

        coroutineScope {
            val listening = CompletableDeferred<Unit>()
            val other = async {
                var heard: RecentValuesStateOutMessage? = null
                client.webSocket("/api") {
                    awaitOfType<RecentValuesStateOutMessage>()
                    listening.complete(Unit)
                    heard = awaitOfType<RecentValuesStateOutMessage> { "level:dimmer" in it.lists }
                }
                checkNotNull(heard)
            }

            client.webSocket("/api") {
                val connect = awaitOfType<RecentValuesStateOutMessage>()
                assertEquals(projectId, connect.projectId)
                assertTrue(connect.lists.isEmpty(), "nothing is recent on a fresh desk")
                withTimeout(20_000) { listening.await() }

                sendSerialized<InMessage>(RecentValuesRecordInMessage("level:dimmer", "pct:62"))
                val recorded = awaitOfType<RecentValuesStateOutMessage> { "level:dimmer" in it.lists }
                assertEquals(projectId, recorded.projectId)
                assertEquals(listOf("pct:62"), recorded.lists["level:dimmer"])
                assertEquals(listOf("pct:62"), state.recentValues.listsFor(projectId)["level:dimmer"])
            }

            val heard = withTimeout(20_000) { other.await() }
            assertEquals(listOf("pct:62"), heard.lists["level:dimmer"], "a second socket sees the record")
        }
    }

    @Test
    fun `a level of an attribute the rig lacks, or one that is not a level, is not recorded`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-a", 1)
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<RecentValuesStateOutMessage>()
            sendSerialized<InMessage>(RecentValuesRecordInMessage("level:gobo", "pct:50"))
            sendSerialized<InMessage>(RecentValuesRecordInMessage("level:rgbColour", "pct:50"))
            sendSerialized<InMessage>(RecentValuesRecordInMessage("colour", "#102030"))
            val recorded = awaitOfType<RecentValuesStateOutMessage> { "colour" in it.lists }
            assertEquals(setOf("colour"), recorded.lists.keys, "a level the rig lacks, and a colour, are not levels: ${recorded.lists}")
        }
    }
}
