package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sampler's *Result* is before Layer 1, and a row whose channels are all parked says so.
 * Extends the route base only for the database-backed [ParkManager].
 */
class FxSamplerParkTest : RouteIntegrationTest() {

    @Test
    fun `a row whose attribute is fully parked is marked parked`() {
        val universe = Universe(0, 0)
        val fixtures = Fixtures()
        fixtures.register {
            addController(MockDmxController(universe))
            val hexes = (0 until 2).map { addFixture(HexFixture(universe, "hex-$it", "Hex $it", 1 + it * 12)) }
            createGroup<HexFixture>("hexes") { addSpread(hexes) }
        }
        val parkManager = ParkManager(state.database, projectId)
        runBlocking { parkManager.park(universe = 0, channel = 1, value = 0u) }
        val store = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            programmerStore = store,
            layerResolver = LayerResolver(CueAssignmentResolver(), store),
            parkManager = parkManager,
        )
        val registry = FxSamplerParityTest.registry
        val sine = FxInstance(
            registry.createEffect("SineWave"), SliderTarget.forGroup("hexes", "dimmer"), FxTiming(BeatDivision.ONE_BAR), BlendMode.OVERRIDE,
        )
        engine.addEffect(sine)

        val samples = engine.sampler { registry.createEffect(it.effectTypeId, it.effect.parameters) }
            .sample(FxSampleRequest(listOf(sine)))
        val dimmer = samples.attribute("dimmer")!!
        assertTrue(dimmer.row("hex-0")!!.parked, "hex-0's dimmer, channel 1, is parked")
        assertFalse(dimmer.row("hex-1")!!.parked)
    }
}
