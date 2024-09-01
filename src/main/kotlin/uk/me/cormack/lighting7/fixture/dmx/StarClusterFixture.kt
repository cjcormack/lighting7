package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType

@FixtureType("starcluster")
class StarClusterFixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 5, key, fixtureName, position) {
    private constructor(
        fixture: StarClusterFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        fixture.position,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): StarClusterFixture = StarClusterFixture(this, transaction)

    enum class DmxMode(override val level: UByte): DmxFixtureSettingValue {
        OFF(0u),
        AUTO_SHOW(51u),
        SOUND_ACTIVE(101u),
        DMX_MODE_1(151u),
        DMX_MODE_2(201u),
    }

    @FixtureProperty
    val dmxMode = DmxFixtureSetting(transaction, universe, firstChannel, DmxMode.entries.toTypedArray())
}
