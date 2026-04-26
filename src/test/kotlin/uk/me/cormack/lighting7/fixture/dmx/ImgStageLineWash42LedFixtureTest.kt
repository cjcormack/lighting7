package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class ImgStageLineWash42LedFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `13ch mode writes pan, tilt, RGBW, macros and program to the right channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = ImgStageLineWash42LedFixture.Mode13Ch(universe, "wash-1", "Wash 1", 1)
            .withTransaction(transaction)

        fixture.pan.value = 100u
        fixture.panFine.value = 110u
        fixture.tilt.value = 120u
        fixture.tiltFine.value = 130u
        fixture.movementSpeed.value = 140u
        fixture.rgbColour.redSlider.value = 10u
        fixture.rgbColour.greenSlider.value = 20u
        fixture.rgbColour.blueSlider.value = 30u
        fixture.white.value = 40u
        fixture.colourMacro.setting = ImgStageLineWash42LedFixture.ColourMacro.COLOUR_5
        fixture.colourChangeSpeed.value = 50u
        fixture.program.setting = ImgStageLineWash42LedFixture.Program.AUTO_PROGRAM_3

        transaction.apply()

        assertEquals(100u.toUByte(), controller.getValue(1))
        assertEquals(110u.toUByte(), controller.getValue(2))
        assertEquals(120u.toUByte(), controller.getValue(3))
        assertEquals(130u.toUByte(), controller.getValue(4))
        assertEquals(140u.toUByte(), controller.getValue(5))
        assertEquals(10u.toUByte(), controller.getValue(7))
        assertEquals(20u.toUByte(), controller.getValue(8))
        assertEquals(30u.toUByte(), controller.getValue(9))
        assertEquals(40u.toUByte(), controller.getValue(10))
        assertEquals(64u.toUByte(), controller.getValue(11))
        assertEquals(50u.toUByte(), controller.getValue(12))
        assertEquals(38u.toUByte(), controller.getValue(13))
    }

    @Test
    fun `dimmer is clamped to the 0-134 dim band`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = ImgStageLineWash42LedFixture.Mode13Ch(universe, "wash-1", "Wash 1", 1)
            .withTransaction(transaction)

        fixture.dimmer.value = 255u
        transaction.apply()
        assertEquals(134u.toUByte(), controller.getValue(6))

        fixture.dimmer.value = 100u
        transaction.apply()
        assertEquals(100u.toUByte(), controller.getValue(6))
    }

    @Test
    fun `strobe writes into the strobe band, fullOn writes max-brightness band`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = ImgStageLineWash42LedFixture.Mode13Ch(universe, "wash-1", "Wash 1", 1)
            .withTransaction(transaction)

        fixture.strobe.strobe(0u)
        transaction.apply()
        assertEquals(135u.toUByte(), controller.getValue(6))

        fixture.strobe.strobe(255u)
        transaction.apply()
        assertEquals(239u.toUByte(), controller.getValue(6))

        fixture.strobe.fullOn()
        transaction.apply()
        assertEquals(240u.toUByte(), controller.getValue(6))
    }
}
