package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.UVFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for GroupBuilder DSL.
 */
class GroupBuilderTest {

    private val testUniverse = Universe(0, 0)

    private fun createFixture(index: Int): UVFixture {
        return UVFixture(testUniverse, "test-$index", "Test $index", index)
    }

    @Test
    fun `fixtureGroup DSL creates group with correct name`() {
        val group = fixtureGroup<UVFixture>("my-group") {
            add(createFixture(0))
        }

        assertEquals("my-group", group.name)
    }

    @Test
    fun `add single fixture`() {
        val fixture = createFixture(0)
        val group = fixtureGroup<UVFixture>("single") {
            add(fixture)
        }

        assertEquals(1, group.size)
        assertEquals(fixture, group[0].fixture)
    }

    @Test
    fun `add multiple fixtures with add`() {
        val fixtures = (0..2).map { createFixture(it) }
        val group = fixtureGroup<UVFixture>("multiple") {
            fixtures.forEach { add(it) }
        }

        assertEquals(3, group.size)
    }

    @Test
    fun `add with metadata sets offsets`() {
        val fixture = createFixture(0)
        val group = fixtureGroup<UVFixture>("with-meta") {
            add(fixture, panOffset = 30.0, tiltOffset = -15.0)
        }

        val member = group[0]
        assertEquals(30.0, member.metadata.panOffset)
        assertEquals(-15.0, member.metadata.tiltOffset)
    }

    @Test
    fun `add with tags sets tags`() {
        val fixture = createFixture(0)
        val group = fixtureGroup<UVFixture>("with-tags") {
            add(fixture, tags = setOf("left", "front"))
        }

        val member = group[0]
        assertTrue(member.metadata.tags.contains("left"))
        assertTrue(member.metadata.tags.contains("front"))
    }

    @Test
    fun `add with vararg tags`() {
        val fixture = createFixture(0)
        val group = fixtureGroup<UVFixture>("vararg-tags") {
            add(fixture, "left", "front")
        }

        assertEquals(setOf("left", "front"), group[0].metadata.tags)
    }

    @Test
    fun `addAll adds multiple fixtures`() {
        val fixtures = (0..2).map { createFixture(it) }
        val group = fixtureGroup<UVFixture>("add-all") {
            addAll(fixtures)
        }

        assertEquals(3, group.size)
    }

    @Test
    fun `addAll with varargs`() {
        val f0 = createFixture(0)
        val f1 = createFixture(1)
        val f2 = createFixture(2)
        val group = fixtureGroup<UVFixture>("add-all-vararg") {
            addAll(f0, f1, f2)
        }

        assertEquals(3, group.size)
    }

    @Test
    fun `addSpread calculates pan offsets`() {
        val fixtures = (0..3).map { createFixture(it) }
        val group = fixtureGroup<UVFixture>("spread-pan") {
            addSpread(fixtures, panSpread = 120.0)
        }

        // 4 fixtures, 120 degree spread centered at 0
        // Positions: -60, -20, +20, +60
        assertEquals(-60.0, group[0].metadata.panOffset, 0.1)
        assertEquals(-20.0, group[1].metadata.panOffset, 0.1)
        assertEquals(20.0, group[2].metadata.panOffset, 0.1)
        assertEquals(60.0, group[3].metadata.panOffset, 0.1)
    }

    @Test
    fun `addSpread calculates tilt offsets`() {
        val fixtures = (0..3).map { createFixture(it) }
        val group = fixtureGroup<UVFixture>("spread-tilt") {
            addSpread(fixtures, tiltSpread = 60.0)
        }

        // 4 fixtures, 60 degree spread centered at 0
        // Positions: -30, -10, +10, +30
        assertEquals(-30.0, group[0].metadata.tiltOffset, 0.1)
        assertEquals(-10.0, group[1].metadata.tiltOffset, 0.1)
        assertEquals(10.0, group[2].metadata.tiltOffset, 0.1)
        assertEquals(30.0, group[3].metadata.tiltOffset, 0.1)
    }

    @Test
    fun `addSymmetric sets symmetricInvert for right half`() {
        val fixtures = (0..3).map { createFixture(it) }
        val group = fixtureGroup<UVFixture>("symmetric") {
            addSymmetric(fixtures, panSpread = 120.0)
        }

        // First half (position < 0.5) should not invert
        assertEquals(false, group[0].metadata.symmetricInvert)
        assertEquals(false, group[1].metadata.symmetricInvert)

        // Second half (position > 0.5) should invert
        assertEquals(true, group[2].metadata.symmetricInvert)
        assertEquals(true, group[3].metadata.symmetricInvert)
    }

    @Test
    fun `configure sets group metadata`() {
        val group = fixtureGroup<UVFixture>("configured") {
            add(createFixture(0))
            configure(
                symmetricMode = SymmetricMode.MIRROR,
                defaultDistribution = "CENTER_OUT"
            )
        }

        assertEquals(SymmetricMode.MIRROR, group.metadata.symmetricMode)
        assertEquals("CENTER_OUT", group.metadata.defaultDistributionName)
    }

    @Test
    fun `default metadata values`() {
        val group = fixtureGroup<UVFixture>("defaults") {
            add(createFixture(0))
        }

        assertEquals(SymmetricMode.NONE, group.metadata.symmetricMode)
        assertEquals("LINEAR", group.metadata.defaultDistributionName)
    }

    @Test
    fun `fixtureGroupOf creates group from list`() {
        val fixtures = (0..2).map { createFixture(it) }
        val group = fixtureGroupOf("from-list", fixtures)

        assertEquals("from-list", group.name)
        assertEquals(3, group.size)
    }

    @Test
    fun `fixtureGroupOf with varargs`() {
        val f0 = createFixture(0)
        val f1 = createFixture(1)
        val group = fixtureGroupOf("from-varargs", f0, f1)

        assertEquals(2, group.size)
    }

    @Test
    fun `members have correct normalized positions`() {
        val fixtures = (0..3).map { createFixture(it) }
        val group = fixtureGroup<UVFixture>("positions") {
            addAll(fixtures)
        }

        // 4 fixtures: 0.0, 0.333, 0.666, 1.0
        assertEquals(0.0, group[0].normalizedPosition, 0.01)
        assertEquals(0.333, group[1].normalizedPosition, 0.01)
        assertEquals(0.666, group[2].normalizedPosition, 0.01)
        assertEquals(1.0, group[3].normalizedPosition, 0.01)
    }

    @Test
    fun `single fixture has position 0_5`() {
        val group = fixtureGroup<UVFixture>("single-pos") {
            add(createFixture(0))
        }

        assertEquals(0.5, group[0].normalizedPosition)
    }

    @Test
    fun `empty group has zero size`() {
        val group = fixtureGroup<UVFixture>("empty") {}
        assertEquals(0, group.size)
    }

    @Test
    fun `order is preserved`() {
        val fixtures = (0..4).map { createFixture(it) }
        val group = fixtureGroup<UVFixture>("ordered") {
            // Add in reverse order
            fixtures.reversed().forEach { add(it) }
        }

        // Should be in the order added (reversed)
        assertEquals("test-4", group[0].fixture.key)
        assertEquals("test-3", group[1].fixture.key)
        assertEquals("test-2", group[2].fixture.key)
        assertEquals("test-1", group[3].fixture.key)
        assertEquals("test-0", group[4].fixture.key)
    }
}
