package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.trait.WithDimmer

/**
 * Single-channel dimmer fixture.
 *
 * Covers all conventional non-DMX-native patches in one class: faces, fresnels, profiles,
 * PARs, single-colour LED rings, house lights, non-dim circuits, and 1-channel hazers. The
 * specific role is carried by the patch row's `key`/`displayName`, not the class.
 */
@FixtureType("generic-dimmer", manufacturer = "Generic", model = "Single-channel dimmer")
class GenericDimmerFixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 1, key, fixtureName),
    WithDimmer
{
    private constructor(
        fixture: GenericDimmerFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): GenericDimmerFixture =
        GenericDimmerFixture(this, transaction)

    @FixtureProperty(category = PropertyCategory.DIMMER)
    override val dimmer = DmxSlider(transaction, universe, firstChannel)
}
