package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.FxTargetsRef
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.testsupport.PULSE_WAVE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Local FX in the programmer band (FX editor plan D5, §3.5): every Local FX applies above every
 * programmer layer's FX, a new one takes the top of its lane, and `moveLocalFx` re-ranks one
 * without restarting anything. Every pass is driven through `FxEngineTickShims`, and what wins is
 * read off the committed DMX.
 */
class LocalFxBandTest {

    private val universe = Universe(0, 0)

    /** Hex A at channel 1 and Hex B at 13: a dimmer at +0, UV at +6. */
    private class Rig(val controller: MockDmxController, val engine: FxEngine) {
        val dimmer: Int get() = controller.currentValues.getValue(1).toInt()
    }

    private fun newRig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-a", "Hex A", 1))
            addFixture(HexFixture(universe, "hex-b", "Hex B", 13))
        }
        return Rig(controller, FxEngine(fixtures = fixtures))
    }

    private fun tick(n: Long): MasterClock.ClockTick {
        val ticksPerBeat = MasterClock.TICKS_PER_BEAT.toLong()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = n / ticksPerBeat,
            tickInBeat = (n % ticksPerBeat).toInt(),
            phase = (n % ticksPerBeat).toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = n * 20L,
        )
    }

    /** One level, whatever the phase: the FX on top is the one the dimmer shows. */
    private class Level(private val value: UByte) : Fx {
        override val name = "Level $value"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: FxContext): FxOutput = FxOutput.Slider(value)
    }

    /** A Local FX: in the band, owned by no layer, Look or cue. [FxEngine.addFx] ranks it. */
    private fun local(effect: Fx, key: String = "hex-a", attribute: String = "dimmer") =
        FxInstance(effect, SliderTarget(key, attribute), FxTiming(cycle = 1.0), BlendMode.OVERRIDE).also {
            it.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE
        }

    /** A programmer layer's FX at layer index [index], as `ProgrammerLayerStack` spawns one. */
    private fun layerFx(effect: Fx, index: Int, layerId: Int = 1) =
        FxInstance(effect, SliderTarget("hex-a", "dimmer"), FxTiming(cycle = 1.0), BlendMode.OVERRIDE).also {
            it.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE + index
            it.programmerLayerFxKey = ProgrammerLayerFxKey(
                layerId,
                StoredFx(
                    fxType = effect.name, family = "intensity", attribute = "dimmer", cycle = 1.0,
                    blendMode = "OVERRIDE", order = "NORMAL", phaseOffset = 0.0, elementMode = null,
                    elementFilter = null, timing = null, parameters = emptyMap(), speedMasterUuid = null,
                    rateSpeedMasterUuid = null,
                ),
                FxTargetsRef.of(listOf(TargetRef.Fixture("hex-a"))),
            )
        }

    private fun Rig.pass(n: Long = 0) = engine.processBeatTick(tick(n))

    private fun Rig.rankOf(instance: FxInstance) = FxEngine.localRankOf(instance.priority)

    @Test
    fun `a Local Pulse applies above a palette layer's FX on the same dimmer`() {
        val registry = FxComposePassTest.registry
        fun sine() = layerFx(registry.createFx("Wave", emptyMap()), index = 3)
        fun pulse() = local(registry.createFx("Wave", PULSE_WAVE))

        val both = newRig().apply { engine.addFx(sine()); engine.addFx(pulse()) }
        val pulseAlone = newRig().apply { engine.addFx(pulse()) }
        val sineAlone = newRig().apply { engine.addFx(sine()) }

        val ticks = 0L until 48L
        val committed = ticks.map { both.pass(it); both.dimmer }
        assertEquals(ticks.map { pulseAlone.pass(it); pulseAlone.dimmer }, committed, "the Pulse wins every pass")
        assertNotEquals(ticks.map { sineAlone.pass(it); sineAlone.dimmer }, committed, "and the two differ")
    }

    @Test
    fun `every Local FX is above every layer's FX in the band`() {
        val rig = newRig()
        val layer = layerFx(Level(40u), index = FxEngine.PROGRAMMER_FX_RANK_CLAMP)
        rig.engine.addFx(layer)
        val mine = local(Level(200u))
        rig.engine.addFx(mine)

        assertTrue(mine.priority > layer.priority)
        assertTrue(FxEngine.isProgrammerFxPriority(mine.priority))
        assertTrue(mine.isLocalFx)
        rig.pass()
        assertEquals(200, rig.dimmer)
    }

    @Test
    fun `a new Local FX takes the top of its lane`() {
        val rig = newRig()
        val first = local(Level(50u)).also { rig.engine.addFx(it) }
        val second = local(Level(200u)).also { rig.engine.addFx(it) }
        val uv = local(Level(10u), attribute = "uv").also { rig.engine.addFx(it) }
        val otherHead = local(Level(10u), key = "hex-b").also { rig.engine.addFx(it) }

        assertEquals(0, rig.rankOf(first))
        assertEquals(1, rig.rankOf(second), "one above the Local FX it shares Hex A's dimmer with")
        assertEquals(0, rig.rankOf(uv), "another attribute is another lane")
        assertEquals(0, rig.rankOf(otherHead), "and so is another head")
        rig.pass()
        assertEquals(200, rig.dimmer)
    }

    @Test
    fun `a new Local FX starts above one still fading out on its key`() {
        val rig = newRig()
        local(Level(50u)).also { rig.engine.addFx(it) }
        val stacked = local(Level(120u)).also { rig.engine.addFx(it) }
        rig.engine.fadeOutFx(listOf(stacked), 10_000)
        assertTrue(stacked.leaving)

        val next = local(Level(200u)).also { rig.engine.addFx(it) }
        assertTrue(next.priority > stacked.priority)
    }

    @Test
    fun `a move re-ranks, and a second move undoes it`() {
        val rig = newRig()
        val low = local(Level(50u)).also { rig.engine.addFx(it) }
        val high = local(Level(200u)).also { rig.engine.addFx(it) }
        rig.pass()
        assertEquals(200, rig.dimmer)

        val down = assertIs<FxEngine.LocalMoveOutcome.Moved>(rig.engine.moveLocalFx(high.id, aboveId = low.id))
        assertEquals(setOf(high.id, low.id), down.changed.toSet(), "both priorities moved")
        rig.pass(1)
        assertEquals(50, rig.dimmer, "the moved FX now applies under the other")

        assertIs<FxEngine.LocalMoveOutcome.Moved>(rig.engine.moveLocalFx(low.id, aboveId = high.id))
        rig.pass(2)
        assertEquals(200, rig.dimmer, "and moving the other back restores the order")
        assertTrue(rig.engine.getFx(low.id) === low && rig.engine.getFx(high.id) === high, "nothing respawned")
    }

    @Test
    fun `a move to above null goes to the bottom`() {
        val rig = newRig()
        val a = local(Level(50u)).also { rig.engine.addFx(it) }
        val b = local(Level(120u)).also { rig.engine.addFx(it) }
        val c = local(Level(200u)).also { rig.engine.addFx(it) }

        assertIs<FxEngine.LocalMoveOutcome.Moved>(rig.engine.moveLocalFx(c.id, aboveId = null))

        val order = listOf(a, b, c).sortedWith(compareBy({ it.priority }, { it.composeOrder }, { it.id }))
        assertEquals(listOf(c, a, b), order)
        rig.pass()
        assertEquals(120, rig.dimmer)

        val again = assertIs<FxEngine.LocalMoveOutcome.Moved>(rig.engine.moveLocalFx(c.id, aboveId = null))
        assertEquals(emptyList(), again.changed, "already at the bottom, so nothing moves")
    }

    @Test
    fun `a copy built before a move takes the moved rank when it is swapped in`() {
        val rig = newRig()
        val parent = local(Level(200u)).also { rig.engine.addFx(it) }
        val other = local(Level(50u)).also { rig.engine.addFx(it) }
        val copies = rig.engine.headCopies(parent)

        rig.engine.moveLocalFx(other.id, aboveId = parent.id)
        assertTrue(parent.priority > other.priority)
        rig.engine.replaceFx(mapOf(parent to copies))

        assertEquals(parent.priority, copies.single().priority)
        rig.pass()
        assertEquals(200, rig.dimmer)
    }

    @Test
    fun `only a Local FX moves, and only below a Local FX`() {
        val rig = newRig()
        val layer = layerFx(Level(40u), index = 2).also { rig.engine.addFx(it) }
        val manual = FxInstance(Level(10u), SliderTarget("hex-a", "dimmer"), FxTiming(), BlendMode.OVERRIDE)
            .also { rig.engine.addFx(it) }
        val mine = local(Level(200u)).also { rig.engine.addFx(it) }
        val before = listOf(layer, manual, mine).map { it.priority }

        assertEquals(FxEngine.LocalMoveOutcome.NotLocal(layer.id), rig.engine.moveLocalFx(layer.id, null))
        assertEquals(FxEngine.LocalMoveOutcome.NotLocal(manual.id), rig.engine.moveLocalFx(manual.id, null))
        assertEquals(FxEngine.LocalMoveOutcome.NotLocal(layer.id), rig.engine.moveLocalFx(mine.id, layer.id))
        assertEquals(FxEngine.LocalMoveOutcome.NotFound(999L), rig.engine.moveLocalFx(999L, null))
        assertEquals(FxEngine.LocalMoveOutcome.NotFound(999L), rig.engine.moveLocalFx(mine.id, 999L))
        assertEquals(before, listOf(layer, manual, mine).map { it.priority }, "a refusal moves nothing")
    }

    @Test
    fun `a detached layer's FX joins the Local FX at their bottom, so nothing on the key moves`() {
        val rig = newRig()
        val mine = local(Level(200u)).also { rig.engine.addFx(it) }
        val layer = layerFx(Level(40u), index = 0, layerId = 7).also { rig.engine.addFx(it) }
        rig.pass()
        assertEquals(200, rig.dimmer)

        assertEquals(1, rig.engine.detachProgrammerLayerFx(7))

        assertTrue(layer.isLocalFx)
        assertTrue(rig.rankOf(layer) >= 0, "in the Local FX's part of the band")
        assertTrue(layer.priority < mine.priority, "under the Local FX it was already under")
        rig.pass(1)
        assertEquals(200, rig.dimmer)
    }
}
