package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType

@FixtureType("scantastic")
class ScantasticFixture (
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 17, key, fixtureName, position) {
    private constructor(
        fixture: ScantasticFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        fixture.position,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): ScantasticFixture = ScantasticFixture(this, transaction)

    enum class OnMode(override val level: UByte): DmxFixtureSettingValue {
        BLACKOUT(0u),
        FULL_ON(128u),
    }

    enum class Mode(override val level: UByte): DmxFixtureSettingValue {
        BLACKOUT(0u),
        SOUND_ACTIVE(128u),
    }

    @FixtureProperty
    val onMode = DmxFixtureSetting(transaction, universe, firstChannel, OnMode.entries.toTypedArray())

    @FixtureProperty
    val mode = DmxFixtureSetting(transaction, universe, firstChannel + 16, Mode.entries.toTypedArray())
}
