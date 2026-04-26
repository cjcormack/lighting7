package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class ShehdsLed19RgbwFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `24ch mode writes pan, tilt, dimmer, three RGBW zones, programs and reset to the right channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = ShehdsLed19RgbwFixture.Mode24Ch(universe, "shehds-1", "Shehds 1", 1)
            .withTransaction(transaction)

        fixture.pan.value = 100u
        fixture.panFine.value = 110u
        fixture.tilt.value = 120u
        fixture.tiltFine.value = 130u
        fixture.panTiltSpeed.value = 140u
        fixture.zoom.value = 150u
        fixture.dimmer.value = 200u
        fixture.strobe.fullOn()

        fixture.zone(0).rgbColour.redSlider.value = 10u
        fixture.zone(0).rgbColour.greenSlider.value = 11u
        fixture.zone(0).rgbColour.blueSlider.value = 12u
        fixture.zone(0).white.value = 13u

        fixture.zone(1).rgbColour.redSlider.value = 20u
        fixture.zone(1).rgbColour.greenSlider.value = 21u
        fixture.zone(1).rgbColour.blueSlider.value = 22u
        fixture.zone(1).white.value = 23u

        fixture.zone(2).rgbColour.redSlider.value = 30u
        fixture.zone(2).rgbColour.greenSlider.value = 31u
        fixture.zone(2).rgbColour.blueSlider.value = 32u
        fixture.zone(2).white.value = 33u

        fixture.program.setting = ShehdsLed19RgbwFixture.Program.AUTO_MODE_2
        fixture.programSpeed.value = 180u
        fixture.controlMode.value = 110u
        fixture.reset.setting = ShehdsLed19RgbwFixture.Reset.RESET

        transaction.apply()

        assertEquals(100u.toUByte(), controller.getValue(1))
        assertEquals(110u.toUByte(), controller.getValue(2))
        assertEquals(120u.toUByte(), controller.getValue(3))
        assertEquals(130u.toUByte(), controller.getValue(4))
        assertEquals(140u.toUByte(), controller.getValue(5))
        assertEquals(150u.toUByte(), controller.getValue(6))
        assertEquals(200u.toUByte(), controller.getValue(7))
        assertEquals(0u.toUByte(), controller.getValue(8))

        assertEquals(10u.toUByte(), controller.getValue(9))
        assertEquals(11u.toUByte(), controller.getValue(10))
        assertEquals(12u.toUByte(), controller.getValue(11))
        assertEquals(13u.toUByte(), controller.getValue(12))

        assertEquals(20u.toUByte(), controller.getValue(13))
        assertEquals(21u.toUByte(), controller.getValue(14))
        assertEquals(22u.toUByte(), controller.getValue(15))
        assertEquals(23u.toUByte(), controller.getValue(16))

        assertEquals(30u.toUByte(), controller.getValue(17))
        assertEquals(31u.toUByte(), controller.getValue(18))
        assertEquals(32u.toUByte(), controller.getValue(19))
        assertEquals(33u.toUByte(), controller.getValue(20))

        assertEquals(201u.toUByte(), controller.getValue(21))
        assertEquals(180u.toUByte(), controller.getValue(22))
        assertEquals(110u.toUByte(), controller.getValue(23))
        assertEquals(255u.toUByte(), controller.getValue(24))
    }

    @Test
    fun `24ch mode exposes three multi-element zones with stable element keys`() {
        val fixture = ShehdsLed19RgbwFixture.Mode24Ch(universe, "shehds-1", "Shehds 1", 1)

        assertEquals(3, fixture.elementCount)
        assertEquals("shehds-1.zone-1", fixture.zone(0).elementKey)
        assertEquals("shehds-1.zone-2", fixture.zone(1).elementKey)
        assertEquals("shehds-1.zone-3", fixture.zone(2).elementKey)
    }

    @Test
    fun `strobe writes the strobe band, fullOn writes 0`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = ShehdsLed19RgbwFixture.Mode24Ch(universe, "shehds-1", "Shehds 1", 1)
            .withTransaction(transaction)

        fixture.strobe.strobe(0u)
        transaction.apply()
        assertEquals(ShehdsLed19RgbwFixture.StrobeChannel.STROBE_MIN, controller.getValue(8))

        fixture.strobe.strobe(255u)
        transaction.apply()
        assertEquals(ShehdsLed19RgbwFixture.StrobeChannel.STROBE_MAX, controller.getValue(8))

        fixture.strobe.fullOn()
        transaction.apply()
        assertEquals(0u.toUByte(), controller.getValue(8))
    }

    @Test
    fun `16ch mode writes pan, tilt, dimmer, RGBW, zoom, programs and reset to the right channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = ShehdsLed19RgbwFixture.Mode16Ch(universe, "shehds-1", "Shehds 1", 1)
            .withTransaction(transaction)

        fixture.pan.value = 100u
        fixture.panFine.value = 110u
        fixture.tilt.value = 120u
        fixture.tiltFine.value = 130u
        fixture.panTiltSpeed.value = 140u
        fixture.dimmer.value = 200u
        fixture.strobe.fullOn()
        fixture.rgbColour.redSlider.value = 10u
        fixture.rgbColour.greenSlider.value = 20u
        fixture.rgbColour.blueSlider.value = 30u
        fixture.white.value = 40u
        fixture.zoom.value = 150u
        fixture.program.setting = ShehdsLed19RgbwFixture.Program.PROGRAM_GRADIENT
        fixture.programSpeed.value = 180u
        fixture.controlMode.value = 50u
        fixture.reset.setting = ShehdsLed19RgbwFixture.Reset.NO_FUNCTION

        transaction.apply()

        assertEquals(100u.toUByte(), controller.getValue(1))
        assertEquals(110u.toUByte(), controller.getValue(2))
        assertEquals(120u.toUByte(), controller.getValue(3))
        assertEquals(130u.toUByte(), controller.getValue(4))
        assertEquals(140u.toUByte(), controller.getValue(5))
        assertEquals(200u.toUByte(), controller.getValue(6))
        assertEquals(0u.toUByte(), controller.getValue(7))
        assertEquals(10u.toUByte(), controller.getValue(8))
        assertEquals(20u.toUByte(), controller.getValue(9))
        assertEquals(30u.toUByte(), controller.getValue(10))
        assertEquals(40u.toUByte(), controller.getValue(11))
        assertEquals(150u.toUByte(), controller.getValue(12))
        assertEquals(146u.toUByte(), controller.getValue(13))
        assertEquals(180u.toUByte(), controller.getValue(14))
        assertEquals(50u.toUByte(), controller.getValue(15))
        assertEquals(0u.toUByte(), controller.getValue(16))
    }
}
