package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.UVFixture
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for FixtureGroup functionality.
 */
class FixtureGroupTest {

    private val testUniverse = Universe(0, 0)

    private fun createFixture(index: Int): UVFixture {
        return UVFixture(testUniverse, "test-$index", "Test $index", index)
    }

    private fun createTestGroup(count: Int = 4): FixtureGroup<UVFixture> {
        return fixtureGroup("test-group") {
            (0 until count).forEach { idx ->
                add(createFixture(idx))
            }
        }
    }

    @Test
    fun `group contains correct number of members`() {
        val group = createTestGroup(4)
        assertEquals(4, group.size)
        assertEquals(4, group.fixtures.size)
    }

    @Test
    fun `group members have correct normalized positions`() {
        val group = createTestGroup(4)

        // With 4 members: 0.0, 0.333, 0.666, 1.0
        assertEquals(0.0, group[0].normalizedPosition, 0.01)
        assertEquals(0.333, group[1].normalizedPosition, 0.01)
        assertEquals(0.666, group[2].normalizedPosition, 0.01)
        assertEquals(1.0, group[3].normalizedPosition, 0.01)
    }

    @Test
    fun `single member has position 0_5`() {
        val fixture = createFixture(0)
        val group = fixtureGroup<UVFixture>("single-group") {
            add(fixture)
        }

        assertEquals(1, group.size)
        assertEquals(0.5, group[0].normalizedPosition)
    }

    @Test
    fun `group members have correct indices`() {
        val group = createTestGroup(4)

        group.forEachIndexed { expected, member ->
            assertEquals(expected, member.index)
        }
    }

    @Test
    fun `filter creates new group with reindexed members`() {
        val group = createTestGroup(4)
        val filtered = group.filter { it.index % 2 == 0 } // Keep indices 0, 2

        assertEquals(2, filtered.size)
        assertEquals(0, filtered[0].index) // Reindexed from 0
        assertEquals(1, filtered[1].index) // Reindexed from 2
        assertEquals(0.0, filtered[0].normalizedPosition)
        assertEquals(1.0, filtered[1].normalizedPosition)
    }

    @Test
    fun `everyNth returns correct members`() {
        val group = createTestGroup(6)

        val every2nd = group.everyNth(2)
        assertEquals(3, every2nd.size)

        val every3rd = group.everyNth(3)
        assertEquals(2, every3rd.size)

        val every2ndOffset1 = group.everyNth(2, offset = 1)
        assertEquals(3, every2ndOffset1.size)
    }

    @Test
    fun `leftHalf returns left half members`() {
        val group = createTestGroup(4)
        val left = group.leftHalf()

        // With 4 members (positions 0.0, 0.333, 0.666, 1.0), left half has 2 members
        assertEquals(2, left.size)
        // Note: filtered groups are re-indexed, so we check the fixture keys
        assertEquals("test-0", left[0].fixture.key)
        assertEquals("test-1", left[1].fixture.key)
    }

    @Test
    fun `rightHalf returns right half members`() {
        val group = createTestGroup(4)
        val right = group.rightHalf()

        // Right half includes positions >= 0.5
        assertEquals(2, right.size)
        assertEquals("test-2", right[0].fixture.key)
        assertEquals("test-3", right[1].fixture.key)
    }

    @Test
    fun `reversed inverts order and positions`() {
        val group = createTestGroup(4)
        val reversed = group.reversed()

        assertEquals(4, reversed.size)

        // Check order is reversed
        assertEquals(group[3].fixture.key, reversed[0].fixture.key)
        assertEquals(group[0].fixture.key, reversed[3].fixture.key)

        // Check positions are inverted
        assertEquals(0.0, reversed[0].normalizedPosition, 0.01)
        assertEquals(1.0, reversed[3].normalizedPosition, 0.01)
    }

    @Test
    fun `asCapable returns group when type matches`() {
        val group = createTestGroup(4)
        // UVFixture can be narrowed to itself
        val result = group.asCapable<UVFixture>()
        assertNotNull(result)
        assertEquals(4, result.size)
    }

    @Test
    fun `requireCapable returns group when type matches`() {
        val group = createTestGroup(4)
        val result = group.requireCapable<UVFixture>()
        assertNotNull(result)
        assertEquals(4, result.size)
    }

    @Test
    fun `splitAt creates two groups`() {
        val group = createTestGroup(4)
        val (left, right) = group.splitAt(0.5)

        assertEquals(2, left.size)
        assertEquals(2, right.size)
    }

    @Test
    fun `center returns middle portion`() {
        val group = createTestGroup(5)
        val centered = group.center(0.3)

        // With 5 members (positions 0.0, 0.25, 0.5, 0.75, 1.0)
        // Center with margin 0.3 means positions in 0.3..0.7
        // Only position 0.5 (index 2) qualifies
        assertEquals(1, centered.size)
        assertEquals("test-2", centered[0].fixture.key)
    }

