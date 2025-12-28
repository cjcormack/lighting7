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
        val fixture = UVFixture(testUniverse, "test-$index", "Test $index", index, index)
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

        val offsets = (0 until groupSize).map { idx ->
            strategy.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        // Center member (index 2) should have offset 0.0
        assertEquals(0.0, offsets[2], 0.001)

        // Edge members should have offset 1.0
        assertEquals(1.0, offsets[0], 0.001)
        assertEquals(1.0, offsets[4], 0.001)

        // Middle members should be between
        assertTrue(offsets[1] > 0.0 && offsets[1] < 1.0)
        assertTrue(offsets[3] > 0.0 && offsets[3] < 1.0)
    }

    @Test
    fun `EDGES_IN starts from edges and converges to center`() {
        val strategy = DistributionStrategy.EDGES_IN
        val groupSize = 5

        val offsets = (0 until groupSize).map { idx ->
            strategy.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        // Edge members should have offset 0.0
        assertEquals(0.0, offsets[0], 0.001)
        assertEquals(0.0, offsets[4], 0.001)

        // Center member should have offset 1.0
        assertEquals(1.0, offsets[2], 0.001)
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

        // Reverse should be 1 - linear
        assertEquals(1.0, reverseOffsets[0], 0.001)
        assertEquals(0.25, reverseOffsets[3], 0.001)
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
    fun `PING_PONG uses normalized position`() {
        val strategy = DistributionStrategy.PING_PONG
        val groupSize = 4

        val offsets = (0 until groupSize).map { idx ->
            strategy.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        // Should match normalized positions
        assertEquals(0.0, offsets[0], 0.001)
        assertEquals(1.0, offsets[3], 0.001)
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

    @Test
    fun `CUSTOM allows arbitrary offset calculation`() {
        // Custom: invert the position
        val strategy = DistributionStrategy.CUSTOM { position ->
            1.0 - position
        }
        val groupSize = 4

        val offsets = (0 until groupSize).map { idx ->
            strategy.calculateOffset(createMember(idx, groupSize), groupSize)
        }

        assertEquals(1.0, offsets[0], 0.001)
        assertEquals(0.0, offsets[3], 0.001)
    }

    @Test
    fun `CUSTOM clamps values to 0-1 range`() {
        val strategy = DistributionStrategy.CUSTOM { _ -> 5.0 }
        val member = createMember(0, 1)
        val offset = strategy.calculateOffset(member, 1)

        assertEquals(1.0, offset) // Clamped from 5.0
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
