package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureTriggers
import uk.me.cormack.lighting7.routes.TriggerPropertyDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EquinoxTwinShotMkIIFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `output1 and output2 are triggers on consecutive channels, and the master is their arm`() {
        val fixture = EquinoxTwinShotMkIIFixture(universe, "twin-shot-1", "Twin Shot 1", 100)

        val triggers = FixtureTriggers.of(fixture)
        assertEquals(listOf("output1" to 100, "output2" to 101), triggers.map { it.name to it.channelNo })
        assertEquals(listOf("A", "B"), triggers.map { it.spec.label })
        assertTrue(triggers.all { it.armChannelNo == 102 && it.spec.armName == "master" })
        assertEquals(setOf("output1", "output2", "master"), FixtureTriggers.reservedNamesOf(fixture::class))

        // Not properties: nothing that resolves a property by name finds them.
        assertTrue(fixture.fixtureProperties.isEmpty())
        // Listed for the panel as triggers, and named for the DMX sheet.
        assertEquals(listOf("output1", "output2"), fixture.generatePropertyDescriptors().filterIsInstance<TriggerPropertyDescriptor>().map { it.name })
        assertEquals("Master enable (arm)", fixture.channelDescriptions()[102])
        assertEquals("Tube A (trigger)", fixture.channelDescriptions()[100])
    }
}
