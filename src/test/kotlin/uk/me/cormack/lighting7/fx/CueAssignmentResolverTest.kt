package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.AttributeCategory
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CueAssignmentResolverTest {

    private val resolver = CueAssignmentResolver()

    private fun slider(
        cueId: Int,
        priority: Int,
        fadeWeight: Double,
        targetKey: String = "fx-1",
        attribute: String = "dimmer",
        value: UByte,
        category: AttributeCategory = AttributeCategory.DIMMER,
    ) = CueAssignmentResolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = fadeWeight,
        targetKey = targetKey,
        targetIsGroup = false,
        attribute = attribute,
        category = category,
        value = CueAssignmentResolver.AttributeValue.Slider(value),
    )

    private fun colour(
        cueId: Int,
        priority: Int,
        fadeWeight: Double,
        targetKey: String = "fx-1",
        red: Int, green: Int, blue: Int,
    ) = CueAssignmentResolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = fadeWeight,
        targetKey = targetKey,
        targetIsGroup = false,
        attribute = "rgbColour",
        category = AttributeCategory.COLOUR,
        value = CueAssignmentResolver.AttributeValue.Colour(ExtendedColour(Color(red, green, blue))),
    )

    private fun position(
        cueId: Int,
        priority: Int,
        fadeWeight: Double,
        targetKey: String = "head-1",
        pan: UByte,
        tilt: UByte,
        targetIsGroup: Boolean = false,
    ) = CueAssignmentResolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = fadeWeight,
        targetKey = targetKey,
        targetIsGroup = targetIsGroup,
        attribute = "position",
        category = AttributeCategory.PAN,
        value = CueAssignmentResolver.AttributeValue.Position(pan, tilt),
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
        val v = result[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")]
        assertIs<CueAssignmentResolver.AttributeValue.Slider>(v)
        assertEquals(180u.toUByte(), v.value)
    }

    /** Spec Example 3 continuation — outgoing cue at 50% still loses to dominant incoming. */
    @Test
    fun `HTP scales by fade weight before max`() {
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.5, value = 100u), // → 50 after scale
            slider(cueId = 2, priority = 2, fadeWeight = 1.0, value = 180u),
        ))
        val v = result[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.AttributeValue.Slider
        assertEquals(180u.toUByte(), v.value)
    }

    // HTP switches to linear blend (Σ v×w) when Σ weights ≤ 1 — otherwise the naive
    // max(v × w) rule dips below the outgoing value midway through a crossfade.

    @Test
    fun `HTP crossfade start — outgoing at 1_0 incoming at 0_0 reads outgoing value`() {
        val v = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 50u),
            slider(cueId = 2, priority = 2, fadeWeight = 0.0, value = 200u),
        ))[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.AttributeValue.Slider
        assertEquals(50u.toUByte(), v.value)
    }

    @Test
    fun `HTP crossfade mid — half-half blends linearly`() {
        val v = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.5, value = 50u),
            slider(cueId = 2, priority = 2, fadeWeight = 0.5, value = 200u),
        ))[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.AttributeValue.Slider
        assertEquals(125u.toUByte(), v.value)
    }

    @Test
    fun `HTP crossfade end — outgoing at 0_0 incoming at 1_0 reads incoming value`() {
        val v = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.0, value = 50u),
            slider(cueId = 2, priority = 2, fadeWeight = 1.0, value = 200u),
        ))[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.AttributeValue.Slider
        assertEquals(200u.toUByte(), v.value)
    }

    @Test
    fun `HTP two cues both at weight 1 keeps max semantics`() {
        val v = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 100u),
            slider(cueId = 2, priority = 2, fadeWeight = 1.0, value = 180u),
        ))[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.AttributeValue.Slider
        assertEquals(180u.toUByte(), v.value)
    }

    @Test
    fun `HTP floating-point sum near 1 still takes the blend path`() {
        // `0.5 + 0.5` = `1.0000000000000002` — without the epsilon this tips into max.
        val v = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.5, value = 50u),
            slider(cueId = 2, priority = 2, fadeWeight = 0.5, value = 200u),
        ))[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.AttributeValue.Slider
        assertEquals(125u.toUByte(), v.value)
    }

    @Test
    fun `HTP setting category uses blend path for crossfade`() {
        val assignment1 = CueAssignmentResolver.Assignment(
            cueId = 1, priority = 1, fadeWeight = 0.5,
            targetKey = "fx-1", targetIsGroup = false,
            attribute = "mode", category = AttributeCategory.SETTING,
            compositionOverride = CompositionRule.HTP,
            value = CueAssignmentResolver.AttributeValue.Setting(60u),
        )
        val assignment2 = assignment1.copy(
            cueId = 2, priority = 2,
            value = CueAssignmentResolver.AttributeValue.Setting(200u),
        )
        val v = resolver.resolve(listOf(assignment1, assignment2))[
            CueAssignmentResolver.Key.fixture("fx-1", "mode")
        ] as CueAssignmentResolver.AttributeValue.Setting
        assertEquals(130u.toUByte(), v.channelValue)
    }

    /** Spec Example 4 — LTP colour, B fully in, A is not contributing fade-out. */
    @Test
    fun `LTP colour picks highest priority when no crossfade`() {
        val result = resolver.resolve(listOf(
            colour(cueId = 1, priority = 1, fadeWeight = 1.0, red = 255, green = 0, blue = 0),
            colour(cueId = 2, priority = 2, fadeWeight = 1.0, red = 0, green = 0, blue = 255),
        ))
        val v = result[CueAssignmentResolver.Key.fixture("fx-1", "rgbColour")] as CueAssignmentResolver.AttributeValue.Colour
        assertEquals(Color(0, 0, 255), v.value.color)
    }

    /** Spec Example 4 crossfade — outgoing A at weight 0.4 (not yet fully released), incoming B at 0.6. */
    @Test
    fun `LTP colour blends RGB during crossfade`() {
        val result = resolver.resolve(listOf(
            colour(cueId = 1, priority = 1, fadeWeight = 0.4, red = 255, green = 0, blue = 0),
            colour(cueId = 2, priority = 2, fadeWeight = 0.6, red = 0, green = 0, blue = 255),
        ))
        val v = result[CueAssignmentResolver.Key.fixture("fx-1", "rgbColour")] as CueAssignmentResolver.AttributeValue.Colour
        // Linear RGB: (1 - 0.6) * red + 0.6 * blue per channel.
        assertEquals(102, v.value.color.red, "red")
        assertEquals(0, v.value.color.green, "green")
        assertEquals(153, v.value.color.blue, "blue")
    }

    @Test
    fun `LTP slider interpolates during crossfade`() {
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.3, value = 100u,
                category = AttributeCategory.PAN, attribute = "pan"),
            slider(cueId = 2, priority = 2, fadeWeight = 0.7, value = 200u,
                category = AttributeCategory.PAN, attribute = "pan"),
        ))
        val v = result[CueAssignmentResolver.Key.fixture("fx-1", "pan")] as CueAssignmentResolver.AttributeValue.Slider
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
                category = AttributeCategory.PAN, attribute = "pan"),
            slider(cueId = 2, priority = 2, fadeWeight = 0.0, value = 200u,
                category = AttributeCategory.PAN, attribute = "pan"),
        ))
        val v = result[CueAssignmentResolver.Key.fixture("fx-1", "pan")] as CueAssignmentResolver.AttributeValue.Slider
        // progress 0 → 100 + (200 - 100) * 0 = 100 (outgoing value).
        assertEquals(100u.toUByte(), v.value)
    }

    @Test
    fun `LTP crossfade end — incoming at 1 pins incoming value`() {
        // Symmetric: progress reaches 1.0, outgoing filtered out (weight=0), winner stands.
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.0, value = 100u,
                category = AttributeCategory.PAN, attribute = "pan"),
            slider(cueId = 2, priority = 2, fadeWeight = 1.0, value = 200u,
                category = AttributeCategory.PAN, attribute = "pan"),
        ))
        val v = result[CueAssignmentResolver.Key.fixture("fx-1", "pan")] as CueAssignmentResolver.AttributeValue.Slider
        assertEquals(200u.toUByte(), v.value)
    }

    @Test
    fun `LTP colour crossfade start — outgoing at 1 pins outgoing colour`() {
        val result = resolver.resolve(listOf(
            colour(cueId = 1, priority = 1, fadeWeight = 1.0, red = 255, green = 0, blue = 0),
            colour(cueId = 2, priority = 2, fadeWeight = 0.0, red = 0, green = 0, blue = 255),
        ))
        val v = result[CueAssignmentResolver.Key.fixture("fx-1", "rgbColour")] as CueAssignmentResolver.AttributeValue.Colour
        assertEquals(Color(255, 0, 0), v.value.color, "outgoing red dominates at progress 0")
    }

    @Test
    fun `LTP settings snap at 50 percent fade progress`() {
        // Progress < 0.5 → outgoing; progress >= 0.5 → incoming.
        val earlyIn = resolver.resolve(listOf(
            CueAssignmentResolver.Assignment(
                cueId = 1, priority = 1, fadeWeight = 0.4, targetKey = "fx-1", targetIsGroup = false,
                attribute = "mode", category = AttributeCategory.SETTING,
                value = CueAssignmentResolver.AttributeValue.Setting(10u),
            ),
            CueAssignmentResolver.Assignment(
                cueId = 2, priority = 2, fadeWeight = 0.4, targetKey = "fx-1", targetIsGroup = false,
                attribute = "mode", category = AttributeCategory.SETTING,
                value = CueAssignmentResolver.AttributeValue.Setting(50u),
            ),
        ))
        val early = earlyIn[CueAssignmentResolver.Key.fixture("fx-1", "mode")] as CueAssignmentResolver.AttributeValue.Setting
        assertEquals(10u.toUByte(), early.channelValue, "below 50% should still be outgoing")

        val lateIn = resolver.resolve(listOf(
            CueAssignmentResolver.Assignment(
                cueId = 1, priority = 1, fadeWeight = 0.6, targetKey = "fx-1", targetIsGroup = false,
                attribute = "mode", category = AttributeCategory.SETTING,
                value = CueAssignmentResolver.AttributeValue.Setting(10u),
            ),
            CueAssignmentResolver.Assignment(
                cueId = 2, priority = 2, fadeWeight = 0.6, targetKey = "fx-1", targetIsGroup = false,
                attribute = "mode", category = AttributeCategory.SETTING,
                value = CueAssignmentResolver.AttributeValue.Setting(50u),
            ),
        ))
        val late = lateIn[CueAssignmentResolver.Key.fixture("fx-1", "mode")] as CueAssignmentResolver.AttributeValue.Setting
        assertEquals(50u.toUByte(), late.channelValue, "at/above 50% should flip to incoming")
    }

    @Test
    fun `specificity — fixture-level beats group-level for the same key`() {
        // Caller pre-expands group to member rows; the "fx-1" key here receives both, one flagged as group.
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 100u).copy(targetIsGroup = true),
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 200u).copy(targetIsGroup = false),
        ))
        val v = result[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.AttributeValue.Slider
        assertEquals(200u.toUByte(), v.value, "fixture-level contributor wins")
    }

    @Test
    fun `per-attribute composition override switches HTP to LTP`() {
        // Dimmer category is HTP by default; override to LTP → highest priority wins, not max.
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 1.0, value = 200u)
                .copy(compositionOverride = CompositionRule.LTP),
            slider(cueId = 2, priority = 2, fadeWeight = 1.0, value = 50u)
                .copy(compositionOverride = CompositionRule.LTP),
        ))
        val v = result[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.AttributeValue.Slider
        assertEquals(50u.toUByte(), v.value, "LTP picks highest-priority (cueId 2 at 50) despite lower value")
    }

    @Test
    fun `single contributor returns its value unchanged`() {
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 5, fadeWeight = 0.2, value = 200u),
        ))
        val v = result[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.AttributeValue.Slider
        // Single HTP contributor: max(200 * 0.2) = 40.
        assertEquals(40u.toUByte(), v.value)
    }

    // ─── Position crossfade ───────────────────────────────────────────────

    /** A dark outgoing cue does not snap the position: a head moved in the dark is the stack's contributor's job. */
    @Test
    fun `position blends linearly across a crossfade whatever the outgoing dimmer`() {
        val result = resolver.resolve(listOf(
            slider(cueId = 1, priority = 1, fadeWeight = 0.5, targetKey = "head-1", value = 0u),
            position(cueId = 1, priority = 1, fadeWeight = 0.5, pan = 0u, tilt = 0u),
            position(cueId = 2, priority = 2, fadeWeight = 0.5, pan = 200u, tilt = 200u),
        ))
        val v = result[CueAssignmentResolver.Key.fixture("head-1", "position")] as CueAssignmentResolver.AttributeValue.Position
        assertEquals(100u.toUByte(), v.pan)
        assertEquals(100u.toUByte(), v.tilt)
    }

    // ─── parseAssignmentValue ─────────────────────────────────────────────

    @Test
    fun `parseAssignmentValue - slider from numeric string`() {
        val v = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.DIMMER, "dimmer", "180")
        assertEquals(CueAssignmentResolver.AttributeValue.Slider(180u.toUByte()), v)
    }

    @Test
    fun `parseAssignmentValue - slider clamps out of range`() {
        val v = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.UV, "uv", "999")
        assertEquals(CueAssignmentResolver.AttributeValue.Slider(255u.toUByte()), v)
        val neg = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.DIMMER, "dimmer", "-10")
        assertEquals(CueAssignmentResolver.AttributeValue.Slider(0u.toUByte()), neg)
    }

    @Test
    fun `parseAssignmentValue - setting category produces Setting value`() {
        val v = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.SETTING, "mode", "64")
        assertEquals(CueAssignmentResolver.AttributeValue.Setting(64u.toUByte()), v)
    }

    @Test
    fun `parseAssignmentValue - OTHER category produces Setting value`() {
        val v = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.OTHER, "misc", "32")
        assertEquals(CueAssignmentResolver.AttributeValue.Setting(32u.toUByte()), v)
    }

    @Test
    fun `parseAssignmentValue - colour from hex`() {
        val v = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.COLOUR, "rgbColour", "#FF0000")
        assertIs<CueAssignmentResolver.AttributeValue.Colour>(v)
        assertEquals(Color.RED, v.value.color)
    }

    @Test
    fun `parseAssignmentValue - a template reference is refused rather than read as white`() {
        // The point of the refusal. `pal:` is legal in an *effect parameter* and nowhere else, and
        // an assignment value is a literal. Without the guard `parseExtendedColour` would take this
        // unrecognised string and answer white — the exact silent failure the retired positional
        // grammar had when its list was empty.
        val v = CueAssignmentResolver.parseAssignmentValue(
            AttributeCategory.COLOUR, "rgbColour", "pal:2f1c8a3e-0000-4000-8000-000000000001",
        )
        assertNull(v, "a template reference in a value must be skipped with a warn, not resolved")
    }

    @Test
    fun `parseAssignmentValue - an unparseable colour still reads as white`() {
        // Unchanged behaviour, pinned beside the refusal above so the contrast is visible: only the
        // reference *shape* is rejected; ordinary junk keeps the loud-white fallback.
        val v = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.COLOUR, "rgbColour", "P1")
        assertIs<CueAssignmentResolver.AttributeValue.Colour>(v)
        assertEquals(Color.WHITE, v.value.color)
    }

    @Test
    fun `parseAssignmentValue - colour from named value with extended channels`() {
        val v = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.COLOUR, "rgbColour", "red;w32;uv16")
        assertIs<CueAssignmentResolver.AttributeValue.Colour>(v)
        assertEquals(Color.RED, v.value.color)
        assertEquals(32u.toUByte(), v.value.white)
        assertEquals(16u.toUByte(), v.value.uv)
    }

    @Test
    fun `parseAssignmentValue - position from pan-comma-tilt`() {
        val v = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.PAN, "position", "100,200")
        assertEquals(CueAssignmentResolver.AttributeValue.Position(100u.toUByte(), 200u.toUByte()), v)
    }

    @Test
    fun `parseAssignmentValue - position rejects malformed pair`() {
        assertNull(CueAssignmentResolver.parseAssignmentValue(AttributeCategory.PAN, "position", "100"))
        assertNull(CueAssignmentResolver.parseAssignmentValue(AttributeCategory.PAN, "position", "abc,def"))
        assertNull(CueAssignmentResolver.parseAssignmentValue(AttributeCategory.PAN, "position", "1,2,3"))
    }

    @Test
    fun `parseAssignmentValue - slider rejects non-numeric`() {
        assertNull(CueAssignmentResolver.parseAssignmentValue(AttributeCategory.DIMMER, "dimmer", "nope"))
    }

    // ─── AttributeValue.serialize round-trip ───────────────────────────────

    @Test
    fun `serialize round-trip - slider`() {
        val original = CueAssignmentResolver.AttributeValue.Slider(180u)
        val parsed = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.DIMMER, "dimmer", original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize round-trip - setting`() {
        val original = CueAssignmentResolver.AttributeValue.Setting(64u)
        val parsed = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.SETTING, "mode", original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize round-trip - plain colour`() {
        val original = CueAssignmentResolver.AttributeValue.Colour(ExtendedColour(Color(255, 128, 64)))
        val parsed = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.COLOUR, "rgbColour", original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize round-trip - extended colour with white amber and uv`() {
        val original = CueAssignmentResolver.AttributeValue.Colour(
            ExtendedColour(Color(10, 20, 30), white = 40u, amber = 50u, uv = 60u)
        )
        val parsed = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.COLOUR, "rgbColour", original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize round-trip - position`() {
        val original = CueAssignmentResolver.AttributeValue.Position(pan = 100u, tilt = 200u)
        val parsed = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.PAN, "position", original.serialize())
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize slider produces decimal string without u suffix`() {
        assertEquals("0", CueAssignmentResolver.AttributeValue.Slider(0u).serialize())
        assertEquals("255", CueAssignmentResolver.AttributeValue.Slider(255u).serialize())
    }

    @Test
    fun `serialize position emits pan-comma-tilt`() {
        assertEquals("128,200", CueAssignmentResolver.AttributeValue.Position(128u, 200u).serialize())
    }
}
