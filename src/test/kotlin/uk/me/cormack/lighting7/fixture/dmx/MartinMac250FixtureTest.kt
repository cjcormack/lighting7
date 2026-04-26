package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class MartinMac250FixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `mode 4 writes shutter, dimmer, wheels, prism, position and speeds to the right channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = MartinMac250Fixture.Mode4Ch(universe, "mac-1", "MAC 1", 1)
            .withTransaction(transaction)

        fixture.strobe.fullOn()
        fixture.dimmer.value = 200u
        fixture.colour.setting = MartinMac250Fixture.Colour.RED
        fixture.gobo.setting = MartinMac250Fixture.Gobo.TRIPLE
        fixture.goboRotation.value = 130u
        fixture.focus.value = 70u
        fixture.prism.setting = MartinMac250Fixture.Prism.MACRO_3
        fixture.pan.value = 100u
        fixture.panFine.value = 110u
        fixture.tilt.value = 120u
        fixture.tiltFine.value = 130u
        fixture.panTiltSpeed.value = 140u
        fixture.effectSpeed.value = 150u

        transaction.apply()

        assertEquals(MartinMac250Fixture.Mode4Ch.OPEN_DEFAULT, controller.getValue(1))
        assertEquals(200u.toUByte(), controller.getValue(2))
        assertEquals(176u.toUByte(), controller.getValue(3))
        assertEquals(40u.toUByte(), controller.getValue(4))
        assertEquals(130u.toUByte(), controller.getValue(5))
        assertEquals(70u.toUByte(), controller.getValue(6))
        assertEquals(226u.toUByte(), controller.getValue(7))
        assertEquals(100u.toUByte(), controller.getValue(8))
        assertEquals(110u.toUByte(), controller.getValue(9))
        assertEquals(120u.toUByte(), controller.getValue(10))
        assertEquals(130u.toUByte(), controller.getValue(11))
        assertEquals(140u.toUByte(), controller.getValue(12))
        assertEquals(150u.toUByte(), controller.getValue(13))
    }

    @Test
    fun `strobe maps into safe band, fullOn writes open, slider clamps below lamp band`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = MartinMac250Fixture.Mode4Ch(universe, "mac-1", "MAC 1", 1)
            .withTransaction(transaction)

        fixture.strobe.strobe(0u)
        transaction.apply()
        assertEquals(MartinMac250Fixture.Mode4Ch.STROBE_BAND_MIN, controller.getValue(1))

        fixture.strobe.strobe(255u)
        transaction.apply()
        assertEquals(MartinMac250Fixture.Mode4Ch.STROBE_BAND_MAX, controller.getValue(1))

        fixture.strobe.fullOn()
        transaction.apply()
        assertEquals(MartinMac250Fixture.Mode4Ch.OPEN_DEFAULT, controller.getValue(1))

        // Raw value writes are clamped to the strobe-band max — Reset/Lamp bands unreachable.
        fixture.strobe.value = 250u
        transaction.apply()
        assertEquals(MartinMac250Fixture.Mode4Ch.STROBE_BAND_MAX, controller.getValue(1))
    }

    @Test
    fun `lampOn lampOff and reset write the dangerous shutter bands directly`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = MartinMac250Fixture.Mode4Ch(universe, "mac-1", "MAC 1", 1)
            .withTransaction(transaction)

        fixture.lampOn()
        transaction.apply()
        assertEquals(228u.toUByte(), controller.getValue(1))

        fixture.lampOff()
        transaction.apply()
        assertEquals(248u.toUByte(), controller.getValue(1))

        fixture.reset()
        transaction.apply()
        assertEquals(208u.toUByte(), controller.getValue(1))
    }
}
