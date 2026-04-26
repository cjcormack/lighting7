package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class Gear4MusicSolParty12BFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `writes int mode, colour wheel, dimmer, RGB and FX to the right channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = Gear4MusicSolParty12BFixture(universe, "sol-1", "SOL 1", 1)
            .withTransaction(transaction)

        fixture.intMode.value = 50u
        fixture.colourWheel.setting = Gear4MusicSolParty12BFixture.ColourWheel.RAINBOW_BALL
        fixture.colourSpeed.value = 90u
        fixture.dimmer.value = 200u
        fixture.rgbColour.redSlider.value = 10u
        fixture.rgbColour.greenSlider.value = 20u
        fixture.rgbColour.blueSlider.value = 30u
        fixture.fx.value = 40u

        transaction.apply()

        assertEquals(50u.toUByte(), controller.getValue(1))
        assertEquals(100u.toUByte(), controller.getValue(2))
        assertEquals(90u.toUByte(), controller.getValue(3))
        assertEquals(200u.toUByte(), controller.getValue(4))
        assertEquals(10u.toUByte(), controller.getValue(5))
        assertEquals(20u.toUByte(), controller.getValue(6))
        assertEquals(30u.toUByte(), controller.getValue(7))
        assertEquals(40u.toUByte(), controller.getValue(8))
    }

    @Test
    fun `colour wheel enum values match personality band starts`() {
        assertEquals(0u.toUByte(), Gear4MusicSolParty12BFixture.ColourWheel.ALL_COL.level)
        assertEquals(40u.toUByte(), Gear4MusicSolParty12BFixture.ColourWheel.RED.level)
        assertEquals(50u.toUByte(), Gear4MusicSolParty12BFixture.ColourWheel.GREEN.level)
        assertEquals(60u.toUByte(), Gear4MusicSolParty12BFixture.ColourWheel.BLUE.level)
        assertEquals(70u.toUByte(), Gear4MusicSolParty12BFixture.ColourWheel.YELLOW.level)
        assertEquals(80u.toUByte(), Gear4MusicSolParty12BFixture.ColourWheel.CYAN.level)
        assertEquals(90u.toUByte(), Gear4MusicSolParty12BFixture.ColourWheel.PURPLE.level)
        assertEquals(100u.toUByte(), Gear4MusicSolParty12BFixture.ColourWheel.RAINBOW_BALL.level)
        assertEquals(200u.toUByte(), Gear4MusicSolParty12BFixture.ColourWheel.R_G_B.level)
        assertEquals(201u.toUByte(), Gear4MusicSolParty12BFixture.ColourWheel.R_G_B_RAINBOW.level)
    }
}
