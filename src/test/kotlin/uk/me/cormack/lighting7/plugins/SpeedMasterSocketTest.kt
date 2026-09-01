package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CODE_SPEED_MASTER_FOLLOWER
import uk.me.cormack.lighting7.models.CODE_SPEED_MASTER_UNKNOWN
import uk.me.cormack.lighting7.models.DaoSpeedMaster
import uk.me.cormack.lighting7.models.DaoSpeedMasters
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.collectUntilOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `speedMasters.*` write path over a real socket — specifically the failure acks
 * introduced with follow (busking-view plan, session 1): a refused or dropped tempo write
 * answers a `speedMasters.error` unicast *and* the usual full-state reply, so a stale client
 * both learns why nothing moved and snaps back to the truth it disagreed with.
 */
class SpeedMasterSocketTest : RouteIntegrationTest() {

    /** Link the seeded master at [index] to master 1 at num/den, and reload the live bank. */
    private fun linkFollower(index: Int, num: Int, den: Int): String {
        val uuid = transaction(state.database) {
            val master = DaoSpeedMaster
                .find { DaoSpeedMasters.masterIndex eq index }
                .single { it.project.id.value == projectId }
            master.followNum = num
            master.followDen = den
            master.uuid.toString()
        }
        state.show.reloadSpeedMasters()
        return uuid
    }

    @Test
    fun `tapping a follower answers the error frame and an unchanged state`() = testApplication {
        mountTestApp(state)
        val followerUuid = linkFollower(index = 2, num = 1, den = 2)
        val m1Bpm = state.show.speedMasterBank.master1().bpm.value
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<SpeedMastersStateOutMessage>() // connect burst

            sendSerialized<InMessage>(SpeedMastersTapInMessage(masterUuid = followerUuid))

            val frames = collectUntilOfType<SpeedMastersStateOutMessage>()
            val error = frames.filterIsInstance<SpeedMasterErrorOutMessage>().single()
            assertEquals(CODE_SPEED_MASTER_FOLLOWER, error.code)
            assertEquals(followerUuid, error.masterUuid)
            assertTrue(
                frames.indexOf(error) < frames.indexOfLast { it is SpeedMastersStateOutMessage },
                "the failure ack must precede the state reply",
            )

            val follower = (frames.last() as SpeedMastersStateOutMessage)
                .masters.single { it.uuid == followerUuid }
            assertEquals(m1Bpm / 2, follower.bpm, "a refused tap must not have moved the follower")
            assertEquals(1, follower.followNum)
            assertEquals(2, follower.followDen)
        }
    }

    @Test
    fun `setBpm on an unknown uuid answers the error frame with SPEED_MASTER_UNKNOWN`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()
        val stray = UUID.randomUUID().toString()

        client.webSocket("/api") {
            awaitOfType<SpeedMastersStateOutMessage>() // connect burst

            sendSerialized<InMessage>(SpeedMastersSetBpmInMessage(bpm = 90.0, masterUuid = stray))

            val frames = collectUntilOfType<SpeedMastersStateOutMessage>()
            val error = frames.filterIsInstance<SpeedMasterErrorOutMessage>().single()
            assertEquals(CODE_SPEED_MASTER_UNKNOWN, error.code)
            assertEquals(stray, error.masterUuid)
        }
    }

    @Test
    fun `the state frame carries usage and the ratio pair`() = testApplication {
        mountTestApp(state)
        val followerUuid = linkFollower(index = 2, num = 1, den = 3)
        transaction(state.database) {
            DaoSpeedMaster
                .find { DaoSpeedMasters.masterIndex eq 2 }
                .single { it.project.id.value == projectId }
                .usageCategory = "position"
        }
        state.show.reloadSpeedMasters()
        val client = createWsClient()

        client.webSocket("/api") {
            val stateFrame = awaitOfType<SpeedMastersStateOutMessage>()
            val follower = stateFrame.masters.single { it.uuid == followerUuid }
            assertEquals("position", follower.usage)
            assertEquals(1, follower.followNum)
            assertEquals(3, follower.followDen)

            val m1 = stateFrame.masters.single { it.index == 1 }
            assertNull(m1.followNum, "a manual master carries no ratio")
        }
    }
}
