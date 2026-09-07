package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PropertyChannelResolver]. Uses a real [HexFixture] — the resolver only
 * reads static `@FixtureProperty` metadata + DMX channel assignments, and takes the current
 * channel values through a reader, so no controller transaction is needed.
 */
class PropertyChannelResolverTest {

    private val universe = Universe(0, 0)
    private fun hex(firstChannel: Int = 1, maxDimmerLevel: UByte = 255u): HexFixture =
        HexFixture(universe, key = "hex-1", fixtureName = "Hex 1", firstChannel = firstChannel, maxDimmerLevel = maxDimmerLevel)

    /** A reader for a show with no controller: every channel unknown. */
    private val unread: ChannelReader = { _, _ -> null }

    /** A reader that answers the Hex's colour channels (2, 3, 4) with [colour] and everything else with 0. */
    private fun showing(colour: Color): ChannelReader = { _, channel ->
        when (channel) {
            2 -> colour.red.toUByte()
            3 -> colour.green.toUByte()
            4 -> colour.blue.toUByte()
            else -> 0u
        }
    }

    private fun sliderValue(v: CueAssignmentResolver.PropertyValue?): UByte =
        assertIs<CueAssignmentResolver.PropertyValue.Slider>(v).value

    private fun colourValue(v: CueAssignmentResolver.PropertyValue?): Color =
        assertIs<CueAssignmentResolver.PropertyValue.Colour>(v).value.color

    // --- Sliders ---

    @Test
    fun `slider scales midi 0 to dmx 0 and midi 127 to dmx 255`() {
        assertEquals(0u.toUByte(), sliderValue(PropertyChannelResolver.toPropertyValue(hex(), "dimmer", 0u, unread)))
        assertEquals(255u.toUByte(), sliderValue(PropertyChannelResolver.toPropertyValue(hex(), "dimmer", 127u, unread)))
    }

    @Test
    fun `slider scales through its own max`() {
        val v = PropertyChannelResolver.toPropertyValue(hex(maxDimmerLevel = 200u), "dimmer", 127u, unread)
        assertEquals(200u.toUByte(), sliderValue(v))
    }

    @Test
    fun `slider scaling at midpoint gives midpoint dmx`() {
        // (64*255 + 63) / 127 = 16383/127 = 129 (rounded up at the halfway point)
        assertEquals(129u.toUByte(), sliderValue(PropertyChannelResolver.toPropertyValue(hex(), "dimmer", 64u, unread)))
    }

    @Test
    fun `unknown property and enum setting are not continuous-writable`() {
        assertNull(PropertyChannelResolver.toPropertyValue(hex(), "nonesuch", 100u, unread))
        // HexFixture.mode is a DmxFixtureSetting → intentionally excluded for faders.
        assertNull(PropertyChannelResolver.toPropertyValue(hex(), "mode", 64u, unread))
    }

    // --- Colour: the write ---

    @Test
    fun `a colour write is a hue, at full saturation and value when the head is unread or black`() {
        assertEquals(Color(255, 0, 0), colourValue(PropertyChannelResolver.toPropertyValue(hex(), "rgbColour", 0u, unread)))
        assertEquals(Color(255, 0, 0), colourValue(PropertyChannelResolver.toPropertyValue(hex(), "rgbColour", 0u, showing(Color.BLACK))))
        val blue = colourValue(PropertyChannelResolver.toPropertyValue(hex(), "rgbColour", 85u, unread))
        assertEquals(255, blue.blue)
        assertEquals(0, blue.red)
        assertTrue(blue.green < 8, "85/128 of the wheel is blue, not cyan: $blue")
    }

    @Test
    fun `a colour write keeps the head's saturation and value`() {
        // A dim, saturated blue turned to red stays dim and saturated.
        assertEquals(
            Color(200, 0, 0),
            colourValue(PropertyChannelResolver.toPropertyValue(hex(), "rgbColour", 0u, showing(Color(0, 0, 200)))),
        )
        // A half-saturated pink turned to green keeps its pastel.
        val green = colourValue(PropertyChannelResolver.toPropertyValue(hex(), "rgbColour", 43u, showing(Color(200, 100, 100))))
        assertEquals(200, green.green, "value kept")
        assertEquals(100, green.red, "saturation kept")
        assertTrue(green.blue in 100..104, "the minor channel follows the hue within rounding: $green")
    }

