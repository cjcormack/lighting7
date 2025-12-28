package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.fixture.Fixture

/**
 * Represents a single controllable element within a multi-element fixture.
 *
 * Fixture elements are sub-units of a larger fixture that can be controlled
 * independently. For example, a quad moving head bar has 4 heads, each with
 * its own pan, tilt, color, and dimmer that can be addressed separately.
 *
 * Elements should implement the relevant capability traits (FixtureWithDimmer,
 * FixtureWithColour, etc.) to enable type-safe effect application.
 *
 * Elements are typically inner classes of the parent fixture and share the
 * parent's transaction context and DMX controller.
 *
 * @param P The parent fixture type
 */
interface FixtureElement<P : Fixture> {
    /**
     * The parent fixture containing this element.
     */
    val parentFixture: P

    /**
     * Zero-based index of this element within the parent.
     */
    val elementIndex: Int

    /**
     * Unique key for this element, typically "parent-key.element-N".
     *
     * This key can be used to identify the element in the fixture registry
     * and for FX targeting.
     */
    val elementKey: String
        get() = "${parentFixture.key}.element-$elementIndex"
}

/**
 * Marker interface for fixtures that contain multiple independently
 * controllable elements.
 *
 * Multi-element fixtures are physical units that contain several
 * separate controllable sub-units. Examples include:
 * - Quad moving head bars (4 independent moving heads)
 * - LED bars with multiple segments
 * - Multi-cell wash lights
 *
 * The elements can be added to fixture groups individually, allowing
 * chase effects and other distributed effects to work across the
 * sub-units of a single physical fixture.
 *
 * Example implementation:
 * ```kotlin
 * @FixtureType("quad-mover-bar")
 * class QuadMoverBarFixture(...) : DmxFixture(...),
 *     MultiElementFixture<QuadMoverBarFixture.Head>,
 *     FixtureWithDimmer  // Master dimmer
 * {
 *     inner class Head(override val elementIndex: Int) :
 *         FixtureElement<QuadMoverBarFixture>,
 *         FixtureWithDimmer,
 *         FixtureWithColour<DmxFixtureColour>,
 *         FixtureWithPosition
 *     {
 *         override val parentFixture get() = this@QuadMoverBarFixture
 *         // ... implement traits ...
 *     }
 *
 *     override val elements = (0 until 4).map { Head(it) }
 *     override val elementCount = 4
 * }
 * ```
 *
 * @param E The element type
 */
interface MultiElementFixture<E : FixtureElement<*>> {
    /**
     * The list of controllable elements in this fixture.
     */
    val elements: List<E>

    /**
     * The number of elements in this fixture.
     */
    val elementCount: Int
        get() = elements.size
}

/**
 * Extension to add all elements from a multi-element fixture to a group builder.
 *
 * This expands the fixture's elements as individual group members with
 * automatic positioning based on their element index.
 *
 * @param P The parent fixture type
 * @param E The element type (must also be the group's fixture type)
 * @param fixture The multi-element fixture to expand
 * @param panSpread Total pan spread in degrees across all elements
 * @param tiltSpread Total tilt spread in degrees across all elements
 */
@Suppress("UNCHECKED_CAST")
inline fun <P : Fixture, reified E> GroupBuilder<E>.addElements(
    fixture: MultiElementFixture<*>,
    panSpread: Double = 0.0,
    tiltSpread: Double = 0.0
) where E : Fixture, E : FixtureElement<P> {
    val elements = fixture.elements.filterIsInstance<E>()
    val count = elements.size

    elements.forEachIndexed { idx, element ->
        val position = if (count > 1) idx.toDouble() / (count - 1) else 0.5
        add(
            element,
            panOffset = (position - 0.5) * panSpread,
            tiltOffset = (position - 0.5) * tiltSpread,
            tags = setOf("element", "element-$idx")
        )
    }
}

/**
 * Extension to add elements from a multi-element fixture to a group builder
 * with symmetric positioning.
 *
 * @param P The parent fixture type
 * @param E The element type (must also be the group's fixture type)
 * @param fixture The multi-element fixture to expand
 * @param panSpread Total pan spread in degrees
 * @param tiltSpread Total tilt spread in degrees
 */
@Suppress("UNCHECKED_CAST")
inline fun <P : Fixture, reified E> GroupBuilder<E>.addElementsSymmetric(
    fixture: MultiElementFixture<*>,
    panSpread: Double = 0.0,
    tiltSpread: Double = 0.0
) where E : Fixture, E : FixtureElement<P> {
    val elements = fixture.elements.filterIsInstance<E>()
    val count = elements.size

    elements.forEachIndexed { idx, element ->
        val position = if (count > 1) idx.toDouble() / (count - 1) else 0.5
        val isRightSide = position > 0.5
        add(
            element,
            panOffset = (position - 0.5) * panSpread,
            tiltOffset = (position - 0.5) * tiltSpread,
            symmetricInvert = isRightSide,
            tags = setOf("element", "element-$idx")
        )
    }
}
