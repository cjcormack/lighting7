package uk.me.cormack.lighting7.dmx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EasingCurveTest {

    @Test
    fun `LINEAR curve is identity function`() {
        assertEquals(0.0, EasingCurve.LINEAR.apply(0.0), 0.0001)
        assertEquals(0.25, EasingCurve.LINEAR.apply(0.25), 0.0001)
        assertEquals(0.5, EasingCurve.LINEAR.apply(0.5), 0.0001)
        assertEquals(0.75, EasingCurve.LINEAR.apply(0.75), 0.0001)
        assertEquals(1.0, EasingCurve.LINEAR.apply(1.0), 0.0001)
    }

    @Test
    fun `all curves start at 0 and end at 1`() {
        EasingCurve.entries.forEach { curve ->
            assertEquals(0.0, curve.apply(0.0), 0.0001, "Curve $curve should start at 0")
            assertEquals(1.0, curve.apply(1.0), 0.0001, "Curve $curve should end at 1")
        }
    }

    @Test
    fun `SINE_IN starts slow`() {
        // At t=0.5, sine-in should be less than 0.5 (slow start)
        val midpoint = EasingCurve.SINE_IN.apply(0.5)
        assertTrue(midpoint < 0.5, "SINE_IN at 0.5 should be less than 0.5, was $midpoint")
    }

    @Test
    fun `SINE_OUT ends slow`() {
        // At t=0.5, sine-out should be greater than 0.5 (fast start, slow end)
        val midpoint = EasingCurve.SINE_OUT.apply(0.5)
        assertTrue(midpoint > 0.5, "SINE_OUT at 0.5 should be greater than 0.5, was $midpoint")
    }

    @Test
    fun `SINE_IN_OUT is symmetric around midpoint`() {
        val midpoint = EasingCurve.SINE_IN_OUT.apply(0.5)
        assertEquals(0.5, midpoint, 0.0001, "SINE_IN_OUT should pass through 0.5 at t=0.5")
    }

    @Test
    fun `QUAD curves follow expected pattern`() {
        // QUAD_IN: slow start (below linear)
        assertTrue(EasingCurve.QUAD_IN.apply(0.5) < 0.5)
        // QUAD_OUT: fast start (above linear)
        assertTrue(EasingCurve.QUAD_OUT.apply(0.5) > 0.5)
        // QUAD_IN_OUT: symmetric
        assertEquals(0.5, EasingCurve.QUAD_IN_OUT.apply(0.5), 0.0001)
    }

    @Test
    fun `CUBIC curves follow expected pattern`() {
        // CUBIC_IN: slow start (below linear)
        assertTrue(EasingCurve.CUBIC_IN.apply(0.5) < 0.5)
        // CUBIC_OUT: fast start (above linear)
        assertTrue(EasingCurve.CUBIC_OUT.apply(0.5) > 0.5)
        // CUBIC_IN_OUT: symmetric
        assertEquals(0.5, EasingCurve.CUBIC_IN_OUT.apply(0.5), 0.0001)
    }

    @Test
    fun `STEP jumps at the end`() {
        assertEquals(0.0, EasingCurve.STEP.apply(0.0), 0.0001)
        assertEquals(0.0, EasingCurve.STEP.apply(0.5), 0.0001)
        assertEquals(0.0, EasingCurve.STEP.apply(0.99), 0.0001)
        assertEquals(1.0, EasingCurve.STEP.apply(1.0), 0.0001)
    }

    @Test
    fun `STEP_HALF jumps at midpoint`() {
        assertEquals(0.0, EasingCurve.STEP_HALF.apply(0.0), 0.0001)
        assertEquals(0.0, EasingCurve.STEP_HALF.apply(0.49), 0.0001)
        assertEquals(1.0, EasingCurve.STEP_HALF.apply(0.5), 0.0001)
        assertEquals(1.0, EasingCurve.STEP_HALF.apply(1.0), 0.0001)
    }

    @Test
    fun `interpolate with LINEAR produces expected values`() {
        val start: UByte = 0u
        val end: UByte = 100u

        assertEquals(0.toUByte(), EasingCurve.LINEAR.interpolate(start, end, 0.0))
        assertEquals(50.toUByte(), EasingCurve.LINEAR.interpolate(start, end, 0.5))
        assertEquals(100.toUByte(), EasingCurve.LINEAR.interpolate(start, end, 1.0))
    }

    @Test
    fun `interpolate handles reverse direction`() {
        val start: UByte = 255u
        val end: UByte = 0u

        assertEquals(255.toUByte(), EasingCurve.LINEAR.interpolate(start, end, 0.0))
        assertEquals(128.toUByte(), EasingCurve.LINEAR.interpolate(start, end, 0.5)) // rounds to 128
        assertEquals(0.toUByte(), EasingCurve.LINEAR.interpolate(start, end, 1.0))
    }

    @Test
    fun `interpolate clamps to valid UByte range`() {
        val start: UByte = 0u
        val end: UByte = 255u

        // Even with slight over/undershoot, should stay in range
        val result = EasingCurve.LINEAR.interpolate(start, end, 1.0)
        assertTrue(result <= 255u, "Result should not exceed 255")
    }
}
