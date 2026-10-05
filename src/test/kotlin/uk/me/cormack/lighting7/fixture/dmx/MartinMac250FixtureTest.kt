package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import uk.me.cormack.lighting7.fixture.FixtureCommands
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
        fixture.colourWheel.setting = MartinMac250Fixture.Colour.RED
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

        // The manual's band runs "strobe, fast → slow": the slowest intensity is the top of the band.
        fixture.strobe.strobe(0u)
        transaction.apply()
        assertEquals(MartinMac250Fixture.Mode4Ch.STROBE_BAND_MAX, controller.getValue(1))

        fixture.strobe.strobe(255u)
        transaction.apply()
        assertEquals(MartinMac250Fixture.Mode4Ch.STROBE_BAND_MIN, controller.getValue(1))

        fixture.strobe.fullOn()
        transaction.apply()
        assertEquals(MartinMac250Fixture.Mode4Ch.OPEN_DEFAULT, controller.getValue(1))

        // Raw value writes are clamped to the strobe-band max — Reset/Lamp bands unreachable.
        fixture.strobe.value = 250u
        transaction.apply()
        assertEquals(MartinMac250Fixture.Mode4Ch.STROBE_BAND_MAX, controller.getValue(1))
    }

    @Test
    fun `lamp and reset are commands on the shutter channel, with the manual's bands`() {
        val fixture = MartinMac250Fixture.Mode4Ch(universe, "mac-1", "MAC 1", 1)
        val commands = FixtureCommands.of(fixture).associateBy { it.name }
        assertEquals(setOf("lampOff", "lampOn", "reset"), commands.keys)
        commands.values.forEach {
            assertEquals(1, it.channelNo)
            assertFalse(it.dedicated, "the shutter channel is the strobe property's too")
        }
        assertEquals(208u.toUByte(), commands.getValue("reset").level)
        assertEquals(208..217, commands.getValue("reset").let { it.bandMin.toInt()..it.bandMax.toInt() })
        assertEquals(228..237, commands.getValue("lampOn").let { it.bandMin.toInt()..it.bandMax.toInt() })
        assertEquals(248..255, commands.getValue("lampOff").let { it.bandMin.toInt()..it.bandMax.toInt() })
        // "Lamp off: time > 5 seconds".
        assertTrue(commands.getValue("lampOff").spec.holdMs > 5_000)
        // Reset and lamp off set what the fixture needs when its menu disables the DMX versions.
        assertEquals(
            listOf(3 to 200, 4 to 0, 7 to 80),
            commands.getValue("reset").alongside.map { it.channelNo to it.level.toInt() },
        )
        assertEquals(commands.getValue("reset").alongside, commands.getValue("lampOff").alongside)
        assertTrue(commands.getValue("lampOn").alongside.isEmpty())
    }
}
