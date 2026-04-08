package uk.me.cormack.lighting7.fx.effects

import uk.me.cormack.lighting7.fx.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompositeEffectTest {

    private fun FxOutput.sliderValue(): UByte = (this as FxOutput.Slider).value
    private fun FxOutput.colourValue(): ExtendedColour = (this as FxOutput.Colour).color

    @Test
    fun `LightningStrike produces both SLIDER and COLOUR outputs`() {
        val effect = LightningStrike()
        val outputs = effect.calculateComposite(0.0)

        assertNotNull(outputs[FxOutputType.SLIDER], "Should produce SLIDER output")
        assertNotNull(outputs[FxOutputType.COLOUR], "Should produce COLOUR output")
    }

    @Test
    fun `LightningStrike flash phase has max brightness`() {
        val effect = LightningStrike(maxBrightness = 255u)
        val outputs = effect.calculateComposite(0.02) // Within flash phase

        val brightness = outputs[FxOutputType.SLIDER]!!.sliderValue()
        assertEquals(255.toUByte(), brightness)
    }

    @Test
    fun `LightningStrike decay phase reduces brightness`() {
        val effect = LightningStrike(maxBrightness = 255u, minBrightness = 0u)

        val flashOutputs = effect.calculateComposite(0.02)
        val decayOutputs = effect.calculateComposite(0.15) // Mid-decay

        val flashBrightness = flashOutputs[FxOutputType.SLIDER]!!.sliderValue()
        val decayBrightness = decayOutputs[FxOutputType.SLIDER]!!.sliderValue()

        assertTrue(decayBrightness < flashBrightness,
            "Decay brightness $decayBrightness should be less than flash $flashBrightness")
    }

    @Test
    fun `LightningStrike dark phase at minimum`() {
        val effect = LightningStrike(minBrightness = 10u)
        val outputs = effect.calculateComposite(0.5) // Well into dark phase

        val brightness = outputs[FxOutputType.SLIDER]!!.sliderValue()
        assertEquals(10.toUByte(), brightness)
    }

    @Test
    fun `LightningStrike declares correct output types`() {
        val effect = LightningStrike()
        assertEquals(setOf(FxOutputType.SLIDER, FxOutputType.COLOUR), effect.outputTypes)
        assertEquals(FxOutputType.SLIDER, effect.outputType)
    }

    @Test
    fun `LightningStrike default calculate returns primary SLIDER output`() {
        val effect = LightningStrike()
        // Default calculate() should return the SLIDER output from calculateComposite()
        val output = effect.calculate(0.02) // Flash phase
        assertTrue(output is FxOutput.Slider)
        assertEquals(255.toUByte(), output.sliderValue())
    }

    @Test
    fun `LightningStrike colour transitions from flash to decay`() {
        val effect = LightningStrike()

        val flashColour = effect.calculateComposite(0.02)[FxOutputType.COLOUR]!!.colourValue()
        val decayColour = effect.calculateComposite(0.25)[FxOutputType.COLOUR]!!.colourValue()

        // Flash colour should be whiter (higher red/green) than decay colour (bluer)
        assertTrue(flashColour.color.red > decayColour.color.red,
            "Flash red ${flashColour.color.red} should be > decay red ${decayColour.color.red}")
    }

    @Test
    fun `LightningStrike respects custom colours`() {
        val customFlash = ExtendedColour.fromColor(java.awt.Color.RED)
        val effect = LightningStrike(flashColour = customFlash)
        val outputs = effect.calculateComposite(0.02)

        val colour = outputs[FxOutputType.COLOUR]!!.colourValue()
        assertEquals(customFlash, colour)
    }
}