    @Test
    fun `a grey or near-white head is lifted to the saturation floor at its own value`() {
        // 120 * (1 - 0.25) = 90 on the minor channels: a clear tint, at the grey's brightness.
        assertEquals(
            Color(120, 90, 90),
            colourValue(PropertyChannelResolver.toPropertyValue(hex(), "rgbColour", 0u, showing(Color(120, 120, 120)))),
        )
        // A warm white keeps its few percent of saturation nowhere: a turn must be visible.
        assertEquals(
            Color(255, 191, 191),
            colourValue(PropertyChannelResolver.toPropertyValue(hex(), "rgbColour", 0u, showing(Color(255, 254, 254)))),
        )
        assertEquals(Color(255, 191, 191), PropertyChannelResolver.colourAtHue(0u, Color(255, 240, 224)), "#FFF0E0, S ≈ 0.12")
        // A pastel above the floor keeps its own saturation: 255 * (1 - 0.3) ≈ 179.
        assertEquals(Color(255, 179, 179), PropertyChannelResolver.colourAtHue(0u, Color(179, 255, 255)))
    }

    @Test
    fun `a flash on a colour is a level, not a hue`() {
        assertEquals(Color(200, 200, 200), colourValue(PropertyChannelResolver.flashPropertyValue(hex(), "rgbColour", 200u)))
    }

    // --- Colour: the read ---

    @Test
    fun `hue reads the wheel and a grey or black head reads nothing`() {
        assertEquals(0u.toUByte(), PropertyChannelResolver.hueRead(255u, 0u, 0u)!!.value7Bit)
        assertEquals(21u.toUByte(), PropertyChannelResolver.hueRead(255u, 255u, 0u)!!.value7Bit, "yellow, 60° of 360")
        assertEquals(85u.toUByte(), PropertyChannelResolver.hueRead(0u, 0u, 255u)!!.value7Bit, "blue, 240° of 360")
        assertNull(PropertyChannelResolver.hueRead(0u, 0u, 0u), "black")
        assertNull(PropertyChannelResolver.hueRead(120u, 120u, 120u), "grey")
        assertNull(PropertyChannelResolver.hueRead(255u, 255u, 255u), "white")
        assertNull(PropertyChannelResolver.hueRead(255u, 250u, 250u), "a tint too faint to see is not a colour the ring may claim")
        assertNull(PropertyChannelResolver.hueRead(255u, 254u, 254u), "#FFFEFE — the effect residue that lit the ring")
        assertNotNull(PropertyChannelResolver.hueRead(255u, 240u, 224u), "#FFF0E0 is a warm white the eye can see, S ≈ 0.12")
        assertNotNull(PropertyChannelResolver.hueRead(255u, 191u, 191u), "the floor itself reads")
    }

    @Test
    fun `tolerance follows chroma, not value`() {
        // Same value, different spread: the saturated head holds its hue six times more finely.
        assertEquals(1, PropertyChannelResolver.hueRead(30u, 0u, 0u)!!.tolerance)
        assertEquals(2, PropertyChannelResolver.hueRead(30u, 20u, 20u)!!.tolerance)
        // A bright pastel is as coarse as a dim saturated head of the same chroma.
        assertEquals(
            PropertyChannelResolver.hueRead(35u, 0u, 0u)!!.tolerance,
            PropertyChannelResolver.hueRead(255u, 220u, 220u)!!.tolerance,
        )
    }

    @Test
    fun `every hue a control can write reads back as itself at full value`() {
        for (v in 0 until PropertyChannelResolver.HUE_STEPS) {
            val c = PropertyChannelResolver.colourAtHue(v.toUByte(), null)
            val read = assertNotNull(PropertyChannelResolver.hueRead(c.red.toUByte(), c.green.toUByte(), c.blue.toUByte()))
            assertEquals(v, read.value7Bit.toInt(), "hue $v wrote $c")
        }
    }

