package uk.me.cormack.lighting7.fx.effects

import uk.me.cormack.lighting7.fx.EffectContext
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.FxOutput
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ColourEffectsTest {

    /** Extract the RGB [Color] from an [FxOutput.Colour] */
    private fun FxOutput.colour(): Color = (this as FxOutput.Colour).color.color

    /** Extract the [ExtendedColour] from an [FxOutput.Colour] */
    private fun FxOutput.extColour(): ExtendedColour = (this as FxOutput.Colour).color

    private fun Color.ext() = ExtendedColour.fromColor(this)

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
        val colours = listOf(Color.RED, Color.GREEN, Color.BLUE).map { it.ext() }
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
        val colours = listOf(Color.RED, Color.BLUE).map { it.ext() }
        val effect = ColourCycle(colours = colours, fadeRatio = 1.0) // Full fade

        // Midway in fade should be purple-ish
        val midFade = effect.calculate(0.25).colour()
        assertTrue(midFade.red > 0, "Should have some red")
        assertTrue(midFade.blue > 0, "Should have some blue")
    }

    @Test
    fun `ColourStrobe alternates between on and off colours`() {
        val effect = ColourStrobe(onColor = Color.WHITE.ext(), offColor = Color.BLACK.ext(), onRatio = 0.5)

        assertEquals(Color.WHITE, effect.calculate(0.0).colour())
        assertEquals(Color.WHITE, effect.calculate(0.25).colour())
        assertEquals(Color.BLACK, effect.calculate(0.5).colour())
        assertEquals(Color.BLACK, effect.calculate(0.75).colour())
    }

    @Test
    fun `ColourPulse oscillates using sine wave`() {
        val effect = ColourPulse(colorA = Color.RED.ext(), colorB = Color.BLUE.ext())

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
        val effect = ColourFade(fromColor = Color.BLACK.ext(), toColor = Color.WHITE.ext(), pingPong = false)

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
        val effect = ColourFade(fromColor = Color.BLACK.ext(), toColor = Color.WHITE.ext(), pingPong = true)

        val atStart = effect.calculate(0.0).colour()
        val atEnd = effect.calculate(1.0).colour()

        // With pingPong, end should be same as start
        assertEquals(atStart.red, atEnd.red)
        assertEquals(atStart.green, atEnd.green)
        assertEquals(atStart.blue, atEnd.blue)
    }

    @Test
    fun `StaticColour always returns the same colour`() {
        val effect = StaticColour(color = Color.CYAN.ext())

        assertEquals(Color.CYAN, effect.calculate(0.0).colour())
        assertEquals(Color.CYAN, effect.calculate(0.5).colour())
        assertEquals(Color.CYAN, effect.calculate(1.0).colour())
    }

    @Test
    fun `ColourFlicker produces variation around base colour`() {
        val effect = ColourFlicker(baseColor = Color(128, 128, 128).ext(), variation = 50)
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

    @Test
    fun `ExtendedColour blends W A UV channels correctly`() {
        val from = ExtendedColour(Color.RED, white = 0u, amber = 100u, uv = 200u)
        val to = ExtendedColour(Color.BLUE, white = 255u, amber = 0u, uv = 0u)
        val effect = ColourFade(fromColor = from, toColor = to, pingPong = false)

        // At midpoint
        val mid = effect.calculate(0.5).extColour()
        assertTrue(mid.white.toInt() in 120..135, "White should be ~128, was ${mid.white}")
        assertTrue(mid.amber.toInt() in 45..55, "Amber should be ~50, was ${mid.amber}")
        assertTrue(mid.uv.toInt() in 95..105, "UV should be ~100, was ${mid.uv}")
    }

    @Test
    fun `StaticColour preserves extended channels`() {
        val color = ExtendedColour(Color.RED, white = 128u, amber = 64u, uv = 32u)
        val effect = StaticColour(color = color)

        val output = effect.calculate(0.5).extColour()
        assertEquals(Color.RED, output.color)
        assertEquals(128u.toUByte(), output.white)
        assertEquals(64u.toUByte(), output.amber)
        assertEquals(32u.toUByte(), output.uv)
    }

    @Test
    fun `StaticColour with context auto-windows for distribution`() {
        val effect = StaticColour(color = Color.RED.ext())
        // Simulate member 0 of a 12-element LINEAR distribution
        val offset = 0.0
        val context = EffectContext(groupSize = 12, memberIndex = 0, distributionOffset = offset, hasDistributionSpread = true, numDistinctSlots = 12)

        fun shiftedPhase(base: Double) = (base - offset + 1.0) % 1.0

        // Base phase within member 0's window [0, 1/12) should return the colour
        assertEquals(Color.RED, effect.calculate(shiftedPhase(0.0), context).colour())
        assertEquals(Color.RED, effect.calculate(shiftedPhase(0.04), context).colour())

        // Base phase outside the window should return black
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.1), context).colour())
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.5), context).colour())
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.99), context).colour())
    }

    @Test
    fun `StaticColour with single element context behaves like no context`() {
        val effect = StaticColour(color = Color.RED.ext())
        val context = EffectContext.SINGLE

        // Should always return the colour regardless of phase
        assertEquals(Color.RED, effect.calculate(0.0, context).colour())
        assertEquals(Color.RED, effect.calculate(0.5, context).colour())
        assertEquals(Color.RED, effect.calculate(1.0, context).colour())
    }

    @Test
    fun `StaticColour with UNIFIED distribution always returns colour`() {
        val effect = StaticColour(color = Color.RED.ext())
        // UNIFIED: hasDistributionSpread = false
        val context = EffectContext(groupSize = 12, memberIndex = 5, hasDistributionSpread = false)

        assertEquals(Color.RED, effect.calculate(0.0, context).colour())
        assertEquals(Color.RED, effect.calculate(0.5, context).colour())
        assertEquals(Color.RED, effect.calculate(0.99, context).colour())
    }

    @Test
    fun `StaticColour chase fires elements in forward order`() {
        val effect = StaticColour(color = Color.RED.ext())
        val groupSize = 4

        // Simulate LINEAR distribution: element i gets offset i/N
        // At basePhase = 0.1, element 0 should be ON (its window is [0, 0.25))
        // At basePhase = 0.3, element 1 should be ON (its window is [0.25, 0.5))

        fun contextFor(idx: Int): EffectContext {
            val offset = idx.toDouble() / groupSize
            return EffectContext(groupSize = groupSize, memberIndex = idx, distributionOffset = offset, hasDistributionSpread = true, numDistinctSlots = groupSize)
        }

        fun shiftedPhase(basePhase: Double, idx: Int): Double {
            return (basePhase - idx.toDouble() / groupSize + 1.0) % 1.0
        }

        // basePhase = 0.1 -> element 0 should be ON (window [0, 0.25))
        assertEquals(Color.RED, effect.calculate(shiftedPhase(0.1, 0), contextFor(0)).colour())
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.1, 1), contextFor(1)).colour())
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.1, 2), contextFor(2)).colour())
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.1, 3), contextFor(3)).colour())

        // basePhase = 0.3 -> element 1 should be ON (window [0.25, 0.5))
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.3, 0), contextFor(0)).colour())
        assertEquals(Color.RED, effect.calculate(shiftedPhase(0.3, 1), contextFor(1)).colour())
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.3, 2), contextFor(2)).colour())
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.3, 3), contextFor(3)).colour())

        // basePhase = 0.6 -> element 2 should be ON (window [0.5, 0.75))
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.6, 0), contextFor(0)).colour())
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.6, 1), contextFor(1)).colour())
        assertEquals(Color.RED, effect.calculate(shiftedPhase(0.6, 2), contextFor(2)).colour())
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.6, 3), contextFor(3)).colour())

        // basePhase = 0.8 -> element 3 should be ON (window [0.75, 1.0))
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.8, 0), contextFor(0)).colour())
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.8, 1), contextFor(1)).colour())
        assertEquals(Color.BLACK, effect.calculate(shiftedPhase(0.8, 2), contextFor(2)).colour())
        assertEquals(Color.RED, effect.calculate(shiftedPhase(0.8, 3), contextFor(3)).colour())
    }
}
