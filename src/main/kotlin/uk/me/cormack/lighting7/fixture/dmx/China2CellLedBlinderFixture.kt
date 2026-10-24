package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.attribute.Slider
import uk.me.cormack.lighting7.fixture.attribute.WhiteMix
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithStrobe

/**
 * China 2-Cell LED Blinder — variable-white audience blinder with two
 * independently addressable cells. Each cell has separate warm-white and
 * cold-white channels (no RGB).
 *
 * Reverse-engineered from the ChamSys library since no manual is bundled.
 * Authoritative channel map:
 * `Manuals/personalities/China_2CellLEDBlind_8ch.md` (transcribed from
 * MagicQ `EDIT HEAD` — the on-disk `.hed` files are obfuscated).
 *
 * ChamSys's manufacturer field is the literal string `China` and the model
 * is `2CellLEDBlind`. The `@FixtureType` annotation uses ChamSys's
 * spelling for consistency with the patch.
 */
@FixtureType("china-2-cell-led-blinder-8ch", manufacturer = "China", model = "2-Cell LED Blinder", kind = FixtureKind.BLINDER)
class China2CellLedBlinderFixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    private val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 8, key, fixtureName),
    WithDimmer, WithStrobe,
    MultiElementFixture<China2CellLedBlinderFixture.Cell> {

    private constructor(
        fixture: China2CellLedBlinderFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): China2CellLedBlinderFixture =
        China2CellLedBlinderFixture(this, transaction)

    /**
     * Channel 3 — built-in programs. Within each band the level controls
     * the speed/intensity of that program; `progSpeed` (ch 4) provides
     * additional speed gating.
     */
    enum class Program(override val level: UByte) : DmxFixtureSettingValue {
        NO_FUNCTION(0u),
        COL_JUMP(40u),
        COL_GRADIENT(80u),
        COL_PULSE(120u),
        COL_MUTATION(160u),
        AUTO_MODE(200u),
        SOUND_ACTIVE(240u),
    }

    /**
     * One of two cells (CH5–6, CH7–8). Each cell has a warm-white and a
     * cold-white slider, and takes a colour through them as their nearest
     * mix ([rgbColour], venue-visit plan D8) — there is no per-cell dimmer
     * or strobe; those are shared on the parent fixture.
     *
     * Both sliders use [AttributeCategory.WHITE] but neither claims
     * [uk.me.cormack.lighting7.fixture.trait.WithWhite] — using that
     * trait for one of the two channels would imply asymmetry that
     * isn't there. The sliders stay for direct control.
     */
    inner class Cell(
        override val elementIndex: Int,
        cellTransaction: ControllerTransaction?,
        private val warmWhiteChannel: Int,
        private val coldWhiteChannel: Int,
    ) : FixtureElement<China2CellLedBlinderFixture>, WithColour {

        override val parentFixture: China2CellLedBlinderFixture
            get() = this@China2CellLedBlinderFixture

        override val elementKey: String
            get() = "${this@China2CellLedBlinderFixture.key}.cell-${elementIndex + 1}"

        @FixtureAttribute("Warm white", category = AttributeCategory.WHITE)
        val warmWhite = DmxSlider(cellTransaction, universe, warmWhiteChannel)

        @FixtureAttribute("Cold white", category = AttributeCategory.WHITE)
        val coldWhite = DmxSlider(cellTransaction, universe, coldWhiteChannel)

        @FixtureAttribute("Warmth", category = AttributeCategory.COLOUR)
        override val rgbColour = DmxWhiteMixColour(warmWhite, coldWhite, WHITE_MIX)

        override fun withTransaction(transaction: ControllerTransaction): Cell =
            Cell(elementIndex, transaction, warmWhiteChannel, coldWhiteChannel)

        override fun toString(): String = "Cell($elementKey)"
    }

    @FixtureAttribute(category = AttributeCategory.DIMMER)
    override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel)

    // The personality's bands: 000–010 open, 011–255 strobe slow → fast.
    // Estimate: the strobe's 1–20 Hz — no rate is published for it. Checked on the rig by
    // FU-MANUAL-S6-STROBE.
    @FixtureAttribute(
        category = AttributeCategory.STROBE,
        strobe = [
            StrobeBand(0, 10, StrobeKind.OPEN),
            StrobeBand(11, 255, StrobeKind.STROBE, hzMin = 1.0, hzMax = 20.0),
        ],
    )
    override val strobe = BandedStrobeChannel(
        transaction, universe, firstChannel + 1,
        strobeMin = STROBE_MIN, strobeMax = STROBE_MAX,
    )

    @FixtureAttribute("Built-in program", category = AttributeCategory.SETTING)
    val program = DmxFixtureSetting(
        transaction, universe, firstChannel + 2, Program.entries.toTypedArray(),
    )

    @FixtureAttribute("Program speed", category = AttributeCategory.SPEED)
    val progSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 3)

    override val elements: List<Cell> = (0 until 2).map { idx ->
        Cell(
            idx, transaction,
            warmWhiteChannel = firstChannel + 4 + (idx * 2),
            coldWhiteChannel = firstChannel + 5 + (idx * 2),
        )
    }

    override val elementCount: Int = 2

    fun cell(index: Int): Cell {
        require(index in 0 until 2) { "Cell index must be 0-1, got $index" }
        return elements[index]
    }

    companion object {
        /**
         * Channel 2 strobe band. Personality lists `000–010 Open` and `011–255
         * Strobe S>F` — the open band is wider than the typical 0–0.
         */
        const val STROBE_MIN: UByte = 11u
        const val STROBE_MAX: UByte = 255u

        // Estimate: no manual states the whites' temperatures; 3000 K and 6500 K are the usual
        // warm and cold LEDs in a two-white blinder. Checked on the rig by FU-MANUAL-S9-WHITES.
        val WHITE_MIX = WhiteMix(warmKelvin = 3000, coldKelvin = 6500)
    }
}
