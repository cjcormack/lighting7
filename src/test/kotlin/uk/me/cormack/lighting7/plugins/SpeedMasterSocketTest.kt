package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
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

    /**
     * The next beat frame for master 1 *at [bpm]*. Two filters, both load-bearing: every master
     * in the bank rides the same stream on its own counter, so matching on the type alone would
     * hand back master 2's beat and compare two unrelated beat numbers — and matching on master 1
     * alone would accept a throttle frame emitted before the retune under test had been applied,
     * which is the one way this test can fail while the code is right.
     */
    private suspend fun DefaultClientWebSocketSession.awaitMaster1BeatAt(bpm: Double) =
        awaitOfType<SpeedMasterBeatOutMessage> { it.index == 1 && it.bpm == bpm }

    /**
     * A tempo move arms the next beat frame past the throttle. Real elapsed time is
     * unavoidable here — the clock is the thing under test — so the tempo is pinned near the
     * 300 BPM ceiling (a beat every 200-250 ms) to keep it to well under a second.
     *
     * The assertion is on beat *numbers* rather than wall-clock: without the arming the only
     * frames released are the multiples of 16, so a second tempo move would be answered 16
     * beats later instead of on the very next one. Two beats of slack absorbs the one real
     * race — a beat boundary crossed in the moment between the change reaching the socket and
     * its collector arming the request — which costs one beat, never sixteen.
     */
    @Test
    fun `a tempo move releases a beat frame without waiting out the throttle`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        client.webSocket("/api") {
            awaitOfType<SpeedMastersStateOutMessage>() // connect burst

            sendSerialized<InMessage>(SpeedMastersSetBpmInMessage(bpm = 300.0))
            val first = awaitMaster1BeatAt(300.0)

            sendSerialized<InMessage>(SpeedMastersSetBpmInMessage(bpm = 240.0))
            val second = awaitMaster1BeatAt(240.0)

            val gap = second.beatNumber - first.beatNumber
            assertTrue(
                gap in 1..2,
                "the retune must release the next beat, not wait out the throttle (gap was $gap)",
            )
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
