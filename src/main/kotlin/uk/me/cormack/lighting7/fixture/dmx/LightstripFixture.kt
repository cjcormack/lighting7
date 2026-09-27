package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithWhite

/**
 * A run of RGBW LED tape on one 5-channel controller: the whole run is one colour. Its length is
 * whatever it was cut to on the install — a ring round the stage edge, a line under a riser — so
 * the type takes a per-patch length ([FixtureType.acceptsLength]) rather than declaring one, and a
 * run laid in segments (four sides of a ring) is one patch with an extra placement per segment,
 * each carrying its own length.
 */
@FixtureType("lightstrip", kind = FixtureKind.STRIP, acceptsLength = true)
class LightstripFixture (
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 5, key, fixtureName), WithColour, WithWhite {
    private constructor(
        fixture: LightstripFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): LightstripFixture = LightstripFixture(this, transaction)

    @FixtureProperty(category = PropertyCategory.COLOUR)
    override val rgbColour = DmxColour(
        transaction,
        universe,
        firstChannel,
        firstChannel + 1,
        firstChannel + 2,
    )

    @FixtureProperty(category = PropertyCategory.WHITE, bundleWithColour = true)
    override val white = DmxSlider(transaction, universe, firstChannel + 3)
}
