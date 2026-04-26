package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer

/**
 * Gear4Music SOL Party 12B — single-section RGB LED party bar.
 *
 * Reverse-engineered from the ChamSys library since no manual is bundled.
 * Authoritative channel map:
 * `Manuals/personalities/Gear4Music_SOLParty12B_8ch.md` (transcribed from
 * MagicQ `EDIT HEAD` — the on-disk `.hed` files are obfuscated).
 *
 * Single global RGB only — no per-cell channels. The colour wheel on ch 2
 * is a self-contained band-based effect; manual RGB on ch 5/6/7 is
 * intended for use when ch 2 is in the `ALL_COL` (000–039) band.
 *
 * No strobe channel and no pan/tilt — typical static LED bar.
 */
@FixtureType("gear4music-sol-party-12b-8ch", manufacturer = "Gear4music", model = "SOL Party 12B")
class Gear4MusicSolParty12BFixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    private val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 8, key, fixtureName),
    WithDimmer, WithColour {

    private constructor(
        fixture: Gear4MusicSolParty12BFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): Gear4MusicSolParty12BFixture =
        Gear4MusicSolParty12BFixture(this, transaction)

    /**
     * Channel 2 — built-in colour wheel. 19 bands; first 8 are single
     * fixed colours, the rest are 2- or 3-colour blends with a rainbow
     * variant. The `ALL_COL` (000–039) band disables the colour wheel
     * so manual RGB on ch 5/6/7 takes over.
     *
     * Within each band the level animates the wheel transition speed;
     * `Col Speed` (ch 3) gates that animation.
     */
    enum class ColourWheel(
        override val level: UByte,
        override val colourPreview: String?,
    ) : DmxFixtureColourSettingValue {
        ALL_COL(0u, null),
        RED(40u, "#FF0000"),
        GREEN(50u, "#00FF00"),
        BLUE(60u, "#0000FF"),
        YELLOW(70u, "#FFFF00"),
        CYAN(80u, "#00FFFF"),
        PURPLE(90u, "#800080"),
        RAINBOW_BALL(100u, null),
        RED_AND_GREEN(110u, null),
        RED_AND_BLUE(120u, null),
        RED_AND_RAINBOW(130u, null),
        GREEN_AND_BLUE(140u, null),
        GREEN_AND_RAINBOW(150u, null),
        BLUE_AND_RAINBOW(160u, null),
        R_G_RAINBOW(170u, null),
        R_B_RAINBOW(180u, null),
        GREEN_BLUE(190u, null),
        R_G_B(200u, null),
        R_G_B_RAINBOW(201u, null),
    }

    @FixtureProperty("Internal mode", category = PropertyCategory.SETTING)
    val intMode: Slider = DmxSlider(transaction, universe, firstChannel)

    @FixtureProperty("Built-in colour wheel", category = PropertyCategory.COLOUR)
    val colourWheel = DmxFixtureSetting(
        transaction, universe, firstChannel + 1, ColourWheel.entries.toTypedArray(),
    )

    @FixtureProperty("Colour-wheel transition speed", category = PropertyCategory.SPEED)
    val colourSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 2)

    @FixtureProperty(category = PropertyCategory.DIMMER)
    override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel + 3)

    @FixtureProperty(category = PropertyCategory.COLOUR)
    override val rgbColour = DmxColour(
        transaction, universe,
        firstChannel + 4,
        firstChannel + 5,
        firstChannel + 6,
    )

    @FixtureProperty("FX / prism macro", category = PropertyCategory.OTHER)
    val fx: Slider = DmxSlider(transaction, universe, firstChannel + 7)
}
