package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class VarytecEasymoveXl60SpotFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `11ch mode writes pan, tilt, dimmer, wheels and reset to the right channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = VarytecEasymoveXl60SpotFixture.Mode11Ch(universe, "easymove-1", "Easymove 1", 1)
            .withTransaction(transaction)

        fixture.pan.value = 100u
        fixture.tilt.value = 110u
        fixture.colour.setting = VarytecEasymoveXl60SpotFixture.Colour.COLOR_3
        fixture.gobo.setting = VarytecEasymoveXl60SpotFixture.Gobo.GOBO_2
        fixture.goboSpin.value = 130u
        fixture.dimmer.value = 200u
        fixture.panTiltSpeed.value = 140u
        fixture.panFine.value = 150u
        fixture.tiltFine.value = 160u
        fixture.reset.setting = VarytecEasymoveXl60SpotFixture.Reset.RESET

        transaction.apply()

        assertEquals(100u.toUByte(), controller.getValue(1))
        assertEquals(110u.toUByte(), controller.getValue(2))
        assertEquals(60u.toUByte(), controller.getValue(3))
        assertEquals(50u.toUByte(), controller.getValue(4))
        assertEquals(130u.toUByte(), controller.getValue(5))
        assertEquals(200u.toUByte(), controller.getValue(7))
        assertEquals(140u.toUByte(), controller.getValue(8))
        assertEquals(150u.toUByte(), controller.getValue(9))
        assertEquals(160u.toUByte(), controller.getValue(10))
        assertEquals(255u.toUByte(), controller.getValue(11))
    }

    @Test
    fun `strobe maps into 1-255 band, fullOn writes 0`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = VarytecEasymoveXl60SpotFixture.Mode11Ch(universe, "easymove-1", "Easymove 1", 1)
            .withTransaction(transaction)

        fixture.strobe.strobe(0u)
        transaction.apply()
        assertEquals(1u.toUByte(), controller.getValue(6))

        fixture.strobe.strobe(255u)
        transaction.apply()
        assertEquals(255u.toUByte(), controller.getValue(6))

        fixture.strobe.fullOn()
        transaction.apply()
        assertEquals(0u.toUByte(), controller.getValue(6))
    }
}
