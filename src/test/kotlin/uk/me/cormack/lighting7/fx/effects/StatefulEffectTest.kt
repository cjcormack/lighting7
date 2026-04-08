package uk.me.cormack.lighting7.fx.effects

import uk.me.cormack.lighting7.fx.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatefulEffectTest {

    private fun FxOutput.sliderValue(): UByte = (this as FxOutput.Slider).value

    private fun tick(tickNumber: Long, timestampMs: Long = tickNumber * 20L) =
        MasterClock.ClockTick(
            tickNumber = tickNumber,
            beatNumber = tickNumber / 24,
            tickInBeat = (tickNumber % 24).toInt(),
            phase = (tickNumber % 24).toDouble() / 24.0,
            timestampMs = timestampMs,
        )

    @Test
    fun `CandleFlicker stays within bounds over many ticks`() {
        val effect = CandleFlicker(baseLevel = 180u, min = 100u, max = 230u)
        effect.initialize()

        for (i in 0L..500L) {
            val output = effect.calculateStateful(tick(i), deltaMs = 20, EffectContext.SINGLE)
            val value = output.sliderValue()
            assertTrue(value >= 100u, "Tick $i: value $value below min 100")
            assertTrue(value <= 230u, "Tick $i: value $value above max 230")
        }
    }

    @Test
    fun `CandleFlicker initialize resets state`() {
        val effect = CandleFlicker(baseLevel = 180u, min = 100u, max = 230u)
        effect.initialize()

        // Run for a while
        for (i in 0L..100L) {
            effect.calculateStateful(tick(i), deltaMs = 20, EffectContext.SINGLE)
        }

        // Re-initialize should reset
        effect.initialize()
        val firstOutput = effect.calculateStateful(tick(0), deltaMs = 0, EffectContext.SINGLE)
        val value = firstOutput.sliderValue()
        // After initialize, first value should be near baseLevel
        assertTrue(value >= 150u && value <= 210u,
            "After initialize, first value $value should be near baseLevel 180")
    }

    @Test
    fun `CandleFlicker respects custom parameters`() {
        val effect = CandleFlicker(baseLevel = 50u, min = 20u, max = 80u)
        effect.initialize()

        for (i in 0L..200L) {
            val output = effect.calculateStateful(tick(i), deltaMs = 20, EffectContext.SINGLE)
            val value = output.sliderValue()
            assertTrue(value >= 20u, "Tick $i: value $value below min 20")
            assertTrue(value <= 80u, "Tick $i: value $value above max 80")
        }
    }

    @Test
    fun `StatefulEffect fallback calculate returns neutral value`() {
        val effect = CandleFlicker()
        // Calling calculate() directly (without tick info) should return neutral
        val output = effect.calculate(0.5)
        assertEquals(0.toUByte(), output.sliderValue())
    }

    @Test
    fun `CandleFlicker produces varying output over time`() {
        val effect = CandleFlicker(baseLevel = 180u, min = 100u, max = 230u, smoothing = 0.5)
        effect.initialize()

        val values = mutableSetOf<UByte>()
        for (i in 0L..200L) {
            val output = effect.calculateStateful(tick(i), deltaMs = 20, EffectContext.SINGLE)
            values.add(output.sliderValue())
        }

        // Should produce multiple distinct values (not just one constant)
        assertTrue(values.size > 3, "Expected variation, got only ${values.size} distinct values: $values")
    }
}
