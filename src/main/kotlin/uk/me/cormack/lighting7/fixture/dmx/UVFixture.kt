package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureSlider
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.FixtureWithDimmer
import uk.me.cormack.lighting7.fixture.FixtureWithUv
import uk.me.cormack.lighting7.fixture.PropertyCategory

@FixtureType("uv")
class UVFixture (
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    private val maxDimmerLevel: UByte = 255u,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 1, key, fixtureName),
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
        fixture.maxDimmerLevel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): UVFixture = UVFixture(this, transaction)

    @FixtureProperty(category = PropertyCategory.UV)
    override val dimmer = DmxFixtureSlider(transaction, universe, firstChannel, max = maxDimmerLevel)

    override val uvColour: FixtureSlider
        get() = dimmer
}
