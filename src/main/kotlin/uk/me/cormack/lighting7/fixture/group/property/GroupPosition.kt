package uk.me.cormack.lighting7.fixture.group.property

import uk.me.cormack.lighting7.fixture.FixtureTarget
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.property.AggregatePosition
import uk.me.cormack.lighting7.fixture.property.AggregateSlider
import uk.me.cormack.lighting7.fixture.property.Slider

/**
 * Group implementation of AggregatePosition that aggregates position operations across group members.
 *
 * This class provides unified pan/tilt access for fixture groups, implementing the same
 * interface as single-fixture positions but operating on all group members.
 *
 * @param T The fixture/target type contained in the group
 * @param group The fixture group to aggregate over
 * @param panAccessor Function to extract the pan slider from each member
 * @param tiltAccessor Function to extract the tilt slider from each member
 */
class GroupPosition<T : FixtureTarget>(
    private val group: FixtureGroup<T>,
    private val panAccessor: (T) -> Slider,
    private val tiltAccessor: (T) -> Slider
) : AggregatePosition {

    /**
     * Pan as aggregate slider with member values.
     */
    override val pan: AggregateSlider by lazy {
        GroupSlider(group, panAccessor)
    }

    /**
     * Tilt as aggregate slider with member values.
     */
    override val tilt: AggregateSlider by lazy {
        GroupSlider(group, tiltAccessor)
    }
}