    @Test
    fun `a dim or pale head reads its written hue within the tolerance its channels allow`() {
        // Every base a write can meet: a dim saturated head, a bright pastel, a dim pastel, a
        // near-white lifted to the floor, and black.
        val bases = listOf(Color(0, 0, 12), Color(255, 179, 179), Color(40, 28, 28), Color(255, 250, 250), Color.BLACK)
        for (base in bases) for (v in 0 until PropertyChannelResolver.HUE_STEPS) {
            val c = PropertyChannelResolver.colourAtHue(v.toUByte(), base)
            val expectedValue = maxOf(base.red, base.green, base.blue).takeIf { it > 0 } ?: 255
            assertEquals(expectedValue, maxOf(c.red, c.green, c.blue), "value kept at $v from $base")
            val read = assertNotNull(
                PropertyChannelResolver.hueRead(c.red.toUByte(), c.green.toUByte(), c.blue.toUByte()),
                "a head just written at hue $v from $base must read a hue: $c",
            )
            val written = PropertyChannelResolver.HeadValue(v.toUByte(), tolerance = 0, circular = true)
            assertTrue(read.agreesWith(written), "hue $v from $base wrote $c and read back ${read.value7Bit} ± ${read.tolerance}")
        }
    }

    @Test
    fun `two heads written at one hue agree whatever their saturation and value`() {
        val saturated = Color(255, 0, 0)
        val pale = Color(255, 250, 250)
        val dim = Color(0, 0, 40)
        for (v in 0 until PropertyChannelResolver.HUE_STEPS) {
            val reads = listOf(saturated, pale, dim).map { base ->
                val c = PropertyChannelResolver.colourAtHue(v.toUByte(), base)
                PropertyChannelResolver.hueRead(c.red.toUByte(), c.green.toUByte(), c.blue.toUByte())!!
            }
            assertNotNull(PropertyChannelResolver.commonValue(reads), "hue $v: $reads")
        }
    }

    @Test
    fun `agreement is every pair, so the answer does not depend on head order`() {
        fun hue(v: Int) = PropertyChannelResolver.HeadValue(v.toUByte(), tolerance = 1, circular = true)
        assertNull(PropertyChannelResolver.commonValue(listOf(hue(0), hue(1), hue(2))), "0 and 2 are two apart")
        assertNull(PropertyChannelResolver.commonValue(listOf(hue(1), hue(0), hue(2))), "the same three, anchored on the middle one")
        assertEquals(0u.toUByte(), PropertyChannelResolver.commonValue(listOf(hue(0), hue(1))))
        assertEquals(1u.toUByte(), PropertyChannelResolver.commonValue(listOf(hue(1), hue(0))), "the first head's value is reported")
        assertNull(PropertyChannelResolver.commonValue(emptyList()))
        val slider = PropertyChannelResolver.HeadValue(50u)
        assertEquals(50u.toUByte(), PropertyChannelResolver.commonValue(listOf(slider, slider)))
        assertNull(PropertyChannelResolver.commonValue(listOf(slider, PropertyChannelResolver.HeadValue(51u))))
    }

    @Test
    fun `heads agree within a hue step and not beyond, and the wheel wraps`() {
        val red = PropertyChannelResolver.hueRead(255u, 0u, 0u)!!
        assertTrue(red.agreesWith(PropertyChannelResolver.hueRead(255u, 3u, 0u)!!), "one bucket of rounding")
        assertFalse(red.agreesWith(PropertyChannelResolver.hueRead(255u, 20u, 0u)!!), "two buckets is a different hue")
        assertFalse(red.agreesWith(PropertyChannelResolver.hueRead(255u, 255u, 0u)!!), "red and yellow — FU-MIDI-SELECTION-COLOUR-RED-ONLY")
        val lastStep = PropertyChannelResolver.colourAtHue(127u, null)
        assertTrue(
            red.agreesWith(PropertyChannelResolver.hueRead(lastStep.red.toUByte(), lastStep.green.toUByte(), lastStep.blue.toUByte())!!),
            "127 and 0 are neighbours on the wheel",
        )
    }

