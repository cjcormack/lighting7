package uk.me.cormack.lighting7.dmx

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `dmx.mock`: an Art-Net universe keeps its controller, fades and listeners, and sends nothing. */
class DmxMockOutputTest : RouteIntegrationTest() {
    override val extraConfig = listOf("dmx.mock" to "true")

    @Test
    fun `an Art-Net universe is built on the silent transport and still reports its channels`() {
        assertTrue(state.dmxMock)
        assertTrue(state.show.fixtures.mockOutput)

        transaction(state.database) {
            DaoUniverseConfig.find { DaoUniverseConfigs.project eq projectId }.forEach { it.controllerType = "ARTNET" }
        }
        DbFixtureLoader.loadFixtures(projectId, state.show.fixtures, state.database, parkSource = state.show.outputSource)

        val controller = assertIs<ArtNetController>(state.show.fixtures.controller(Universe(0, 0)))
        assertTrue(controller.mockOutput)

        val heard = CountDownLatch(1)
        controller.registerListener(object : ChannelChangeListener {
            override fun channelsChanged(changes: Map<Int, UByte>) {
                if (changes[7] == 180u.toUByte()) heard.countDown()
            }
        })
        controller.setValue(7, 180u, fadeMs = 0)

        assertEquals(180u.toUByte(), controller.getValue(7))
        assertTrue(heard.await(2, TimeUnit.SECONDS), "the transmit loop still tells the listeners what it would have sent")
    }
}
