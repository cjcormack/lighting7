package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.ParkSource
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.dmx.packChannelKey
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import kotlinx.coroutines.runBlocking
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.testsupport.SineSlider
import uk.me.cormack.lighting7.testsupport.WindowedColour
import uk.me.cormack.lighting7.testsupport.WindowedSlider
import java.awt.Color
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end effects → cue layer → programmer → baseline pipeline test. Each test case matches a Worked Example from
 * [docs/lighting-composition-model.md](../../../../../../../../docs/lighting-composition-model.md),
 * plus a regression test for the Phase 0 smoke-check items that the 2026-04-19d review flagged
 * as missing automated coverage.
 *
 * Per Phase 5 of the cue-authoring unification plan: driving synthetic beat ticks through the
 * engine pays off the Phase 0 deferral that was blocked on an accessible `DmxController` stub.
 * [MockDmxController] already serves as the stub (main source), and [FxEngine.processBeatTickSuspend]
 * was relaxed to `internal` so this suite can pump ticks without the real-time loop — through the
 * `processBeatTick` shim in `FxEngineTickShims.kt`, which is where the `runBlocking` lives.
 *
 * These tests are deliberately deterministic: they use [WindowedSlider] / [WindowedColour] for
 * effect outputs so the asserted composition arithmetic does not depend on the phase of a
 * sine wave or the exact tick the loop lands on. The [TimingSource.WALL_CLOCK] path is driven
 * by calling [FxEngine.processWallClockTick] directly.
 */
class FxEnginePipelineTest {

    private val universe = Universe(0, 0)

    private data class Rig(
        val controller: MockDmxController,
        val fixtures: Fixtures,
        val engine: FxEngine,
        val programmerStore: ProgrammerStore,
        val speedMasters: SpeedMasterBank,
        val parkSource: MutableParkSource,
    )

    /**
     * Minimal mutable [ParkSource] for tests that exercise the park override path without
     * spinning up the full [uk.me.cormack.lighting7.dmx.ParkManager] (DB + project).
     */
    private class MutableParkSource : ParkSource {
        private val parked = mutableMapOf<Long, UByte>()
        fun park(universe: Int, channel: Int, value: UByte) {
            parked[packChannelKey(universe, channel)] = value
        }
        override fun getParkedValue(universe: Int, channel: Int): UByte? =
            parked[packChannelKey(universe, channel)]
        override fun isParked(universe: Int, channel: Int): Boolean =
            parked.containsKey(packChannelKey(universe, channel))
    }

