package uk.me.cormack.lighting7.fx.effects

import uk.me.cormack.lighting7.fx.FxOutput
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ColourEffectsTest {

    private fun FxOutput.colour(): Color = (this as FxOutput.Colour).color

    @Test
    fun `RainbowCycle produces full hue rotation`() {
        val effect = RainbowCycle(saturation = 1.0f, brightness = 1.0f)

        // At phase 0, should be red (hue 0)
        val atZero = effect.calculate(0.0).colour()
        assertEquals(255, atZero.red)
        assertEquals(0, atZero.blue)

        // At phase 0.33, should be around green (hue ~120)
        val atThird = effect.calculate(0.333).colour()
        assertTrue(atThird.green > atThird.red, "At 1/3, green should dominate")
        assertTrue(atThird.green > atThird.blue, "At 1/3, green should dominate")

        // At phase 0.66, should be around blue (hue ~240)
        val atTwoThirds = effect.calculate(0.666).colour()
        assertTrue(atTwoThirds.blue > atTwoThirds.red, "At 2/3, blue should dominate")
        assertTrue(atTwoThirds.blue > atTwoThirds.green, "At 2/3, blue should dominate")
    }

    @Test
    fun `ColourCycle steps through colours`() {
        val colours = listOf(Color.RED, Color.GREEN, Color.BLUE)
        val effect = ColourCycle(colours = colours, fadeRatio = 0.0) // No fade for clear steps

        // Each color should occupy 1/3 of the phase
        val atStart = effect.calculate(0.0).colour()
        assertEquals(Color.RED, atStart)

        val atMiddle = effect.calculate(0.4).colour()
        assertEquals(Color.GREEN, atMiddle)

        val atEnd = effect.calculate(0.7).colour()
        assertEquals(Color.BLUE, atEnd)
    }

    @Test
    fun `ColourCycle with fade produces intermediate colours`() {
        val colours = listOf(Color.RED, Color.BLUE)
        val effect = ColourCycle(colours = colours, fadeRatio = 1.0) // Full fade

        // Midway in fade should be purple-ish
        val midFade = effect.calculate(0.25).colour()
        assertTrue(midFade.red > 0, "Should have some red")
        assertTrue(midFade.blue > 0, "Should have some blue")
    }

    @Test
    fun `ColourStrobe alternates between on and off colours`() {
        val effect = ColourStrobe(onColor = Color.WHITE, offColor = Color.BLACK, onRatio = 0.5)

        assertEquals(Color.WHITE, effect.calculate(0.0).colour())
        assertEquals(Color.WHITE, effect.calculate(0.25).colour())
        assertEquals(Color.BLACK, effect.calculate(0.5).colour())
        assertEquals(Color.BLACK, effect.calculate(0.75).colour())
    }

    @Test
    fun `ColourPulse oscillates using sine wave`() {
        val effect = ColourPulse(colorA = Color.RED, colorB = Color.BLUE)

        // ColourPulse uses sin(phase * 2π), so at phase 0 starts at midpoint
        val atStart = effect.calculate(0.0).colour()
        // sin(0) = 0, ratio = 0.5, so midpoint between RED and BLUE
        assertTrue(atStart.red > 0 && atStart.red < 255, "Should be between colors at phase 0")
        assertTrue(atStart.blue > 0 && atStart.blue < 255, "Should be between colors at phase 0")

        // At 0.25, sin(π/2) = 1, ratio = 1.0, should be at colorB (BLUE)
        val atQuarter = effect.calculate(0.25).colour()
        assertEquals(0, atQuarter.red)
        assertEquals(255, atQuarter.blue)

        // At 0.75, sin(3π/2) = -1, ratio = 0.0, should be at colorA (RED)
        val atThreeQuarters = effect.calculate(0.75).colour()
        assertEquals(255, atThreeQuarters.red)
        assertEquals(0, atThreeQuarters.blue)
    }

    @Test
    fun `ColourFade produces linear transition`() {
        val effect = ColourFade(fromColor = Color.BLACK, toColor = Color.WHITE, pingPong = false)

        val atStart = effect.calculate(0.0).colour()
        assertEquals(0, atStart.red)
        assertEquals(0, atStart.green)
        assertEquals(0, atStart.blue)

        val atEnd = effect.calculate(1.0).colour()
        assertEquals(255, atEnd.red)
        assertEquals(255, atEnd.green)
        assertEquals(255, atEnd.blue)

        // Midpoint should be gray
        val atMid = effect.calculate(0.5).colour()
        assertTrue(atMid.red in 120..135, "Mid should be around 128, was ${atMid.red}")
    }

    @Test
    fun `ColourFade with pingPong returns to start`() {
        val effect = ColourFade(fromColor = Color.BLACK, toColor = Color.WHITE, pingPong = true)

        val atStart = effect.calculate(0.0).colour()
        val atEnd = effect.calculate(1.0).colour()

        // With pingPong, end should be same as start
        assertEquals(atStart.red, atEnd.red)
        assertEquals(atStart.green, atEnd.green)
        assertEquals(atStart.blue, atEnd.blue)
    }

    @Test
    fun `StaticColour always returns the same colour`() {
        val effect = StaticColour(color = Color.CYAN)

        assertEquals(Color.CYAN, effect.calculate(0.0).colour())
        assertEquals(Color.CYAN, effect.calculate(0.5).colour())
        assertEquals(Color.CYAN, effect.calculate(1.0).colour())
    }

    @Test
    fun `ColourFlicker produces variation around base colour`() {
        val effect = ColourFlicker(baseColor = Color(128, 128, 128), variation = 50)
        val colours = mutableSetOf<Color>()

        for (i in 0..100) {
            val phase = i / 100.0
            val colour = effect.calculate(phase).colour()

            // Should stay within variation range
            assertTrue(colour.red >= 78 && colour.red <= 178,
                "Red ${colour.red} should be within variation of 128")
            assertTrue(colour.green >= 78 && colour.green <= 178,
                "Green ${colour.green} should be within variation of 128")
            assertTrue(colour.blue >= 78 && colour.blue <= 178,
                "Blue ${colour.blue} should be within variation of 128")

            colours.add(colour)
        }

        // Should produce varying colours
        assertTrue(colours.size > 1, "ColourFlicker should produce varying colours")
    }
}
