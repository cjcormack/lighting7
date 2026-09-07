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
import kotlin.math.abs

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
        assertEquals(127u.toUByte(), PropertyChannelResolver.readHead(dimmer, { _, _ -> 200u })!!.value7Bit)
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

    // --- Colour axes ---

    private fun write(axis: ColourAxis, v: Int, current: Color?): Color = colourValue(
        PropertyChannelResolver.toPropertyValue(hex(), "rgbColour", v.toUByte(), current?.let(::showing) ?: unread, axis),
    )

    private fun hueSteps(c: Color): Float = Color.RGBtoHSB(c.red, c.green, c.blue, null)[0] * PropertyChannelResolver.HUE_STEPS

    private val colourHead = PropertyChannelResolver.describePropertyRead(hex(), "rgbColour")!!

    @Test
    fun `an explicit hue axis is the same write and read as none`() {
        val blue = Color(0, 0, 200)
        assertEquals(
            colourValue(PropertyChannelResolver.toPropertyValue(hex(), "rgbColour", 40u, showing(blue))),
            write(ColourAxis.HUE, 40, blue),
        )
        assertEquals(
            PropertyChannelResolver.readHead(colourHead, showing(blue)),
            PropertyChannelResolver.readHead(colourHead, showing(blue), ColourAxis.HUE),
        )
        assertEquals(ColourAxis.HUE, null.effective)
    }

    @Test
    fun `a fine trim at centre leaves the head on its coarse step and moves half a step at the ends`() {
        val onStep = PropertyChannelResolver.colourAtHue(40u, Color(0, 0, 200))
        assertEquals(onStep, write(ColourAxis.HUE_FINE, PropertyChannelResolver.HUE_FINE_CENTRE, onStep))
        assertEquals(39.5f, hueSteps(write(ColourAxis.HUE_FINE, 0, onStep)), 0.1f, "the bottom of the trim is half a step down")
        assertEquals(40 + 63 / 128f, hueSteps(write(ColourAxis.HUE_FINE, 127, onStep)), 0.1f, "the top is one fine position short of half a step up")
        // A black or unread head starts from red at full saturation and value, and the trim wraps below red.
        assertEquals(Color(255, 0, 0), write(ColourAxis.HUE_FINE, 64, null))
        assertEquals(Color(255, 0, 0), write(ColourAxis.HUE_FINE, 64, Color.BLACK))
        val belowRed = write(ColourAxis.HUE_FINE, 0, null)
        assertEquals(255, belowRed.red)
        assertEquals(0, belowRed.green)
        assertTrue(belowRed.blue in 1..8, "half a step below red is a touch of magenta: $belowRed")
        // A grey is lifted to the floor at its own value, as the hue write does.
        assertEquals(Color(120, 90, 90), write(ColourAxis.HUE_FINE, 64, Color(120, 120, 120)))
    }

    @Test
    fun `the fine read is the offset within the coarse step and round-trips within its tolerance`() {
        val base = PropertyChannelResolver.colourAtHue(40u, Color(0, 0, 255))
        // A coarse step written into 8-bit channels lands a hair off the step, so the rest reads as
        // the centre *within the head's tolerance* (11 here), never as a different position.
        val rest = PropertyChannelResolver.readHead(colourHead, showing(base), ColourAxis.HUE_FINE)!!
        assertTrue(abs(rest.value7Bit.toInt() - 64) <= rest.tolerance, "rest reads ${rest.value7Bit} ± ${rest.tolerance}")
        assertTrue(abs(rest.value7Bit.toInt() - 64) <= 1, "and at full chroma that is within one position")
        for (v in listOf(8, 16, 32, 64, 96, 111, 120)) {
            val written = write(ColourAxis.HUE_FINE, v, base)
            val read = PropertyChannelResolver.readHead(colourHead, showing(written), ColourAxis.HUE_FINE)!!
            assertTrue(abs(read.value7Bit.toInt() - v) <= read.tolerance, "fine $v read back as ${read.value7Bit} ± ${read.tolerance}")
            assertTrue(read.circular, "the trim wraps: its ends are one position apart, not the whole travel")
            val coarse = PropertyChannelResolver.readHead(colourHead, showing(written), ColourAxis.HUE)!!
            assertEquals(40, coarse.value7Bit.toInt(), "the coarse read is unmoved by fine $v")
        }
        // Dim and pale heads hold the trim more coarsely, and still read back within what they hold.
        for (head in listOf(Color(0, 0, 60), Color(179, 179, 255))) {
            val written = write(ColourAxis.HUE_FINE, 32, PropertyChannelResolver.colourAtHue(40u, head))
            val read = PropertyChannelResolver.readHead(colourHead, showing(written), ColourAxis.HUE_FINE)!!
            assertTrue(abs(read.value7Bit.toInt() - 32) <= read.tolerance, "$head: ${read.value7Bit} ± ${read.tolerance}")
        }
        assertNull(PropertyChannelResolver.hueFineRead(120u, 120u, 120u), "a grey has no hue to trim")
        assertNull(PropertyChannelResolver.hueFineRead(0u, 0u, 0u), "nor has black")
        // The ends of the trim sit on the boundary between two coarse steps, and channel rounding
        // decides which side a head lands on — so 0 can read back as 127 for the same colour. They
        // are neighbours, and every coarse step must round-trip its ends through `agreesWith`
        // rather than through the raw distance, or a group written at 0 darkens its own ring.
        for (coarse in 0..127) {
            val step = PropertyChannelResolver.colourAtHue(coarse.toUByte(), Color(0, 0, 255))
            for (v in listOf(0, 127)) {
                val written = write(ColourAxis.HUE_FINE, v, step)
                val read = PropertyChannelResolver.readHead(colourHead, showing(written), ColourAxis.HUE_FINE)!!
                assertTrue(
                    read.agreesWith(PropertyChannelResolver.HeadValue(v.toUByte(), read.tolerance, circular = true)),
                    "coarse $coarse, fine $v read back as ${read.value7Bit} ± ${read.tolerance}",
                )
            }
        }
    }

    @Test
    fun `saturation writes v over 127 at the head's hue and value`() {
        val pastelBlue = Color(60, 60, 200)
        assertEquals(Color(0, 0, 200), write(ColourAxis.SATURATION, 127, pastelBlue))
        assertEquals(Color(200, 200, 200), write(ColourAxis.SATURATION, 0, pastelBlue), "0 is a white at the head's level")
        val half = write(ColourAxis.SATURATION, 64, pastelBlue)
        assertEquals(200, half.blue, "value kept")
        assertEquals(half.red, half.green)
        assertTrue(half.red in 98..100, "half saturation: $half")
        // A black or unread head has no hue or value to keep: red at full value.
        assertEquals(Color(255, 0, 0), write(ColourAxis.SATURATION, 127, null))
        assertEquals(Color(255, 255, 255), write(ColourAxis.SATURATION, 0, Color.BLACK))
    }

    @Test
    fun `saturation reads chroma over the largest channel, and nothing on black`() {
        assertEquals(127, PropertyChannelResolver.saturationRead(0u, 0u, 200u)!!.value7Bit.toInt())
        assertEquals(0, PropertyChannelResolver.saturationRead(200u, 200u, 200u)!!.value7Bit.toInt(), "a grey reads 0, not nothing")
        assertNull(PropertyChannelResolver.saturationRead(0u, 0u, 0u))
        assertEquals(1, PropertyChannelResolver.saturationRead(0u, 0u, 200u)!!.tolerance)
        assertEquals(4, PropertyChannelResolver.saturationRead(0u, 0u, 40u)!!.tolerance, "ceil(127 / 40)")
        for (v in 0..127) {
            val bright = write(ColourAxis.SATURATION, v, Color(0, 0, 255))
            assertEquals(v, PropertyChannelResolver.saturationRead(bright.red.toUByte(), bright.green.toUByte(), bright.blue.toUByte())!!.value7Bit.toInt(), "at full value $v reads back exactly")
            val dim = write(ColourAxis.SATURATION, v, Color(0, 0, 40))
            val read = PropertyChannelResolver.saturationRead(dim.red.toUByte(), dim.green.toUByte(), dim.blue.toUByte())!!
            assertTrue(abs(read.value7Bit.toInt() - v) <= read.tolerance, "at value 40, $v reads back as ${read.value7Bit} ± ${read.tolerance}")
        }
        // Two heads at different values written at one saturation agree.
        val a = write(ColourAxis.SATURATION, 90, Color(0, 0, 255))
        val b = write(ColourAxis.SATURATION, 90, Color(0, 0, 40))
        assertNotNull(PropertyChannelResolver.commonValue(listOf(
            PropertyChannelResolver.saturationRead(a.red.toUByte(), a.green.toUByte(), a.blue.toUByte())!!,
            PropertyChannelResolver.saturationRead(b.red.toUByte(), b.green.toUByte(), b.blue.toUByte())!!,
        )))
    }

    @Test
    fun `brightness writes v over 127 as the value at the head's hue and saturation`() {
        assertEquals(Color(0, 0, 255), write(ColourAxis.BRIGHTNESS, 127, Color(0, 0, 60)))
        assertEquals(Color.BLACK, write(ColourAxis.BRIGHTNESS, 0, Color(0, 0, 60)), "0 is black — hue and saturation go with it")
        val half = write(ColourAxis.BRIGHTNESS, 64, Color(60, 60, 200))
        assertTrue(half.blue in 128..129, "half value: $half")
        assertEquals(half.red, half.green)
        assertTrue(half.red in 38..40, "saturation kept: $half")
        // A black or unread head has neither to keep: grey, a white level.
        val grey = write(ColourAxis.BRIGHTNESS, 64, null)
        assertEquals(grey.red, grey.green)
        assertEquals(grey.red, grey.blue)
        assertTrue(grey.red in 128..129, "$grey")
        assertEquals(Color(255, 255, 255), write(ColourAxis.BRIGHTNESS, 127, Color.BLACK))
    }

    @Test
    fun `brightness reads the largest channel and reads black as zero, not nothing`() {
        assertEquals(PropertyChannelResolver.scaleDmxTo7Bit(200u), PropertyChannelResolver.brightnessRead(60u, 60u, 200u).value7Bit)
        assertEquals(0, PropertyChannelResolver.brightnessRead(0u, 0u, 0u).value7Bit.toInt())
        assertNotNull(PropertyChannelResolver.readHead(colourHead, showing(Color.BLACK), ColourAxis.BRIGHTNESS), "a black head has a brightness: none")
        for (v in 0..127) {
            val written = write(ColourAxis.BRIGHTNESS, v, Color(0, 0, 255))
            val read = PropertyChannelResolver.brightnessRead(written.red.toUByte(), written.green.toUByte(), written.blue.toUByte())
            assertTrue(abs(read.value7Bit.toInt() - v) <= read.tolerance, "$v reads back as ${read.value7Bit}")
        }
        // Two heads at different hues and saturations written at one brightness agree.
        val a = write(ColourAxis.BRIGHTNESS, 90, Color(0, 0, 255))
        val b = write(ColourAxis.BRIGHTNESS, 90, Color(255, 200, 200))
        assertNotNull(PropertyChannelResolver.commonValue(listOf(
            PropertyChannelResolver.brightnessRead(a.red.toUByte(), a.green.toUByte(), a.blue.toUByte()),
            PropertyChannelResolver.brightnessRead(b.red.toUByte(), b.green.toUByte(), b.blue.toUByte()),
        )))
    }

    @Test
    fun `an axis on a slider writes and reads nothing`() {
        assertNull(PropertyChannelResolver.toPropertyValue(hex(), "dimmer", 64u, unread, ColourAxis.HUE_FINE))
        assertNull(PropertyChannelResolver.toPropertyValue(hex(), "dimmer", 64u, unread, ColourAxis.HUE), "an explicit hue is still an axis a slider has not got")
        val dimmer = PropertyChannelResolver.describePropertyRead(hex(), "dimmer")!!
        val at100: ChannelReader = { _, _ -> 100u }
        assertNull(PropertyChannelResolver.readHead(dimmer, at100, ColourAxis.SATURATION))
        assertNotNull(PropertyChannelResolver.readHead(dimmer, at100), "no axis: the slider reads as before")
    }

    @Test
    fun `a colour-axis write carries the head's current white, amber and UV, and a flash does not`() {
        // Hex channels: 2..4 colour, 5 amber, 6 white, 7 UV.
        val lit: ChannelReader = { _, channel ->
            when (channel) {
                4 -> 200u
                5 -> 10u
                6 -> 200u
                7 -> 30u
                else -> 0u
            }
        }
        for (axis in ColourAxis.entries) {
            val value = assertIs<CueAssignmentResolver.PropertyValue.Colour>(
                PropertyChannelResolver.toPropertyValue(hex(), "rgbColour", 40u, lit, axis),
            ).value
            assertEquals(200u.toUByte(), value.white, "$axis keeps the white")
            assertEquals(10u.toUByte(), value.amber, "$axis keeps the amber")
            assertEquals(30u.toUByte(), value.uv, "$axis keeps the UV")
        }
        val unreadValue = assertIs<CueAssignmentResolver.PropertyValue.Colour>(
            PropertyChannelResolver.toPropertyValue(hex(), "rgbColour", 40u, unread),
        ).value
        assertEquals(0u.toUByte(), unreadValue.white, "an emitter that cannot be read is 0")
        val flash = assertIs<CueAssignmentResolver.PropertyValue.Colour>(
            PropertyChannelResolver.flashPropertyValue(hex(), "rgbColour", 200u),
        ).value
        assertEquals(0u.toUByte(), flash.white, "a flash is a level: W/A/UV 0")
    }
}
