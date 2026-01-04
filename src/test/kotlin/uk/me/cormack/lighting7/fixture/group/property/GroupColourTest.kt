package uk.me.cormack.lighting7.fixture.group.property

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.group.fixtureGroup
import uk.me.cormack.lighting7.fixture.group.rgbColour
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for GroupColour aggregation behavior.
 *
 * Key behaviors tested:
 * - Uniformity detection (isUniform, value returns null when non-uniform)
 * - Setting colour applies to all members
 * - memberValues returns individual member colours
 * - Individual channel sliders are aggregates
 */
class GroupColourTest {

    private val testUniverse = Universe(0, 0)

    private fun createHexFixture(index: Int, controller: MockDmxController): HexFixture {
        val transaction = createTestTransaction(controller)
        // HexFixture has 12 channels, so space them out
        val firstChannel = index * 12
        return HexFixture(testUniverse, "hex-$index", "Hex $index", firstChannel, transaction = transaction)
    }

    @Test
    fun `value is uniform when all members have same colour`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createHexFixture(it, controller) }

        val group = fixtureGroup<HexFixture>("test") {
            fixtures.forEach { add(it) }
        }

        group.rgbColour.value = Color.RED

        assertTrue(group.rgbColour.isUniform)
        assertEquals(Color.RED, group.rgbColour.value)
    }

    @Test
    fun `value is null when members have different colours`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createHexFixture(it, controller) }

        val group = fixtureGroup<HexFixture>("test") {
            fixtures.forEach { add(it) }
        }

        // Set different colours through fixtures
        fixtures[0].rgbColour.value = Color.RED
        fixtures[1].rgbColour.value = Color.GREEN
        fixtures[2].rgbColour.value = Color.BLUE

        assertFalse(group.rgbColour.isUniform)
        assertNull(group.rgbColour.value)
    }

    @Test
    fun `setting colour applies to all members`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createHexFixture(it, controller) }

        val group = fixtureGroup<HexFixture>("test") {
            fixtures.forEach { add(it) }
        }

        group.rgbColour.value = Color(100, 150, 200)

        val values = group.rgbColour.memberValues
        assertEquals(3, values.size)
        values.forEach { color ->
            assertNotNull(color)
            assertEquals(100, color.red)
            assertEquals(150, color.green)
            assertEquals(200, color.blue)
        }
    }

    @Test
    fun `memberValues returns individual colours`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createHexFixture(it, controller) }

        val group = fixtureGroup<HexFixture>("test") {
            fixtures.forEach { add(it) }
        }

        // Set different colours through fixtures
        fixtures[0].rgbColour.value = Color.RED
        fixtures[1].rgbColour.value = Color.GREEN
        fixtures[2].rgbColour.value = Color.BLUE

        val values = group.rgbColour.memberValues
        assertEquals(3, values.size)
        assertEquals(Color.RED, values[0])
        assertEquals(Color.GREEN, values[1])
        assertEquals(Color.BLUE, values[2])
    }

    @Test
    fun `redSlider is aggregate slider`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createHexFixture(it, controller) }

        val group = fixtureGroup<HexFixture>("test") {
            fixtures.forEach { add(it) }
        }

        // Set all to white first
        group.rgbColour.value = Color.WHITE

        // Verify red slider is uniform
        assertTrue(group.rgbColour.redSlider.isUniform)
        assertEquals(255u.toUByte(), group.rgbColour.redSlider.value)

        // Modify just red slider
        group.rgbColour.redSlider.value = 128u

        // All reds should be 128
        assertEquals(listOf(128u.toUByte(), 128u.toUByte(), 128u.toUByte()), group.rgbColour.redSlider.memberValues)
    }

    @Test
    fun `greenSlider is aggregate slider`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..1).map { createHexFixture(it, controller) }

        val group = fixtureGroup<HexFixture>("test") {
            fixtures.forEach { add(it) }
        }

        group.rgbColour.greenSlider.value = 200u

        assertEquals(listOf(200u.toUByte(), 200u.toUByte()), group.rgbColour.greenSlider.memberValues)
        assertTrue(group.rgbColour.greenSlider.isUniform)
    }

    @Test
    fun `blueSlider is aggregate slider`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..1).map { createHexFixture(it, controller) }

        val group = fixtureGroup<HexFixture>("test") {
            fixtures.forEach { add(it) }
        }

        group.rgbColour.blueSlider.value = 50u

        assertEquals(listOf(50u.toUByte(), 50u.toUByte()), group.rgbColour.blueSlider.memberValues)
        assertTrue(group.rgbColour.blueSlider.isUniform)
    }

    @Test
    fun `setting null colour is ignored`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..1).map { createHexFixture(it, controller) }

        val group = fixtureGroup<HexFixture>("test") {
            fixtures.forEach { add(it) }
        }

        group.rgbColour.value = Color.YELLOW
        group.rgbColour.value = null

        // Should remain yellow
        assertEquals(Color.YELLOW, group.rgbColour.value)
    }

    @Test
    fun `fadeToColour applies to all members`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createHexFixture(it, controller) }

        val group = fixtureGroup<HexFixture>("test") {
            fixtures.forEach { add(it) }
        }

        group.rgbColour.fadeToColour(Color.MAGENTA, 1000)

        // All should be magenta (mock doesn't do actual fading)
        assertTrue(group.rgbColour.isUniform)
        assertEquals(Color.MAGENTA, group.rgbColour.value)
    }

    @Test
    fun `accessing individual member fixture allows colour modification`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createHexFixture(it, controller) }

        val group = fixtureGroup<HexFixture>("test") {
            fixtures.forEach { add(it) }
        }

        group.rgbColour.value = Color.CYAN

        // Access individual member through group.fixtures
        val memberFixture = group.fixtures[1]
        assertNotNull(memberFixture)
        assertEquals(Color.CYAN, memberFixture.rgbColour.value)

        // Modify individual member
        memberFixture.rgbColour.value = Color.ORANGE

        // Group should now be non-uniform
        assertFalse(group.rgbColour.isUniform)
        assertEquals(Color.ORANGE, group.rgbColour.memberValues[1])
    }

    @Test
    fun `memberCount returns number of members`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..4).map { createHexFixture(it, controller) }

        val group = fixtureGroup<HexFixture>("test") {
            fixtures.forEach { add(it) }
        }

        assertEquals(5, group.rgbColour.memberCount)
    }

    @Test
    fun `single member group is always uniform`() {
        val controller = MockDmxController(testUniverse)
        val fixture = createHexFixture(0, controller)

        val group = fixtureGroup<HexFixture>("single") {
            add(fixture)
        }

        group.rgbColour.value = Color.PINK

        assertTrue(group.rgbColour.isUniform)
        assertEquals(Color.PINK, group.rgbColour.value)
    }
}
