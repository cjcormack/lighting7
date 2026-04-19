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
        val v = result[Layer3Resolver.Key("fx-1", "dimmer")]
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
        val v = result[Layer3Resolver.Key("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(180u.toUByte(), v.value)
    }

    /** Spec Example 4 — LTP colour, B fully in, A is not contributing fade-out. */
    @Test
    fun `LTP colour picks highest priority when no crossfade`() {
        val result = resolver.resolve(listOf(
            colour(cueId = 1, priority = 1, fadeWeight = 1.0, red = 255, green = 0, blue = 0),
            colour(cueId = 2, priority = 2, fadeWeight = 1.0, red = 0, green = 0, blue = 255),
        ))
        val v = result[Layer3Resolver.Key("fx-1", "rgbColour")] as Layer3Resolver.PropertyValue.Colour
        assertEquals(Color(0, 0, 255), v.value.color)
    }

    /** Spec Example 4 crossfade — outgoing A at weight 0.4 (not yet fully released), incoming B at 0.6. */
    @Test
    fun `LTP colour blends RGB during crossfade`() {
        val result = resolver.resolve(listOf(
            colour(cueId = 1, priority = 1, fadeWeight = 0.4, red = 255, green = 0, blue = 0),
            colour(cueId = 2, priority = 2, fadeWeight = 0.6, red = 0, green = 0, blue = 255),
        ))
        val v = result[Layer3Resolver.Key("fx-1", "rgbColour")] as Layer3Resolver.PropertyValue.Colour
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
        val v = result[Layer3Resolver.Key("fx-1", "pan")] as Layer3Resolver.PropertyValue.Slider
        // outgoing 100 → incoming 200 at progress 0.7 → 100 + 100 * 0.7 = 170
        assertEquals(170u.toUByte(), v.value)
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
        val early = earlyIn[Layer3Resolver.Key("fx-1", "mode")] as Layer3Resolver.PropertyValue.Setting
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
        val late = lateIn[Layer3Resolver.Key("fx-1", "mode")] as Layer3Resolver.PropertyValue.Setting
        assertEquals(50u.toUByte(), late.channelValue, "at/above 50% should flip to incoming")
    }

    @Test
    fun `specificity — fixture-level beats group-level for the same key`() {
        // Caller pre-expands group to member rows; the "fx-1" key here receives both, one flagged as group.
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 100u).copy(targetIsGroup = true),
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 200u).copy(targetIsGroup = false),
        ))
        val v = result[Layer3Resolver.Key("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
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
        val v = result[Layer3Resolver.Key("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(50u.toUByte(), v.value, "LTP picks highest-priority (cueId 2 at 50) despite lower value")
    }

    @Test
    fun `single contributor returns its value unchanged`() {
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 5, fadeWeight = 0.2, value = 200u),
        ))
        val v = result[Layer3Resolver.Key("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
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
}
