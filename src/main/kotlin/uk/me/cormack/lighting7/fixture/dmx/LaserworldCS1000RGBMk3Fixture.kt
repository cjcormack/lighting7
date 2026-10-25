package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.CompactDisplayRole
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureAttribute
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.AttributeCategory

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
        ;

        /** The pattern's own colours (ORIGINAL), an auto change and a multi-colour step have none. */
        override val noColour: Boolean get() = colourPreview == null
    }

    @FixtureAttribute(category = AttributeCategory.SETTING, compactDisplay = CompactDisplayRole.PRIMARY)
    val mode = DmxFixtureSetting(transaction, universe, firstChannel, OperationMode.entries.toTypedArray())

    @FixtureAttribute("Pattern select", category = AttributeCategory.SETTING)
    val pattern = DmxSlider(transaction, universe, firstChannel + 1)

    @FixtureAttribute("Rotation (Z-axis)", category = AttributeCategory.OTHER)
    val rotationZ = DmxSlider(transaction, universe, firstChannel + 2)

    @FixtureAttribute("Rotation X-axis", category = AttributeCategory.OTHER)
    val rotationX = DmxSlider(transaction, universe, firstChannel + 3)

    @FixtureAttribute("Rotation Y-axis", category = AttributeCategory.OTHER)
    val rotationY = DmxSlider(transaction, universe, firstChannel + 4)

    @FixtureAttribute("Offset X-axis", category = AttributeCategory.PAN)
    val offsetX = DmxSlider(transaction, universe, firstChannel + 5)

    @FixtureAttribute("Offset Y-axis", category = AttributeCategory.TILT)
    val offsetY = DmxSlider(transaction, universe, firstChannel + 6)

    @FixtureAttribute("Size X", category = AttributeCategory.OTHER)
    val sizeX = DmxSlider(transaction, universe, firstChannel + 7)

    @FixtureAttribute("Size Y", category = AttributeCategory.OTHER)
    val sizeY = DmxSlider(transaction, universe, firstChannel + 8)

    @FixtureAttribute("Drawing effect", category = AttributeCategory.SETTING)
    val drawingEffect = DmxSlider(transaction, universe, firstChannel + 9)

    @FixtureAttribute("Scan speed / Points", category = AttributeCategory.SPEED, compactDisplay = CompactDisplayRole.SECONDARY)
    val scanSpeed = DmxSlider(transaction, universe, firstChannel + 10)

    @FixtureAttribute("Colour change", category = AttributeCategory.COLOUR)
    val colourChange = DmxFixtureSetting(transaction, universe, firstChannel + 11, ColourChange.entries.toTypedArray())

    @FixtureAttribute("Colour speed", category = AttributeCategory.SPEED)
    val colourSpeed = DmxSlider(transaction, universe, firstChannel + 12)
}