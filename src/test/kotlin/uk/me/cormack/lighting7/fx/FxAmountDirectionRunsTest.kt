package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.Fusion100SpotMkIIFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Size, direction and N-shot (effects plan session 1 — D2, D5, D9, P3), driven by synthetic passes
 * as `FxEnginePipelineTest` is, so what reaches the controller is a function of the tick alone.
 *
 * "Underneath" is a programmer value under a programmer-band effect: the band composes over the
 * programmer rather than being suppressed by it, so the value is exactly what the effect is weighed
 * against.
 */
class FxAmountDirectionRunsTest {

    private val universe = Universe(0, 0)

    private class Rig(val controller: MockDmxController, val fixtures: Fixtures, val engine: FxEngine, val programmer: ProgrammerStore)

    /** Two hexes (channels 1 and 13, dimmer first) in a group `pair`, and a spot whose pan and tilt are 25 and 26. */
    private fun newRig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            val a = addFixture(HexFixture(universe, "hex-a", "Hex A", 1))
            val b = addFixture(HexFixture(universe, "hex-b", "Hex B", 13))
            addFixture(Fusion100SpotMkIIFixture.Mode5Ch(universe, "spot", "Spot", 25))
            createGroup<HexFixture>("pair") { addSpread(listOf(a, b)) }
        }
        val programmer = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmer,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmer),
        )
        return Rig(controller, fixtures, engine, programmer)
    }

    private fun tick(n: Long): MasterClock.ClockTick {
        val ticksPerBeat = MasterClock.TICKS_PER_BEAT.toLong()
        val tickInBeat = (n % ticksPerBeat).toInt()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = n / ticksPerBeat,
            tickInBeat = tickInBeat,
            phase = tickInBeat.toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = 1_000_000L + n * 20L,
        )
    }

    /** A constant level, whatever the phase. */
    private class Level(private val value: UByte) : Fx {
        override val name = "Level"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: FxContext): FxOutput = FxOutput.Slider(value)
    }

    /** Paints its phase: 10 at the top of the cycle, rising 2 a percent. */
    private class PhaseSlider : Fx {
        override val name = "Phase Slider"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: FxContext): FxOutput =
            FxOutput.Slider((10 + (phase * 200).toInt()).toUByte())
    }

    private fun bandEffect(
        effect: Fx,
        target: FxTarget,
        blendMode: BlendMode = BlendMode.OVERRIDE,
        division: Double = CycleLength.ONE_BEAT,
        dynamics: (FxDynamics) -> FxDynamics = { it },
    ) = FxInstance(effect, target, FxTiming(cycle = division), blendMode).also {
        it.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE
        it.updateDynamics(dynamics)
    }

    private fun Rig.programmerDimmer(key: String, level: UByte) =
        programmer.put(ProgrammerOwner.WEB, key, "dimmer", CueAssignmentResolver.AttributeValue.Slider(level))

    // ─── Size (D2, P3) ─────────────────────────────────────────────────────

    @Test
    fun `a Replace dimmer at 50 percent sits halfway between the level underneath and the effect`() {
        val rig = newRig()
        rig.programmerDimmer("hex-a", 100u)
        rig.engine.addFx(bandEffect(Level(200u), SliderTarget("hex-a", "dimmer")) { it.copy(amount = 0.5) })

        rig.engine.processBeatTick(tick(0))

        assertEquals(150u.toUByte(), rig.controller.currentValues[1], "halfway between 100 underneath and the effect's 200")
    }

    @Test
    fun `a Replace dimmer at 50 percent going to 0 lands halfway down, never at 0`() {
        val rig = newRig()
        rig.programmerDimmer("hex-a", 100u)
        rig.engine.addFx(bandEffect(Level(0u), SliderTarget("hex-a", "dimmer")) { it.copy(amount = 0.5) })

        rig.engine.processBeatTick(tick(0))

        assertEquals(50u.toUByte(), rig.controller.currentValues[1])
    }

    @Test
    fun `a 0 percent effect leaves what is underneath untouched, and the envelope weighs with size`() {
        val rig = newRig()
        rig.programmerDimmer("hex-a", 100u)
        val effect = bandEffect(Level(200u), SliderTarget("hex-a", "dimmer")) { it.copy(amount = 0.0) }
        rig.engine.addFx(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(100u.toUByte(), rig.controller.currentValues[1], "a 0 % Size paints nothing")

        // The envelope is the other factor of the weight (§3.5's runtime part): 1.0 × 0.5.
        rig.engine.updateFx(effect.id, newAmount = 1.0)
        effect.intensityMultiplier = 0.5
        rig.engine.processBeatTick(tick(1))
        assertEquals(150u.toUByte(), rig.controller.currentValues[1], "the envelope weighs after the blend, as size does")
        assertEquals(0.5, effect.weight(effect.dynamics))
    }

    @Test
    fun `an Add dimmer at 50 percent adds half its amount to the level underneath`() {
        val rig = newRig()
        rig.programmerDimmer("hex-a", 100u)
        rig.engine.addFx(
            bandEffect(Level(60u), SliderTarget("hex-a", "dimmer"), BlendMode.ADDITIVE) { it.copy(amount = 0.5) },
        )

        rig.engine.processBeatTick(tick(0))

        assertEquals(130u.toUByte(), rig.controller.currentValues[1], "100 + 60 × 0.5")
    }

    @Test
    fun `an Around circle at 50 percent has half the radius about the position underneath`() {
        val circle = registry.createFx(
            "Circle",
            mapOf("panCentre" to "128", "tiltCentre" to "128", "panRadius" to "64", "tiltRadius" to "64"),
        )
        fun positionAt(size: Double, n: Long): Pair<Int, Int> {
            val rig = newRig()
            rig.programmer.put(
                ProgrammerOwner.WEB, "spot", "position", CueAssignmentResolver.AttributeValue.Position(100u, 100u),
            )
            rig.engine.addFx(
                bandEffect(circle, PositionTarget("spot"), BlendMode.ADDITIVE) { it.copy(amount = size) },
            )
            rig.engine.processBeatTick(tick(n))
            return rig.controller.currentValues.getValue(25).toInt() to rig.controller.currentValues.getValue(26).toInt()
        }

        // Phase 0: cos = 1, sin = 0 — the head is a full radius right of centre.
        assertEquals(164 to 100, positionAt(1.0, 0), "at full size, the whole radius about the position underneath")
        assertEquals(132 to 100, positionAt(0.5, 0), "at 50 %, half the radius about the same point")
        // A quarter cycle on: cos = 0, sin = 1.
        assertEquals(100 to 132, positionAt(0.5, 6), "the tilt axis scales the same way")
        assertEquals(100 to 100, positionAt(0.0, 6), "at 0 %, the position underneath")
    }

    @Test
    fun `a colour effect is weighed per channel`() {
        val rig = newRig()
        rig.programmer.put(
            ProgrammerOwner.WEB, "hex-a", "rgbColour",
            CueAssignmentResolver.AttributeValue.Colour(ExtendedColour(Color(200, 0, 100), 0u, 0u, 0u)),
        )
        val blue = object : Fx {
            override val name = "Blue"
            override val outputType = FxOutputType.COLOUR
            override fun calculate(phase: Double, context: FxContext): FxOutput =
                FxOutput.Colour(ExtendedColour(Color(0, 0, 255), 0u, 0u, 0u))
        }
        rig.engine.addFx(bandEffect(blue, ColourTarget("hex-a")) { it.copy(amount = 0.5) })

        rig.engine.processBeatTick(tick(0))

        // HexFixture: dimmer 1, red 2, green 3, blue 4.
        assertEquals(100, rig.controller.currentValues.getValue(2).toInt(), "red 200 → 0, halfway")
        assertEquals(0, rig.controller.currentValues.getValue(3).toInt(), "green 0 → 0")
        // 100 + (255 − 100) × 0.5 = 177.5, rounded.
        assertEquals(178, rig.controller.currentValues.getValue(4).toInt(), "blue 100 → 255, halfway")
    }

    @Test
    fun `a discrete channel takes the effect from half weight up`() {
        assertFalse(weighDiscrete(0.49))
        assertTrue(weighDiscrete(0.5))
        assertEquals(150, weigh(100, 200, 0.5))
        assertEquals(100, weigh(100, 200, 0.0))
        assertEquals(200, weigh(100, 200, 1.0))
    }

    // ─── Direction (D5) ────────────────────────────────────────────────────

    @Test
    fun `Colour Cycle backward runs blue, green, red`() {
        val cycle = registry.createFx("ColourCycle", mapOf("colours" to "#ff0000,#00ff00,#0000ff", "fadeRatio" to "0"))
        fun coloursAt(direction: FxDirection): List<Color> {
            val instance = FxInstance(cycle, ColourTarget("hex-a"), FxTiming(cycle = 3.0))
            instance.updateDynamics { it.copy(direction = direction) }
            // Mid-segment of each third of a three-beat cycle.
            return listOf(12L, 36L, 60L).map { n ->
                val phase = instance.calculatePhase(tick(n), instance.dynamics)
                ((cycle.calculate(phase, FxContext.SINGLE)) as FxOutput.Colour).color.color
            }
        }

        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE), coloursAt(FxDirection.FORWARD))
        assertEquals(listOf(Color.BLUE, Color.GREEN, Color.RED), coloursAt(FxDirection.BACKWARD))
    }

    @Test
    fun `backward reads phase 0 as the very end of the cycle, never past it`() {
        val phase = FxDirection.BACKWARD.apply(0.0, 0)
        assertTrue(phase < 1.0, "a list indexed by floor(phase × n) must not run off its end")
        assertEquals(0.75, FxDirection.BACKWARD.apply(0.25, 7))
    }

    @Test
    fun `Bounce runs forward on even cycles and back on odd ones`() {
        val rig = newRig()
        rig.engine.addFx(
            bandEffect(PhaseSlider(), SliderTarget("hex-a", "dimmer")) { it.copy(direction = FxDirection.BOUNCE) },
        )
        fun levelAt(n: Long): Int {
            rig.engine.processBeatTick(tick(n))
            return rig.controller.currentValues.getValue(1).toInt()
        }

        // A quarter into each one-beat cycle: forward 0.25 paints 60, back 0.75 paints 160.
        assertEquals(60, levelAt(6), "cycle 0 runs forward")
        assertEquals(160, levelAt(30), "cycle 1 runs back")
        assertEquals(60, levelAt(54), "cycle 2 forward again")
        assertEquals(160, levelAt(78), "cycle 3 back")
    }

    @Test
    fun `direction is applied to each member's own phase`() {
        val rig = newRig()
        rig.engine.addFx(
            bandEffect(PhaseSlider(), SliderTarget.forGroup("pair", "dimmer")) {
                it.copy(direction = FxDirection.BACKWARD)
            },
        )

        rig.engine.processBeatTick(tick(6))

        // NORMAL over two: offsets 0 and 0.5, so the members sit at 0.25 and 0.75 — read backward,
        // 0.75 and 0.25.
        assertEquals(160, rig.controller.currentValues.getValue(1).toInt(), "hex-a at 1 − 0.25")
        assertEquals(60, rig.controller.currentValues.getValue(13).toInt(), "hex-b at 1 − 0.75")
    }

    // ─── N-shot (D9) ───────────────────────────────────────────────────────

    @Test
    fun `a 2-shot ends after two cycles, each member handing back to the level underneath after its own`() {
        val rig = newRig()
        rig.programmerDimmer("hex-a", 40u)
        rig.programmerDimmer("hex-b", 40u)
        val effect = bandEffect(Level(200u), SliderTarget.forGroup("pair", "dimmer")) { it.copy(runs = 2) }
        rig.engine.addFx(effect)
        fun levels(n: Long): Pair<Int, Int> {
            rig.engine.processBeatTick(tick(n))
            return rig.controller.currentValues.getValue(1).toInt() to rig.controller.currentValues.getValue(13).toInt()
        }

        // Started at tick 100; one beat (24 ticks) a cycle. hex-a runs from 0 (offset 0), hex-b from
        // half a cycle in (offset 0.5), so it holds underneath until its own clock reaches 0.
        assertEquals(200 to 40, levels(100), "hex-b waits for its offset")
        assertEquals(200 to 200, levels(112), "half a cycle in, both run")
        assertEquals(200 to 200, levels(147), "the last tick of hex-a's second cycle")
        assertEquals(40 to 200, levels(148), "hex-a hands back after its own two cycles")
        assertNotNull(rig.engine.getFx(effect.id), "running while hex-b has runs left")
        assertEquals(40 to 200, levels(159), "hex-b's last tick")
        assertEquals(40 to 40, levels(160), "hex-b hands back after its own two")
        assertNull(rig.engine.getFx(effect.id), "two cycles past the largest offset, the effect is spent and gone")
        assertEquals(40 to 40, levels(161), "and what is underneath stays")
    }

    @Test
    fun `a held N-shot keeps counting under Blind`() {
        val rig = newRig()
        rig.programmerDimmer("hex-a", 40u)
        val effect = bandEffect(Level(200u), SliderTarget("hex-a", "dimmer")) { it.copy(runs = 1) }
        rig.engine.addFx(effect)

        rig.engine.processBeatTick(tick(0))
        assertEquals(200, rig.controller.currentValues.getValue(1).toInt(), "running before Blind")

        // Blind holds the band (show-cue-editing plan D7): the apply is skipped, the clock is not.
        rig.programmer.blind = true
        rig.engine.processBeatTick(tick(12))
        assertNotNull(rig.engine.getFx(effect.id), "half way through its one run, held")
        rig.engine.processBeatTick(tick(24))
        assertNull(rig.engine.getFx(effect.id), "its one cycle ran out while it was held")

        rig.programmer.blind = false
        rig.engine.processBeatTick(tick(25))
        assertTrue(rig.engine.getActiveFx().isEmpty(), "leaving Blind finds it spent, not restarted")
    }

    @Test
    fun `a spent programmer-layer N-shot is kept, painting nothing, so a recook cannot fire it again`() {
        val rig = newRig()
        rig.programmerDimmer("hex-a", 40u)
        val effect = bandEffect(Level(200u), SliderTarget("hex-a", "dimmer")) { it.copy(runs = 1) }
        effect.programmerLayerFxKey = ProgrammerLayerFxKey(
            layerId = 7,
            effect = StoredFx(
                fxType = "Level", family = "intensity", attribute = "dimmer",
                cycle = CycleLength.ONE_BEAT, blendMode = "OVERRIDE", order = "NORMAL",
                phaseOffset = 0.0, elementMode = null, elementFilter = null, timing = null,
                parameters = emptyMap(), speedMasterUuid = null, rateSpeedMasterUuid = null, runs = 1,
            ),
            target = uk.me.cormack.lighting7.models.FxTargetsRef.Single(uk.me.cormack.lighting7.models.TargetRef.Fixture("hex-a")),
        )
        rig.engine.addFx(effect)

        rig.engine.processBeatTick(tick(0))
        rig.engine.processBeatTick(tick(24))

        assertTrue(effect.spent, "spent")
        assertNotNull(rig.engine.getFx(effect.id), "kept for its layer")
        assertEquals(40, rig.controller.currentValues.getValue(1).toInt(), "painting nothing")
        assertTrue(rig.engine.fxDto(effect).spent, "and reported as spent")

        // A new count wakes it, from now.
        rig.engine.updateFx(effect.id, newRuns = 2)
        assertFalse(effect.spent)
        rig.engine.processBeatTick(tick(30))
        assertEquals(200, rig.controller.currentValues.getValue(1).toInt(), "running its new two cycles")
    }

    @Test
    fun `a wall-clock N-shot counts its own seconds from its first pass`() {
        val rig = newRig()
        rig.programmerDimmer("hex-a", 40u)
        val effect = bandEffect(Level(200u), SliderTarget("hex-a", "dimmer"), division = 1.0) { it.copy(runs = 1) }
        effect.timingSource = TimingSource.WALL_CLOCK
        // A long-running wall-clock effect given a count mid-run counts from then, not from its start.
        effect.accumulatedScaledMs = 10_000.0
        rig.engine.addFx(effect)

        effect.advanceWallClock(0)
        val first = effect.calculateWallClockPhase(effect.dynamics)
        assertEquals(0.0, first, "the count starts at the top of a cycle")
        effect.advanceWallClock(999)
        assertFalse(FxInstance.isHolding(effect.calculateWallClockPhase(effect.dynamics)), "still in its one second")
        effect.advanceWallClock(1)
        assertTrue(FxInstance.isHolding(effect.calculateWallClockPhase(effect.dynamics)), "one second on, done")
    }

    @Test
    fun `a forever effect's phase is unchanged by the new pipeline`() {
        val instance = FxInstance(PhaseSlider(), SliderTarget("hex-a", "dimmer"), FxTiming(cycle = 2.0))
        instance.phaseOffset = 0.1
        for (n in listOf(0L, 7L, 48L, 1_000_003L)) {
            val expected = (MasterClock.phaseForDivision(n, 2.0) + 0.1) % 1.0
            assertEquals(expected, instance.calculatePhase(tick(n), instance.dynamics), 1e-12, "tick $n")
        }
    }

    private companion object {
        /** Loaded once per JVM; the compiled-script cache makes a second load nearly free. */
        val registry: FxRegistry by lazy {
            FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInFx(it) }
        }
    }
}
