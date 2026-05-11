package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.CompactDisplayRole
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.PropertyCategory

/**
 * Laserworld CS-1000RGB MK3 - RGB show laser, 13 DMX channels.
 *
 * DMX Layout (13 channels):
 * - Ch 1:  Operation mode (off / sound-to-light / stand-alone / DMX)
 * - Ch 2:  Pattern select
 * - Ch 3:  Rotation Z-axis (angle / counter-clockwise speed / clockwise speed)
 * - Ch 4:  Rotation X-axis (position steps / automatic)
 * - Ch 5:  Rotation Y-axis (position steps / automatic)
 * - Ch 6:  Offset X-axis (position steps / automatic movement)
 * - Ch 7:  Offset Y-axis (position steps / automatic movement)
 * - Ch 8:  Size X (big to small)
 * - Ch 9:  Size Y (big to small)
 * - Ch 10: Drawing effect
 * - Ch 11: Scan speed / Points
 * - Ch 12: Colour change
 * - Ch 13: Colour speed
 */
@FixtureType("laserworld-cs1000rgb-mk3", manufacturer = "Laserworld", model = "CS-1000RGB MK3", kind = FixtureKind.LASER)
class LaserworldCS1000RGBMk3Fixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 13, key, fixtureName) {
    private constructor(
        fixture: LaserworldCS1000RGBMk3Fixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): LaserworldCS1000RGBMk3Fixture =
        LaserworldCS1000RGBMk3Fixture(this, transaction)

    enum class OperationMode(override val level: UByte) : DmxFixtureSettingValue {
        LASER_OFF(0u),
        SOUND_TO_LIGHT(50u),
        STAND_ALONE(100u),
        DMX_MODE(150u),
    }

    enum class ColourChange(
        override val level: UByte,
        override val colourPreview: String?,
    ) : DmxFixtureColourSettingValue {
        ORIGINAL(0u, null),
        PURPLE(25u, "#800080"),
        RED(50u, "#FF0000"),
        YELLOW(75u, "#FFFF00"),
        GREEN(100u, "#00FF00"),
        CYAN(125u, "#00FFFF"),
        BLUE(150u, "#0000FF"),
        WHITE(175u, "#FFFFFF"),
        AUTO_COLOUR_CHANGE(200u, null),
        MULTI_COLOUR_STEP(225u, null),
    }

    @FixtureProperty(category = PropertyCategory.SETTING, compactDisplay = CompactDisplayRole.PRIMARY)
    val mode = DmxFixtureSetting(transaction, universe, firstChannel, OperationMode.entries.toTypedArray())

    @FixtureProperty("Pattern select", category = PropertyCategory.SETTING)
    val pattern = DmxSlider(transaction, universe, firstChannel + 1)

    @FixtureProperty("Rotation (Z-axis)", category = PropertyCategory.OTHER)
    val rotationZ = DmxSlider(transaction, universe, firstChannel + 2)

    @FixtureProperty("Rotation X-axis", category = PropertyCategory.OTHER)
    val rotationX = DmxSlider(transaction, universe, firstChannel + 3)

    @FixtureProperty("Rotation Y-axis", category = PropertyCategory.OTHER)
    val rotationY = DmxSlider(transaction, universe, firstChannel + 4)

    @FixtureProperty("Offset X-axis", category = PropertyCategory.PAN)
    val offsetX = DmxSlider(transaction, universe, firstChannel + 5)

    @FixtureProperty("Offset Y-axis", category = PropertyCategory.TILT)
    val offsetY = DmxSlider(transaction, universe, firstChannel + 6)

    @FixtureProperty("Size X", category = PropertyCategory.OTHER)
    val sizeX = DmxSlider(transaction, universe, firstChannel + 7)

    @FixtureProperty("Size Y", category = PropertyCategory.OTHER)
    val sizeY = DmxSlider(transaction, universe, firstChannel + 8)

    @FixtureProperty("Drawing effect", category = PropertyCategory.SETTING)
    val drawingEffect = DmxSlider(transaction, universe, firstChannel + 9)

    @FixtureProperty("Scan speed / Points", category = PropertyCategory.SPEED, compactDisplay = CompactDisplayRole.SECONDARY)
    val scanSpeed = DmxSlider(transaction, universe, firstChannel + 10)

    @FixtureProperty("Colour change", category = PropertyCategory.COLOUR)
    val colourChange = DmxFixtureSetting(transaction, universe, firstChannel + 11, ColourChange.entries.toTypedArray())

    @FixtureProperty("Colour speed", category = PropertyCategory.SPEED)
    val colourSpeed = DmxSlider(transaction, universe, firstChannel + 12)
}