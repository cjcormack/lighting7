package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class Gear4MusicOrbit70FixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `13ch mode writes pan, tilt, dimmer, RGBW, static colour and program to the right channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = Gear4MusicOrbit70Fixture.Mode13Ch(universe, "orbit-1", "Orbit 1", 1)
            .withTransaction(transaction)

        fixture.pan.value = 100u
        fixture.panFine.value = 110u
        fixture.tilt.value = 120u
        fixture.tiltFine.value = 130u
        fixture.rotationSpeed.value = 140u
        fixture.dimmer.value = 200u
        fixture.rgbColour.redSlider.value = 10u
        fixture.rgbColour.greenSlider.value = 20u
        fixture.rgbColour.blueSlider.value = 30u
        fixture.white.value = 40u
        fixture.staticColour.value = 50u
        fixture.program.setting = Gear4MusicOrbit70Fixture.Program.PROGRAM_1_LEDS_ON

        transaction.apply()

        assertEquals(100u.toUByte(), controller.getValue(1))
        assertEquals(110u.toUByte(), controller.getValue(2))
        assertEquals(120u.toUByte(), controller.getValue(3))
        assertEquals(130u.toUByte(), controller.getValue(4))
        assertEquals(140u.toUByte(), controller.getValue(5))
        assertEquals(200u.toUByte(), controller.getValue(6))
        assertEquals(10u.toUByte(), controller.getValue(8))
        assertEquals(20u.toUByte(), controller.getValue(9))
        assertEquals(30u.toUByte(), controller.getValue(10))
        assertEquals(40u.toUByte(), controller.getValue(11))
        assertEquals(50u.toUByte(), controller.getValue(12))
        assertEquals(80u.toUByte(), controller.getValue(13))
    }

    @Test
    fun `strobe writes into the strobe band, fullOn writes the LED-open band`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = Gear4MusicOrbit70Fixture.Mode13Ch(universe, "orbit-1", "Orbit 1", 1)
            .withTransaction(transaction)

        fixture.strobe.strobe(0u)
        transaction.apply()
        assertEquals(16u.toUByte(), controller.getValue(7))

        fixture.strobe.strobe(255u)
        transaction.apply()
        assertEquals(131u.toUByte(), controller.getValue(7))

        fixture.strobe.fullOn()
        transaction.apply()
        assertEquals(248u.toUByte(), controller.getValue(7))
    }
}
