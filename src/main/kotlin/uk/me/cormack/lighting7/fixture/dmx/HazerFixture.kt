package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.CompactDisplayRole
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.PropertyCategory

@FixtureType("hazer")
class HazerFixture (
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 2, key, fixtureName) {
    private constructor(
        fixture: HazerFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): HazerFixture = HazerFixture(this, transaction)

    @FixtureProperty(category = PropertyCategory.OTHER, compactDisplay = CompactDisplayRole.SECONDARY)
    val pumpControl = DmxSlider(transaction, universe, firstChannel)

    @FixtureProperty(category = PropertyCategory.SPEED, compactDisplay = CompactDisplayRole.PRIMARY)
    val fanSpeed = DmxSlider(transaction, universe, firstChannel + 1)
}
