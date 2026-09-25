package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The channel-mapping frame's `properties` — the join of [uk.me.cormack.lighting7.fx.PropertyChannelWriter.propertyKeysByChannel]
 * onto each address's mapping, the cache that builds it once per register version, and the
 * wire rule that an uncovered address still says `[]`.
 */
class ChannelMappingMessageTest {

    private val universe = Universe(0, 0)

    private fun fixtures(vararg hexAt: Int): Fixtures = Fixtures().also { it.patch(*hexAt) }

    private fun Fixtures.patch(vararg hexAt: Int) = register {
        addController(MockDmxController(universe))
        hexAt.forEachIndexed { i, first -> addFixture(HexFixture(universe, "hex-${i + 1}", "Hex ${i + 1}", first)) }
    }

    private fun propertiesAt(message: ChannelMappingStateOutMessage, channel: Int): Set<Pair<String, String>> =
        message.mappings.getValue(universe.universe).getValue(channel).properties
            .map { it.targetKey to it.propertyName }.toSet()

    @Test
    fun `each address carries the keys that drive it, joined on universe and channel`() {
        val message = buildChannelMappingMessage(fixtures(1, 13))
        // HexFixture: dimmer +0, R/G/B +1..+3, amber +4, white +5, UV +6.
        assertEquals(setOf("hex-1" to "dimmer"), propertiesAt(message, 1))
        assertEquals(setOf("hex-1" to "amber", "hex-1" to "rgbColour"), propertiesAt(message, 5))
        assertEquals(setOf("hex-2" to "amber", "hex-2" to "rgbColour"), propertiesAt(message, 17))
        assertEquals("Hex 2", message.mappings.getValue(0).getValue(17).fixtureName)
    }

    @Test
    fun `the frame is built once per register version and rebuilt when the patch changes`() {
        val fixtures = fixtures(1)
        val first = buildChannelMappingMessage(fixtures)
        assertSame(first, buildChannelMappingMessage(fixtures), "a second socket reuses the frame")

        fixtures.patch(1, 13)
        val second = buildChannelMappingMessage(fixtures)
        assertNotSame(first, second)
        assertEquals(setOf("hex-2" to "dimmer"), propertiesAt(second, 13))
    }

    @Test
    fun `a different register never gets another register's cached frame`() {
        val one = buildChannelMappingMessage(fixtures(1))
        val other = buildChannelMappingMessage(fixtures(13))
        assertNotSame(one, other)
        assertEquals(setOf("hex-1" to "dimmer"), propertiesAt(other, 13))
    }

    @Test
    fun `an address no property covers still says so on a socket that drops defaults`() {
        val message = buildChannelMappingMessage(fixtures(1))
        val uncovered = message.mappings.getValue(0).entries.first { it.value.properties.isEmpty() }
        // The WS converter is a plain Json (encodeDefaults = false): without @EncodeDefault the
        // empty list would vanish and read, on the client, as a desk that predates the field.
        val encoded = Json.encodeToString(message.mappings.getValue(0).getValue(uncovered.key))
        assertTrue("\"properties\":[]" in encoded, encoded)
    }
}
