package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.trait.WithAmber
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithStrobe
import uk.me.cormack.lighting7.fixture.trait.WithUv
import uk.me.cormack.lighting7.fixture.trait.WithWhite

@FixtureType("hex", manufacturer = "Chauvet", model = "Freedom Par Hex", kind = FixtureKind.PAR)
class HexFixture(
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
        fixture: HexFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        fixture.maxDimmerLevel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): HexFixture = HexFixture(this, transaction)

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

    @FixtureAttribute(category = AttributeCategory.DIMMER)
    override val dimmer = DmxSlider(transaction, universe, firstChannel, max = maxDimmerLevel)

    @FixtureAttribute(category = AttributeCategory.COLOUR)
    override val rgbColour = DmxColour(
        transaction,
        universe,
        firstChannel + 1,
        firstChannel + 2,
        firstChannel + 3,
    )

    @FixtureAttribute(category = AttributeCategory.AMBER, bundleWithColour = true)
    override val amber = DmxSlider(transaction, universe, firstChannel + 4)

    @FixtureAttribute(category = AttributeCategory.WHITE, bundleWithColour = true)
    override val white = DmxSlider(transaction, universe, firstChannel + 5)

    @FixtureAttribute(category = AttributeCategory.UV, bundleWithColour = true)
    override val uv = DmxSlider(transaction, universe, firstChannel + 6)

    // Estimate: no manual to hand — 0–9 open below the strobe band the class writes, and the strobe's
    // 1–20 Hz. Checked on the rig by FU-MANUAL-S6-STROBE.
    @FixtureAttribute(
        category = AttributeCategory.STROBE,
        strobe = [
            StrobeBand(0, 9, StrobeKind.OPEN),
            StrobeBand(10, 255, StrobeKind.STROBE, hzMin = 1.0, hzMax = 20.0),
        ],
    )
    override val strobe = BandedStrobeChannel(
        transaction, universe, firstChannel + 7,
        strobeMin = 10u, strobeMax = 255u,
    )

    @FixtureAttribute(category = AttributeCategory.SETTING)
    val mode = DmxFixtureSetting(transaction, universe, firstChannel + 9, ProgramMode.entries.toTypedArray())

    @FixtureAttribute(category = AttributeCategory.SPEED)
    val programSpeed = DmxSlider(transaction, universe, firstChannel + 10)

    @FixtureAttribute(category = AttributeCategory.SETTING)
    val dimmerMode = DmxFixtureSetting(transaction, universe, firstChannel + 11, DimmerMode.entries.toTypedArray())
}
