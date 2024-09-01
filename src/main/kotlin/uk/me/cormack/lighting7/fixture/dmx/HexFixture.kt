package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import kotlin.math.roundToInt

@FixtureType("hex")
class HexFixture(
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
        fixture: HexFixture,
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

    override fun withTransaction(transaction: ControllerTransaction): HexFixture = HexFixture(this, transaction)

    class Strobe(transaction: ControllerTransaction?, universe: Universe, channelNo: Int): DmxFixtureSlider(transaction, universe, channelNo), FixtureStrobe {
        override fun fullOn() {
            this.value = 0u
        }

        override fun strobe(intensity: UByte) {
            this.value = ((245F / 255F * intensity.toFloat()).roundToInt() + 10).toUByte()
        }
    }

    enum class ProgramMode(override val level: UByte) : DmxFixtureSettingValue {
        NONE(0u),
        SOUND_ACTIVE_6(201u),
        SOUND_ACTIVE_63(226u),
    }

    enum class DimmerMode(override val level: UByte) : DmxFixtureSettingValue {
        MANUAL(0u),
        OFF(52u),
        FAST(102u),
        MEDIUM(153u),
        SLOW(204u),
    }

    @FixtureProperty
    override val dimmer = DmxFixtureSlider(transaction, universe, firstChannel, max = maxDimmerLevel)

    @FixtureProperty
    override val rgbColour = DmxFixtureColour(
        transaction,
        universe,
        firstChannel + 1,
        firstChannel + 2,
        firstChannel + 3,
    )

    @FixtureProperty
    val amberColour = DmxFixtureSlider(transaction, universe, firstChannel + 4)
    @FixtureProperty
    val whiteColour = DmxFixtureSlider(transaction, universe, firstChannel + 5)
    @FixtureProperty
    override val uvColour = DmxFixtureSlider(transaction, universe, firstChannel + 6)

    @FixtureProperty
    override val strobe = Strobe(transaction, universe, firstChannel + 7)

    @FixtureProperty
    val mode = DmxFixtureSetting(transaction, universe, firstChannel + 9, ProgramMode.entries.toTypedArray())

    @FixtureProperty
    val programSpeed = DmxFixtureSlider(transaction, universe, firstChannel + 10)

    @FixtureProperty
    val dimmerMode = DmxFixtureSetting(transaction, universe, firstChannel + 11, DimmerMode.entries.toTypedArray())
}
