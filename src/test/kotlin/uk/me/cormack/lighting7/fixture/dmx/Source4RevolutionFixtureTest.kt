package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class Source4RevolutionFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `base frame writes dimmer, position, beam-shaping, gel and frame channels in order`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = Source4RevolutionFixture.BaseFrame31Ch(universe, "s4rev-1", "S4 Rev 1", 1)
            .withTransaction(transaction)

        fixture.dimmer.value = 200u
        fixture.pan.value = 100u
        fixture.panFine.value = 110u
        fixture.tilt.value = 120u
        fixture.tiltFine.value = 130u
        fixture.mediaFrame.value = 10u
        fixture.focus.value = 20u
        fixture.zoom.value = 30u
        fixture.focusTime.value = 40u
        fixture.colTime.value = 50u
        fixture.beamTime.value = 60u
        // Ch 12 (Reset) intentionally not exposed.
        fixture.gelScroller.setting = Source4RevolutionFixture.GelFrame.FRAME_5
        // Ch 14 (Reserved) intentionally not exposed.
        fixture.iris.value = 80u
        fixture.fbWheelPos.value = 90u
        fixture.fbWheelFunc.value = 91u
        fixture.fbWheelRot.value = 92u
        fixture.fbWheelRotFine.value = 93u
        fixture.rbWheelPos.value = 100u
        fixture.rbWheelFunc.value = 101u
        fixture.rbWheelRot.value = 102u
        fixture.rbWheelRotFine.value = 103u
        fixture.frame1Pos.value = 110u
        fixture.frame1Rot.value = 111u
        fixture.frame2Pos.value = 112u
        fixture.frame2Rot.value = 113u
        fixture.frame3Pos.value = 114u
        fixture.frame3Rot.value = 115u
        fixture.frame4Pos.value = 116u
        fixture.frame4Rot.value = 117u

        transaction.apply()

        assertEquals(200u.toUByte(), controller.getValue(1))
        assertEquals(100u.toUByte(), controller.getValue(2))
        assertEquals(110u.toUByte(), controller.getValue(3))
        assertEquals(120u.toUByte(), controller.getValue(4))
        assertEquals(130u.toUByte(), controller.getValue(5))
        assertEquals(10u.toUByte(), controller.getValue(6))
        assertEquals(20u.toUByte(), controller.getValue(7))
        assertEquals(30u.toUByte(), controller.getValue(8))
        assertEquals(40u.toUByte(), controller.getValue(9))
        assertEquals(50u.toUByte(), controller.getValue(10))
        assertEquals(60u.toUByte(), controller.getValue(11))
        // Ch 12 (Reset) was never written; default is 0.
        assertEquals(0u.toUByte(), controller.getValue(12))
        assertEquals(91u.toUByte(), controller.getValue(13))
        // Ch 14 (Reserved) was never written; default is 0.
        assertEquals(0u.toUByte(), controller.getValue(14))
        assertEquals(80u.toUByte(), controller.getValue(15))
        assertEquals(90u.toUByte(), controller.getValue(16))
        assertEquals(91u.toUByte(), controller.getValue(17))
        assertEquals(92u.toUByte(), controller.getValue(18))
        assertEquals(93u.toUByte(), controller.getValue(19))
        assertEquals(100u.toUByte(), controller.getValue(20))
        assertEquals(101u.toUByte(), controller.getValue(21))
        assertEquals(102u.toUByte(), controller.getValue(22))
        assertEquals(103u.toUByte(), controller.getValue(23))
        assertEquals(110u.toUByte(), controller.getValue(24))
        assertEquals(111u.toUByte(), controller.getValue(25))
        assertEquals(112u.toUByte(), controller.getValue(26))
        assertEquals(113u.toUByte(), controller.getValue(27))
        assertEquals(114u.toUByte(), controller.getValue(28))
        assertEquals(115u.toUByte(), controller.getValue(29))
        assertEquals(116u.toUByte(), controller.getValue(30))
        assertEquals(117u.toUByte(), controller.getValue(31))
    }

    @Test
    fun `gel scroller enum levels match the captured 14-frame band layout`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = Source4RevolutionFixture.BaseFrame31Ch(universe, "s4rev-1", "S4 Rev 1", 1)
            .withTransaction(transaction)

        // Frame 0 is the band start (000–017).
        fixture.gelScroller.setting = Source4RevolutionFixture.GelFrame.FRAME_0
        transaction.apply()
        assertEquals(0u.toUByte(), controller.getValue(13))

        // Frame 7 is the middle of the scroller (band 128–145).
        fixture.gelScroller.setting = Source4RevolutionFixture.GelFrame.FRAME_7
        transaction.apply()
        assertEquals(128u.toUByte(), controller.getValue(13))

        // Frame 13 is the last band (238–255).
        fixture.gelScroller.setting = Source4RevolutionFixture.GelFrame.FRAME_13
        transaction.apply()
        assertEquals(238u.toUByte(), controller.getValue(13))
    }
}
