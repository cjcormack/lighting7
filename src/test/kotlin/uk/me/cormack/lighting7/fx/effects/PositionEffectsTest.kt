package uk.me.cormack.lighting7.fx.effects

import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.fx.EffectContext
import uk.me.cormack.lighting7.fx.FxOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PositionEffectsTest {

    private fun FxOutput.position(): Pair<UByte, UByte> {
        val pos = this as FxOutput.Position
        return Pair(pos.pan, pos.tilt)
    }

    @Test
    fun `Circle produces circular movement`() {
        val effect = Circle(
            panCenter = 128u,
            tiltCenter = 128u,
            panRadius = 50u,
            tiltRadius = 50u
        )

        // At phase 0, cos(0) = 1 for pan, sin(0) = 0 for tilt
        val atZero = effect.calculate(0.0).position()
        assertEquals(178.toUByte(), atZero.first) // pan = 128 + 50
        assertEquals(128.toUByte(), atZero.second) // tilt = center

        // At phase 0.25, cos(π/2) = 0, sin(π/2) = 1
        val atQuarter = effect.calculate(0.25).position()
        assertEquals(128.toUByte(), atQuarter.first) // pan = center
        assertEquals(178.toUByte(), atQuarter.second) // tilt = 128 + 50

        // At phase 0.5, cos(π) = -1, sin(π) = 0
        val atHalf = effect.calculate(0.5).position()
        assertEquals(78.toUByte(), atHalf.first) // pan = 128 - 50
        assertEquals(128.toUByte(), atHalf.second) // tilt = center
    }

    @Test
    fun `Figure8 produces figure-8 pattern`() {
        val effect = Figure8(
            panCenter = 128u,
            tiltCenter = 128u,
            panRadius = 50u,
            tiltRadius = 30u
        )

        // Collect positions to verify it traces a figure-8
        val positions = (0..20).map { i ->
            effect.calculate(i / 20.0).position()
        }

        // Should have variation in both axes
        val pans = positions.map { it.first.toInt() }
        val tilts = positions.map { it.second.toInt() }

        assertTrue(pans.max() - pans.min() > 50, "Pan should have significant range")
        assertTrue(tilts.max() - tilts.min() > 30, "Tilt should have significant range")
    }

    @Test
    fun `Sweep moves between positions with pingPong`() {
        // Default has pingPong=true and SINE_IN_OUT curve
        val effect = Sweep(
            startPan = 0u,
            startTilt = 0u,
            endPan = 255u,
            endTilt = 255u,
            curve = EasingCurve.LINEAR, // Use LINEAR for predictable testing
            pingPong = false // Disable pingPong for simpler testing
        )

        val atStart = effect.calculate(0.0).position()
        assertEquals(0.toUByte(), atStart.first)
        assertEquals(0.toUByte(), atStart.second)

        val atMid = effect.calculate(0.5).position()
        assertEquals(127.toUByte(), atMid.first)  // 255 * 0.5 = 127
        assertEquals(127.toUByte(), atMid.second)

        val atEnd = effect.calculate(1.0).position()
        assertEquals(255.toUByte(), atEnd.first)
        assertEquals(255.toUByte(), atEnd.second)
    }

    @Test
    fun `Sweep with pingPong returns to start`() {
        val effect = Sweep(
            startPan = 0u,
            startTilt = 0u,
            endPan = 255u,
            endTilt = 255u,
            curve = EasingCurve.LINEAR,
            pingPong = true
        )

        val atStart = effect.calculate(0.0).position()
        val atEnd = effect.calculate(1.0).position()

        // With pingPong, end should return to start
        assertEquals(atStart.first, atEnd.first)
        assertEquals(atStart.second, atEnd.second)

        // At 0.5 should be at end position (peak of pingPong)
        val atMid = effect.calculate(0.5).position()
        assertEquals(255.toUByte(), atMid.first)
        assertEquals(255.toUByte(), atMid.second)
    }

    @Test
    fun `PanSweep only changes pan`() {
        val effect = PanSweep(
            startPan = 50u,
            endPan = 200u,
            tilt = 100u,
            curve = EasingCurve.LINEAR,
            pingPong = false
        )

        val atStart = effect.calculate(0.0).position()
        assertEquals(50.toUByte(), atStart.first)
        assertEquals(100.toUByte(), atStart.second)

        val atEnd = effect.calculate(1.0).position()
        assertEquals(200.toUByte(), atEnd.first)
        assertEquals(100.toUByte(), atEnd.second) // tilt unchanged
    }

    @Test
    fun `TiltSweep only changes tilt`() {
        val effect = TiltSweep(
            startTilt = 50u,
            endTilt = 200u,
            pan = 100u,
            curve = EasingCurve.LINEAR,
            pingPong = false
        )

        val atStart = effect.calculate(0.0).position()
        assertEquals(100.toUByte(), atStart.first) // pan unchanged
        assertEquals(50.toUByte(), atStart.second)

        val atEnd = effect.calculate(1.0).position()
        assertEquals(100.toUByte(), atEnd.first) // pan unchanged
        assertEquals(200.toUByte(), atEnd.second)
    }

    @Test
    fun `StaticPosition never changes`() {
        val effect = StaticPosition(pan = 100u, tilt = 150u)

        assertEquals(Pair(100.toUByte(), 150.toUByte()), effect.calculate(0.0).position())
        assertEquals(Pair(100.toUByte(), 150.toUByte()), effect.calculate(0.5).position())
        assertEquals(Pair(100.toUByte(), 150.toUByte()), effect.calculate(1.0).position())
    }

    @Test
    fun `RandomPosition stays within range`() {
        val effect = RandomPosition(
            panCenter = 128u,
            tiltCenter = 128u,
            panRange = 50u,
            tiltRange = 50u
        )

        for (i in 0..100) {
            val phase = i / 100.0
            val (pan, tilt) = effect.calculate(phase).position()

            assertTrue(pan >= 78u && pan <= 178u,
                "Pan $pan should be within range [78, 178]")
            assertTrue(tilt >= 78u && tilt <= 178u,
                "Tilt $tilt should be within range [78, 178]")
        }
    }

    @Test
    fun `Circle stays within valid DMX range`() {
        // Test edge case where circle could go out of bounds
        val effect = Circle(
            panCenter = 250u,
            tiltCenter = 250u,
            panRadius = 50u,
            tiltRadius = 50u
        )

        for (i in 0..100) {
            val phase = i / 100.0
            val (pan, tilt) = effect.calculate(phase).position()

            assertTrue(pan <= 255u, "Pan should not exceed 255")
            assertTrue(tilt <= 255u, "Tilt should not exceed 255")
        }
    }

    @Test
    fun `StaticPosition with context auto-windows for distribution`() {
        val effect = StaticPosition(pan = 50u, tilt = 200u)
        val context = EffectContext(groupSize = 4, memberIndex = 0)

        // Phase within the window (0 to 1/4) should return the position
        assertEquals(Pair(50.toUByte(), 200.toUByte()), effect.calculate(0.0, context).position())
        assertEquals(Pair(50.toUByte(), 200.toUByte()), effect.calculate(0.2, context).position())

        // Phase outside the window should return center (128, 128)
        assertEquals(Pair(128.toUByte(), 128.toUByte()), effect.calculate(0.25, context).position())
        assertEquals(Pair(128.toUByte(), 128.toUByte()), effect.calculate(0.5, context).position())
        assertEquals(Pair(128.toUByte(), 128.toUByte()), effect.calculate(0.99, context).position())
    }

    @Test
    fun `StaticPosition with single element context behaves like no context`() {
        val effect = StaticPosition(pan = 50u, tilt = 200u)

        assertEquals(Pair(50.toUByte(), 200.toUByte()), effect.calculate(0.0).position())
        assertEquals(Pair(50.toUByte(), 200.toUByte()), effect.calculate(0.5).position())
        assertEquals(Pair(50.toUByte(), 200.toUByte()), effect.calculate(1.0).position())
    }
}
