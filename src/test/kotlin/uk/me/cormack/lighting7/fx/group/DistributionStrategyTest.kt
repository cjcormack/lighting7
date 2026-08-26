package uk.me.cormack.lighting7.fx.group

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.UVFixture
import uk.me.cormack.lighting7.fixture.group.GroupMember
import uk.me.cormack.lighting7.fixture.group.MemberMetadata
import uk.me.cormack.lighting7.fixture.group.fixtureGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for DistributionStrategy phase offset calculations.
 */
class DistributionStrategyTest {

    private val testUniverse = Universe(0, 0)

    private fun createMember(index: Int, groupSize: Int): GroupMember<UVFixture> {
        val fixture = UVFixture(testUniverse, "test-$index", "Test $index", index)
        return GroupMember(
            fixture = fixture,
            index = index,
            normalizedPosition = if (groupSize > 1) index.toDouble() / (groupSize - 1) else 0.5,
            metadata = MemberMetadata()
        )
    }

    @Test
    fun `LINEAR distributes offsets evenly`() {
        val strategy = DistributionStrategy.LINEAR
        val groupSize = 4

        val offsets = (0 until groupSize).map { idx ->
            strategy.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        assertEquals(0.0, offsets[0], 0.001)
        assertEquals(0.25, offsets[1], 0.01)
        assertEquals(0.5, offsets[2], 0.01)
        assertEquals(0.75, offsets[3], 0.01)
    }

    @Test
    fun `UNIFIED returns 0 for all members`() {
        val strategy = DistributionStrategy.UNIFIED
        val groupSize = 4

        val offsets = (0 until groupSize).map { idx ->
            strategy.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        assertTrue(offsets.all { it == 0.0 })
    }

    @Test
    fun `CENTER_OUT starts from center and expands outward`() {
        val strategy = DistributionStrategy.CENTER_OUT
        val groupSize = 5
        // distinctSlots(5) = 3 (center, mid, edge)

        val offsets = (0 until groupSize).map { idx ->
            strategy.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        // Center member (index 2) should have offset 0.0
        assertEquals(0.0, offsets[2], 0.001)

        // Symmetric pairs should share offsets
        assertEquals(offsets[0], offsets[4], 0.001) // edge pair
        assertEquals(offsets[1], offsets[3], 0.001) // mid pair

        // Offsets should increase from center to edge
        assertTrue(offsets[1] > offsets[2]) // mid > center
        assertTrue(offsets[0] > offsets[1]) // edge > mid
    }

    @Test
    fun `EDGES_IN starts from edges and converges to center`() {
        val strategy = DistributionStrategy.EDGES_IN
        val groupSize = 5

        val offsets = (0 until groupSize).map { idx ->
            strategy.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        // Edge members should fire first (lowest offset)
        assertEquals(0.0, offsets[0], 0.001)
        assertEquals(0.0, offsets[4], 0.001)

        // Center member should fire last (highest offset)
        assertTrue(offsets[2] > offsets[1])
        assertTrue(offsets[2] > offsets[0])

        // Symmetric pairs should share offsets
        assertEquals(offsets[0], offsets[4], 0.001)
        assertEquals(offsets[1], offsets[3], 0.001)
    }

    @Test
    fun `REVERSE is the opposite of LINEAR`() {
        val linear = DistributionStrategy.LINEAR
        val reverse = DistributionStrategy.REVERSE
        val groupSize = 4

        val linearOffsets = (0 until groupSize).map { idx ->
            linear.calculateOffset(createMember(idx, groupSize), groupSize)
        }
        val reverseOffsets = (0 until groupSize).map { idx ->
            reverse.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        // Reverse order: last member fires first, first fires last
        assertEquals(0.75, reverseOffsets[0], 0.001) // (4-1-0)/4 = 0.75
        assertEquals(0.0, reverseOffsets[3], 0.001)   // (4-1-3)/4 = 0.0

        // Reverse offsets should be linear offsets in reverse order
        assertEquals(linearOffsets[0], reverseOffsets[3], 0.001)
        assertEquals(linearOffsets[3], reverseOffsets[0], 0.001)
    }

    @Test
    fun `SPLIT divides group into mirrored halves`() {
        val strategy = DistributionStrategy.SPLIT
        val groupSize = 4

        val offsets = (0 until groupSize).map { idx ->
            strategy.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        // First and last should have same offset
        assertEquals(offsets[0], offsets[3], 0.001)
        // Second and third should have same offset
        assertEquals(offsets[1], offsets[2], 0.001)
    }

    @Test
    fun `PING_PONG uses LINEAR offsets with triangle phase`() {
        val strategy = DistributionStrategy.PING_PONG
        val groupSize = 4

        val offsets = (0 until groupSize).map { idx ->
            strategy.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        // Should match LINEAR distribution offsets
        assertEquals(0.0, offsets[0], 0.001)
        assertEquals(0.25, offsets[1], 0.001)
        assertEquals(0.5, offsets[2], 0.001)
        assertEquals(0.75, offsets[3], 0.001)

        // PING_PONG uses triangle phase remap
        assertTrue(strategy.usesTrianglePhase)
    }

    @Test
    fun `RANDOM produces deterministic values with same seed`() {
        val strategy1 = DistributionStrategy.RANDOM(42)
        val strategy2 = DistributionStrategy.RANDOM(42)
        val groupSize = 4

        val offsets1 = (0 until groupSize).map { idx ->
            strategy1.calculateOffset(createMember(idx, groupSize), groupSize)
        }
        val offsets2 = (0 until groupSize).map { idx ->
            strategy2.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        offsets1.indices.forEach { idx ->
            assertEquals(offsets1[idx], offsets2[idx], 0.001)
        }
    }

    @Test
    fun `RANDOM with different seeds produces different values`() {
        val strategy1 = DistributionStrategy.RANDOM(42)
        val strategy2 = DistributionStrategy.RANDOM(123)
        val groupSize = 4

        val offsets1 = (0 until groupSize).map { idx ->
            strategy1.calculateOffset(createMember(idx, groupSize), groupSize)
        }
        val offsets2 = (0 until groupSize).map { idx ->
            strategy2.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        // At least one should be different (statistically very likely)
        assertTrue(offsets1 != offsets2)
    }

    @Test
    fun `POSITIONAL uses normalized position directly`() {
        val strategy = DistributionStrategy.POSITIONAL
        val groupSize = 4

        val offsets = (0 until groupSize).map { idx ->
            val member = createMember(idx, groupSize)
            strategy.calculateOffset(member, groupSize)
        }

        // Should match normalized positions exactly
        assertEquals(0.0, offsets[0], 0.001)
        assertEquals(0.333, offsets[1], 0.01)
        assertEquals(0.666, offsets[2], 0.01)
        assertEquals(1.0, offsets[3], 0.001)
    }

    /**
     * [DistributionStrategy.offsets] is the whole-list form the FX tick path uses, and the
     * default implementation just maps [DistributionStrategy.calculateOffset]. [RANDOM]
     * overrides it — its per-member form derives an O(n) permutation, so asking n times was
     * O(n²) — and the two must not be allowed to drift apart.
     */
    @Test
    fun `offsets agrees with calculateOffset for every strategy`() {
        val strategies = listOf(
            DistributionStrategy.LINEAR,
            DistributionStrategy.UNIFIED,
            DistributionStrategy.CENTER_OUT,
            DistributionStrategy.EDGES_IN,
            DistributionStrategy.REVERSE,
            DistributionStrategy.SPLIT,
            DistributionStrategy.PING_PONG,
            DistributionStrategy.POSITIONAL,
            DistributionStrategy.RANDOM(42),
        )

        for (groupSize in listOf(1, 2, 7, 16)) {
            val members = (0 until groupSize).map { createMember(it, groupSize) }
            for (strategy in strategies) {
                assertEquals(
                    members.map { strategy.calculateOffset(it, groupSize) },
                    strategy.offsets(members, groupSize).toList(),
                    "$strategy disagrees with itself at groupSize=$groupSize",
                )
            }
        }
    }

    /**
     * [RANDOM] memoises the last permutation it derived. The memo is keyed on group size and
     * lives outside the data class's identity, so it must neither serve one size's permutation
     * for another nor make two `RANDOM(seed)` instances unequal — the FX plan cache compares
     * strategies by equality and would rebuild every tick if they were.
     */
    @Test
    fun `RANDOM memoises per group size without changing identity`() {
        val strategy = DistributionStrategy.RANDOM(7)
        val small = (0 until 4).map { createMember(it, 4) }
        val large = (0 until 9).map { createMember(it, 9) }

        val smallFirst = strategy.offsets(small, 4).toList()
        val largeOffsets = strategy.offsets(large, 9).toList()
        val smallAgain = strategy.offsets(small, 4).toList()

        assertEquals(smallFirst, smallAgain, "switching size and back must give the same offsets")
        assertEquals(smallFirst, DistributionStrategy.RANDOM(7).offsets(small, 4).toList())
        assertEquals(4, smallFirst.toSet().size, "a permutation gives every member a distinct slot")
        assertEquals(9, largeOffsets.toSet().size)
        assertEquals(DistributionStrategy.RANDOM(7), strategy, "the memo is not part of equality")
        assertEquals(DistributionStrategy.RANDOM(7).hashCode(), strategy.hashCode())
    }

    @Test
    fun `fromName returns correct strategy`() {
        assertEquals(DistributionStrategy.LINEAR, DistributionStrategy.fromName("LINEAR"))
        assertEquals(DistributionStrategy.UNIFIED, DistributionStrategy.fromName("UNIFIED"))
        assertEquals(DistributionStrategy.CENTER_OUT, DistributionStrategy.fromName("CENTER_OUT"))
        assertEquals(DistributionStrategy.EDGES_IN, DistributionStrategy.fromName("EDGES_IN"))
        assertEquals(DistributionStrategy.REVERSE, DistributionStrategy.fromName("REVERSE"))
        assertEquals(DistributionStrategy.SPLIT, DistributionStrategy.fromName("SPLIT"))
        assertEquals(DistributionStrategy.PING_PONG, DistributionStrategy.fromName("PING_PONG"))
        assertEquals(DistributionStrategy.POSITIONAL, DistributionStrategy.fromName("POSITIONAL"))
    }

    @Test
    fun `fromName is case insensitive`() {
        assertEquals(DistributionStrategy.LINEAR, DistributionStrategy.fromName("linear"))
        assertEquals(DistributionStrategy.CENTER_OUT, DistributionStrategy.fromName("center_out"))
        assertEquals(DistributionStrategy.PING_PONG, DistributionStrategy.fromName("Ping_Pong"))
    }

    @Test
    fun `fromName defaults to LINEAR for unknown names`() {
        assertEquals(DistributionStrategy.LINEAR, DistributionStrategy.fromName("unknown"))
        assertEquals(DistributionStrategy.LINEAR, DistributionStrategy.fromName(""))
    }

    @Test
    fun `availableStrategies contains all named strategies`() {
        val available = DistributionStrategy.availableStrategies

        assertTrue(available.contains("LINEAR"))
        assertTrue(available.contains("UNIFIED"))
        assertTrue(available.contains("CENTER_OUT"))
        assertTrue(available.contains("EDGES_IN"))
        assertTrue(available.contains("REVERSE"))
        assertTrue(available.contains("SPLIT"))
        assertTrue(available.contains("PING_PONG"))
        assertTrue(available.contains("POSITIONAL"))
    }

    @Test
    fun `all offsets are within 0 to 1 range`() {
        val strategies = listOf(
            DistributionStrategy.LINEAR,
            DistributionStrategy.UNIFIED,
            DistributionStrategy.CENTER_OUT,
            DistributionStrategy.EDGES_IN,
            DistributionStrategy.REVERSE,
            DistributionStrategy.SPLIT,
            DistributionStrategy.PING_PONG,
            DistributionStrategy.POSITIONAL,
            DistributionStrategy.RANDOM(42)
        )
        val groupSize = 8

        strategies.forEach { strategy ->
            (0 until groupSize).forEach { idx ->
                val offset = strategy.calculateOffset(createMember(idx, groupSize), groupSize)
                assertTrue(offset >= 0.0, "Offset should be >= 0 for $strategy at index $idx")
                assertTrue(offset <= 1.0, "Offset should be <= 1 for $strategy at index $idx")
            }
        }
    }

    @Test
    fun `single member group handles edge cases`() {
        val strategies = listOf(
            DistributionStrategy.LINEAR,
            DistributionStrategy.UNIFIED,
            DistributionStrategy.CENTER_OUT,
            DistributionStrategy.EDGES_IN,
            DistributionStrategy.PING_PONG,
            DistributionStrategy.POSITIONAL
        )

        strategies.forEach { strategy ->
            val offset = strategy.calculateOffset(createMember(0, 1), 1)
            assertTrue(offset >= 0.0 && offset <= 1.0, "$strategy should handle single member")
        }
    }
}
