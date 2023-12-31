package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*

@FixtureType("lightstrip")
class LightstripFixture (
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 5, key, fixtureName, position), DmxFixtureWithColour {
    private constructor(
        fixture: LightstripFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        fixture.position,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): LightstripFixture = LightstripFixture(this, transaction)

    @FixtureProperty
    override val rgbColour = DmxFixtureColour(
        transaction,
        universe,
        firstChannel,
        firstChannel + 1,
        firstChannel + 2,
    )

    @FixtureProperty
    val whiteColour = DmxFixtureSlider(transaction, universe, firstChannel + 3)
}
