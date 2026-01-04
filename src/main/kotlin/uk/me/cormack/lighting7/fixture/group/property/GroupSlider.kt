package uk.me.cormack.lighting7.fixture.group.property

import uk.me.cormack.lighting7.fixture.FixtureTarget
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.property.AggregateSlider
import uk.me.cormack.lighting7.fixture.property.Slider

/**
 * Group implementation of AggregateSlider that aggregates slider operations across group members.
 *
 * This class provides unified slider access for fixture groups, implementing the same
 * interface as single-fixture sliders but operating on all group members.
 *
 * @param T The fixture/target type contained in the group
 * @param group The fixture group to aggregate over
 * @param sliderAccessor Function to extract the slider from each member
 */
class GroupSlider<T : FixtureTarget>(
    private val group: FixtureGroup<T>,
    private val sliderAccessor: (T) -> Slider
) : AggregateSlider {

    /**
     * Get all member sliders.
     */
    private val memberSliders: List<Slider>
        get() = group.fixtures.map { sliderAccessor(it) }

    /**
     * Values from all members.
     * For nested groups, this returns the aggregate values from each child group.
     */
    override val memberValues: List<UByte?>
        get() = memberSliders.map { it.value }

    /**
     * Current value if all members are uniform, null otherwise.
     *
     * When setting, applies the value to all members.
     * Setting to null is ignored.
     */
    override var value: UByte?
        get() = if (isUniform) memberValues.firstOrNull()?.let { it } else null
        set(newValue) {
            if (newValue != null) {
                memberSliders.forEach { it.value = newValue }
            }
        }

    /**
     * Fade all members to the target value over the specified duration.
     *
     * @param value Target value to fade to
     * @param fadeMs Duration of the fade in milliseconds
     */
    override fun fadeToValue(value: UByte, fadeMs: Long) {
        memberSliders.forEach { it.fadeToValue(value, fadeMs) }
    }

    /**
     * Access a specific member's slider by index.
     *
     * @param index The member index (0-based)
     * @return The slider for the specified member
     * @throws IndexOutOfBoundsException if index is out of range
     */
    operator fun get(index: Int): Slider = sliderAccessor(group.fixtures[index])
}
