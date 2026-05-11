package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithUv

// UV fixtures ship in many form factors (PAR cans, bars, panels) and the
// `uv` type key is intentionally generic. Leave GENERIC rather than pick a
// shape that will be wrong half the time.
@FixtureType("uv")
class UVFixture (
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    private val maxDimmerLevel: UByte = 255u,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 1, key, fixtureName),
    WithDimmer, WithUv
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
    override val dimmer = DmxSlider(transaction, universe, firstChannel, max = maxDimmerLevel)

    override val uv: Slider
        get() = dimmer
}
