package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*

@FixtureType("hazer")
class HazerFixture (
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int = 0,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 2, key, fixtureName, position) {
    private constructor(
        fixture: HazerFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        fixture.position,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): HazerFixture = HazerFixture(this, transaction)

    @FixtureProperty
    val pumpControl = DmxFixtureSlider(transaction, universe, firstChannel)

    @FixtureProperty
    val fanSpeed = DmxFixtureSlider(transaction, universe, firstChannel + 1)
}
