package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.UVFixture
import uk.me.cormack.lighting7.fixture.property.AggregateColour
import uk.me.cormack.lighting7.fixture.property.AggregateSlider
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for trait extension properties on fixture groups.
 *
 * These tests verify that the extension properties (dimmer, rgbColour, uv, etc.)
 * work correctly with type-bounded groups and return appropriate aggregate types.
 */
class TraitExtensionsTest {

    private val testUniverse = Universe(0, 0)

    // ============================================
    // WithDimmer extension tests
    // ============================================

    @Test
    fun `dimmer extension returns AggregateSlider for WithDimmer group`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val fixtures = (0..2).map {
            UVFixture(testUniverse, "uv-$it", "UV $it", it, transaction = transaction)
        }

        val group = fixtureGroup<UVFixture>("uv-group") {
            fixtures.forEach { add(it) }
        }

        val dimmer = group.dimmer
        assertNotNull(dimmer)
        assertTrue(dimmer is AggregateSlider)
        assertEquals(3, dimmer.memberCount)
    }

    @Test
    fun `dimmer extension sets all member values`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val fixtures = (0..2).map {
            UVFixture(testUniverse, "uv-$it", "UV $it", it, transaction = transaction)
        }

        val group = fixtureGroup<UVFixture>("uv-group") {
            fixtures.forEach { add(it) }
        }

        group.dimmer.value = 200u

        // Verify through group's dimmer
        assertTrue(group.dimmer.isUniform)
        assertEquals(200u.toUByte(), group.dimmer.value)
        assertEquals(listOf(200u.toUByte(), 200u.toUByte(), 200u.toUByte()), group.dimmer.memberValues)
    }

    // ============================================
    // WithColour extension tests
    // ============================================

    @Test
    fun `rgbColour extension returns AggregateColour for WithColour group`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val fixtures = (0..2).map {
            HexFixture(testUniverse, "hex-$it", "Hex $it", it * 12, transaction = transaction)
        }

        val group = fixtureGroup<HexFixture>("hex-group") {
            fixtures.forEach { add(it) }
        }

        val colour = group.rgbColour
        assertNotNull(colour)
        assertTrue(colour is AggregateColour)
        assertEquals(3, colour.memberCount)
    }

    @Test
    fun `rgbColour extension sets all member colours`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val fixtures = (0..1).map {
            HexFixture(testUniverse, "hex-$it", "Hex $it", it * 12, transaction = transaction)
        }

        val group = fixtureGroup<HexFixture>("hex-group") {
            fixtures.forEach { add(it) }
        }

        group.rgbColour.value = Color(100, 150, 200)

        // Verify through group's colour
        assertTrue(group.rgbColour.isUniform)
        val resultColour = group.rgbColour.value
        assertNotNull(resultColour)
        assertEquals(100, resultColour.red)
        assertEquals(150, resultColour.green)
        assertEquals(200, resultColour.blue)
    }

    @Test
    fun `rgbColour redSlider is aggregate`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val fixtures = (0..2).map {
            HexFixture(testUniverse, "hex-$it", "Hex $it", it * 12, transaction = transaction)
        }

        val group = fixtureGroup<HexFixture>("hex-group") {
            fixtures.forEach { add(it) }
        }

        val redSlider = group.rgbColour.redSlider
        assertTrue(redSlider is AggregateSlider)

        redSlider.value = 255u

        // Verify through the aggregate slider
        assertTrue(redSlider.isUniform)
        assertEquals(255u.toUByte(), redSlider.value)
        assertEquals(listOf(255u.toUByte(), 255u.toUByte(), 255u.toUByte()), redSlider.memberValues)
    }

    // ============================================
    // WithUv extension tests
    // ============================================

    @Test
    fun `uv extension returns AggregateSlider for WithUv group`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val fixtures = (0..2).map {
            HexFixture(testUniverse, "hex-$it", "Hex $it", it * 12, transaction = transaction)
        }

        val group = fixtureGroup<HexFixture>("hex-group") {
            fixtures.forEach { add(it) }
        }

        val uv = group.uv
        assertNotNull(uv)
        assertTrue(uv is AggregateSlider)
    }

    @Test
    fun `uv extension sets all member values`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val fixtures = (0..1).map {
            HexFixture(testUniverse, "hex-$it", "Hex $it", it * 12, transaction = transaction)
        }

        val group = fixtureGroup<HexFixture>("hex-group") {
            fixtures.forEach { add(it) }
        }

        group.uv.value = 128u

        // Verify through group's UV slider
        assertTrue(group.uv.isUniform)
        assertEquals(128u.toUByte(), group.uv.value)
        assertEquals(listOf(128u.toUByte(), 128u.toUByte()), group.uv.memberValues)
    }

    // ============================================
    // Combined trait tests
    // ============================================

    @Test
    fun `group with multiple traits supports all extensions`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val fixtures = (0..1).map {
            HexFixture(testUniverse, "hex-$it", "Hex $it", it * 12, transaction = transaction)
        }

        val group = fixtureGroup<HexFixture>("hex-group") {
            fixtures.forEach { add(it) }
        }

        // Set dimmer
        group.dimmer.value = 255u

        // Set colour
        group.rgbColour.value = Color.RED

        // Set UV
        group.uv.value = 200u

        // Verify all are set correctly
        assertTrue(group.dimmer.isUniform)
        assertTrue(group.rgbColour.isUniform)
        assertTrue(group.uv.isUniform)

        assertEquals(255u.toUByte(), group.dimmer.value)
        assertEquals(Color.RED, group.rgbColour.value)
        assertEquals(200u.toUByte(), group.uv.value)
    }

    // ============================================
    // Type-safe extension availability
    // ============================================

    @Test
    fun `extensions create new aggregate for each access`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val fixtures = (0..1).map {
            UVFixture(testUniverse, "uv-$it", "UV $it", it, transaction = transaction)
        }

        val group = fixtureGroup<UVFixture>("uv-group") {
            fixtures.forEach { add(it) }
        }

        // Each access returns a fresh aggregate reflecting current state
        group.dimmer.value = 100u
        val firstRead = group.dimmer.value

        group.dimmer.value = 200u
        val secondRead = group.dimmer.value

        assertEquals(100u.toUByte(), firstRead)
        assertEquals(200u.toUByte(), secondRead)
    }

    @Test
    fun `uv and dimmer are different properties on UVFixture`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val fixtures = (0..1).map {
            UVFixture(testUniverse, "uv-$it", "UV $it", it, transaction = transaction)
        }

        val group = fixtureGroup<UVFixture>("uv-group") {
            fixtures.forEach { add(it) }
        }

        // UVFixture's dimmer and uv point to the same slider (see UVFixture implementation)
        // So setting one affects the other
        group.dimmer.value = 150u

        // Since UVFixture.uv returns dimmer, both should be the same
        assertEquals(group.dimmer.value, group.uv.value)
    }
}