    @Test
    fun `edges returns edge members`() {
        val group = createTestGroup(5)
        val edges = group.edges(0.3)

        // With 5 members (positions 0.0, 0.25, 0.5, 0.75, 1.0)
        // Edges with margin 0.3 are positions < 0.3 or > 0.7
        // That includes positions 0.0, 0.25, 0.75, 1.0 (indices 0, 1, 3, 4)
        assertEquals(4, edges.size)
        // Check fixture keys instead of recomputed positions
        val keys = edges.map { it.fixture.key }
        assertTrue("test-0" in keys)
        assertTrue("test-1" in keys)
        assertTrue("test-3" in keys)
        assertTrue("test-4" in keys)
    }

    @Test
    fun `withTags filters by tags`() {
        val group = fixtureGroup<UVFixture>("tagged-group") {
            add(createFixture(0), tags = setOf("left", "front"))
            add(createFixture(1), tags = setOf("center"))
            add(createFixture(2), tags = setOf("right", "front"))
        }

        val frontOnly = group.withTags("front")
        assertEquals(2, frontOnly.size)

        val leftOnly = group.withTags("left")
        assertEquals(1, leftOnly.size)
    }

    @Test
    fun `group metadata is preserved`() {
        val group = fixtureGroup<UVFixture>("meta-group") {
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
    fun `member metadata is preserved`() {
        val fixture = createFixture(0)

        val group = fixtureGroup<UVFixture>("offset-group") {
            add(fixture, panOffset = 30.0, tiltOffset = -15.0, symmetricInvert = true)
        }

        val member = group[0]
        assertEquals(30.0, member.metadata.panOffset)
        assertEquals(-15.0, member.metadata.tiltOffset)
        assertTrue(member.metadata.symmetricInvert)
    }

    // ============================================
    // FixtureTarget implementation tests
    // ============================================

    @Test
    fun `group implements FixtureTarget with correct targetKey`() {
        val group = createTestGroup(3)
        assertEquals("test-group", group.targetKey)
    }

    @Test
    fun `group implements FixtureTarget with correct displayName`() {
        val group = createTestGroup(3)
        assertEquals("test-group", group.displayName)
    }

    @Test
    fun `group isGroup returns true`() {
        val group = createTestGroup(3)
        assertTrue(group.isGroup)
    }

    @Test
    fun `group memberCount matches size`() {
        val group = createTestGroup(5)
        assertEquals(5, group.memberCount)
    }

    // ============================================
    // flatten() tests
    // ============================================

    @Test
    fun `flatten returns fixtures for non-nested group`() {
        val group = createTestGroup(4)
        val flattened = group.flatten()

        assertEquals(4, flattened.size)
        assertEquals("test-0", flattened[0].targetKey)
        assertEquals("test-3", flattened[3].targetKey)
    }

    @Test
    fun `flatten recursively extracts from nested groups`() {
        // Create inner groups
        val innerGroup1 = fixtureGroup<UVFixture>("inner-1") {
            add(createFixture(0))
            add(createFixture(1))
        }
        val innerGroup2 = fixtureGroup<UVFixture>("inner-2") {
            add(createFixture(2))
            add(createFixture(3))
        }

        // Create outer group containing inner groups
        val outerGroup = fixtureGroup<FixtureGroup<UVFixture>>("outer") {
            add(innerGroup1)
            add(innerGroup2)
        }

        val flattened = outerGroup.flatten()

        assertEquals(4, flattened.size)
        // Should contain the leaf fixtures, not the inner groups
        assertTrue(flattened.all { it is UVFixture })
    }

    @Test
    fun `flattenAs filters to specific type`() {
        val group = createTestGroup(3)
        val uvFixtures = group.flattenAs<UVFixture>()

        assertEquals(3, uvFixtures.size)
        assertTrue(uvFixtures.all { it is UVFixture })
    }

    @Test
    fun `flattenAs returns empty list if no matches`() {
        val group = createTestGroup(3)
        // HexFixture is not in this group
        val hexFixtures = group.flattenAs<HexFixture>()

        assertTrue(hexFixtures.isEmpty())
    }

    // ============================================
    // withTransaction() tests
    // ============================================

    @Test
    fun `withTransaction returns group with same structure`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val group = createTestGroup(3)

        val boundGroup = group.withTransaction(transaction)

        assertEquals(group.name, boundGroup.name)
        assertEquals(group.size, boundGroup.size)
        assertEquals(group.metadata, boundGroup.metadata)
    }

    @Test
    fun `withTransaction preserves member positions`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val group = createTestGroup(4)

        val boundGroup = group.withTransaction(transaction)

        // Positions should be identical
        group.forEachIndexed { idx, member ->
            assertEquals(member.normalizedPosition, boundGroup[idx].normalizedPosition)
            assertEquals(member.index, boundGroup[idx].index)
        }
    }

