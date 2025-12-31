package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.FixtureWithDimmer
import uk.me.cormack.lighting7.fixture.PropertyCategory

@FixtureType("quadbar")
class QuadBarFixture (
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 1, key, fixtureName, position) {
    private constructor(
        fixture: QuadBarFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        fixture.position,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): QuadBarFixture = QuadBarFixture(this, transaction)

    enum class ShowMode(override val level: UByte): DmxFixtureSettingValue {
        OFF(0u),
        MOVEMENT_1(8u),
        MOVEMENT_2(23u),
        MOVEMENT_3(38u),
        MOVEMENT_4(53u),
        MOVEMENT_5(68u),
        MOVEMENT_6(83u),
        MOVEMENT_7(98u),
        MOVEMENT_8(113u),
        MOVEMENT_9(128u),
        MOVEMENT_10(143u),
        MOVEMENT_11(158u),
        MOVEMENT_12(173u),
        MOVEMENT_13(188u),
        MOVEMENT_14(203u),
        MOVEMENT_15(218u),
        MOVEMENT_016(233u),
        SOUND_ACTIVE(248u),
    }

    @FixtureProperty(category = PropertyCategory.SETTING)
    val showMode = DmxFixtureSetting(transaction, universe, firstChannel, ShowMode.entries.toTypedArray())
}