    @Test
    fun `a slider read is exact and never agrees with a hue`() {
        val a = PropertyChannelResolver.HeadValue(100u)
        assertTrue(a.agreesWith(PropertyChannelResolver.HeadValue(100u)))
        assertFalse(a.agreesWith(PropertyChannelResolver.HeadValue(101u)))
        assertFalse(a.agreesWith(PropertyChannelResolver.HeadValue(100u, tolerance = 1, circular = true)))
    }

    @Test
    fun `readHead reads a slider through its range and a colour as one hue`() {
        val dimmer = assertIs<PropertyChannelResolver.PropertyRead.Slider>(
            PropertyChannelResolver.describePropertyRead(hex(maxDimmerLevel = 200u), "dimmer"),
        )
        assertEquals(127u.toUByte(), PropertyChannelResolver.readHead(dimmer) { _, _ -> 200u }!!.value7Bit)
        val colour = assertIs<PropertyChannelResolver.PropertyRead.Colour>(
            PropertyChannelResolver.describePropertyRead(hex(), "rgbColour"),
        )
        assertEquals(85u.toUByte(), PropertyChannelResolver.readHead(colour, showing(Color(0, 0, 255)))!!.value7Bit)
        assertNull(PropertyChannelResolver.readHead(colour, showing(Color(50, 50, 50))), "grey has no hue")
        assertNull(PropertyChannelResolver.readHead(colour, unread), "a channel that cannot be read")
    }

    // --- Scaling helpers ---

    @Test
    fun `scaleWithinRange stays within bounds`() {
        assertEquals(50u.toUByte(), PropertyChannelResolver.scaleWithinRange(0u, 50u, 200u))
        assertEquals(200u.toUByte(), PropertyChannelResolver.scaleWithinRange(127u, 50u, 200u))
        val mid = PropertyChannelResolver.scaleWithinRange(64u, 0u, 100u)
        assertTrue(mid.toInt() in 49..51, "midpoint should be ~50, was $mid")
    }

    @Test
    fun `scaleDmxTo7Bit round-trips endpoints`() {
        assertEquals(0u.toUByte(), PropertyChannelResolver.scaleDmxTo7Bit(0u))
        assertEquals(127u.toUByte(), PropertyChannelResolver.scaleDmxTo7Bit(255u))
    }

    @Test
    fun `scaleWithinRangeTo7Bit round-trips sub-range endpoints`() {
        assertEquals(0u.toUByte(), PropertyChannelResolver.scaleWithinRangeTo7Bit(50u, 50u, 200u))
        assertEquals(127u.toUByte(), PropertyChannelResolver.scaleWithinRangeTo7Bit(200u, 50u, 200u))
    }

    // --- Descriptions ---

    @Test
    fun `describeFixtureProperty returns channels without reading value`() {
        val desc = PropertyChannelResolver.describeFixtureProperty(hex(), "dimmer")
        assertEquals(1, desc.size)
        assertEquals(1, desc.single().channel)
        assertEquals(PropertyCategory.DIMMER, desc.single().category)
        assertEquals(0u.toUByte(), desc.single().min)
        assertEquals(255u.toUByte(), desc.single().max)

        val colour = PropertyChannelResolver.describeFixtureProperty(hex(), "rgbColour")
        assertEquals(3, colour.size)
        assertEquals(listOf(2, 3, 4), colour.map { it.channel })
        assertTrue(colour.all { it.category == PropertyCategory.COLOUR })
    }

    @Test
    fun `fixture at offset describes channels at correct offset`() {
        val uv = PropertyChannelResolver.describeFixtureProperty(hex(firstChannel = 100), "uv").single()
        // uv = firstChannel+6 = 106
        assertEquals(106, uv.channel)
        assertEquals(PropertyCategory.UV, uv.category)
    }

    @Test
    fun `describeFixtureProperty returns empty for enum setting and unknown`() {
        assertTrue(PropertyChannelResolver.describeFixtureProperty(hex(), "mode").isEmpty())
        assertTrue(PropertyChannelResolver.describeFixtureProperty(hex(), "nonesuch").isEmpty())
        assertNull(PropertyChannelResolver.describePropertyRead(hex(), "mode"))
    }
}
