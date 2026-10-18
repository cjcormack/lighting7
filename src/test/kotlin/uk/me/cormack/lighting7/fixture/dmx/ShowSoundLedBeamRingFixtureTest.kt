package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import uk.me.cormack.lighting7.routes.SettingPropertyDescriptor
import uk.me.cormack.lighting7.routes.SliderPropertyDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShowSoundLedBeamRingFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `14ch mode writes every property to the head file's channel`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = ShowSoundLedBeamRingFixture.Mode14Ch(universe, "beam-1", "Beam 1", 1)
            .withTransaction(transaction)

        fixture.pan.value = 100u
        fixture.panFine.value = 101u
        fixture.tilt.value = 102u
        fixture.tiltFine.value = 103u
        fixture.panTiltSpeed.value = 104u
        fixture.dimmer.value = 105u
        fixture.strobe.strobe(0u)
        fixture.colourWheel.setting = ShowSoundLedBeamRingFixture.Colour.BLUE
        fixture.gobo.setting = ShowSoundLedBeamRingFixture.Gobo.STARS
        fixture.prism.setting = ShowSoundLedBeamRingFixture.Prism.PRISM_ROTATING
        fixture.macro.setting = ShowSoundLedBeamRingFixture.Macro.STOP
        fixture.ringFx.setting = ShowSoundLedBeamRingFixture.RingFx.CHASE
        fixture.ringSpeed.value = 106u

        transaction.apply()

        assertEquals(
            listOf(100, 101, 102, 103, 104, 105, 100, 48, 40, 128, 101, 0, 75, 106),
            (1..14).map { controller.getValue(it).toInt() },
            "ch 7 is the strobe's first strobe value; ch 12 the reset command's, which nothing writes through the fixture",
        )
    }

    @Test
    fun `strobe maps into the 100-200 band, fullOn writes 0`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = ShowSoundLedBeamRingFixture.Mode14Ch(universe, "beam-1", "Beam 1", 1)
            .withTransaction(transaction)

        fixture.strobe.strobe(255u)
        transaction.apply()
        assertEquals(200u.toUByte(), controller.getValue(7))

        fixture.strobe.fullOn()
        transaction.apply()
        assertEquals(0u.toUByte(), controller.getValue(7))
    }

    @Test
    fun `the ring's bands reach the descriptor, and the speed is a ring speed slider`() {
        val descriptors = ShowSoundLedBeamRingFixture.Mode14Ch(universe, "beam-1", "Beam 1", 1)
            .generatePropertyDescriptors()
        val ring = descriptors.filterIsInstance<SettingPropertyDescriptor>().single { it.name == "ringFx" }
        assertEquals("ring", ring.category)
        val byName = ring.options.associateBy { it.name }
        assertNull(byName.getValue("OFF").ringColour)
        assertNull(byName.getValue("OFF").ringChase)
        assertEquals("#FFFFFF", byName.getValue("COLOUR").ringColour)
        assertEquals(true, byName.getValue("CHASE").ringChase)
        assertEquals(true, byName.getValue("CHASE_2").ringChase)
        assertNull(descriptors.filterIsInstance<SettingPropertyDescriptor>().single { it.name == "colourWheel" }
            .options.first().ringColour, "only a ring option carries a ring colour")

        val speed = descriptors.filterIsInstance<SliderPropertyDescriptor>().single { it.name == "ringSpeed" }
        assertEquals("ring_speed", speed.category)
    }
}
