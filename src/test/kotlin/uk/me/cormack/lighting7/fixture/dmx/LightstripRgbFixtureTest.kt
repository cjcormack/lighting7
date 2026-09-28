package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.createTestTransaction
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LightstripRgbFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `colour writes red green blue on three consecutive channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val strip = LightstripRgbFixture(universe, "strip-1", "Strip 1", 40)
            .withTransaction(transaction)

        strip.rgbColour.value = Color(200, 100, 50)
        transaction.apply()

        assertEquals(200u.toUByte(), controller.getValue(40))
        assertEquals(100u.toUByte(), controller.getValue(41))
        assertEquals(50u.toUByte(), controller.getValue(42))
        assertEquals(3, strip.channelCount)
    }

    @Test
    fun `registered as a variable-length colour-only strip`() {
        val info = assertNotNull(FixtureTypeRegistry.typeInfoForKey("lightstrip-rgb"))
        assertEquals(3, info.channelCount)
        assertEquals(FixtureKind.STRIP, info.kind)
        assertTrue(info.acceptsLength)
        assertEquals(listOf("colour"), info.capabilities)
    }

    @Test
    fun `extended colour drops white amber and uv on a pure RGB head`() {
        val strip = LightstripRgbFixture(universe, "strip-1", "Strip 1", 1)
        val ext = ExtendedColour(Color(200, 100, 50), white = 128u, amber = 64u, uv = 180u)

        val writes = PropertyChannelWriter.resolve(strip, "rgbColour", CueAssignmentResolver.PropertyValue.Colour(ext))

        assertEquals(mapOf(1 to 200u.toUByte(), 2 to 100u.toUByte(), 3 to 50u.toUByte()), writes.associate { it.channel to it.value })
    }
}
