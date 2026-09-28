package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.trait.WithColour

/**
 * A run of RGB LED tape on a 3-channel controller: red, green, blue, and nothing else — no white
 * and no master dimmer, so brightness is the colour's own level. The whole run is one colour.
 *
 * The RGB sibling of [LightstripFixture], and a separate type rather than a mode of it: the two
 * are different controllers, not one unit set by a DIP switch. Like it, the length is cut to the
 * install, so it takes a per-patch length ([FixtureType.acceptsLength]).
 */
@FixtureType(
    "lightstrip-rgb",
    manufacturer = "Generic",
    model = "RGB lightstrip",
    kind = FixtureKind.STRIP,
    acceptsLength = true,
)
class LightstripRgbFixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 3, key, fixtureName), WithColour {
    private constructor(
        fixture: LightstripRgbFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): LightstripRgbFixture =
        LightstripRgbFixture(this, transaction)

    @FixtureProperty(category = PropertyCategory.COLOUR)
    override val rgbColour = DmxColour(
        transaction,
        universe,
        firstChannel,
        firstChannel + 1,
        firstChannel + 2,
    )
}
