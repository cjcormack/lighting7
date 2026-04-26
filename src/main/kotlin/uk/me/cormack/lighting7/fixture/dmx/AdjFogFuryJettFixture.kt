package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithAmber
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithStrobe

/**
 * ADJ Fog Fury Jett — vertical fog blaster with 12 × 3W RGBA LEDs.
 *
 * Five DMX personalities (1/2/3/5/7 channel). Only Mode 7 is implemented for the
 * TCH 2026 patch; the other modes remain as `// TODO` enum entries.
 *
 * Important: per the manual, "Color Macros / RGBA will not work unless fog is
 * active." Scripts that drive the LEDs should also raise the [Mode7Ch.fog]
 * channel above 31 if they want any visible output.
 */
sealed class AdjFogFuryJettFixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<AdjFogFuryJettFixture.Mode> {

    enum class Mode(
        override val channelCount: Int,
        override val modeName: String,
    ) : DmxChannelMode {
        // TODO: MODE_1CH (1, "1-Channel (Fog + random colour)")
        // TODO: MODE_2CH (2, "2-Channel (Fog + colour macros)")
        // TODO: MODE_3CH (3, "3-Channel (Fog + colour macros + strobe)")
        // TODO: MODE_5CH (5, "5-Channel (Fog + RGBA)")
        MODE_7CH(7, "7-Channel (Fog + RGBA + Strobe + Dimmer)"),
    }

    /**
     * 7-channel mode — full feature set.
     *
     * - Ch 1: Fog trigger (0–31 off, 32–255 max fog).
     * - Ch 2: Red.
     * - Ch 3: Green.
     * - Ch 4: Blue.
     * - Ch 5: Amber.
     * - Ch 6: Strobe (off / strobe / pulse / random strobe).
     * - Ch 7: Master dimmer.
     */
    @FixtureType("adj-fog-fury-jett-7ch", manufacturer = "ADJ", model = "Fog Fury Jett")
    class Mode7Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : AdjFogFuryJettFixture(
        universe, firstChannel, 7, key, fixtureName, transaction,
    ), WithDimmer, WithColour, WithAmber, WithStrobe {
        override val mode = Mode.MODE_7CH

        private constructor(fixture: Mode7Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction,
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode7Ch =
            Mode7Ch(this, transaction)

        @FixtureProperty("Fog trigger (0–31 off, 32–255 max fog)", category = PropertyCategory.OTHER)
        val fog: Slider = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty(category = PropertyCategory.COLOUR)
        override val rgbColour = DmxColour(
            transaction, universe,
            firstChannel + 1,
            firstChannel + 2,
            firstChannel + 3,
        )

        @FixtureProperty(category = PropertyCategory.AMBER, bundleWithColour = true)
        override val amber: Slider = DmxSlider(transaction, universe, firstChannel + 4)

        /**
         * Channel 6 strobe band layout from the manual:
         * - 0–31    off
         * - 32–95   strobe slow → fast
         * - 96–159  pulse effect
         * - 160–255 random strobe slow → fast
         *
         * Only the linear strobe band (32–95) is exposed; pulse and random-
         * strobe bands are reachable by writing the raw channel value.
         */
        @FixtureProperty(category = PropertyCategory.STROBE)
        override val strobe = BandedStrobeChannel(
            transaction, universe, firstChannel + 5,
            strobeMin = 32u, strobeMax = 95u,
        )

        @FixtureProperty(category = PropertyCategory.DIMMER)
        override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel + 6)
    }
}
