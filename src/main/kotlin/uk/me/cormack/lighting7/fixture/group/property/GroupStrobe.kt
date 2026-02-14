package uk.me.cormack.lighting7.fixture.group.property

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.property.AggregateStrobe
import uk.me.cormack.lighting7.fixture.property.Strobe

/**
 * Group implementation of AggregateStrobe that aggregates strobe operations across group members.
 *
 * This class provides unified strobe access for fixture groups, implementing the same
 * interface as single-fixture strobes but operating on all group members.
 *
 * @param T The fixture/target type contained in the group
 * @param group The fixture group to aggregate over
 * @param strobeAccessor Function to extract the strobe from each member
 */
class GroupStrobe<T : GroupableFixture>(
    private val group: FixtureGroup<T>,
    private val strobeAccessor: (T) -> Strobe
) : AggregateStrobe {

    /**
     * Get all member strobes.
     */
    private val memberStrobes: List<Strobe>
        get() = group.fixtures.map { strobeAccessor(it) }

    /**
     * Number of members in this aggregate.
     */
    override val memberCount: Int
        get() = group.size

    /**
     * Set all members to full on mode (no strobing).
     */
    override fun fullOn() {
        memberStrobes.forEach { it.fullOn() }
    }

    /**
     * Enable strobing at the specified intensity/speed on all members.
     *
     * @param intensity Strobe intensity/speed
     */
    override fun strobe(intensity: UByte) {
        memberStrobes.forEach { it.strobe(intensity) }
    }

    /**
     * Access a specific member's strobe by index.
     *
     * @param index The member index (0-based)
     * @return The strobe for the specified member
     * @throws IndexOutOfBoundsException if index is out of range
     */
    operator fun get(index: Int): Strobe = strobeAccessor(group.fixtures[index])
}