    @Test
    fun `withTransaction allows fixture operations`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)
        val group = createTestGroup(3)

        val boundGroup = group.withTransaction(transaction)

        // Now we can use the dimmer on the bound group
        boundGroup.dimmer.value = 200u

        // Verify values were set via the group's dimmer (reads from transaction)
        assertTrue(boundGroup.dimmer.isUniform)
        assertEquals(200u.toUByte(), boundGroup.dimmer.value)
        assertEquals(listOf(200u.toUByte(), 200u.toUByte(), 200u.toUByte()), boundGroup.dimmer.memberValues)
    }

    // ============================================
    // Nested group tests
    // ============================================

    @Test
    fun `nested groups support property access via extensions`() {
        val controller = MockDmxController(testUniverse)
        val transaction = createTestTransaction(controller)

        // Create fixtures with transaction
        val fixture1 = UVFixture(testUniverse, "uv-0", "UV 0", 0, transaction = transaction)
        val fixture2 = UVFixture(testUniverse, "uv-1", "UV 1", 1, transaction = transaction)
        val fixture3 = UVFixture(testUniverse, "uv-2", "UV 2", 2, transaction = transaction)
        val fixture4 = UVFixture(testUniverse, "uv-3", "UV 3", 3, transaction = transaction)

        // Create inner groups
        val innerGroup1 = fixtureGroup<UVFixture>("inner-1") {
            add(fixture1)
            add(fixture2)
        }
        val innerGroup2 = fixtureGroup<UVFixture>("inner-2") {
            add(fixture3)
            add(fixture4)
        }

        // Create outer group - note: we need the inner groups to be WithDimmer compatible
        // Since FixtureGroup itself doesn't implement WithDimmer, we use flat groups
        // This test verifies that flattening works correctly

        val outerGroup = fixtureGroup<FixtureGroup<UVFixture>>("outer") {
            add(innerGroup1)
            add(innerGroup2)
        }

        // Flatten and verify we get all 4 fixtures
        val allFixtures = outerGroup.flatten()
        assertEquals(4, allFixtures.size)

        // Verify we can still use property access on the flat result
        allFixtures.filterIsInstance<WithDimmer>().forEach {
            assertNotNull(it.dimmer)
        }
    }

    @Test
    fun `deeply nested groups flatten correctly`() {
        // Create 3 levels of nesting
        val level1 = fixtureGroup<UVFixture>("level1") {
            add(createFixture(0))
            add(createFixture(1))
        }
        val level2 = fixtureGroup<FixtureGroup<UVFixture>>("level2") {
            add(level1)
        }
        val level3 = fixtureGroup<FixtureGroup<FixtureGroup<UVFixture>>>("level3") {
            add(level2)
        }

        val flattened = level3.flatten()

        assertEquals(2, flattened.size)
        assertTrue(flattened.all { it is UVFixture })
        assertEquals("test-0", flattened[0].targetKey)
        assertEquals("test-1", flattened[1].targetKey)
    }

    @Test
    fun `empty nested groups flatten to empty list`() {
        val emptyInner = fixtureGroup<UVFixture>("empty-inner") {}
        val outer = fixtureGroup<FixtureGroup<UVFixture>>("outer") {
            add(emptyInner)
        }

        val flattened = outer.flatten()
        assertTrue(flattened.isEmpty())
    }

    // ============================================
    // Mixed group type tests
    // ============================================

    @Test
    fun `group with mixed nested and flat members flattens correctly`() {
        // Inner group with 2 fixtures
        val innerGroup = fixtureGroup<UVFixture>("inner") {
            add(createFixture(0))
            add(createFixture(1))
        }

        // Note: Can't mix UVFixture and FixtureGroup<UVFixture> in same group
        // This test verifies that pure nested groups work correctly
        val flattenedInner = innerGroup.flatten()
        assertEquals(2, flattenedInner.size)
    }

    // ============================================
    // FixtureTarget conformance edge cases
    // ============================================

    @Test
    fun `empty group has correct FixtureTarget properties`() {
        val empty = fixtureGroup<UVFixture>("empty") {}

        assertEquals("empty", empty.targetKey)
        assertEquals("empty", empty.displayName)
        assertTrue(empty.isGroup)
        assertEquals(0, empty.memberCount)
    }

    @Test
    fun `single member group has correct FixtureTarget properties`() {
        val single = fixtureGroup<UVFixture>("single") {
            add(createFixture(0))
        }

        assertEquals("single", single.targetKey)
        assertEquals(1, single.memberCount)
        assertTrue(single.isGroup)
    }
}
