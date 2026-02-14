package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture

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
 * This interface extends [GroupableFixture], enabling elements to be used in
 * fixture groups alongside standalone fixtures.
 *
 * @param P The parent fixture type
 */
interface FixtureElement<P : Fixture> : GroupableFixture {
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

    // FixtureTarget implementation
    override val targetKey: String
        get() = elementKey

    override val displayName: String
        get() = "${parentFixture.fixtureName} Element ${elementIndex + 1}"

    override val isGroup: Boolean
        get() = false

    override val memberCount: Int
        get() = 1

    override fun withTransaction(transaction: ControllerTransaction): FixtureElement<P>
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
 * @param E The element type (must extend FixtureElement)
 * @param fixture The multi-element fixture to expand
 * @param panSpread Total pan spread in degrees across all elements
 * @param tiltSpread Total tilt spread in degrees across all elements
 */
inline fun <P : Fixture, reified E : FixtureElement<P>> GroupBuilder<E>.addElements(
    fixture: MultiElementFixture<*>,
    panSpread: Double = 0.0,
    tiltSpread: Double = 0.0
) {
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
 * @param E The element type (must extend FixtureElement)
 * @param fixture The multi-element fixture to expand
 * @param panSpread Total pan spread in degrees
 * @param tiltSpread Total tilt spread in degrees
 */
inline fun <P : Fixture, reified E : FixtureElement<P>> GroupBuilder<E>.addElementsSymmetric(
    fixture: MultiElementFixture<*>,
    panSpread: Double = 0.0,
    tiltSpread: Double = 0.0
) {
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

/**
 * Get all elements of this multi-element fixture as a [FixtureGroup].
 *
 * This provides a convenient way to operate on all elements as a single unit,
 * enabling operations like:
 * - Setting all element dimmers: `fixture.elementsGroup.dimmer.value = 200u`
 * - Filtering elements: `fixture.elementsGroup.everyNth(2)`
 * - Applying effects with distribution across elements
 *
 * Elements are automatically indexed with normalized positions (0.0-1.0)
 * and tagged with "element" and "element-N" for filtering.
 *
 * The group name is derived from the fixture key as "{fixture-key}-elements".
 *
 * @return A FixtureGroup containing all elements with proper positioning
 */
val <F, E> F.elementsGroup: FixtureGroup<E>
    where F : Fixture, F : MultiElementFixture<E>, E : FixtureElement<*>
    get() {
        val count = elements.size
        return FixtureGroup(
            name = "$key-elements",
            members = elements.mapIndexed { idx, element ->
                GroupMember(
                    fixture = element,
                    index = idx,
                    normalizedPosition = if (count > 1) idx.toDouble() / (count - 1) else 0.5,
                    metadata = MemberMetadata(tags = setOf("element", "element-$idx"))
                )
            }
        )
    }