    private fun newRig(firstChannel: Int = 1): Rig {
        val parkSource = MutableParkSource()
        val controller = MockDmxController(universe, parkSource = parkSource)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-a", "Hex A", firstChannel))
        }
        val programmerStore = ProgrammerStore()
        val speedMasters = SpeedMasterBank()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = speedMasters,
            programmerStore = programmerStore,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmerStore),
        )
        return Rig(controller, fixtures, engine, programmerStore, speedMasters, parkSource)
    }

    /**
     * Build a synthetic [MasterClock.ClockTick] at the given tick number. The engine's tick
     * loop owns the wall-clock timestamp normally; here we pick a fixed monotonically-
     * increasing value so `deltaMs` comes out positive.
     */
    private fun tick(n: Long): MasterClock.ClockTick {
        val ticksPerBeat = MasterClock.TICKS_PER_BEAT.toLong()
        val beat = n / ticksPerBeat
        val tickInBeat = (n % ticksPerBeat).toInt()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = beat,
            tickInBeat = tickInBeat,
            phase = tickInBeat.toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = 1_000_000L + n * 20L,
        )
    }

    private fun dimmerAssignment(
        cueId: Int,
        priority: Int = 1,
        targetKey: String = "hex-a",
        value: UByte,
        compositionOverride: CompositionRule = CompositionRule.UNSET,
    ) = CueAssignmentResolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = 1.0,
        targetKey = targetKey,
        targetIsGroup = false,
        propertyName = "dimmer",
        category = PropertyCategory.DIMMER,
        compositionOverride = compositionOverride,
        value = CueAssignmentResolver.PropertyValue.Slider(value),
    )

    private fun colourAssignment(
        cueId: Int,
        priority: Int = 1,
        targetKey: String = "hex-a",
        color: Color,
    ) = CueAssignmentResolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = 1.0,
        targetKey = targetKey,
        targetIsGroup = false,
        propertyName = "rgbColour",
        category = PropertyCategory.COLOUR,
        value = CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(color, 0u, 0u, 0u)),
    )

    private fun makeStaticDimmer(value: UByte, blendMode: BlendMode, priority: Int = 0): FxInstance =
        FxInstance(
            effect = WindowedSlider(value),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = blendMode,
        ).also { it.priority = priority }

    // ─── Worked Example 1: parked channel under an effect ───────────────────

    @Test
    fun `Worked Example 1 — parked channel defeats effect output at transmit time`() {
        val rig = newRig(firstChannel = 1)
        // Park channel 1 at 128 before any effect runs.
        rig.parkSource.park(universe.universe, 1, 128u)

        // SineSlider on dimmer, OVERRIDE blend. The engine has no ParkManager wired in, so the
        // effect still writes to the controller's raw `values` map; parking wins at transmit
        // time via `getEffectiveValue`.
        val effect = FxInstance(
            effect = SineSlider(),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        )
        rig.engine.addEffect(effect)

        // Pump a handful of ticks so the sine wave evaluates at several phases — park must win
        // for every one of them.
        for (n in 0L..8L) {
            rig.engine.processBeatTick(tick(n))
            assertEquals(
                128u.toUByte(), rig.controller.getEffectiveValue(1),
                "parked value must be the effective output regardless of effect phase (tick=$n)",
            )
        }
    }

    // ─── Worked Example 2: direct write below a running effect ──────────────

    @Test
    fun `Worked Example 2 — a programmer value suppresses a manual effect on its property`() {
        val rig = newRig(firstChannel = 1)
        // Programmer sticky write: operator dragged dimmer to 180.
        rig.programmerStore.put(ProgrammerOwner.WEB, "hex-a", "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u))

        // The programmer sits above effects: a manual (priority 0) effect on the same
        // property is suppressed — the reset pass paints 180 and the apply is skipped.
        val effect = makeStaticDimmer(value = 30u, blendMode = BlendMode.ADDITIVE)
        rig.engine.addEffect(effect)

        rig.engine.processBeatTick(tick(0))

        assertEquals(
            180u.toUByte(), rig.controller.currentValues[1],
            "the programmer value must win over the effect — no ADDITIVE composition on top",
        )
    }

    @Test
    fun `a programmer-band effect is exempt from suppression and composes over the value`() {
        val rig = newRig(firstChannel = 1)
        rig.programmerStore.put(ProgrammerOwner.WEB, "hex-a", "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u))

        // Same effect, but in the reserved programmer priority band — Session 2's
        // programmer-owned busking FX. It modulates on top of the programmer value.
        val effect = makeStaticDimmer(
            value = 30u, blendMode = BlendMode.ADDITIVE, priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE,
        )
        rig.engine.addEffect(effect)

        rig.engine.processBeatTick(tick(0))

        assertEquals(
            210u.toUByte(), rig.controller.currentValues[1],
            "band effects compose over the programmer value: 180 + 30 = 210",
        )
    }

    @Test
    fun `a suppressed effect's removal leaves the programmer value on stage`() {
        val rig = newRig(firstChannel = 1)
        rig.programmerStore.put(ProgrammerOwner.WEB, "hex-a", "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u))

        val effect = makeStaticDimmer(value = 30u, blendMode = BlendMode.ADDITIVE)
        val id = rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "suppressed while running")

        rig.engine.removeEffect(id)
        assertEquals(
            180u.toUByte(), rig.controller.currentValues[1],
            "effect removal must reset to the programmer sticky value, not to zero",
        )
    }

    @Test
    fun `suppression is per property — the same effect elsewhere still applies`() {
        val rig = newRig(firstChannel = 1)
        // Programmer holds the dimmer; an effect on the white slider is untouched.
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, rig.fixtures.fixture<HexFixture>("hex-a"), "dimmer",
            CueAssignmentResolver.PropertyValue.Slider(180u),
        )

        val whiteEffect = FxInstance(
            effect = WindowedSlider(value = 90u),
            target = SliderTarget("hex-a", "white"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        )
        rig.engine.addEffect(whiteEffect)
        rig.engine.processBeatTick(tick(0))

        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "dimmer holds the programmer value")
        assertEquals(90u.toUByte(), rig.controller.currentValues[6], "white effect applies normally")
    }

    @Test
    fun `clearing the programmer entry lets a suppressed effect resume`() {
        val rig = newRig(firstChannel = 1)
        val hex = rig.fixtures.fixture<HexFixture>("hex-a")
        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, hex, "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u),
        )

        val effect = makeStaticDimmer(value = 30u, blendMode = BlendMode.OVERRIDE)
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "suppressed under the programmer")

        rig.engine.clearProgrammerProperty(ProgrammerOwner.WEB, hex, "dimmer")
        rig.engine.processBeatTick(tick(1))
        assertEquals(
            30u.toUByte(), rig.controller.currentValues[1],
            "with the entry cleared the effect paints again on the next tick",
        )
    }

    // ─── Worked Example 3: two cues contributing HTP dimmer ─────────────────

    @Test
    fun `Worked Example 3 — HTP dimmer composition takes max across cues`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(cueId = 10, priority = 1, value = 100u)))
        rig.engine.setCueAssignments(20, listOf(dimmerAssignment(cueId = 20, priority = 2, value = 180u)))

        // With no effect running, Layer 4 publish is enough. Pump a tick to confirm the effect
        // reset path (when it runs) doesn't stomp Layer 4.
        val harmless = makeStaticDimmer(value = 50u, blendMode = BlendMode.MAX)
        rig.engine.addEffect(harmless)
        rig.engine.processBeatTick(tick(0))

        // Layer 4 HTP: max(100, 180) = 180. MAX-blend effect at 50 keeps 180.
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])

        // Fading cue A out: weight 0.5 scales A to 50. max(50, 180) = 180. Cue B stays dominant.
        rig.engine.updateCueFadeWeights(mapOf(10 to 0.5))
        rig.engine.processBeatTick(tick(1))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])
    }

    // ─── Worked Example 4: two cues contributing LTP colour ─────────────────

    @Test
    fun `Worked Example 4 — LTP colour crossfade interpolates linearly in RGB`() {
        val rig = newRig(firstChannel = 1)
        val red = colourAssignment(cueId = 10, priority = 1, color = Color(255, 0, 0))
        val blue = colourAssignment(cueId = 20, priority = 2, color = Color(0, 0, 255))

        rig.engine.setCueAssignments(10, listOf(red))
        rig.engine.setCueAssignments(20, listOf(blue))

        // Start of crossfade: B just triggered. Weight B = 0 pins A (red) on stage.
        rig.engine.updateCueFadeWeights(mapOf(10 to 1.0, 20 to 0.0))
        assertEquals(255u.toUByte(), rig.controller.currentValues[2], "start: full red")
        assertEquals(0u.toUByte(), rig.controller.currentValues[4], "start: no blue")

        // Mid-crossfade at 60% into B as described in the Worked Example. The resolver uses
        // the incoming's weight as the interpolation ratio. (1-0.6)*255=102 red, 0.6*255=153 blue.
        rig.engine.updateCueFadeWeights(mapOf(10 to 0.4, 20 to 0.6))
        assertEquals(102u.toUByte(), rig.controller.currentValues[2], "mid: 40% red")
        assertEquals(0u.toUByte(), rig.controller.currentValues[3], "mid: no green")
        assertEquals(153u.toUByte(), rig.controller.currentValues[4], "mid: 60% blue")

        // End of crossfade: pure blue.
        rig.engine.updateCueFadeWeights(mapOf(10 to 0.0, 20 to 1.0))
        assertEquals(0u.toUByte(), rig.controller.currentValues[2])
        assertEquals(255u.toUByte(), rig.controller.currentValues[4])
    }

    // ─── Worked Example 5: cue edit session with discard ────────────────────

    @Test
    fun `Worked Example 5 — discard restores snapshot`() {
        val rig = newRig(firstChannel = 1)

        // Pre-edit state: cue 42 asserts dimmer=200 and amber colour on hex-a.
        val amber = Color(255, 191, 0)
        val snapshot = listOf(
            dimmerAssignment(cueId = 42, priority = 1, value = 200u),
            colourAssignment(cueId = 42, priority = 1, color = amber),
        )
        rig.engine.setCueAssignments(42, snapshot)
        assertEquals(200u.toUByte(), rig.controller.currentValues[1])
        assertEquals(255u.toUByte(), rig.controller.currentValues[2], "amber red channel")

        // Operator edits: dimmer = 50, colour = cyan.
        val cyan = Color(0, 255, 255)
        rig.engine.setCueAssignments(42, listOf(
            dimmerAssignment(cueId = 42, priority = 1, value = 50u),
            colourAssignment(cueId = 42, priority = 1, color = cyan),
        ))
        assertEquals(50u.toUByte(), rig.controller.currentValues[1])
        assertEquals(0u.toUByte(), rig.controller.currentValues[2], "cyan red channel")
        assertEquals(255u.toUByte(), rig.controller.currentValues[3], "cyan green channel")
        assertEquals(255u.toUByte(), rig.controller.currentValues[4], "cyan blue channel")

        // Discard: re-apply the snapshot. Stage reflects restored state on the next publish.
        rig.engine.setCueAssignments(42, snapshot)
        assertEquals(200u.toUByte(), rig.controller.currentValues[1], "dimmer restored")
        assertEquals(255u.toUByte(), rig.controller.currentValues[2], "amber red restored")
        assertEquals(191u.toUByte(), rig.controller.currentValues[3], "amber green restored")
        assertEquals(0u.toUByte(), rig.controller.currentValues[4], "amber blue restored")
    }

    // ─── Phase 0 smoke-check regression coverage ────────────────────────────

    @Test
    fun `SineSlider plus updateChannel at 180 — direct write remains visible as effect baseline`() {
        val rig = newRig(firstChannel = 1)
        // updateChannel equivalent: the shim lifts the dimmer channel to a programmer entry.
        rig.programmerStore.put(ProgrammerOwner.WEB, "hex-a", "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u))
        // Immediately paint the sticky value onto the controller so any tick that runs
        // without a cue reset reads 180 as the fallback baseline (matches the real
        // updateChannel socket handler which writes through the controller).
        rig.controller.setValue(1, 180u, 0L)

        val effect = FxInstance(
            effect = SineSlider(),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        )
        rig.engine.addEffect(effect)

        // Drive several ticks. Under OVERRIDE the effect replaces the baseline each tick —
        // the guarantee here is that removing the effect falls back to 180 (programmer
        // sticky), i.e. the effect reset path observes the manual write, not zero.
        for (n in 0L..4L) rig.engine.processBeatTick(tick(n))

        rig.engine.removeEffect(effect.id)
        assertEquals(
            180u.toUByte(), rig.controller.currentValues[1],
            "post-removal reset must fall through the cue layer (empty) → programmer (180)",
        )
    }

    @Test
    fun `two OVERRIDE effects on one property — higher priority wins`() {
        val rig = newRig(firstChannel = 1)

        val low = FxInstance(
            effect = WindowedSlider(value = 80u),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        ).also { it.priority = 1 }

        val high = FxInstance(
            effect = WindowedSlider(value = 220u),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        ).also { it.priority = 5 }

        rig.engine.addEffect(low)
        rig.engine.addEffect(high)
        rig.engine.processBeatTick(tick(0))

        assertEquals(
            220u.toUByte(), rig.controller.currentValues[1],
            "higher-priority OVERRIDE effect composes last and wins",
        )
    }

    @Test
    fun `park plus effect — park wins at transmit, effect still drives raw write`() {
        val rig = newRig(firstChannel = 1)
        rig.parkSource.park(universe.universe, 1, 200u)

        val effect = makeStaticDimmer(value = 80u, blendMode = BlendMode.OVERRIDE)
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))

        // Raw write from effect is 80 (no ParkManager short-circuit in the engine path).
        assertEquals(80u.toUByte(), rig.controller.currentValues[1])
        // But the effective output for transmission is the parked value.
        assertEquals(200u.toUByte(), rig.controller.getEffectiveValue(1))
    }

    // ─── WALL_CLOCK timing path ─────────────────────────────────────────────

    @Test
    fun `WALL_CLOCK timing source is driven by processWallClockTick`() {
        val rig = newRig(firstChannel = 1)

        val effect = FxInstance(
            effect = WindowedSlider(value = 111u),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = 1.0),
            blendMode = BlendMode.OVERRIDE,
        ).apply {
            timingSource = TimingSource.WALL_CLOCK
        }
        rig.engine.addEffect(effect)

        // A beat-tick should be a no-op for a WALL_CLOCK-tagged effect — the beat snapshot
        // list skips it, so the controller stays untouched.
        rig.engine.processBeatTick(tick(0))
        assertNull(
            rig.controller.currentValues[1],
            "beat tick must not apply wall-clock effect; channel 1 should still be unwritten",
        )

        rig.engine.processWallClockTick()
        assertEquals(
            111u.toUByte(), rig.controller.currentValues[1],
            "wall-clock tick must apply wall-clock effect",
        )
    }

    // ─── Additional composition invariants ──────────────────────────────────

    @Test
    fun `cue assignments composed under a MAX-blend effect — Layer 4 baseline wins when higher`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(cueId = 10, value = 200u)))

        // MAX-blend effect at 50 should NOT lower the Layer 4 baseline of 200.
        val effect = makeStaticDimmer(value = 50u, blendMode = BlendMode.MAX)
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(200u.toUByte(), rig.controller.currentValues[1], "max(200, 50) = 200")

        // MAX-blend effect at 220 should lift above Layer 4.
        val lifter = makeStaticDimmer(value = 220u, blendMode = BlendMode.MAX)
        rig.engine.addEffect(lifter)
        rig.engine.processBeatTick(tick(1))
        assertEquals(220u.toUByte(), rig.controller.currentValues[1], "max(200, 220) = 220")
    }

    @Test
    fun `removing the only effect covering a property resets to Layer 4 composed value`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(cueId = 10, value = 120u)))

        val effect = makeStaticDimmer(value = 255u, blendMode = BlendMode.OVERRIDE)
        val id = rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(255u.toUByte(), rig.controller.currentValues[1], "effect OVERRIDES Layer 4")

        rig.engine.removeEffect(id)
        assertEquals(
            120u.toUByte(), rig.controller.currentValues[1],
            "removal falls back to Layer 4 composed value (120)",
        )
    }

    @Test
    fun `clearing all cue assignments with an effect running — reset tick paints fallback`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(cueId = 10, value = 180u)))

        // Effect running, Layer 4 asserted: max(180, 40) = 180.
        val effect = makeStaticDimmer(value = 40u, blendMode = BlendMode.MAX)
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])

        rig.engine.clearAllCueAssignments()
        // Layer 4 cleared. Next beat tick should reset to programmer (empty) → baseline (0),
        // then compose max(0, 40) = 40.
        rig.engine.processBeatTick(tick(1))
        assertEquals(40u.toUByte(), rig.controller.currentValues[1])
    }

    // ─── Worked Example 6: programmer property-level writes ─────────────────

    @Test
    fun `Worked Example 6 — writeProgrammerProperty paints RGB and UV channels`() {
        val rig = newRig(firstChannel = 1)
        val hex = rig.fixtures.fixture<HexFixture>("hex-a")

        val red = ExtendedColour(Color(255, 0, 0), uv = 128u)
        rig.engine.writeProgrammerProperty(ProgrammerOwner.WEB, hex, "rgbColour", CueAssignmentResolver.PropertyValue.Colour(red))

        // Hex R/G/B at channels 2/3/4, UV at channel 7.
        assertEquals(255u.toUByte(), rig.controller.currentValues[2], "red painted")
        assertEquals(0u.toUByte(), rig.controller.currentValues[3], "green painted")
        assertEquals(0u.toUByte(), rig.controller.currentValues[4], "blue painted")
        assertEquals(128u.toUByte(), rig.controller.currentValues[7], "uv painted via WithUv")
    }

    @Test
    fun `Worked Example 6 — a newer raw channel write wins over a programmer colour entry`() {
        val rig = newRig(firstChannel = 1)
        val hex = rig.fixtures.fixture<HexFixture>("hex-a")

        val red = ExtendedColour(Color(255, 0, 0))
        rig.engine.writeProgrammerProperty(ProgrammerOwner.WEB, hex, "rgbColour", CueAssignmentResolver.PropertyValue.Colour(red))
        assertEquals(255u.toUByte(), rig.controller.currentValues[2])

        // A raw channel write lands in the sideband; being newer than the colour entry it
        // wins recency arbitration for its channel.
        rig.engine.writeProgrammerChannel(
            ProgrammerOwner.WEB, 0, 3, 128u,
            coveringKey = CueAssignmentResolver.Key.fixture("hex-a", "rgbColour"),
        )
        assertEquals(128u.toUByte(), rig.controller.currentValues[3], "manual channel write wins")

        // Writing the colour again is newer still — and absorbs the sideband slot.
        rig.engine.writeProgrammerProperty(ProgrammerOwner.WEB, hex, "rgbColour", CueAssignmentResolver.PropertyValue.Colour(red))
        assertEquals(0u.toUByte(), rig.controller.currentValues[3], "programmer re-write stomps previous")
        assertNull(rig.programmerStore.getChannel(0, 3), "sideband slot absorbed by the property write")
    }

    @Test
    fun `Worked Example 6 — a programmer value overrides a cue on the same property`() {
        val rig = newRig(firstChannel = 1)
        val hex = rig.fixtures.fixture<HexFixture>("hex-a")

        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB,
            hex, "rgbColour",
            CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(Color(255, 0, 0))),
        )
        assertEquals(255u.toUByte(), rig.controller.currentValues[2], "programmer red on stage")

        // A cue with blue publishes underneath — the programmer keeps winning.
        rig.engine.setCueAssignments(
            10,
            listOf(colourAssignment(cueId = 10, priority = 1, color = Color(0, 0, 255))),
        )
        assertEquals(255u.toUByte(), rig.controller.currentValues[2], "programmer red beats the cue")
        assertEquals(0u.toUByte(), rig.controller.currentValues[4], "cue blue held below")

        // Clear the programmer entry: the cue's blue lands.
        rig.engine.clearProgrammerProperty(ProgrammerOwner.WEB, hex, "rgbColour")
        assertEquals(0u.toUByte(), rig.controller.currentValues[2], "red released")
        assertEquals(255u.toUByte(), rig.controller.currentValues[4], "cue blue emerges")

        // And removing the cue cascades to baseline.
        rig.engine.removeCueAssignments(10)
        assertEquals(0u.toUByte(), rig.controller.currentValues[4], "cue blue cleared")
    }

    @Test
    fun `Worked Example 6 — clearProgrammerProperty cascades back to baseline`() {
        val rig = newRig(firstChannel = 1)
        val hex = rig.fixtures.fixture<HexFixture>("hex-a")

        rig.engine.writeProgrammerProperty(
            ProgrammerOwner.WEB,
            hex, "rgbColour",
            CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(Color(200, 100, 50), uv = 128u)),
        )
        assertEquals(200u.toUByte(), rig.controller.currentValues[2])

        rig.engine.clearProgrammerProperty(ProgrammerOwner.WEB, hex, "rgbColour")
        assertEquals(0u.toUByte(), rig.controller.currentValues[2], "red cleared")
        assertEquals(0u.toUByte(), rig.controller.currentValues[3], "green cleared")
        assertEquals(0u.toUByte(), rig.controller.currentValues[4], "blue cleared")
        assertEquals(0u.toUByte(), rig.controller.currentValues[7], "uv cleared")

        // The programmer no longer holds the property.
        assertNull(rig.programmerStore.get("hex-a", "rgbColour"))
    }

    @Test
    fun `Worked Example 6 — slider property write and clear`() {
        val rig = newRig(firstChannel = 1)
        val hex = rig.fixtures.fixture<HexFixture>("hex-a")

        rig.engine.writeProgrammerProperty(ProgrammerOwner.WEB, hex, "dimmer", CueAssignmentResolver.PropertyValue.Slider(180u))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])
        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(180u),
            rig.programmerStore.get("hex-a", "dimmer")?.value?.resolved,
        )

        rig.engine.clearProgrammerProperty(ProgrammerOwner.WEB, hex, "dimmer")
        assertEquals(0u.toUByte(), rig.controller.currentValues[1])
        assertNull(rig.programmerStore.get("hex-a", "dimmer"))
    }

    @Test
    fun `pipeline is deterministic across many ticks for static effects`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(cueId = 10, value = 100u)))

        val effect = makeStaticDimmer(value = 50u, blendMode = BlendMode.ADDITIVE)
        rig.engine.addEffect(effect)

        // Run 50 ticks; the output must be stable: 100 (layer 4) + 50 (additive) = 150.
        for (n in 0L..49L) {
            rig.engine.processBeatTick(tick(n))
            assertEquals(
                150u.toUByte(), rig.controller.currentValues[1],
                "output must be stable across ticks (tick=$n)",
            )
        }
        // No spurious writes outside channel 1.
        assertTrue(
            rig.controller.writeLog.all { it.first == 1 },
            "only channel 1 should have been written; saw ${rig.controller.writeLog.map { it.first }.toSet()}",
        )
    }

    /**
     * A crossfade frame recomposes from the resolver's cached [CueAssignmentResolver.Cook]
     * at the frame's weights, rather than from `copy`-ing every fading row and re-cooking.
     * The cook is only reusable because it is a pure function of the *rows*, so a weight-only
     * republish must land on exactly what a full one at the same weights would.
     *
     * The failure this catches is a field slipping into the cook that isn't weight-independent:
     * the fade then quantises to whatever the weights were when the cue was applied, and only
     * snaps right at end-of-fade, when the outgoing cue's removal republishes fully.
     */
    @Test
    fun `a weight-only republish composes what a full republish at those weights would`() {
        val rig = newRig(firstChannel = 1)
        val outgoing = colourAssignment(cueId = 10, priority = 1, color = Color(255, 0, 0))
        val incoming = colourAssignment(cueId = 20, priority = 2, color = Color(0, 0, 255))
        rig.engine.setCueAssignments(10, listOf(outgoing))
        rig.engine.setCueAssignments(20, listOf(incoming))

        // Weight-only path: neither weight reaches 1.0, so nothing completes.
        rig.engine.updateCueFadeWeights(mapOf(10 to 0.35, 20 to 0.65))
        val fromReweight = rig.engine.layerResolver.current.index

        // Same weights, but re-asserting the rows forces the full applyAssignments path.
        // The weight has to be restated: setCueAssignments defaults to 1.0, which ends the fade.
        rig.engine.setCueAssignments(20, listOf(incoming), weight = 0.65)
        val fromFullPublish = rig.engine.layerResolver.current.index

        assertEquals(fromFullPublish, fromReweight)
    }

    // ─── Speed masters: one pass, N timebases ────────────────────────────────

    /** Frame whose slot 0 sees [tick0] and slot 1 sees [tick1]. */
    private fun frameOf(tick0: MasterClock.ClockTick, tick1: MasterClock.ClockTick) =
        SpeedMasterBank.Frame(arrayOf(tick0, tick1), maxOf(tick0.timestampMs, tick1.timestampMs))

    @Test
    fun `two effects on two masters get their own masters' phases in one pass`() {
        val rig = newRig(firstChannel = 1)
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        rig.speedMasters.load(
            listOf(
                SpeedMasterSnapshot(u1, 1, "Master 1", 120.0, SpeedMasterSource.MANUAL),
                SpeedMasterSnapshot(u2, 2, "Master 2", 60.0, SpeedMasterSource.MANUAL),
            )
        )

        // Same beat division, different masters. Master 2's clock has advanced half a beat
        // while master 1 sits at the beat boundary.
        val onMaster1 = FxInstance(
            effect = SineSlider(),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
        )
        val onMaster2 = FxInstance(
            effect = SineSlider(),
            target = SliderTarget("hex-a", "uv"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
        ).also { it.speedMasterUuid = u2 }

        rig.engine.addEffect(onMaster1)
        rig.engine.addEffect(onMaster2)
        assertEquals(0, onMaster1.speedMasterSlot)
        assertEquals(1, onMaster2.speedMasterSlot, "addEffect binds the uuid to its runtime slot")

        runBlocking { rig.engine.processBeatTickSuspend(frameOf(tick(0), tick(12))) }

        assertEquals(0.0, onMaster1.lastPhase, 0.001, "master 1's effect sees master 1's tick")
        assertEquals(0.5, onMaster2.lastPhase, 0.001, "master 2's effect sees master 2's tick — half a beat on")
    }

    @Test
    fun `deleting a master rebinds its effects to master 1 on the next pass`() {
        val rig = newRig(firstChannel = 1)
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        rig.speedMasters.load(
            listOf(
                SpeedMasterSnapshot(u1, 1, "Master 1", 120.0, SpeedMasterSource.MANUAL),
                SpeedMasterSnapshot(u2, 2, "Master 2", 60.0, SpeedMasterSource.MANUAL),
            )
        )

        val effect = FxInstance(
            effect = SineSlider(),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
        ).also { it.speedMasterUuid = u2 }
        rig.engine.addEffect(effect)
        assertEquals(1, effect.speedMasterSlot)

        // Master 2's row is deleted; the bank reloads without it.
        rig.speedMasters.load(listOf(SpeedMasterSnapshot(u1, 1, "Master 1", 120.0, SpeedMasterSource.MANUAL)))

        // The next pass re-binds before processing: the effect degrades to master 1's
        // timebase rather than freezing or crashing.
        rig.engine.processBeatTick(tick(12))
        assertEquals(0, effect.speedMasterSlot, "a deleted master's effects rebind to master 1")
        assertEquals(0.5, effect.lastPhase, 0.001, "and take master 1's tick from the uniform frame")
    }

    @Test
    fun `updateEffect's atomic swap preserves the master assignment`() {
        val rig = newRig(firstChannel = 1)
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        rig.speedMasters.load(
            listOf(
                SpeedMasterSnapshot(u1, 1, "Master 1", 120.0, SpeedMasterSource.MANUAL),
                SpeedMasterSnapshot(u2, 2, "Master 2", 60.0, SpeedMasterSource.MANUAL),
            )
        )

        val effect = FxInstance(
            effect = SineSlider(),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
        ).also { it.speedMasterUuid = u2 }
        val id = rig.engine.addEffect(effect)

        // A beat-division edit takes the atomic-swap branch (newTiming != null) — the
        // hand-enumerated field copy must carry the master or the edit silently resets
        // the effect to master 1.
        val updated = rig.engine.updateEffect(id, newTiming = FxTiming(beatDivision = BeatDivision.HALF))

        assertEquals(u2, updated?.speedMasterUuid, "the swap must carry speedMasterUuid")
        assertEquals(1, updated?.speedMasterSlot, "and the bound runtime slot")
    }

    @Test
    fun `updateEffect's atomic swap preserves the rate-master assignment`() {
        val rig = newRig(firstChannel = 1)
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        rig.speedMasters.load(
            listOf(
                SpeedMasterSnapshot(u1, 1, "Master 1", 120.0, SpeedMasterSource.MANUAL),
                SpeedMasterSnapshot(u2, 2, "Master 2", 60.0, SpeedMasterSource.MANUAL),
            )
        )

        val effect = FxInstance(
            effect = SineSlider(),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
        ).also { it.rateSpeedMasterUuid = u2 }
        val id = rig.engine.addEffect(effect)

        val updated = rig.engine.updateEffect(id, newTiming = FxTiming(beatDivision = BeatDivision.HALF))

        assertEquals(u2, updated?.rateSpeedMasterUuid, "the swap must carry rateSpeedMasterUuid too")
        assertEquals(1, updated?.rateMasterSlot, "and its bound runtime slot")
    }

    /**
     * The swap hand-copies every field that must survive, and wall-clock phase now lives
     * entirely in `accumulatedScaledMs` — so forgetting it there snaps an edited wall-clock
     * effect back to the start of its cycle, which is the discontinuity the accumulator
     * exists to prevent.
     */
    @Test
    fun `updateEffect's atomic swap preserves accumulated wall-clock time`() {
        val rig = newRig(firstChannel = 1)
        val effect = FxInstance(
            effect = SineSlider(),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = 4.0),
        ).also { it.timingSource = TimingSource.WALL_CLOCK }
        val id = rig.engine.addEffect(effect)

        // Three seconds into a four second cycle.
        effect.advanceWallClock(3_000, 1.0)
        assertEquals(0.75, effect.calculateWallClockPhase(), 1e-9)

        val updated = rig.engine.updateEffect(id, newTiming = FxTiming(beatDivision = 4.0))

        assertEquals(3_000.0, updated?.accumulatedScaledMs ?: -1.0, 1e-9, "the swap must carry the accumulator")
        assertEquals(0.75, updated?.calculateWallClockPhase() ?: -1.0, 1e-9, "so the phase does not snap to 0")
    }

    /**
     * `rateSpeedMasterUuid == null` means **unscaled**, which is not the same as "master 1".
     * `SpeedMasterBank.slotFor(null)` is slot 0 — master 1 — so before [FxInstance.NO_RATE_MASTER]
     * every wall-clock effect without a rate master silently followed master 1's tempo. Invisible
     * at the default 120 BPM (the scale is then exactly 1.0) and a rig-wide speed change the
     * moment anything tapped master 1.
     */
    @Test
    fun `a wall-clock effect with no rate master ignores master 1's tempo`() {
        val rig = newRig(firstChannel = 1)
        val u1 = UUID.randomUUID()
        rig.speedMasters.load(
            listOf(SpeedMasterSnapshot(u1, 1, "Master 1", 240.0, SpeedMasterSource.MANUAL))
        )

        fun wallClock(property: String) = FxInstance(
            effect = SineSlider(),
            target = SliderTarget("hex-a", property),
            timing = FxTiming(beatDivision = 4.0),
        ).apply { timingSource = TimingSource.WALL_CLOCK }

        val unscaled = wallClock("dimmer")
        val onMaster1 = wallClock("uv").apply { rateSpeedMasterUuid = u1 }
        rig.engine.addEffect(unscaled)
        rig.engine.addEffect(onMaster1)

        assertEquals(FxInstance.NO_RATE_MASTER, unscaled.rateMasterSlot, "no uuid, no rate master")
        assertEquals(0, onMaster1.rateMasterSlot, "master 1's uuid still resolves to slot 0")

        // The first pass only stamps the clock (deltaMs is 0 by construction); the second
        // carries a real elapsed time, which the sleep makes non-zero.
        rig.engine.processWallClockTick()
        Thread.sleep(5)
        rig.engine.processWallClockTick()

        assertTrue(unscaled.accumulatedScaledMs > 0.0, "the pass must have advanced something")
        assertEquals(
            2.0 * unscaled.accumulatedScaledMs,
            onMaster1.accumulatedScaledMs,
            1e-9,
            "master 1 at 240 BPM scales its own subscriber x2 and leaves the other alone",
        )
    }

    /**
     * Until this landed the rate master could be *preserved* but never *changed* —
     * `updateEffect` had no parameter for it, so an effect's rate assignment was fixed at
     * creation.
     */
    @Test
    fun `updateEffect can reassign the rate master, on both branches`() {
        val rig = newRig(firstChannel = 1)
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        rig.speedMasters.load(
            listOf(
                SpeedMasterSnapshot(u1, 1, "Master 1", 120.0, SpeedMasterSource.MANUAL),
                SpeedMasterSnapshot(u2, 2, "Master 2", 60.0, SpeedMasterSource.MANUAL),
            )
        )

        val effect = FxInstance(
            effect = SineSlider(),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
        )
        val id = rig.engine.addEffect(effect)

        // In-place branch: nothing immutable changed.
        val inPlace = rig.engine.updateEffect(id, newRateSpeedMasterUuid = u2)
        assertEquals(u2, inPlace?.rateSpeedMasterUuid)
        assertEquals(1, inPlace?.rateMasterSlot)

        // Swap branch: a timing edit alongside the reassignment.
        val swapped = rig.engine.updateEffect(
            id,
            newTiming = FxTiming(beatDivision = BeatDivision.HALF),
            newRateSpeedMasterUuid = u1,
        )
        assertEquals(u1, swapped?.rateSpeedMasterUuid)
        assertEquals(0, swapped?.rateMasterSlot)
    }

    // ─── Within-cue layer stomp (FU-LOOK-STOMP-WITHIN-CUE) ──────────────────

    @Test
    fun `a stomped layer's effect stops painting and the cue's cooked value shows through`() {
        val rig = newRig(firstChannel = 1)
        // Layer 4 holds 100 — the value the stomping layer asserted, which is why the cook
        // publishes a suppression entry naming the layer beneath it.
        rig.engine.setCueAssignments(
            cueId = 1,
            assignments = listOf(dimmerAssignment(cueId = 1, value = 100u)),
            stompSuppression = mapOf(7 to mapOf("hex-a" to setOf("dimmer"))),
        )

        val effect = makeStaticDimmer(value = 30u, blendMode = BlendMode.OVERRIDE).also {
            it.cueId = 1
            it.cueLayerId = 7
        }
        val id = rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))

        assertEquals(
            100u.toUByte(), rig.controller.currentValues[1],
            "the stomped layer's effect must not paint over the cooked value",
        )
        assertTrue(
            rig.engine.getActiveEffects().any { it.id == id },
            "suppression, not removal — the instance keeps running so clearing the stomp can undo it",
        )
    }

    @Test
    fun `clearing the stomp brings the same instance back`() {
        // The whole reason within-cue stomp suppresses instead of removing: disabling the stomping
        // layer, or pulling its amount to zero, only triggers a recook — and a recook has no
        // removed instance to bring back. Phase survives too, which a respawn would not give.
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(
            cueId = 1,
            assignments = listOf(dimmerAssignment(cueId = 1, value = 100u)),
            stompSuppression = mapOf(7 to mapOf("hex-a" to setOf("dimmer"))),
        )
        val effect = makeStaticDimmer(value = 30u, blendMode = BlendMode.OVERRIDE).also {
            it.cueId = 1
            it.cueLayerId = 7
        }
        val id = rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(100u.toUByte(), rig.controller.currentValues[1], "stomped")

        // Re-cooked with the stomp off: same rows, no suppression.
        rig.engine.setCueAssignments(
            cueId = 1,
            assignments = listOf(dimmerAssignment(cueId = 1, value = 100u)),
        )
        rig.engine.processBeatTick(tick(1))

        assertEquals(30u.toUByte(), rig.controller.currentValues[1], "the effect paints again")
        assertTrue(rig.engine.getActiveEffects().any { it.id == id }, "and it is the same instance")
    }

    @Test
    fun `a layer's stomp leaves the cue's own ad-hoc effect running`() {
        // A cue's ad-hoc effects belong to no layer, so nothing in the stack is above them to
        // switch them off — they sit alongside the cue's local rows, which already beat every
        // layer on values.
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(
            cueId = 1,
            assignments = listOf(dimmerAssignment(cueId = 1, value = 100u)),
            stompSuppression = mapOf(7 to mapOf("hex-a" to setOf("dimmer"))),
        )

        val adHoc = makeStaticDimmer(value = 30u, blendMode = BlendMode.OVERRIDE).also {
            it.cueId = 1
            // cueLayerId deliberately left null — this is the cue's own effect, not a layer's.
        }
        rig.engine.addEffect(adHoc)
        rig.engine.processBeatTick(tick(0))

        assertEquals(30u.toUByte(), rig.controller.currentValues[1])
    }

    @Test
    fun `stomp is per property — the stomped layer's other effects still paint`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(
            cueId = 1,
            assignments = listOf(dimmerAssignment(cueId = 1, value = 100u)),
            stompSuppression = mapOf(7 to mapOf("hex-a" to setOf("dimmer"))),
        )

        val onWhite = FxInstance(
            effect = WindowedSlider(90u),
            target = SliderTarget("hex-a", "white"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        ).also { it.cueId = 1; it.cueLayerId = 7 }
        rig.engine.addEffect(onWhite)
        rig.engine.processBeatTick(tick(0))

        assertEquals(
            90u.toUByte(), rig.controller.currentValues[6],
            "the stomper asserted dimmer only, so the same layer's white effect is untouched",
        )
    }

    @Test
    fun `a stomped programmer layer's effect is suppressed despite the band exemption`() {
        // Programmer-layer effects live in the reserved band, and the band is exempt from
        // *programmer* suppression on purpose — it modulates on top of the operator's values. Stomp
        // is checked before that exemption, or programmer stomp could never do anything at all.
        val rig = newRig(firstChannel = 1)
        rig.programmerStore.put(
            ProgrammerOwner.WEB, "hex-a", "dimmer", CueAssignmentResolver.PropertyValue.Slider(100u),
        )
        rig.engine.setProgrammerStompSuppression(mapOf(3 to mapOf("hex-a" to setOf("dimmer"))))

        val effect = makeStaticDimmer(
            value = 30u,
            blendMode = BlendMode.ADDITIVE,
            priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE,
        ).also { it.programmerLayerId = 3 }
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))

        assertEquals(
            100u.toUByte(), rig.controller.currentValues[1],
            "stomped: no ADDITIVE composition on top, despite the band exemption",
        )
    }

    @Test
    fun `a cue that stops drops its stomp suppression with its rows`() {
        // The suppression's lifecycle is the cue's, which is why it is stored per cue and set in the
        // same locked mutation as the rows. A stale entry would leave a layer's effect silenced
        // after the cue that silenced it had gone.
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(
            cueId = 1,
            assignments = listOf(dimmerAssignment(cueId = 1, value = 100u)),
            stompSuppression = mapOf(7 to mapOf("hex-a" to setOf("dimmer"))),
        )
        val effect = makeStaticDimmer(value = 30u, blendMode = BlendMode.OVERRIDE).also {
            it.cueId = 1
            it.cueLayerId = 7
        }
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(100u.toUByte(), rig.controller.currentValues[1], "stomped while the cue is live")

        rig.engine.removeCueAssignments(1)
        rig.engine.processBeatTick(tick(1))

        assertEquals(30u.toUByte(), rig.controller.currentValues[1])
    }
}
