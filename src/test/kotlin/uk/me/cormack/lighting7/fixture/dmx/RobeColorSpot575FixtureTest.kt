package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class RobeColorSpot575FixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `mode 2 writes position, speed, wheels, prism, frost, iris, zoom, focus, strobe and dimmer in order`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = RobeColorSpot575Fixture.Mode2Ch(universe, "spot-1", "Spot 1", 1)
            .withTransaction(transaction)

        fixture.pan.value = 100u
        fixture.panFine.value = 110u
        fixture.tilt.value = 120u
        fixture.tiltFine.value = 130u
        fixture.panTiltSpeed.value = 50u
        // Ch 6 (Control) intentionally not exposed.
        fixture.colour1.setting = RobeColorSpot575Fixture.Colour1.RED
        fixture.colour2.setting = RobeColorSpot575Fixture.Colour2.DEEP_BLUE
        fixture.staticGobo.setting = RobeColorSpot575Fixture.StaticGobo.GOBO_3
        fixture.rotatingGobo.setting = RobeColorSpot575Fixture.RotGobo.INDEX_GOBO_2
        fixture.goboRotation.value = 64u
        fixture.prism.setting = RobeColorSpot575Fixture.Prism.MACRO_5
        fixture.prismRotation.value = 200u
        fixture.frost.value = 90u
        fixture.iris.value = 100u
        fixture.zoom.value = 80u
        fixture.focus.value = 70u
        fixture.strobe.fullOn()
        fixture.dimmer.value = 220u

        transaction.apply()

        assertEquals(100u.toUByte(), controller.getValue(1))
        assertEquals(110u.toUByte(), controller.getValue(2))
        assertEquals(120u.toUByte(), controller.getValue(3))
        assertEquals(130u.toUByte(), controller.getValue(4))
        assertEquals(50u.toUByte(), controller.getValue(5))
        // Ch 6 (Control) was never written; default is 0.
        assertEquals(0u.toUByte(), controller.getValue(6))
        assertEquals(140u.toUByte(), controller.getValue(7))
        assertEquals(140u.toUByte(), controller.getValue(8))
        assertEquals(75u.toUByte(), controller.getValue(9))
        assertEquals(8u.toUByte(), controller.getValue(10))
        assertEquals(64u.toUByte(), controller.getValue(11))
        assertEquals(160u.toUByte(), controller.getValue(12))
        assertEquals(200u.toUByte(), controller.getValue(13))
        assertEquals(90u.toUByte(), controller.getValue(14))
        assertEquals(100u.toUByte(), controller.getValue(15))
        assertEquals(80u.toUByte(), controller.getValue(16))
        assertEquals(70u.toUByte(), controller.getValue(17))
        assertEquals(RobeColorSpot575Fixture.Mode2Ch.OPEN_DEFAULT, controller.getValue(18))
        assertEquals(220u.toUByte(), controller.getValue(19))
    }

    @Test
    fun `strobe maps into safe band, fullOn writes open, slider clamps below pulse band`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = RobeColorSpot575Fixture.Mode2Ch(universe, "spot-1", "Spot 1", 1)
            .withTransaction(transaction)

        fixture.strobe.strobe(0u)
        transaction.apply()
        assertEquals(RobeColorSpot575Fixture.Mode2Ch.STROBE_BAND_MIN, controller.getValue(18))

        fixture.strobe.strobe(255u)
        transaction.apply()
        assertEquals(RobeColorSpot575Fixture.Mode2Ch.STROBE_BAND_MAX, controller.getValue(18))

        fixture.strobe.fullOn()
        transaction.apply()
        assertEquals(RobeColorSpot575Fixture.Mode2Ch.OPEN_DEFAULT, controller.getValue(18))

        // Raw value writes are clamped to the strobe-band max — pulse / random-strobe bands unreachable.
        fixture.strobe.value = 200u
        transaction.apply()
        assertEquals(RobeColorSpot575Fixture.Mode2Ch.STROBE_BAND_MAX, controller.getValue(18))
    }

    @Test
    fun `lampOn lampOff and reset family write the dangerous control bands directly`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = RobeColorSpot575Fixture.Mode2Ch(universe, "spot-1", "Spot 1", 1)
            .withTransaction(transaction)

        fixture.lampOn()
        transaction.apply()
        assertEquals(130u.toUByte(), controller.getValue(6))

        fixture.resetPanTilt()
        transaction.apply()
        assertEquals(140u.toUByte(), controller.getValue(6))

        fixture.resetColour()
        transaction.apply()
        assertEquals(150u.toUByte(), controller.getValue(6))

        fixture.resetGobo()
        transaction.apply()
        assertEquals(160u.toUByte(), controller.getValue(6))

        fixture.resetDimmer()
        transaction.apply()
        assertEquals(170u.toUByte(), controller.getValue(6))

        fixture.resetFocusZoomFrost()
        transaction.apply()
        assertEquals(180u.toUByte(), controller.getValue(6))

        fixture.resetIrisPrism()
        transaction.apply()
        assertEquals(190u.toUByte(), controller.getValue(6))

        fixture.reset()
        transaction.apply()
        assertEquals(200u.toUByte(), controller.getValue(6))

        fixture.lampOff()
        transaction.apply()
        assertEquals(230u.toUByte(), controller.getValue(6))
    }
}
