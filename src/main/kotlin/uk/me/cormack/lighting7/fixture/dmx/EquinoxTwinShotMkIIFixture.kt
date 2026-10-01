package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.fixture.FixtureBody
import uk.me.cormack.lighting7.fixture.BodyArchetype
import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureTrigger
import uk.me.cormack.lighting7.fixture.FixtureType

/**
 * Equinox Twin Shot MKII (EQLED406) — twin electric confetti / streamer launcher.
 *
 * Pyro-adjacent fire trigger. Three DMX channels:
 * - Ch 1: Output 1 (0–50 idle, 51–255 fire).
 * - Ch 2: Output 2 (0–50 idle, 51–255 fire).
 * - Ch 3: Master (0–50 idle, 51–255 enabled). Outputs 1/2 only fire while master is high.
 *
 * **Safety**: each fire spends a physical cartridge, so the two outputs are
 * [one-shot triggers][FixtureTrigger] and the master is their arm (stage-view plan session 9,
 * D15, D16) — none of the three is a `@FixtureProperty`. No Look, template, cue row, effect,
 * programmer value or Record can hold one, nothing composes or crossfades them, and the desk owns
 * all three channels: the outputs sit idle except for one ~300 ms pulse per fire, and the master
 * follows the desk's arm. A fire is a cue event, the cannon's hold-to-fire button or a MIDI
 * `FireTrigger`, and only while the desk is armed. The class still implements no FX-targetable
 * trait, so a tempo effect cannot reach it either.
 */
@FixtureType("equinox-twin-shot-mkii", manufacturer = "Equinox", model = "Twin Shot MKII", kind = FixtureKind.EFFECT, body = FixtureBody(BodyArchetype.CANNON))
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

    @FixtureTrigger("Tube A", label = "A", armName = ARM, armDescription = ARM_DESCRIPTION)
    val output1: DmxTrigger = DmxTrigger(universe, firstChannel, firstChannel + 2)

    @FixtureTrigger("Tube B", label = "B", armName = ARM, armDescription = ARM_DESCRIPTION)
    val output2: DmxTrigger = DmxTrigger(universe, firstChannel + 1, firstChannel + 2)

    private companion object {
        /** Channel 3: the master enable both tubes share, and the name a stored row once used for it. */
        const val ARM = "master"
        const val ARM_DESCRIPTION = "Master enable"
    }
}
