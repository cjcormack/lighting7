package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class China2CellLedBlinderFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `writes dimmer, strobe, programs and two WW-CW cells to the right channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", 1)
            .withTransaction(transaction)

        fixture.dimmer.value = 200u
        fixture.strobe.fullOn()
        fixture.program.setting = China2CellLedBlinderFixture.Program.COL_GRADIENT
        fixture.progSpeed.value = 128u

        fixture.cell(0).warmWhite.value = 10u
        fixture.cell(0).coldWhite.value = 20u
        fixture.cell(1).warmWhite.value = 30u
        fixture.cell(1).coldWhite.value = 40u

        transaction.apply()

        assertEquals(200u.toUByte(), controller.getValue(1))
        assertEquals(0u.toUByte(), controller.getValue(2))
        assertEquals(80u.toUByte(), controller.getValue(3))
        assertEquals(128u.toUByte(), controller.getValue(4))
        assertEquals(10u.toUByte(), controller.getValue(5))
        assertEquals(20u.toUByte(), controller.getValue(6))
        assertEquals(30u.toUByte(), controller.getValue(7))
        assertEquals(40u.toUByte(), controller.getValue(8))
    }

    @Test
    fun `exposes two multi-element cells with stable element keys`() {
        val fixture = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", 1)

        assertEquals(2, fixture.elementCount)
        assertEquals("blinder-1.cell-1", fixture.cell(0).elementKey)
        assertEquals("blinder-1.cell-2", fixture.cell(1).elementKey)
    }

    @Test
    fun `strobe writes the 011-255 strobe band, fullOn writes 0`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", 1)
            .withTransaction(transaction)

        fixture.strobe.strobe(0u)
        transaction.apply()
        assertEquals(China2CellLedBlinderFixture.StrobeChannel.STROBE_MIN, controller.getValue(2))

        fixture.strobe.strobe(255u)
        transaction.apply()
        assertEquals(China2CellLedBlinderFixture.StrobeChannel.STROBE_MAX, controller.getValue(2))

        fixture.strobe.fullOn()
        transaction.apply()
        assertEquals(0u.toUByte(), controller.getValue(2))
    }

    @Test
    fun `program enum values match personality band starts`() {
        assertEquals(0u.toUByte(), China2CellLedBlinderFixture.Program.NO_FUNCTION.level)
        assertEquals(40u.toUByte(), China2CellLedBlinderFixture.Program.COL_JUMP.level)
        assertEquals(80u.toUByte(), China2CellLedBlinderFixture.Program.COL_GRADIENT.level)
        assertEquals(120u.toUByte(), China2CellLedBlinderFixture.Program.COL_PULSE.level)
        assertEquals(160u.toUByte(), China2CellLedBlinderFixture.Program.COL_MUTATION.level)
        assertEquals(200u.toUByte(), China2CellLedBlinderFixture.Program.AUTO_MODE.level)
        assertEquals(240u.toUByte(), China2CellLedBlinderFixture.Program.SOUND_ACTIVE.level)
    }
}
