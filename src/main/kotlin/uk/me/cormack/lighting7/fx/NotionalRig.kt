package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureAttribute
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.PanTiltAxis
import uk.me.cormack.lighting7.fixture.attribute.Slider
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithUv
import uk.me.cormack.lighting7.show.Fixtures

/**
 * Heads the rig does not have, for drawing an FX before there is a selection to draw it on (FX editor
 * plan D15): the FX palette editor's *On eight heads*. A scratch register of [count] [NotionalHead]s
 * in one group, never patched, with no controller, so nothing it holds can reach the wire. The
 * preview route runs a draft over it through an [FxEngine] of its own, so the members, the spread and
 * the fold are the engine's (P3).
 */
internal class NotionalRig(val count: Int) {
    val fixtures = Fixtures()

    init {
        require(count in 1..FxSampler.MAX_ROWS) { "notional: $count is not between 1 and ${FxSampler.MAX_ROWS}" }
        val universe = Universe(0, 0)
        fixtures.register {
            val heads = (1..count).map { i ->
                addFixture(NotionalHead(universe, "$KEY_PREFIX$i", "Head $i", (i - 1) * NotionalHead.CHANNELS + 1))
            }
            createGroup<GroupableFixture>(GROUP) { addSpread(heads) }
        }
    }

    companion object {
        /** The group every notional draft targets. */
        const val GROUP = "notional"
        const val KEY_PREFIX = "notional-"
    }
}

/**
 * One notional head: every attribute an FX palette can drive, a channel each. Its type is not in
 * `FixtureTypeRegistry`'s list, so no patch can name it.
 */
@FixtureType("notional-head", model = "Notional head", kind = FixtureKind.MOVING_HEAD)
internal class NotionalHead(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, CHANNELS, key, fixtureName), WithDimmer, WithColour, WithUv, WithPosition {
    private constructor(head: NotionalHead, transaction: ControllerTransaction) :
        this(head.universe, head.key, head.fixtureName, head.firstChannel, transaction)

    override fun withTransaction(transaction: ControllerTransaction): NotionalHead = NotionalHead(this, transaction)

    @FixtureAttribute(category = AttributeCategory.DIMMER)
    override val dimmer = DmxSlider(transaction, universe, firstChannel)

    @FixtureAttribute(category = AttributeCategory.COLOUR)
    override val rgbColour = DmxColour(transaction, universe, firstChannel + 1, firstChannel + 2, firstChannel + 3)

    @FixtureAttribute(category = AttributeCategory.UV)
    override val uv = DmxSlider(transaction, universe, firstChannel + 4)

    @FixtureAttribute(category = AttributeCategory.PAN, axis = PanTiltAxis.PAN)
    override val pan: Slider = DmxSlider(transaction, universe, firstChannel + 5)

    @FixtureAttribute(category = AttributeCategory.TILT, axis = PanTiltAxis.TILT)
    override val tilt: Slider = DmxSlider(transaction, universe, firstChannel + 6)

    @FixtureAttribute(category = AttributeCategory.ZOOM)
    val zoom: Slider = DmxSlider(transaction, universe, firstChannel + 7)

    @FixtureAttribute(category = AttributeCategory.FOCUS)
    val focus: Slider = DmxSlider(transaction, universe, firstChannel + 8)

    @FixtureAttribute(category = AttributeCategory.IRIS)
    val iris: Slider = DmxSlider(transaction, universe, firstChannel + 9)

    @FixtureAttribute(category = AttributeCategory.FROST)
    val frost: Slider = DmxSlider(transaction, universe, firstChannel + 10)

    companion object {
        const val CHANNELS = 11
    }
}
