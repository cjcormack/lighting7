package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [PropertyChannelResolver]. Uses a real [HexFixture] — the resolver only
 * reads static `@FixtureProperty` metadata + DMX channel assignments, so no controller
 * transaction is needed.
 */
class PropertyChannelResolverTest {

    private val universe = Universe(0, 0)
    private fun hex(firstChannel: Int = 1): HexFixture =
        HexFixture(universe, key = "hex-1", fixtureName = "Hex 1", firstChannel = firstChannel)

    @Test
    fun `slider property resolves to single channel write`() {
        val writes = PropertyChannelResolver.resolveFixtureProperty(hex(), "dimmer", 127u)
        assertEquals(1, writes.size)
        val w = writes.single()
        assertEquals(1, w.channel)
        assertEquals(universe, w.universe)
        assertEquals(PropertyCategory.DIMMER, w.category)
        assertEquals(255u.toUByte(), w.value)
    }

    @Test
    fun `slider scales midi 0 to dmx 0 and midi 127 to dmx 255`() {
        val atZero = PropertyChannelResolver.resolveFixtureProperty(hex(), "dimmer", 0u).single()
        val atMax = PropertyChannelResolver.resolveFixtureProperty(hex(), "dimmer", 127u).single()
        assertEquals(0u.toUByte(), atZero.value)
        assertEquals(255u.toUByte(), atMax.value)
    }

    @Test
    fun `slider scaling at midpoint gives midpoint dmx`() {
        val mid = PropertyChannelResolver.resolveFixtureProperty(hex(), "dimmer", 64u).single()
        // Using (64*255 + 63) / 127 = 16383/127 = 129 (rounded up at the halfway point)
        assertEquals(129u.toUByte(), mid.value)
    }

    @Test
    fun `colour property resolves to three channel writes same value`() {
        val writes = PropertyChannelResolver.resolveFixtureProperty(hex(), "rgbColour", 127u)
        assertEquals(3, writes.size)
        // Hex has R/G/B at firstChannel+1..+3, i.e. channels 2, 3, 4.
        assertEquals(listOf(2, 3, 4), writes.map { it.channel })
        assertTrue(writes.all { it.value == 255u.toUByte() })
        assertTrue(writes.all { it.category == PropertyCategory.COLOUR })
    }

    @Test
    fun `unknown property returns empty list`() {
        val writes = PropertyChannelResolver.resolveFixtureProperty(hex(), "nonesuch", 100u)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `enum setting property returns empty list`() {
        // HexFixture.mode is a DmxFixtureSetting → intentionally excluded for faders.
        val writes = PropertyChannelResolver.resolveFixtureProperty(hex(), "mode", 64u)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `fixture at offset produces channels at correct offset`() {
        val writes = PropertyChannelResolver.resolveFixtureProperty(hex(firstChannel = 100), "uv", 127u)
        // uv = firstChannel+6 = 106
        assertEquals(106, writes.single().channel)
        assertEquals(PropertyCategory.UV, writes.single().category)
    }

    @Test
    fun `scale7BitToDmx endpoints`() {
        assertEquals(0u.toUByte(), PropertyChannelResolver.scale7BitToDmx(0u))
        assertEquals(255u.toUByte(), PropertyChannelResolver.scale7BitToDmx(127u))
    }

    @Test
    fun `scale7BitToDmx clamps above 127`() {
        assertEquals(255u.toUByte(), PropertyChannelResolver.scale7BitToDmx(200u))
    }

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
    }

    @Test
    fun `describeFixtureProperty returns empty for enum setting`() {
        val desc = PropertyChannelResolver.describeFixtureProperty(hex(), "mode")
        assertTrue(desc.isEmpty())
    }
}
