package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import kotlin.math.roundToInt

@FixtureType("whex")
class WhexFixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    private val maxDimmerLevel: UByte = 255u,
    transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 12, key, fixtureName, position),
    FixtureWithDimmer, DmxFixtureWithColour, FixtureWithUv, FixtureWithStrobe
{
    private constructor(
        fixture: WhexFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        fixture.position,
        fixture.maxDimmerLevel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): WhexFixture = WhexFixture(this, transaction)

    class Strobe(transaction: ControllerTransaction?, universe: Universe, channelNo: Int): DmxFixtureSlider(transaction, universe, channelNo), FixtureStrobe {
        override fun fullOn() {
            this.value = 0u
        }

        override fun strobe(intensity: UByte) {
            this.value = ((255F / 245F * intensity.toFloat()).roundToInt() + 10).toUByte()
        }
    }

    enum class ProgramMode(override val level: UByte) : DmxFixtureSettingValue {
        NONE(0u),
        AUTO_PROGRAM_1(111u),
        AUTO_PROGRAM_2(61u),
        AUTO_PROGRAM_3(111u),
        AUTO_PROGRAM_4(161u),
        SOUND_ACTIVE(241u),
    }

    enum class DimmerMode(override val level: UByte) : DmxFixtureSettingValue {
        MANUAL(0u),
        OFF(52u),
        FAST(102u),
        MEDIUM(153u),
        SLOW(204u),
    }

    @FixtureProperty(category = PropertyCategory.DIMMER)
    override val dimmer = DmxFixtureSlider(transaction, universe, firstChannel, max = maxDimmerLevel)

    @FixtureProperty(category = PropertyCategory.COLOUR)
    override val rgbColour = DmxFixtureColour(
        transaction,
        universe,
        firstChannel + 1,
        firstChannel + 2,
        firstChannel + 3,
    )

    @FixtureProperty(category = PropertyCategory.WHITE, bundleWithColour = true)
    val whiteColour = DmxFixtureSlider(transaction, universe, firstChannel + 4)

    @FixtureProperty(category = PropertyCategory.AMBER, bundleWithColour = true)
    val amberColour = DmxFixtureSlider(transaction, universe, firstChannel + 5)

    @FixtureProperty(category = PropertyCategory.UV, bundleWithColour = true)
    override val uvColour = DmxFixtureSlider(transaction, universe, firstChannel + 6)

    @FixtureProperty(category = PropertyCategory.STROBE)
    override val strobe = Strobe(transaction, universe, firstChannel + 7)

    @FixtureProperty(category = PropertyCategory.SETTING)
    val mode = DmxFixtureSetting(transaction, universe, firstChannel + 9, ProgramMode.entries.toTypedArray())

    @FixtureProperty(category = PropertyCategory.SPEED)
    val programSpeed = DmxFixtureSlider(transaction, universe, firstChannel + 10)

    @FixtureProperty(category = PropertyCategory.SETTING)
    val dimmerMode = DmxFixtureSetting(transaction, universe, firstChannel + 11, DimmerMode.entries.toTypedArray())
}
