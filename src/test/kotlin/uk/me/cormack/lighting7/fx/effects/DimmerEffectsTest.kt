package uk.me.cormack.lighting7.fx.effects

import uk.me.cormack.lighting7.fx.FxOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DimmerEffectsTest {

    private fun FxOutput.sliderValue(): UByte = (this as FxOutput.Slider).value

    @Test
    fun `SineWave oscillates between min and max`() {
        val effect = SineWave(min = 0u, max = 255u)

        // sin(0) = 0, normalized = 0.5, so starts at midpoint
        val atZero = effect.calculate(0.0).sliderValue()
        assertEquals(127.toUByte(), atZero) // midpoint (255 * 0.5 truncated)

        // sin(π/2) = 1, normalized = 1.0, at max
        val atQuarter = effect.calculate(0.25).sliderValue()
        assertEquals(255.toUByte(), atQuarter)

        // sin(π) = 0, normalized = 0.5, back to midpoint
        val atHalf = effect.calculate(0.5).sliderValue()
        assertEquals(127.toUByte(), atHalf)

        // sin(3π/2) = -1, normalized = 0.0, at min
        val atThreeQuarters = effect.calculate(0.75).sliderValue()
        assertEquals(0.toUByte(), atThreeQuarters)
    }

    @Test
    fun `SineWave respects min and max bounds`() {
        val effect = SineWave(min = 50u, max = 200u)

        // Test across full phase range
        for (i in 0..100) {
            val phase = i / 100.0
            val value = effect.calculate(phase).sliderValue()
            assertTrue(value >= 50u, "Value $value at phase $phase should be >= 50")
            assertTrue(value <= 200u, "Value $value at phase $phase should be <= 200")
        }
    }

    @Test
    fun `RampUp goes from min to max`() {
        val effect = RampUp(min = 0u, max = 255u)

        assertEquals(0.toUByte(), effect.calculate(0.0).sliderValue())
        assertEquals(127.toUByte(), effect.calculate(0.5).sliderValue()) // 255 * 0.5 = 127.5 -> 127
        assertEquals(255.toUByte(), effect.calculate(1.0).sliderValue())
    }

    @Test
    fun `RampDown goes from max to min`() {
        val effect = RampDown(min = 0u, max = 255u)

        assertEquals(255.toUByte(), effect.calculate(0.0).sliderValue())
        assertEquals(127.toUByte(), effect.calculate(0.5).sliderValue()) // 255 * 0.5 = 127.5 -> 127
        assertEquals(0.toUByte(), effect.calculate(1.0).sliderValue())
    }

    @Test
    fun `Triangle rises then falls`() {
        val effect = Triangle(min = 0u, max = 255u)

        // At 0, starts at min
        assertEquals(0.toUByte(), effect.calculate(0.0).sliderValue())

        // At 0.5, at max
        assertEquals(255.toUByte(), effect.calculate(0.5).sliderValue())

        // At 1.0, back to min
        assertEquals(0.toUByte(), effect.calculate(1.0).sliderValue())
    }

    @Test
    fun `Pulse follows attack-hold-release envelope`() {
        val effect = Pulse(min = 0u, max = 255u, attackRatio = 0.25, holdRatio = 0.5)
        // attack 0-0.25, hold 0.25-0.75, release 0.75-1.0

        // Start at min
        assertEquals(0.toUByte(), effect.calculate(0.0).sliderValue())

        // During attack (halfway through attack phase)
        val duringAttack = effect.calculate(0.125).sliderValue()
        assertTrue(duringAttack > 0u && duringAttack < 255u, "During attack should be rising")

        // During hold - should be at max
        val duringHold = effect.calculate(0.5).sliderValue()
        assertEquals(255.toUByte(), duringHold)

        // End at min
        assertEquals(0.toUByte(), effect.calculate(1.0).sliderValue())
    }

    @Test
    fun `SquareWave alternates between min and max`() {
        val effect = SquareWave(min = 0u, max = 255u, dutyCycle = 0.5)

        // First half should be max
        assertEquals(255.toUByte(), effect.calculate(0.0).sliderValue())
        assertEquals(255.toUByte(), effect.calculate(0.25).sliderValue())
        assertEquals(255.toUByte(), effect.calculate(0.49).sliderValue())

        // Second half should be min
        assertEquals(0.toUByte(), effect.calculate(0.5).sliderValue())
        assertEquals(0.toUByte(), effect.calculate(0.75).sliderValue())
    }

    @Test
    fun `Strobe flashes on briefly`() {
        val effect = Strobe(offValue = 0u, onValue = 255u, onRatio = 0.1)

        // First 10% should be on
        assertEquals(255.toUByte(), effect.calculate(0.0).sliderValue())
        assertEquals(255.toUByte(), effect.calculate(0.05).sliderValue())

        // Rest should be off
        assertEquals(0.toUByte(), effect.calculate(0.1).sliderValue())
        assertEquals(0.toUByte(), effect.calculate(0.5).sliderValue())
        assertEquals(0.toUByte(), effect.calculate(0.99).sliderValue())
    }

    @Test
    fun `Breathe stays within bounds`() {
        val effect = Breathe(min = 0u, max = 255u)

        for (i in 0..100) {
            val phase = i / 100.0
            val value = effect.calculate(phase).sliderValue()
            assertTrue(value >= 0u, "Breathe value should be >= 0")
            assertTrue(value <= 255u, "Breathe value should be <= 255")
        }
    }

    @Test
    fun `Flicker produces varying values within range`() {
        val effect = Flicker(min = 50u, max = 200u)
        val values = mutableSetOf<UByte>()

        // Collect values at different phases
        for (i in 0..100) {
            val phase = i / 100.0
            val value = effect.calculate(phase).sliderValue()
            assertTrue(value >= 50u, "Flicker value should be >= 50")
            assertTrue(value <= 200u, "Flicker value should be <= 200")
            values.add(value)
        }

        // Should produce varying values (not all the same)
        assertTrue(values.size > 1, "Flicker should produce varying values")
    }
}
