package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType

@FixtureType("laserword_cs100")
class LaserworldCS100Fixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    transaction: ControllerTransaction? = null,
): DmxFixture(universe, firstChannel, 13, key, fixtureName, position) {
    private constructor(
        fixture: LaserworldCS100Fixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        fixture.position,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): LaserworldCS100Fixture = LaserworldCS100Fixture(this, transaction)

    enum class OperationMode(override val level: UByte): DmxFixtureSettingValue {
        LASER_OFF(0u),
        SOUND_TO_LIGHT(50u),
        AUTO_MODE(100u),
        DMX_MODE(150u),
    }

    @FixtureProperty
    val mode = DmxFixtureSetting(transaction, universe, firstChannel, OperationMode.entries.toTypedArray())

    @FixtureProperty("Pattern select")
    val pattern = DmxFixtureSlider(transaction, universe, firstChannel + 1)
    @FixtureProperty("Rotation (Z-axis)")
    val rotationZ = DmxFixtureSlider(transaction, universe, firstChannel + 2)
    @FixtureProperty("Rotation X-axis")
    val rotationX = DmxFixtureSlider(transaction, universe, firstChannel + 3)
    @FixtureProperty("Rotation Y-axis")
    val rotationY = DmxFixtureSlider(transaction, universe, firstChannel + 4)
    @FixtureProperty("Offset X-axis")
    val offsetX = DmxFixtureSlider(transaction, universe, firstChannel + 5)
    @FixtureProperty("Offset Y-axis")
    val offsetY = DmxFixtureSlider(transaction, universe, firstChannel + 6)
    @FixtureProperty("Size X")
    val sizeX = DmxFixtureSlider(transaction, universe, firstChannel + 7)
    @FixtureProperty("Size Y")
    val sizeY = DmxFixtureSlider(transaction, universe, firstChannel + 8)
    @FixtureProperty("Drawing effect")
    val drawingEffect = DmxFixtureSlider(transaction, universe, firstChannel + 9)
    @FixtureProperty("Scan speed / Points")
    val scanSpeed = DmxFixtureSlider(transaction, universe, firstChannel + 10)
    @FixtureProperty("Colour change")
    val colourChange = DmxFixtureSlider(transaction, universe, firstChannel + 11)
    @FixtureProperty("Colour speed")
    val colourSpeed = DmxFixtureSlider(transaction, universe, firstChannel + 12)
}
