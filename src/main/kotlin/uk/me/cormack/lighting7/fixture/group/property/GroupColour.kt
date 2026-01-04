package uk.me.cormack.lighting7.fixture.group.property

import uk.me.cormack.lighting7.fixture.FixtureTarget
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.property.AggregateColour
import uk.me.cormack.lighting7.fixture.property.AggregateSlider
import uk.me.cormack.lighting7.fixture.property.Colour
import java.awt.Color

/**
 * Group implementation of AggregateColour that aggregates colour operations across group members.
 *
 * This class provides unified colour access for fixture groups, implementing the same
 * interface as single-fixture colours but operating on all group members.
 *
 * @param T The fixture/target type contained in the group
 * @param group The fixture group to aggregate over
 * @param colourAccessor Function to extract the colour from each member
 */
class GroupColour<T : FixtureTarget>(
    private val group: FixtureGroup<T>,
    private val colourAccessor: (T) -> Colour
) : AggregateColour {

    /**
     * Get all member colours.
     */
    private val memberColours: List<Colour>
        get() = group.fixtures.map { colourAccessor(it) }

    /**
     * Red channel as aggregate slider.
     */
    override val redSlider: AggregateSlider by lazy {
        GroupSlider(group) { colourAccessor(it).redSlider }
    }

    /**
     * Green channel as aggregate slider.
     */
    override val greenSlider: AggregateSlider by lazy {
        GroupSlider(group) { colourAccessor(it).greenSlider }
    }

    /**
     * Blue channel as aggregate slider.
     */
    override val blueSlider: AggregateSlider by lazy {
        GroupSlider(group) { colourAccessor(it).blueSlider }
    }

    /**
     * Colours from all members.
     * For nested groups, this returns the aggregate colours from each child group.
     */
    override val memberValues: List<Color?>
        get() = memberColours.map { it.value }

    /**
     * Current colour if all members are uniform, null otherwise.
     *
     * When setting, applies the colour to all members.
     * Setting to null is ignored.
     */
    override var value: Color?
        get() = if (isUniform) memberValues.firstOrNull()?.let { it } else null
        set(newValue) {
            if (newValue != null) {
                memberColours.forEach { it.value = newValue }
            }
        }

    /**
     * Fade all members to the target colour over the specified duration.
     *
     * @param colour Target colour to fade to
     * @param fadeMs Duration of the fade in milliseconds
     */
    override fun fadeToColour(colour: Color, fadeMs: Long) {
        memberColours.forEach { it.fadeToColour(colour, fadeMs) }
    }

    /**
     * Access a specific member's colour by index.
     *
     * @param index The member index (0-based)
     * @return The colour for the specified member
     * @throws IndexOutOfBoundsException if index is out of range
     */
    operator fun get(index: Int): Colour = colourAccessor(group.fixtures[index])
}
