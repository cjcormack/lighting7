package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class KamLiteobar252FixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `writes macro, strobe and three RGB cells to the right channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = KamLiteobar252Fixture(universe, "lb-1", "Liteobar 1", 1)
            .withTransaction(transaction)

        fixture.macro.setting = KamLiteobar252Fixture.Macro.DIMMER_1
        fixture.strobe.fullOn()

        fixture.cell(0).rgbColour.redSlider.value = 10u
        fixture.cell(0).rgbColour.greenSlider.value = 11u
        fixture.cell(0).rgbColour.blueSlider.value = 12u

        fixture.cell(1).rgbColour.redSlider.value = 20u
        fixture.cell(1).rgbColour.greenSlider.value = 21u
        fixture.cell(1).rgbColour.blueSlider.value = 22u

        fixture.cell(2).rgbColour.redSlider.value = 30u
        fixture.cell(2).rgbColour.greenSlider.value = 31u
        fixture.cell(2).rgbColour.blueSlider.value = 32u

        transaction.apply()

        assertEquals(41u.toUByte(), controller.getValue(1))
        assertEquals(0u.toUByte(), controller.getValue(2))

        assertEquals(10u.toUByte(), controller.getValue(3))
        assertEquals(11u.toUByte(), controller.getValue(4))
        assertEquals(12u.toUByte(), controller.getValue(5))

        assertEquals(20u.toUByte(), controller.getValue(6))
        assertEquals(21u.toUByte(), controller.getValue(7))
        assertEquals(22u.toUByte(), controller.getValue(8))

        assertEquals(30u.toUByte(), controller.getValue(9))
        assertEquals(31u.toUByte(), controller.getValue(10))
        assertEquals(32u.toUByte(), controller.getValue(11))
    }

    @Test
    fun `exposes three multi-element cells with stable element keys`() {
        val fixture = KamLiteobar252Fixture(universe, "lb-1", "Liteobar 1", 1)

        assertEquals(3, fixture.elementCount)
        assertEquals("lb-1.cell-1", fixture.cell(0).elementKey)
        assertEquals("lb-1.cell-2", fixture.cell(1).elementKey)
        assertEquals("lb-1.cell-3", fixture.cell(2).elementKey)
    }

    @Test
    fun `strobe writes the strobe band, fullOn writes 0`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = KamLiteobar252Fixture(universe, "lb-1", "Liteobar 1", 1)
            .withTransaction(transaction)

        fixture.strobe.strobe(0u)
        transaction.apply()
        assertEquals(KamLiteobar252Fixture.STROBE_MIN, controller.getValue(2))

        fixture.strobe.strobe(255u)
        transaction.apply()
        assertEquals(KamLiteobar252Fixture.STROBE_MAX, controller.getValue(2))

        fixture.strobe.fullOn()
        transaction.apply()
        assertEquals(0u.toUByte(), controller.getValue(2))
    }

    @Test
    fun `macro enum values match personality band starts`() {
        assertEquals(0u.toUByte(), KamLiteobar252Fixture.Macro.BLACK_OUT.level)
        assertEquals(41u.toUByte(), KamLiteobar252Fixture.Macro.DIMMER_1.level)
        assertEquals(81u.toUByte(), KamLiteobar252Fixture.Macro.DIMMER_2.level)
        assertEquals(121u.toUByte(), KamLiteobar252Fixture.Macro.COL_FLASH.level)
        assertEquals(161u.toUByte(), KamLiteobar252Fixture.Macro.COL_CHANGE.level)
        assertEquals(200u.toUByte(), KamLiteobar252Fixture.Macro.COL_FLOW.level)
        assertEquals(241u.toUByte(), KamLiteobar252Fixture.Macro.DREAM_FLOW.level)
    }
}
