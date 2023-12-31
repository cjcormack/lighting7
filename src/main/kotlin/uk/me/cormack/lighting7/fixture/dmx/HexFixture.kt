package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*

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
    FixtureWithDimmer, DmxFixtureWithColour, FixtureWithUv
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

    enum class ProgramMode(override val level: UByte) : DmxFixtureSettingValue {
        NONE(0u),
        SOUND_ACTIVE(201u),
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
    val whiteColour = DmxFixtureSlider(transaction, universe, firstChannel + 4)
    @FixtureProperty
    val amberColour = DmxFixtureSlider(transaction, universe, firstChannel + 5)
    @FixtureProperty
    override val uvColour = DmxFixtureSlider(transaction, universe, firstChannel + 6)

    @FixtureProperty
    val mode = DmxFixtureSetting(transaction, universe, firstChannel + 9, ProgramMode.entries.toTypedArray())
}
