package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.seedUser
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The park family over a real socket, for venue parks: `parkState` names whose each park is and the
 * venue parks the show released; `restoreChannelPark` takes one back; and on a **venue** a park write
 * is an admin's (venue plan D3) — an operator's is answered `parkError VENUE_ADMIN_ONLY` and changes
 * nothing, while in a show either role parks.
 */
class ParkSocketTest : RouteIntegrationTest() {

    @Test
    fun `parkState carries each park's owner and the releases, and a restore takes the venue's back`() = testApplication {
        mountTestApp(state)
        val f = seedShowOnVenue(state)
        runBlocking { state.projectManager.switchProject(f.showId) }
        val client = createWsClient()

        client.webSocket("/api") {
            val snapshot = awaitOfType<ParkStateOutMessage>()
            assertEquals(
                setOf(ParkedChannelState(0, 5, 128u, RigOwner.VENUE), ParkedChannelState(1, 7, 200u, RigOwner.SHOW, 255u)),
                snapshot.channels.toSet(),
            )
            assertEquals(listOf(ReleasedParkState(0, 12, 0u)), snapshot.released)

            sendSerialized<InMessage>(RestoreChannelParkInMessage(0, 12))
            val restored = awaitOfType<ParkStateOutMessage> { it.released.isEmpty() }
            assertTrue(restored.channels.contains(ParkedChannelState(0, 12, 0u, RigOwner.VENUE)), "${restored.channels}")
        }
    }

    @Test
    fun `an operator's park on a venue is refused by name, an admin's lands, and an operator parks in a show`() = testApplication {
        mountTestApp(state)
        val f = seedShowOnVenue(state)
        seedUser(state, "admin", UserRole.ADMIN)
        seedUser(state, "op", UserRole.OPERATOR)
        runBlocking { state.projectManager.switchProject(f.venueId) }
        val client = createWsClient()
        val operator = client.loginCookieHeader("op")
        val admin = client.loginCookieHeader("admin")

        client.webSocket("/api", request = { header(HttpHeaders.Cookie, operator) }) {
            awaitOfType<ParkStateOutMessage>()
            sendSerialized<InMessage>(ParkChannelInMessage(0, 40, 99u))
            val refused = awaitOfType<ParkErrorOutMessage>()
            assertEquals(CODE_VENUE_ADMIN_ONLY_FOR_TEST, refused.code)
            sendSerialized<InMessage>(UnparkChannelInMessage(0, 5))
            awaitOfType<ParkErrorOutMessage>()
        }
        assertFalse(state.show.parkManager.isParked(0, 40), "the operator's park changed nothing")
        assertTrue(state.show.parkManager.isParked(0, 5), "nor did the operator's unpark")

        client.webSocket("/api", request = { header(HttpHeaders.Cookie, admin) }) {
            awaitOfType<ParkStateOutMessage>()
            sendSerialized<InMessage>(ParkChannelInMessage(0, 40, 99u))
            awaitOfType<ParkStateOutMessage> { s -> s.channels.any { it.channel == 40 } }
        }
        assertEquals(99u.toUByte(), state.show.parkManager.getParkedValue(0, 40), "the venue's draft parks it")

        runBlocking { state.projectManager.switchProject(f.showId) }
        client.webSocket("/api", request = { header(HttpHeaders.Cookie, operator) }) {
            awaitOfType<ParkStateOutMessage>()
            sendSerialized<InMessage>(ParkChannelInMessage(0, 41, 33u))
            awaitOfType<ParkStateOutMessage> { s -> s.channels.any { it.channel == 41 } }
        }
        assertEquals(33u.toUByte(), state.show.parkManager.getParkedValue(0, 41), "in a show either role parks")
    }

    private companion object {
        const val CODE_VENUE_ADMIN_ONLY_FOR_TEST = uk.me.cormack.lighting7.routes.CODE_VENUE_ADMIN_ONLY
    }
}
