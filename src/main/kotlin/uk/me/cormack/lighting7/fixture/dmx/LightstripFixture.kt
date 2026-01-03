package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.PropertyCategory

@FixtureType("lightstrip")
class LightstripFixture (
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 5, key, fixtureName), DmxFixtureWithColour {
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
    override val rgbColour = DmxFixtureColour(
        transaction,
        universe,
        firstChannel,
        firstChannel + 1,
        firstChannel + 2,
    )

    @FixtureProperty(category = PropertyCategory.WHITE, bundleWithColour = true)
    val whiteColour = DmxFixtureSlider(transaction, universe, firstChannel + 3)
}
