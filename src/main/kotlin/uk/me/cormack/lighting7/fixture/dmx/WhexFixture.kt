package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.property.Strobe
import uk.me.cormack.lighting7.fixture.trait.WithAmber
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithStrobe
import uk.me.cormack.lighting7.fixture.trait.WithUv
import uk.me.cormack.lighting7.fixture.trait.WithWhite
import kotlin.math.roundToInt

@FixtureType("whex", kind = FixtureKind.PAR)
class WhexFixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    private val maxDimmerLevel: UByte = 255u,
    transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 12, key, fixtureName),
    WithDimmer, WithColour, WithWhite, WithAmber, WithUv, WithStrobe
{
    private constructor(
        fixture: WhexFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        fixture.maxDimmerLevel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): WhexFixture = WhexFixture(this, transaction)

    /**
     * 0 is no strobe; 10–255 strobes slow → fast. [strobe] spreads its 0..255 intensity over that
     * band — 245 steps above 10, so 255 lands on 255. (It used to scale *up* by 255/245 and then add
     * 10, which ran past 255 from intensity 236 and wrapped a near-full strobe round to a slow one.)
     */
    class DmxStrobe(transaction: ControllerTransaction?, universe: Universe, channelNo: Int): DmxSlider(transaction, universe, channelNo), Strobe {
        override fun fullOn() {
            this.value = 0u
        }

        override fun strobe(intensity: UByte) {
            this.value = (STROBE_MIN + (intensity.toFloat() * (255 - STROBE_MIN) / 255F).roundToInt()).toUByte()
        }

        companion object {
            /** The strobe band's slowest level. */
            const val STROBE_MIN = 10
        }
    }

    // Program 1 used to share 111 with program 3, so no level reached it.
    // Estimate: no manual to hand; 11 continues the programs' 50-step spacing (61, 111, 161).
    // Checked on the rig by FU-MANUAL-S5-LIBRARY-OPTICS.
    enum class ProgramMode(override val level: UByte) : DmxFixtureSettingValue {
        NONE(0u),
        AUTO_PROGRAM_1(11u),
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
    override val dimmer = DmxSlider(transaction, universe, firstChannel, max = maxDimmerLevel)

    @FixtureProperty(category = PropertyCategory.COLOUR)
    override val rgbColour = DmxColour(
        transaction,
        universe,
        firstChannel + 1,
        firstChannel + 2,
        firstChannel + 3,
    )

    @FixtureProperty(category = PropertyCategory.WHITE, bundleWithColour = true)
    override val white = DmxSlider(transaction, universe, firstChannel + 4)

    @FixtureProperty(category = PropertyCategory.AMBER, bundleWithColour = true)
    override val amber = DmxSlider(transaction, universe, firstChannel + 5)

    @FixtureProperty(category = PropertyCategory.UV, bundleWithColour = true)
    override val uv = DmxSlider(transaction, universe, firstChannel + 6)

    @FixtureProperty(category = PropertyCategory.STROBE)
    override val strobe = DmxStrobe(transaction, universe, firstChannel + 7)

    @FixtureProperty(category = PropertyCategory.SETTING)
    val mode = DmxFixtureSetting(transaction, universe, firstChannel + 9, ProgramMode.entries.toTypedArray())

    @FixtureProperty(category = PropertyCategory.SPEED)
    val programSpeed = DmxSlider(transaction, universe, firstChannel + 10)

    @FixtureProperty(category = PropertyCategory.SETTING)
    val dimmerMode = DmxFixtureSetting(transaction, universe, firstChannel + 11, DimmerMode.entries.toTypedArray())
}
