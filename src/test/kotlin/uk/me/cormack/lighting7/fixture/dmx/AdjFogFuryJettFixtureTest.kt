package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class AdjFogFuryJettFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `7ch mode writes RGBA, fog, dimmer and strobe to the right channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = AdjFogFuryJettFixture.Mode7Ch(universe, "fog-1", "Fog 1", 10)
            .withTransaction(transaction)

        fixture.fog.value = 200u
        fixture.rgbColour.redSlider.value = 100u
        fixture.rgbColour.greenSlider.value = 110u
        fixture.rgbColour.blueSlider.value = 120u
        fixture.amber.value = 130u
        fixture.strobe.fullOn()
        fixture.dimmer.value = 255u

        transaction.apply()

        assertEquals(200u.toUByte(), controller.getValue(10))
        assertEquals(100u.toUByte(), controller.getValue(11))
        assertEquals(110u.toUByte(), controller.getValue(12))
        assertEquals(120u.toUByte(), controller.getValue(13))
        assertEquals(130u.toUByte(), controller.getValue(14))
        assertEquals(0u.toUByte(), controller.getValue(15))
        assertEquals(255u.toUByte(), controller.getValue(16))
    }

    @Test
    fun `strobe band maps intensity into 32-95`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = AdjFogFuryJettFixture.Mode7Ch(universe, "fog-1", "Fog 1", 1)
            .withTransaction(transaction)

        fixture.strobe.strobe(0u)
        transaction.apply()
        assertEquals(32u.toUByte(), controller.getValue(6))

        fixture.strobe.strobe(255u)
        transaction.apply()
        assertEquals(95u.toUByte(), controller.getValue(6))
    }
}
