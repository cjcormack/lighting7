package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Layer3ResolverTest {

    private val resolver = Layer3Resolver()

    private fun slider(
        cueId: Int,
        priority: Int,
        fadeWeight: Double,
        targetKey: String = "fx-1",
        propertyName: String = "dimmer",
        value: UByte,
        category: PropertyCategory = PropertyCategory.DIMMER,
    ) = Layer3Resolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = fadeWeight,
        targetKey = targetKey,
        targetIsGroup = false,
        propertyName = propertyName,
        category = category,
        value = Layer3Resolver.PropertyValue.Slider(value),
    )

    private fun colour(
        cueId: Int,
        priority: Int,
        fadeWeight: Double,
        targetKey: String = "fx-1",
        red: Int, green: Int, blue: Int,
    ) = Layer3Resolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = fadeWeight,
        targetKey = targetKey,
        targetIsGroup = false,
        propertyName = "rgbColour",
        category = PropertyCategory.COLOUR,
        value = Layer3Resolver.PropertyValue.Colour(ExtendedColour(Color(red, green, blue))),
    )

    @Test
    fun `empty input yields empty output`() {
        assertTrue(resolver.resolve(emptyList()).isEmpty())
    }

    /** Spec Example 3 — HTP dimmer, two cues both fully in. */
    @Test
    fun `HTP max over two dimmer contributors`() {
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 100u),
            slider(cueId = 2, priority = 2, fadeWeight = 1.0, value = 180u),
        ))
        val v = result[Layer3Resolver.Key.fixture("fx-1", "dimmer")]
        assertIs<Layer3Resolver.PropertyValue.Slider>(v)
        assertEquals(180u.toUByte(), v.value)
    }

    /** Spec Example 3 continuation — outgoing cue at 50% still loses to dominant incoming. */
    @Test
    fun `HTP scales by fade weight before max`() {
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.5, value = 100u), // → 50 after scale
            slider(cueId = 2, priority = 2, fadeWeight = 1.0, value = 180u),
        ))
        val v = result[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(180u.toUByte(), v.value)
    }

    // HTP switches to linear blend (Σ v×w) when Σ weights ≤ 1 — otherwise the naive
    // max(v × w) rule dips below the outgoing value midway through a crossfade.

    @Test
    fun `HTP crossfade start — outgoing at 1_0 incoming at 0_0 reads outgoing value`() {
        val v = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 50u),
            slider(cueId = 2, priority = 2, fadeWeight = 0.0, value = 200u),
        ))[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(50u.toUByte(), v.value)
    }

    @Test
    fun `HTP crossfade mid — half-half blends linearly`() {
        val v = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.5, value = 50u),
            slider(cueId = 2, priority = 2, fadeWeight = 0.5, value = 200u),
        ))[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(125u.toUByte(), v.value)
    }

    @Test
    fun `HTP crossfade end — outgoing at 0_0 incoming at 1_0 reads incoming value`() {
        val v = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.0, value = 50u),
            slider(cueId = 2, priority = 2, fadeWeight = 1.0, value = 200u),
        ))[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(200u.toUByte(), v.value)
    }

    @Test
    fun `HTP two cues both at weight 1 keeps max semantics`() {
        val v = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 100u),
            slider(cueId = 2, priority = 2, fadeWeight = 1.0, value = 180u),
        ))[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(180u.toUByte(), v.value)
    }

    @Test
    fun `HTP floating-point sum near 1 still takes the blend path`() {
        // `0.5 + 0.5` = `1.0000000000000002` — without the epsilon this tips into max.
        val v = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.5, value = 50u),
            slider(cueId = 2, priority = 2, fadeWeight = 0.5, value = 200u),
        ))[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(125u.toUByte(), v.value)
    }

    @Test
    fun `HTP setting category uses blend path for crossfade`() {
        val assignment1 = Layer3Resolver.Assignment(
            cueId = 1, priority = 1, fadeWeight = 0.5,
            targetKey = "fx-1", targetIsGroup = false,
            propertyName = "mode", category = PropertyCategory.SETTING,
            compositionOverride = CompositionRule.HTP,
            value = Layer3Resolver.PropertyValue.Setting(60u),
        )
        val assignment2 = assignment1.copy(
            cueId = 2, priority = 2,
            value = Layer3Resolver.PropertyValue.Setting(200u),
        )
        val v = resolver.resolve(listOf(assignment1, assignment2))[
            Layer3Resolver.Key.fixture("fx-1", "mode")
        ] as Layer3Resolver.PropertyValue.Setting
        assertEquals(130u.toUByte(), v.channelValue)
    }

    /** Spec Example 4 — LTP colour, B fully in, A is not contributing fade-out. */
    @Test
    fun `LTP colour picks highest priority when no crossfade`() {
        val result = resolver.resolve(listOf(
            colour(cueId = 1, priority = 1, fadeWeight = 1.0, red = 255, green = 0, blue = 0),
            colour(cueId = 2, priority = 2, fadeWeight = 1.0, red = 0, green = 0, blue = 255),
        ))
        val v = result[Layer3Resolver.Key.fixture("fx-1", "rgbColour")] as Layer3Resolver.PropertyValue.Colour
        assertEquals(Color(0, 0, 255), v.value.color)
    }

    /** Spec Example 4 crossfade — outgoing A at weight 0.4 (not yet fully released), incoming B at 0.6. */
    @Test
    fun `LTP colour blends RGB during crossfade`() {
        val result = resolver.resolve(listOf(
            colour(cueId = 1, priority = 1, fadeWeight = 0.4, red = 255, green = 0, blue = 0),
            colour(cueId = 2, priority = 2, fadeWeight = 0.6, red = 0, green = 0, blue = 255),
        ))
        val v = result[Layer3Resolver.Key.fixture("fx-1", "rgbColour")] as Layer3Resolver.PropertyValue.Colour
        // Linear RGB: (1 - 0.6) * red + 0.6 * blue per channel.
        assertEquals(102, v.value.color.red, "red")
        assertEquals(0, v.value.color.green, "green")
        assertEquals(153, v.value.color.blue, "blue")
    }

    @Test
    fun `LTP slider interpolates during crossfade`() {
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.3, value = 100u,
                category = PropertyCategory.PAN, propertyName = "pan"),
            slider(cueId = 2, priority = 2, fadeWeight = 0.7, value = 200u,
                category = PropertyCategory.PAN, propertyName = "pan"),
        ))
        val v = result[Layer3Resolver.Key.fixture("fx-1", "pan")] as Layer3Resolver.PropertyValue.Slider
        // outgoing 100 → incoming 200 at progress 0.7 → 100 + 100 * 0.7 = 170
        assertEquals(170u.toUByte(), v.value)
    }

    /**
     * Regression test for the Phase 1b composeLtp filter fix. At the very start of a
     * crossfade (outgoing.weight=1.0, incoming.weight=0.0) the outgoing's value must still
     * win. The previous `fadeWeight > 0 && fadeWeight < 1.0` filter excluded outgoing at
     * exactly 1.0, causing the incoming's raw value to snap onto stage.
     */
    @Test
    fun `LTP crossfade start — outgoing at 1 pins outgoing value`() {
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 100u,
                category = PropertyCategory.PAN, propertyName = "pan"),
            slider(cueId = 2, priority = 2, fadeWeight = 0.0, value = 200u,
                category = PropertyCategory.PAN, propertyName = "pan"),
        ))
        val v = result[Layer3Resolver.Key.fixture("fx-1", "pan")] as Layer3Resolver.PropertyValue.Slider
        // progress 0 → 100 + (200 - 100) * 0 = 100 (outgoing value).
        assertEquals(100u.toUByte(), v.value)
    }

    @Test
    fun `LTP crossfade end — incoming at 1 pins incoming value`() {
        // Symmetric: progress reaches 1.0, outgoing filtered out (weight=0), winner stands.
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.0, value = 100u,
                category = PropertyCategory.PAN, propertyName = "pan"),
            slider(cueId = 2, priority = 2, fadeWeight = 1.0, value = 200u,
                category = PropertyCategory.PAN, propertyName = "pan"),
        ))
        val v = result[Layer3Resolver.Key.fixture("fx-1", "pan")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(200u.toUByte(), v.value)
    }

    @Test
    fun `LTP colour crossfade start — outgoing at 1 pins outgoing colour`() {
        val result = resolver.resolve(listOf(
            colour(cueId = 1, priority = 1, fadeWeight = 1.0, red = 255, green = 0, blue = 0),
            colour(cueId = 2, priority = 2, fadeWeight = 0.0, red = 0, green = 0, blue = 255),
        ))
        val v = result[Layer3Resolver.Key.fixture("fx-1", "rgbColour")] as Layer3Resolver.PropertyValue.Colour
        assertEquals(Color(255, 0, 0), v.value.color, "outgoing red dominates at progress 0")
    }

    @Test
    fun `LTP settings snap at 50 percent fade progress`() {
        // Progress < 0.5 → outgoing; progress >= 0.5 → incoming.
        val earlyIn = resolver.resolve(listOf(
            Layer3Resolver.Assignment(
                cueId = 1, priority = 1, fadeWeight = 0.4, targetKey = "fx-1", targetIsGroup = false,
                propertyName = "mode", category = PropertyCategory.SETTING,
                value = Layer3Resolver.PropertyValue.Setting(10u),
            ),
            Layer3Resolver.Assignment(
                cueId = 2, priority = 2, fadeWeight = 0.4, targetKey = "fx-1", targetIsGroup = false,
                propertyName = "mode", category = PropertyCategory.SETTING,
                value = Layer3Resolver.PropertyValue.Setting(50u),
            ),
        ))
        val early = earlyIn[Layer3Resolver.Key.fixture("fx-1", "mode")] as Layer3Resolver.PropertyValue.Setting
        assertEquals(10u.toUByte(), early.channelValue, "below 50% should still be outgoing")

        val lateIn = resolver.resolve(listOf(
            Layer3Resolver.Assignment(
                cueId = 1, priority = 1, fadeWeight = 0.6, targetKey = "fx-1", targetIsGroup = false,
                propertyName = "mode", category = PropertyCategory.SETTING,
                value = Layer3Resolver.PropertyValue.Setting(10u),
            ),
            Layer3Resolver.Assignment(
                cueId = 2, priority = 2, fadeWeight = 0.6, targetKey = "fx-1", targetIsGroup = false,
                propertyName = "mode", category = PropertyCategory.SETTING,
                value = Layer3Resolver.PropertyValue.Setting(50u),
            ),
        ))
        val late = lateIn[Layer3Resolver.Key.fixture("fx-1", "mode")] as Layer3Resolver.PropertyValue.Setting
        assertEquals(50u.toUByte(), late.channelValue, "at/above 50% should flip to incoming")
    }

    @Test
    fun `specificity — fixture-level beats group-level for the same key`() {
        // Caller pre-expands group to member rows; the "fx-1" key here receives both, one flagged as group.
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 100u).copy(targetIsGroup = true),
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 200u).copy(targetIsGroup = false),
        ))
        val v = result[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(200u.toUByte(), v.value, "fixture-level contributor wins")
    }

    @Test
    fun `per-property composition override switches HTP to LTP`() {
        // Dimmer category is HTP by default; override to LTP → highest priority wins, not max.
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 200u)
                .copy(compositionOverride = CompositionRule.LTP),
            slider(cueId = 2, priority = 2, fadeWeight = 1.0, value = 50u)
                .copy(compositionOverride = CompositionRule.LTP),
        ))
        val v = result[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(50u.toUByte(), v.value, "LTP picks highest-priority (cueId 2 at 50) despite lower value")
    }

    @Test
    fun `single contributor returns its value unchanged`() {
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 5, fadeWeight = 0.2, value = 200u),
        ))
        val v = result[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        // Single HTP contributor: max(200 * 0.2) = 40.
        assertEquals(40u.toUByte(), v.value)
    }

    // ─── parseAssignmentValue ─────────────────────────────────────────────

    @Test
    fun `parseAssignmentValue - slider from numeric string`() {
        val v = Layer3Resolver.parseAssignmentValue(PropertyCategory.DIMMER, "dimmer", "180")
        assertEquals(Layer3Resolver.PropertyValue.Slider(180u.toUByte()), v)
    }

    @Test
    fun `parseAssignmentValue - slider clamps out of range`() {
        val v = Layer3Resolver.parseAssignmentValue(PropertyCategory.UV, "uv", "999")
        assertEquals(Layer3Resolver.PropertyValue.Slider(255u.toUByte()), v)
        val neg = Layer3Resolver.parseAssignmentValue(PropertyCategory.DIMMER, "dimmer", "-10")
        assertEquals(Layer3Resolver.PropertyValue.Slider(0u.toUByte()), neg)
    }

    @Test
    fun `parseAssignmentValue - setting category produces Setting value`() {
        val v = Layer3Resolver.parseAssignmentValue(PropertyCategory.SETTING, "mode", "64")
        assertEquals(Layer3Resolver.PropertyValue.Setting(64u.toUByte()), v)
    }

    @Test
    fun `parseAssignmentValue - OTHER category produces Setting value`() {
        val v = Layer3Resolver.parseAssignmentValue(PropertyCategory.OTHER, "misc", "32")
        assertEquals(Layer3Resolver.PropertyValue.Setting(32u.toUByte()), v)
    }

    @Test
    fun `parseAssignmentValue - colour from hex`() {
        val v = Layer3Resolver.parseAssignmentValue(PropertyCategory.COLOUR, "rgbColour", "#FF0000")
        assertIs<Layer3Resolver.PropertyValue.Colour>(v)
        assertEquals(Color.RED, v.value.color)
    }

    @Test
    fun `parseAssignmentValue - palette ref resolves against supplied palette`() {
        val palette = listOf(
            ExtendedColour(Color(10, 20, 30)),
            ExtendedColour(Color(40, 50, 60)),
        )
        val v = Layer3Resolver.parseAssignmentValue(
            PropertyCategory.COLOUR, "rgbColour", "P2", palette,
        )
        assertIs<Layer3Resolver.PropertyValue.Colour>(v)
        assertEquals(Color(40, 50, 60), v.value.color)
    }

    @Test
    fun `parseAssignmentValue - palette ref with empty palette falls through to white`() {
        // P1 isn't a valid hex or named colour, so parseExtendedColour returns white.
        val v = Layer3Resolver.parseAssignmentValue(
            PropertyCategory.COLOUR, "rgbColour", "P1",
        )
        assertIs<Layer3Resolver.PropertyValue.Colour>(v)
        assertEquals(Color.WHITE, v.value.color)
    }

    @Test
    fun `parseAssignmentValue - hex value ignores supplied palette`() {
        val palette = listOf(ExtendedColour(Color(10, 20, 30)))
        val v = Layer3Resolver.parseAssignmentValue(
            PropertyCategory.COLOUR, "rgbColour", "#00FF00", palette,
        )
        assertIs<Layer3Resolver.PropertyValue.Colour>(v)
        assertEquals(Color(0, 255, 0), v.value.color)
    }

    @Test
    fun `parseAssignmentValue - palette ref wraps modulo palette size`() {
        val palette = listOf(
            ExtendedColour(Color(10, 20, 30)),
            ExtendedColour(Color(40, 50, 60)),
        )
        // P3 on a 2-entry palette → (3-1) mod 2 = 0 → first entry.
        val v = Layer3Resolver.parseAssignmentValue(
            PropertyCategory.COLOUR, "rgbColour", "P3", palette,
        )
        assertIs<Layer3Resolver.PropertyValue.Colour>(v)
        assertEquals(Color(10, 20, 30), v.value.color)
    }

    @Test
    fun `parseAssignmentValue - colour from named value with extended channels`() {
        val v = Layer3Resolver.parseAssignmentValue(PropertyCategory.COLOUR, "rgbColour", "red;w32;uv16")
        assertIs<Layer3Resolver.PropertyValue.Colour>(v)
        assertEquals(Color.RED, v.value.color)
        assertEquals(32u.toUByte(), v.value.white)
        assertEquals(16u.toUByte(), v.value.uv)
    }

    @Test
    fun `parseAssignmentValue - position from pan-comma-tilt`() {
        val v = Layer3Resolver.parseAssignmentValue(PropertyCategory.PAN, "position", "100,200")
        assertEquals(Layer3Resolver.PropertyValue.Position(100u.toUByte(), 200u.toUByte()), v)
    }

    @Test
    fun `parseAssignmentValue - position rejects malformed pair`() {
        assertNull(Layer3Resolver.parseAssignmentValue(PropertyCategory.PAN, "position", "100"))
        assertNull(Layer3Resolver.parseAssignmentValue(PropertyCategory.PAN, "position", "abc,def"))
        assertNull(Layer3Resolver.parseAssignmentValue(PropertyCategory.PAN, "position", "1,2,3"))
    }

    @Test
    fun `parseAssignmentValue - slider rejects non-numeric`() {
        assertNull(Layer3Resolver.parseAssignmentValue(PropertyCategory.DIMMER, "dimmer", "nope"))
    }

    // ─── PropertyValue.serialize round-trip ───────────────────────────────

    @Test
    fun `serialize round-trip - slider`() {
        val original = Layer3Resolver.PropertyValue.Slider(180u)
        val parsed = Layer3Resolver.parseAssignmentValue(PropertyCategory.DIMMER, "dimmer", original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize round-trip - setting`() {
        val original = Layer3Resolver.PropertyValue.Setting(64u)
        val parsed = Layer3Resolver.parseAssignmentValue(PropertyCategory.SETTING, "mode", original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize round-trip - plain colour`() {
        val original = Layer3Resolver.PropertyValue.Colour(ExtendedColour(Color(255, 128, 64)))
        val parsed = Layer3Resolver.parseAssignmentValue(PropertyCategory.COLOUR, "rgbColour", original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize round-trip - extended colour with white amber and uv`() {
        val original = Layer3Resolver.PropertyValue.Colour(
            ExtendedColour(Color(10, 20, 30), white = 40u, amber = 50u, uv = 60u)
        )
        val parsed = Layer3Resolver.parseAssignmentValue(PropertyCategory.COLOUR, "rgbColour", original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize round-trip - position`() {
        val original = Layer3Resolver.PropertyValue.Position(pan = 100u, tilt = 200u)
        val parsed = Layer3Resolver.parseAssignmentValue(PropertyCategory.PAN, "position", original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize slider produces decimal string without u suffix`() {
        assertEquals("0", Layer3Resolver.PropertyValue.Slider(0u).serialize())
        assertEquals("255", Layer3Resolver.PropertyValue.Slider(255u).serialize())
    }

    @Test
    fun `serialize position emits pan-comma-tilt`() {
        assertEquals("128,200", Layer3Resolver.PropertyValue.Position(128u, 200u).serialize())
    }
}
