package uk.me.cormack.lighting7.fx

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for BlendMode blending behavior.
 *
 * Note: The actual blend logic is in FxTarget subclasses. These tests verify
 * the expected behavior based on the mode's contract.
 */
class BlendModeTest {

    /**
     * Helper that mirrors the blend logic in SliderTarget.applyBlendMode
     */
    private fun blendSlider(base: UByte, effect: UByte, mode: BlendMode): UByte {
        return when (mode) {
            BlendMode.OVERRIDE -> effect
            BlendMode.ADDITIVE -> (base.toInt() + effect.toInt()).coerceIn(0, 255).toUByte()
            BlendMode.MULTIPLY -> ((base.toInt() * effect.toInt()) / 255).toUByte()
            BlendMode.MAX -> maxOf(base, effect)
            BlendMode.MIN -> minOf(base, effect)
        }
    }

    @Test
    fun `OVERRIDE replaces base value completely`() {
        assertEquals(200.toUByte(), blendSlider(100u, 200u, BlendMode.OVERRIDE))
        assertEquals(0.toUByte(), blendSlider(255u, 0u, BlendMode.OVERRIDE))
        assertEquals(128.toUByte(), blendSlider(0u, 128u, BlendMode.OVERRIDE))
    }

    @Test
    fun `ADDITIVE adds effect to base value`() {
        assertEquals(150.toUByte(), blendSlider(100u, 50u, BlendMode.ADDITIVE))
        assertEquals(200.toUByte(), blendSlider(100u, 100u, BlendMode.ADDITIVE))
    }

    @Test
    fun `ADDITIVE clamps to 255`() {
        assertEquals(255.toUByte(), blendSlider(200u, 100u, BlendMode.ADDITIVE))
        assertEquals(255.toUByte(), blendSlider(255u, 255u, BlendMode.ADDITIVE))
    }

    @Test
    fun `MULTIPLY scales base by effect ratio`() {
        // 100 * (128/255) = ~50
        val result = blendSlider(100u, 128u, BlendMode.MULTIPLY)
        assertEquals(50.toUByte(), result)

        // 255 * (255/255) = 255
        assertEquals(255.toUByte(), blendSlider(255u, 255u, BlendMode.MULTIPLY))

        // Anything * 0 = 0
        assertEquals(0.toUByte(), blendSlider(255u, 0u, BlendMode.MULTIPLY))
    }

    @Test
    fun `MAX takes the higher value`() {
        assertEquals(200.toUByte(), blendSlider(100u, 200u, BlendMode.MAX))
        assertEquals(200.toUByte(), blendSlider(200u, 100u, BlendMode.MAX))
        assertEquals(255.toUByte(), blendSlider(255u, 0u, BlendMode.MAX))
        assertEquals(128.toUByte(), blendSlider(128u, 128u, BlendMode.MAX))
    }

    @Test
    fun `MIN takes the lower value`() {
        assertEquals(100.toUByte(), blendSlider(100u, 200u, BlendMode.MIN))
        assertEquals(100.toUByte(), blendSlider(200u, 100u, BlendMode.MIN))
        assertEquals(0.toUByte(), blendSlider(255u, 0u, BlendMode.MIN))
        assertEquals(128.toUByte(), blendSlider(128u, 128u, BlendMode.MIN))
    }

    @Test
    fun `blend modes handle edge cases`() {
        // Zero base value
        assertEquals(100.toUByte(), blendSlider(0u, 100u, BlendMode.OVERRIDE))
        assertEquals(100.toUByte(), blendSlider(0u, 100u, BlendMode.ADDITIVE))
        assertEquals(0.toUByte(), blendSlider(0u, 100u, BlendMode.MULTIPLY))

        // Full brightness base
        assertEquals(50.toUByte(), blendSlider(255u, 50u, BlendMode.OVERRIDE))
        assertEquals(255.toUByte(), blendSlider(255u, 50u, BlendMode.ADDITIVE)) // clamped
        assertEquals(50.toUByte(), blendSlider(255u, 50u, BlendMode.MULTIPLY))
    }

    @Test
    fun `all blend modes are covered`() {
        // Ensure all enum values are handled
        val modes = BlendMode.entries
        assertEquals(5, modes.size, "Should have 5 blend modes")

        // Each mode should produce a value in range
        modes.forEach { mode ->
            val result = blendSlider(128u, 128u, mode)
            assert(result <= 255u) { "Mode $mode should produce valid UByte" }
        }
    }
}
