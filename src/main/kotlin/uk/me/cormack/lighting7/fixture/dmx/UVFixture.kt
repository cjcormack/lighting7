package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*

@FixtureType("uv")
class UVFixture (
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    private val maxDimmerLevel: UByte = 255u,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 1, key, fixtureName, position),
    FixtureWithDimmer, FixtureWithUv
{
    private constructor(
        fixture: UVFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        fixture.position,
        fixture.maxDimmerLevel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): UVFixture = UVFixture(this, transaction)

    @FixtureProperty
    override val dimmer = DmxFixtureSlider(transaction, universe, firstChannel, max = maxDimmerLevel)

    override val uvColour: FixtureSlider
        get() = dimmer
}
