package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.effects.StaticValue
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine-level tests for the PROGRAMMER layer's half-(b) behaviours: the blind gate
 * (enter/exit republish, state preservation, writes-while-blind), fades on clear/set
 * (uncovered keys ramp; effect-covered keys settle next tick and snap), and provenance
 * winner transitions across layer events.
 */
class ProgrammerLayerTest {

    private val universe = Universe(0, 0)

    private data class Rig(
        val controller: MockDmxController,
        val fixtures: Fixtures,
        val engine: FxEngine,
        val programmerStore: ProgrammerStore,
    )

    private fun newRig(firstChannel: Int = 1): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-a", "Hex A", firstChannel))
        }
        val programmerStore = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            masterClock = MasterClock(),
            programmerStore = programmerStore,
            layerResolver = LayerResolver(Layer3Resolver(), programmerStore),
        )
        return Rig(controller, fixtures, engine, programmerStore)
    }

    private fun Rig.hex(): HexFixture = fixtures.fixture("hex-a")

    private fun dimmerAssignment(cueId: Int, value: UByte) = Layer3Resolver.Assignment(
        cueId = cueId,
        priority = 1,
        fadeWeight = 1.0,
        targetKey = "hex-a",
        targetIsGroup = false,
        propertyName = "dimmer",
        category = PropertyCategory.DIMMER,
        value = Layer3Resolver.PropertyValue.Slider(value),
    )

    private fun tick(n: Long) = MasterClock.ClockTick(
        tickNumber = n, beatNumber = 0, tickInBeat = n.toInt(), phase = 0.0,
        timestampMs = 1_000_000L + n * 20L,
    )

    // ─── Blind ───────────────────────────────────────────────────────────────

    @Test
    fun `blind enter reveals the cue value and exit restores the programmer value`() {
        val rig = newRig()
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)))
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", Layer3Resolver.PropertyValue.Slider(180u),
        )
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "programmer over cue")

        rig.engine.setProgrammerBlind(true)
        assertEquals(100u.toUByte(), rig.controller.currentValues[1], "blind reveals the cue value")

        // The stored state is untouched — entry present, touched preserved.
        val slot = rig.programmerStore.get("hex-a", "dimmer")!!
        assertEquals(ProgrammerOwner.WEB, slot.owner)
        assertTrue(slot.touched, "blind must not clear the touched flag")

        rig.engine.setProgrammerBlind(false)
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "exit restores the staged value")
    }

    @Test
    fun `writes while blind stage silently and land on exit`() {
        val rig = newRig()
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)))
        rig.engine.setProgrammerBlind(true)

        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", Layer3Resolver.PropertyValue.Slider(222u),
        )
        assertEquals(
            100u.toUByte(), rig.controller.currentValues[1],
            "a write while blind must not reach the stage",
        )

        rig.engine.setProgrammerBlind(false)
        assertEquals(222u.toUByte(), rig.controller.currentValues[1], "staged value lands on exit")
    }

    @Test
    fun `blind gates effect suppression too`() {
        val rig = newRig()
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", Layer3Resolver.PropertyValue.Slider(180u),
        )
        val effect = FxInstance(
            effect = StaticValue(60u),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        )
        rig.engine.addEffect(effect)

        rig.engine.processBeatTick(tick(0))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "effect suppressed under programmer")

        rig.engine.setProgrammerBlind(true)
        rig.engine.processBeatTick(tick(1))
        assertEquals(60u.toUByte(), rig.controller.currentValues[1], "blind releases the suppression")

        rig.engine.setProgrammerBlind(false)
        rig.engine.processBeatTick(tick(2))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "suppression resumes on exit")
    }

    // ─── Fades ───────────────────────────────────────────────────────────────

    @Test
    fun `clearing an uncovered key with fadeMs requests a ramp back to the cue value`() {
        val rig = newRig()
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)))
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", Layer3Resolver.PropertyValue.Slider(180u),
        )

        rig.engine.clearProgrammerProperty(ProgrammerOwner.WEB, rig.hex(), "dimmer", fadeMs = 1000)

        val last = rig.controller.changesTo(1).last()
        assertEquals(100u.toUByte(), last.newValue, "release target is the cue value")
        assertEquals(1000L, last.fadeMs, "the release rides the DmxController ramp")
    }

    @Test
    fun `setting a value with fadeMs requests a fade-to-value`() {
        val rig = newRig()
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", Layer3Resolver.PropertyValue.Slider(200u), fadeMs = 500,
        )
        val last = rig.controller.changesTo(1).last()
        assertEquals(200u.toUByte(), last.newValue)
        assertEquals(500L, last.fadeMs)
    }

    @Test
    fun `clearing an effect-covered key snaps on the next tick instead of fading`() {
        val rig = newRig()
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", Layer3Resolver.PropertyValue.Slider(180u),
        )
        val effect = FxInstance(
            effect = StaticValue(60u),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        )
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])

        // The publish skips effect-covered keys entirely — no faded write appears.
        val changesBefore = rig.controller.changesTo(1).size
        rig.engine.clearProgrammerProperty(ProgrammerOwner.WEB, rig.hex(), "dimmer", fadeMs = 1000)
        assertEquals(changesBefore, rig.controller.changesTo(1).size, "covered key publish is skipped")

        // The next tick settles the channel: the effect resumes painting (snap).
        rig.engine.processBeatTick(tick(1))
        assertEquals(60u.toUByte(), rig.controller.currentValues[1], "effect resumes on the next tick")
        assertEquals(0L, rig.controller.changesTo(1).last().fadeMs, "tick writes snap")
    }

    // ─── Provenance ──────────────────────────────────────────────────────────

    @Test
    fun `provenance follows the winner across programmer, effect, and cue transitions`() {
        val rig = newRig()

        fun sourceOf(property: String = "dimmer"): FxEngine.ProvenanceSource? =
            rig.engine.computeProvenance()
                .firstOrNull { it.targetKey == "hex-a" && it.propertyName == property }?.source

        assertNull(sourceOf(), "nothing contributes — baseline is omitted")

        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(10, 100u)))
        assertEquals(FxEngine.ProvenanceSource.CUE, sourceOf())
        assertEquals(
            10,
            rig.engine.computeProvenance().first { it.propertyName == "dimmer" }.cueId,
            "the winning cue is named",
        )

        val effectId = rig.engine.addEffect(
            FxInstance(
                effect = StaticValue(60u),
                target = SliderTarget("hex-a", "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
                blendMode = BlendMode.OVERRIDE,
            )
        )
        assertEquals(FxEngine.ProvenanceSource.EFFECT, sourceOf())
        assertEquals(
            effectId,
            rig.engine.computeProvenance().first { it.propertyName == "dimmer" }.effectId,
        )

        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", Layer3Resolver.PropertyValue.Slider(180u),
        )
        assertEquals(
            FxEngine.ProvenanceSource.PROGRAMMER, sourceOf(),
            "a programmer entry suppresses the effect and owns the value",
        )

        rig.engine.setProgrammerBlind(true)
        assertEquals(FxEngine.ProvenanceSource.EFFECT, sourceOf(), "blind hands the win back")
        rig.engine.setProgrammerBlind(false)

        rig.engine.removeEffect(effectId)
        rig.engine.clearProgrammerProperty(ProgrammerOwner.WEB, rig.hex(), "dimmer")
        assertEquals(FxEngine.ProvenanceSource.CUE, sourceOf(), "back to the cue after both release")

        rig.engine.removeCueAssignments(10)
        assertNull(sourceOf(), "everything released — baseline again")
    }

    @Test
    fun `a programmer-band effect wins provenance over the programmer value it rides`() {
        val rig = newRig()
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.hex(), "dimmer", Layer3Resolver.PropertyValue.Slider(180u),
        )
        rig.engine.addEffect(
            FxInstance(
                effect = StaticValue(30u),
                target = SliderTarget("hex-a", "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
                blendMode = BlendMode.ADDITIVE,
            ).also { it.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE }
        )
        val entry = rig.engine.computeProvenance().first { it.propertyName == "dimmer" }
        assertEquals(FxEngine.ProvenanceSource.EFFECT, entry.source, "band effects modulate on top")
    }
}
