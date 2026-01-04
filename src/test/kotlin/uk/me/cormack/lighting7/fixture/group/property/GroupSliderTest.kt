package uk.me.cormack.lighting7.fixture.group.property

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import uk.me.cormack.lighting7.fixture.dmx.UVFixture
import uk.me.cormack.lighting7.fixture.group.dimmer
import uk.me.cormack.lighting7.fixture.group.fixtureGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for GroupSlider aggregation behavior.
 *
 * Key behaviors tested:
 * - Uniformity detection (isUniform, value returns null when non-uniform)
 * - Setting value applies to all members
 * - memberValues returns individual member values
 * - min/max across members
 */
class GroupSliderTest {

    private val testUniverse = Universe(0, 0)

    private fun createFixture(index: Int, controller: MockDmxController): UVFixture {
        val transaction = createTestTransaction(controller)
        return UVFixture(testUniverse, "test-$index", "Test $index", index, transaction = transaction)
    }

    @Test
    fun `value is uniform when all members have same value`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val fixtures = (0..2).map { createFixture(it, controller) }

        val group = fixtureGroup<UVFixture>("test") {
            fixtures.forEach { add(it) }
        }

        // Set all to same value
        group.dimmer.value = 128u

        assertTrue(group.dimmer.isUniform)
        assertEquals(128u.toUByte(), group.dimmer.value)
    }

    @Test
    fun `value is null when members have different values`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createFixture(it, controller) }

        val group = fixtureGroup<UVFixture>("test") {
            fixtures.forEach { add(it) }
        }

        // Set different values through fixtures directly
        fixtures[0].dimmer.value = 100u
        fixtures[1].dimmer.value = 150u
        fixtures[2].dimmer.value = 200u

        assertFalse(group.dimmer.isUniform)
        assertNull(group.dimmer.value)
    }

    @Test
    fun `setting value applies to all members`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createFixture(it, controller) }

        val group = fixtureGroup<UVFixture>("test") {
            fixtures.forEach { add(it) }
        }

        group.dimmer.value = 200u

        assertEquals(listOf(200u.toUByte(), 200u.toUByte(), 200u.toUByte()), group.dimmer.memberValues)
    }

    @Test
    fun `memberValues returns individual values`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createFixture(it, controller) }

        val group = fixtureGroup<UVFixture>("test") {
            fixtures.forEach { add(it) }
        }

        // Set different values through fixtures
        fixtures[0].dimmer.value = 50u
        fixtures[1].dimmer.value = 100u
        fixtures[2].dimmer.value = 150u

        val values = group.dimmer.memberValues
        assertEquals(3, values.size)
        assertEquals(50u.toUByte(), values[0])
        assertEquals(100u.toUByte(), values[1])
        assertEquals(150u.toUByte(), values[2])
    }

    @Test
    fun `minValue returns minimum across members`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createFixture(it, controller) }

        val group = fixtureGroup<UVFixture>("test") {
            fixtures.forEach { add(it) }
        }

        fixtures[0].dimmer.value = 50u
        fixtures[1].dimmer.value = 25u
        fixtures[2].dimmer.value = 150u

        assertEquals(25u.toUByte(), group.dimmer.minValue)
    }

    @Test
    fun `maxValue returns maximum across members`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createFixture(it, controller) }

        val group = fixtureGroup<UVFixture>("test") {
            fixtures.forEach { add(it) }
        }

        fixtures[0].dimmer.value = 50u
        fixtures[1].dimmer.value = 200u
        fixtures[2].dimmer.value = 150u

        assertEquals(200u.toUByte(), group.dimmer.maxValue)
    }

    @Test
    fun `memberCount returns number of members`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..4).map { createFixture(it, controller) }

        val group = fixtureGroup<UVFixture>("test") {
            fixtures.forEach { add(it) }
        }

        assertEquals(5, group.dimmer.memberCount)
    }

    @Test
    fun `setting null value is ignored`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..1).map { createFixture(it, controller) }

        val group = fixtureGroup<UVFixture>("test") {
            fixtures.forEach { add(it) }
        }

        group.dimmer.value = 100u
        group.dimmer.value = null

        // Values should remain unchanged
        assertEquals(100u.toUByte(), group.dimmer.memberValues[0])
        assertEquals(100u.toUByte(), group.dimmer.memberValues[1])
    }

    @Test
    fun `fadeToValue applies to all members`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createFixture(it, controller) }

        val group = fixtureGroup<UVFixture>("test") {
            fixtures.forEach { add(it) }
        }

        group.dimmer.fadeToValue(128u, 1000)

        // All should be set (mock doesn't do actual fading)
        assertEquals(listOf(128u.toUByte(), 128u.toUByte(), 128u.toUByte()), group.dimmer.memberValues)
    }

    @Test
    fun `accessing individual member fixture allows modification`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..2).map { createFixture(it, controller) }

        val group = fixtureGroup<UVFixture>("test") {
            fixtures.forEach { add(it) }
        }

        group.dimmer.value = 100u

        // Access individual member through group.fixtures
        val memberFixture = group.fixtures[1]
        assertNotNull(memberFixture)
        assertEquals(100u.toUByte(), memberFixture.dimmer.value)

        // Modify individual member
        memberFixture.dimmer.value = 200u

        // Group should now be non-uniform
        assertFalse(group.dimmer.isUniform)
        assertEquals(200u.toUByte(), group.dimmer.memberValues[1])
    }

    @Test
    fun `empty values result in null min max`() {
        val controller = MockDmxController(testUniverse)
        val group = fixtureGroup<UVFixture>("empty") {}

        assertNull(group.dimmer.minValue)
        assertNull(group.dimmer.maxValue)
    }

    @Test
    fun `single member group is always uniform`() {
        val controller = MockDmxController(testUniverse)
        val fixture = createFixture(0, controller)

        val group = fixtureGroup<UVFixture>("single") {
            add(fixture)
        }

        group.dimmer.value = 150u

        assertTrue(group.dimmer.isUniform)
        assertEquals(150u.toUByte(), group.dimmer.value)
    }

    @Test
    fun `isUniform is true when all members have same non-null value`() {
        val controller = MockDmxController(testUniverse)
        val fixtures = (0..3).map { createFixture(it, controller) }

        val group = fixtureGroup<UVFixture>("test") {
            fixtures.forEach { add(it) }
        }

        // All default to 0
        assertTrue(group.dimmer.isUniform)

        // Set all to same value
        group.dimmer.value = 255u
        assertTrue(group.dimmer.isUniform)
    }
}
