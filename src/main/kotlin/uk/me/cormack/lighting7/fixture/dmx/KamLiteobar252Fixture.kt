package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.property.Strobe
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithStrobe
import kotlin.math.roundToInt

/**
 * Kam Liteobar 252 — three-cell LED bar (RGB only).
 *
 * Reverse-engineered from the ChamSys library since no manual is bundled.
 * Authoritative channel map:
 * `Manuals/personalities/Kam_Liteobar252_11ch.md` (transcribed from MagicQ
 * `EDIT HEAD` — the on-disk `.hed` files are obfuscated).
 *
 * The fixture has no continuous master dimmer. Overall brightness comes
 * from the cell RGB values themselves; the macro channel must be in the
 * `DIMMER_1` or `DIMMER_2` band (041–120) for cells to be visible. Below
 * 041 the fixture is in blackout regardless of cell levels. Scripts that
 * just want "cells working normally" should set `macro.setting = Macro.DIMMER_1`
 * after fixture creation — the channel default at startup is 000 (blackout).
 */
@FixtureType("kam-liteobar-252-11ch", manufacturer = "Kam", model = "Liteobar 252")
class KamLiteobar252Fixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    private val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 11, key, fixtureName),
    WithStrobe,
    MultiElementFixture<KamLiteobar252Fixture.Cell> {

    private constructor(
        fixture: KamLiteobar252Fixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction): KamLiteobar252Fixture =
        KamLiteobar252Fixture(this, transaction)

    /**
     * Channel 1 — master macro. Selects between blackout, two dimmer
     * passthrough modes, and four built-in colour effects. Within each
     * effect band the level controls the speed/intensity of that effect.
     */
    enum class Macro(override val level: UByte) : DmxFixtureSettingValue {
        BLACK_OUT(0u),
        DIMMER_1(41u),
        DIMMER_2(81u),
        COL_FLASH(121u),
        COL_CHANGE(161u),
        COL_FLOW(200u),
        DREAM_FLOW(241u),
    }

    /**
     * Channel 2 strobe. Personality lists `001–255 Strobe S>F` with no
     * sub-bands — modelled like the Varytec Easymove: 0 = constant on
     * (no strobe), 1–255 = slow → fast.
     */
    class StrobeChannel(
        transaction: ControllerTransaction?,
        universe: Universe,
        channelNo: Int,
    ) : DmxSlider(transaction, universe, channelNo), Strobe {
        override fun fullOn() {
            value = 0u
        }

        override fun strobe(intensity: UByte) {
            val span = (STROBE_MAX - STROBE_MIN).toFloat()
            value = ((span / 255F * intensity.toFloat()).roundToInt() + STROBE_MIN.toInt()).toUByte()
        }

        companion object {
            const val STROBE_MIN: UByte = 1u
            const val STROBE_MAX: UByte = 255u
        }
    }

    /**
     * One of three RGB cells (CH3–5, CH6–8, CH9–11). Each cell exposes
     * [WithColour] independently so it can be FX-targeted via the
     * [MultiElementFixture] elements list.
     */
    inner class Cell(
        override val elementIndex: Int,
        cellTransaction: ControllerTransaction?,
        private val cellFirstChannel: Int,
    ) : FixtureElement<KamLiteobar252Fixture>, WithColour {

        override val parentFixture: KamLiteobar252Fixture
            get() = this@KamLiteobar252Fixture

        override val elementKey: String
            get() = "${this@KamLiteobar252Fixture.key}.cell-${elementIndex + 1}"

        @FixtureProperty(category = PropertyCategory.COLOUR)
        override val rgbColour = DmxColour(
            cellTransaction, universe,
            cellFirstChannel,
            cellFirstChannel + 1,
            cellFirstChannel + 2,
        )

        override fun withTransaction(transaction: ControllerTransaction): Cell =
            Cell(elementIndex, transaction, cellFirstChannel)

        override fun toString(): String = "Cell($elementKey)"
    }

    @FixtureProperty("Master macro (blackout / dimmer / built-in effects)", category = PropertyCategory.SETTING)
    val macro = DmxFixtureSetting(
        transaction, universe, firstChannel, Macro.entries.toTypedArray(),
    )

    @FixtureProperty(category = PropertyCategory.STROBE)
    override val strobe = StrobeChannel(transaction, universe, firstChannel + 1)

    override val elements: List<Cell> = (0 until 3).map { idx ->
        Cell(idx, transaction, firstChannel + 2 + (idx * 3))
    }

    override val elementCount: Int = 3

    fun cell(index: Int): Cell {
        require(index in 0 until 3) { "Cell index must be 0-2, got $index" }
        return elements[index]
    }
}
