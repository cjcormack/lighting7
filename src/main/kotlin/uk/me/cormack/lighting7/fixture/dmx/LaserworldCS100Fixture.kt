package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.PropertyCategory

@FixtureType("laserword_cs100")
class LaserworldCS100Fixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 13, key, fixtureName) {
    private constructor(
        fixture: LaserworldCS100Fixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): LaserworldCS100Fixture = LaserworldCS100Fixture(this, transaction)

    enum class OperationMode(override val level: UByte): DmxFixtureSettingValue {
        LASER_OFF(0u),
        SOUND_TO_LIGHT(50u),
        AUTO_MODE(100u),
        DMX_MODE(150u),
    }

    @FixtureProperty(category = PropertyCategory.SETTING)
    val mode = DmxFixtureSetting(transaction, universe, firstChannel, OperationMode.entries.toTypedArray())

    @FixtureProperty("Pattern select", category = PropertyCategory.SETTING)
    val pattern = DmxSlider(transaction, universe, firstChannel + 1)

    @FixtureProperty("Rotation (Z-axis)", category = PropertyCategory.OTHER)
    val rotationZ = DmxSlider(transaction, universe, firstChannel + 2)

    @FixtureProperty("Rotation X-axis", category = PropertyCategory.OTHER)
    val rotationX = DmxSlider(transaction, universe, firstChannel + 3)

    @FixtureProperty("Rotation Y-axis", category = PropertyCategory.OTHER)
    val rotationY = DmxSlider(transaction, universe, firstChannel + 4)

    @FixtureProperty("Offset X-axis", category = PropertyCategory.POSITION)
    val offsetX = DmxSlider(transaction, universe, firstChannel + 5)

    @FixtureProperty("Offset Y-axis", category = PropertyCategory.POSITION)
    val offsetY = DmxSlider(transaction, universe, firstChannel + 6)

    @FixtureProperty("Size X", category = PropertyCategory.OTHER)
    val sizeX = DmxSlider(transaction, universe, firstChannel + 7)

    @FixtureProperty("Size Y", category = PropertyCategory.OTHER)
    val sizeY = DmxSlider(transaction, universe, firstChannel + 8)

    @FixtureProperty("Drawing effect", category = PropertyCategory.SETTING)
    val drawingEffect = DmxSlider(transaction, universe, firstChannel + 9)

    @FixtureProperty("Scan speed / Points", category = PropertyCategory.SPEED)
    val scanSpeed = DmxSlider(transaction, universe, firstChannel + 10)

    @FixtureProperty("Colour change", category = PropertyCategory.COLOUR)
    val colourChange = DmxSlider(transaction, universe, firstChannel + 11)

    @FixtureProperty("Colour speed", category = PropertyCategory.SPEED)
    val colourSpeed = DmxSlider(transaction, universe, firstChannel + 12)
}
