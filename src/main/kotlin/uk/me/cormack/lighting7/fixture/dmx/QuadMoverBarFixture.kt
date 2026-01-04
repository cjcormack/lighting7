package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition

/**
 * Example multi-element fixture: a bar with 4 independent moving heads.
 *
 * This fixture demonstrates how to implement [MultiElementFixture] to expose
 * individual elements that can be controlled separately or as a group.
 *
 * Each head has:
 * - Dimmer (brightness control)
 * - RGB colour
 * - Pan/Tilt position
 *
 * The fixture also has a master dimmer that affects all heads.
 *
 * DMX Channel Layout (32 channels total):
 * - Channel 0: Master dimmer
 * - Heads 1-4 (7 channels each, starting at channels 1, 8, 15, 22):
 *   - +0: Head dimmer
 *   - +1: Red
 *   - +2: Green
 *   - +3: Blue
 *   - +4: Pan
 *   - +5: Pan fine
 *   - +6: Tilt
 *
 * Note: This is an example fixture. Actual channel layouts vary by manufacturer.
 */
@FixtureType("quad-mover-bar", manufacturer = "Example", model = "Quad Mover Bar")
class QuadMoverBarFixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 29, key, fixtureName),
    WithDimmer,
    MultiElementFixture<QuadMoverBarFixture.Head> {

    private constructor(
        fixture: QuadMoverBarFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): QuadMoverBarFixture =
        QuadMoverBarFixture(this, transaction)

    /**
     * A single moving head within the quad bar.
     *
     * Each head is an independent unit with its own dimmer, colour, and position
     * controls. Heads can be added to fixture groups individually.
     *
     * Note: Head does not extend Fixture (which is sealed) but implements the
     * relevant trait interfaces for direct control.
     */
    inner class Head(
        override val elementIndex: Int,
        private val headTransaction: ControllerTransaction?
    ) : FixtureElement<QuadMoverBarFixture>,
        WithDimmer,
        WithColour,
        WithPosition {

        override val parentFixture: QuadMoverBarFixture
            get() = this@QuadMoverBarFixture

        override val elementKey: String
            get() = "${this@QuadMoverBarFixture.key}.head-$elementIndex"

        /** Display name for this head */
        val headName: String = "$fixtureName Head ${elementIndex + 1}"

        private val headFirstChannel = firstChannel + 1 + (elementIndex * 7)

        @FixtureProperty("Head dimmer", category = PropertyCategory.DIMMER)
        override val dimmer = DmxSlider(headTransaction, universe, headFirstChannel)

        @FixtureProperty("Head RGB colour", category = PropertyCategory.COLOUR)
        override val rgbColour = DmxColour(
            headTransaction,
            universe,
            headFirstChannel + 1,  // Red
            headFirstChannel + 2,  // Green
            headFirstChannel + 3,  // Blue
        )

        @FixtureProperty("Head pan (horizontal)", category = PropertyCategory.POSITION)
        override val pan = DmxSlider(headTransaction, universe, headFirstChannel + 4)

        @FixtureProperty("Head pan fine", category = PropertyCategory.POSITION)
        val panFine = DmxSlider(headTransaction, universe, headFirstChannel + 5)

        @FixtureProperty("Head tilt (vertical)", category = PropertyCategory.POSITION)
        override val tilt = DmxSlider(headTransaction, universe, headFirstChannel + 6)

        /** Create a copy of this head bound to a transaction */
        override fun withTransaction(transaction: ControllerTransaction): Head =
            Head(elementIndex, transaction)

        /** Set all head values to black/off */
        fun blackout() {
            dimmer.value = 0u
            rgbColour.value = java.awt.Color.BLACK
        }

        override fun toString(): String = "Head($elementKey)"
    }

    /** Master dimmer affecting all heads */
    @FixtureProperty("Master dimmer", category = PropertyCategory.DIMMER)
    override val dimmer = DmxSlider(transaction, universe, firstChannel)

    /** The four individual heads */
    override val elements: List<Head> = (0 until 4).map { Head(it, transaction) }

    override val elementCount: Int = 4

    /**
     * Get a specific head by index (0-3).
     */
    fun head(index: Int): Head {
        require(index in 0 until elementCount) { "Head index must be 0-3, got $index" }
        return elements[index]
    }

    /**
     * Apply the same dimmer value to all heads.
     */
    fun setAllHeadsDimmer(value: UByte) {
        elements.forEach { it.dimmer.value = value }
    }

    /**
     * Apply the same colour to all heads.
     */
    fun setAllHeadsColour(colour: java.awt.Color) {
        elements.forEach { it.rgbColour.value = colour }
    }

    override fun blackout() {
        super.blackout()
        elements.forEach { it.blackout() }
    }

    override fun toString(): String = "QuadMoverBarFixture($key, ${elements.size} heads)"
}
