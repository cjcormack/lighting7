package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.property.Slider
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
     * One of two cells (CH5–6, CH7–8). Each cell exposes a warm-white
     * and cold-white slider — there is no per-cell dimmer or strobe;
     * those are shared on the parent fixture.
     *
     * Both sliders use [PropertyCategory.WHITE] but neither claims
     * [uk.me.cormack.lighting7.fixture.trait.WithWhite] — using that
     * trait for one of the two channels would imply asymmetry that
     * isn't there. Variable-white blending is done by setting the two
     * sliders independently.
     */
    inner class Cell(
        override val elementIndex: Int,
        cellTransaction: ControllerTransaction?,
        private val warmWhiteChannel: Int,
        private val coldWhiteChannel: Int,
    ) : FixtureElement<China2CellLedBlinderFixture> {

        override val parentFixture: China2CellLedBlinderFixture
            get() = this@China2CellLedBlinderFixture

        override val elementKey: String
            get() = "${this@China2CellLedBlinderFixture.key}.cell-${elementIndex + 1}"

        @FixtureProperty("Warm white", category = PropertyCategory.WHITE)
        val warmWhite: Slider = DmxSlider(cellTransaction, universe, warmWhiteChannel)

        @FixtureProperty("Cold white", category = PropertyCategory.WHITE)
        val coldWhite: Slider = DmxSlider(cellTransaction, universe, coldWhiteChannel)

        override fun withTransaction(transaction: ControllerTransaction): Cell =
            Cell(elementIndex, transaction, warmWhiteChannel, coldWhiteChannel)

        override fun toString(): String = "Cell($elementKey)"
    }

    @FixtureProperty(category = PropertyCategory.DIMMER)
    override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel)

    @FixtureProperty(category = PropertyCategory.STROBE)
    override val strobe = BandedStrobeChannel(
        transaction, universe, firstChannel + 1,
        strobeMin = STROBE_MIN, strobeMax = STROBE_MAX,
    )

    @FixtureProperty("Built-in program", category = PropertyCategory.SETTING)
    val program = DmxFixtureSetting(
        transaction, universe, firstChannel + 2, Program.entries.toTypedArray(),
    )

    @FixtureProperty("Program speed", category = PropertyCategory.SPEED)
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
    }
}
