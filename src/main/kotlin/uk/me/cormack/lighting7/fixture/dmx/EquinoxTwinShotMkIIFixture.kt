package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.property.Slider

/**
 * Equinox Twin Shot MKII (EQLED406) — twin electric confetti / streamer launcher.
 *
 * Pyro-adjacent fire trigger. Three DMX channels:
 * - Ch 1: Output 1 (0–50 idle, 51–255 fire).
 * - Ch 2: Output 2 (0–50 idle, 51–255 fire).
 * - Ch 3: Master (0–50 idle, 51–255 enabled). Outputs 1/2 only fire while master is high.
 *
 * **Safety**: this fixture intentionally does not implement any FX-targetable trait
 * (no `WithDimmer` / `WithStrobe` / `WithColour`). Random tempo-driven effects must
 * not be allowed to drive the trigger channels, since each fire pulse expends a
 * physical confetti cartridge. Scripts should treat the channels as momentary
 * triggers (raise high → wait → release).
 */
@FixtureType("equinox-twin-shot-mkii", manufacturer = "Equinox", model = "Twin Shot MKII", kind = FixtureKind.EFFECT)
class EquinoxTwinShotMkIIFixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 3, key, fixtureName) {

    private constructor(
        fixture: EquinoxTwinShotMkIIFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): EquinoxTwinShotMkIIFixture =
        EquinoxTwinShotMkIIFixture(this, transaction)

    @FixtureProperty("Output 1 fire trigger (≥51 to fire)", category = PropertyCategory.OTHER)
    val output1: Slider = DmxSlider(transaction, universe, firstChannel)

    @FixtureProperty("Output 2 fire trigger (≥51 to fire)", category = PropertyCategory.OTHER)
    val output2: Slider = DmxSlider(transaction, universe, firstChannel + 1)

    @FixtureProperty("Master enable (≥51 enables outputs 1/2)", category = PropertyCategory.OTHER)
    val master: Slider = DmxSlider(transaction, universe, firstChannel + 2)
}
