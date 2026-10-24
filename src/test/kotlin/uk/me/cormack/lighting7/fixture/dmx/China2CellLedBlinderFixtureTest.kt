package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.createTestTransaction
import uk.me.cormack.lighting7.fixture.group.GroupWhiteMixAttributeDescriptor
import uk.me.cormack.lighting7.fx.BlendMode
import uk.me.cormack.lighting7.fx.ColourTarget
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.FxOutput
import uk.me.cormack.lighting7.routes.WhiteMixAttributeDescriptor
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class China2CellLedBlinderFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `writes dimmer, strobe, programs and two WW-CW cells to the right channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", 1)
            .withTransaction(transaction)

        fixture.dimmer.value = 200u
        fixture.strobe.fullOn()
        fixture.program.setting = China2CellLedBlinderFixture.Program.COL_GRADIENT
        fixture.progSpeed.value = 128u

        fixture.cell(0).warmWhite.value = 10u
        fixture.cell(0).coldWhite.value = 20u
        fixture.cell(1).warmWhite.value = 30u
        fixture.cell(1).coldWhite.value = 40u

        transaction.apply()

        assertEquals(200u.toUByte(), controller.getValue(1))
        assertEquals(0u.toUByte(), controller.getValue(2))
        assertEquals(80u.toUByte(), controller.getValue(3))
        assertEquals(128u.toUByte(), controller.getValue(4))
        assertEquals(10u.toUByte(), controller.getValue(5))
        assertEquals(20u.toUByte(), controller.getValue(6))
        assertEquals(30u.toUByte(), controller.getValue(7))
        assertEquals(40u.toUByte(), controller.getValue(8))
    }

    @Test
    fun `exposes two multi-element cells with stable element keys`() {
        val fixture = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", 1)

        assertEquals(2, fixture.elementCount)
        assertEquals("blinder-1.cell-1", fixture.cell(0).elementKey)
        assertEquals("blinder-1.cell-2", fixture.cell(1).elementKey)
    }

    @Test
    fun `strobe writes the 011-255 strobe band, fullOn writes 0`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", 1)
            .withTransaction(transaction)

        fixture.strobe.strobe(0u)
        transaction.apply()
        assertEquals(China2CellLedBlinderFixture.STROBE_MIN, controller.getValue(2))

        fixture.strobe.strobe(255u)
        transaction.apply()
        assertEquals(China2CellLedBlinderFixture.STROBE_MAX, controller.getValue(2))

        fixture.strobe.fullOn()
        transaction.apply()
        assertEquals(0u.toUByte(), controller.getValue(2))
    }

    @Test
    fun `program enum values match personality band starts`() {
        assertEquals(0u.toUByte(), China2CellLedBlinderFixture.Program.NO_FUNCTION.level)
        assertEquals(40u.toUByte(), China2CellLedBlinderFixture.Program.COL_JUMP.level)
        assertEquals(80u.toUByte(), China2CellLedBlinderFixture.Program.COL_GRADIENT.level)
        assertEquals(120u.toUByte(), China2CellLedBlinderFixture.Program.COL_PULSE.level)
        assertEquals(160u.toUByte(), China2CellLedBlinderFixture.Program.COL_MUTATION.level)
        assertEquals(200u.toUByte(), China2CellLedBlinderFixture.Program.AUTO_MODE.level)
        assertEquals(240u.toUByte(), China2CellLedBlinderFixture.Program.SOUND_ACTIVE.level)
    }

    @Test
    fun `a cell's colour lands as its nearest warm-cold mix, on that cell's channels only`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", 1)
            .withTransaction(transaction)

        fixture.cell(0).rgbColour.value = Color.RED
        fixture.cell(1).rgbColour.value = Color(0, 0, 128)
        transaction.apply()

        assertEquals(255u.toUByte(), controller.getValue(5))
        assertEquals(0u.toUByte(), controller.getValue(6))
        assertEquals(0u.toUByte(), controller.getValue(7))
        assertEquals(128u.toUByte(), controller.getValue(8))
        assertEquals(0u.toUByte(), controller.getValue(1), "the master dimmer is the parent's, untouched")
    }

    @Test
    fun `a cell's colour reads back as the mix its sliders make, however they were set`() {
        val (_, transaction) = createTestTransaction(universe)
        val cell = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", 1)
            .withTransaction(transaction).cell(0)

        cell.warmWhite.value = 200u
        cell.coldWhite.value = 60u

        assertEquals(China2CellLedBlinderFixture.WHITE_MIX.colourOf(200u, 60u), cell.rgbColour.value)
    }

    @Test
    fun `a colour effect on a cell drives its whites`() {
        val (controller, transaction) = createTestTransaction(universe)
        val cell = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", 1)
            .withTransaction(transaction).cell(1)

        ColourTarget(cell.elementKey).applyValueToFixture(cell, FxOutput.Colour(ExtendedColour(Color.BLUE)), BlendMode.OVERRIDE)
        transaction.apply()

        assertEquals(0u.toUByte(), controller.getValue(7))
        assertEquals(255u.toUByte(), controller.getValue(8))
    }

    @Test
    fun `each cell describes its warmth with the type's declared whites, and the parent has none`() {
        val fixture = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", 1)

        assertTrue(fixture.generateAttributeDescriptors().none { it is WhiteMixAttributeDescriptor })
        val cells = checkNotNull(fixture.generateElementDescriptors())
        for ((index, cell) in cells.withIndex()) {
            val warmth = cell.attributes.filterIsInstance<WhiteMixAttributeDescriptor>().single()
            assertEquals("rgbColour", warmth.name)
            assertEquals(5 + index * 2, warmth.warmChannel.channelNo)
            assertEquals(6 + index * 2, warmth.coldChannel.channelNo)
            assertEquals(3000, warmth.warmKelvin)
            assertEquals(6500, warmth.coldKelvin)
            assertEquals(2, cell.attributes.count { it.category == "white" }, "the sliders stay for direct control")
        }
        val allCells = fixture.generateElementGroupAttributeDescriptors()?.filterIsInstance<GroupWhiteMixAttributeDescriptor>()?.single()
        assertEquals("rgbColour", allCells?.name)
        assertEquals(listOf(5 to 6, 7 to 8), allCells?.memberChannels?.map { it.warmChannel.channelNo to it.coldChannel.channelNo })
    }

    @Test
    fun `the type reports colour among its capabilities, from its cells`() {
        val info = assertNotNull(FixtureTypeRegistry.typeInfoForKey("china-2-cell-led-blinder-8ch"))
        assertEquals(listOf("dimmer", "strobe", "multi-element", "colour"), info.capabilities)
    }
}
