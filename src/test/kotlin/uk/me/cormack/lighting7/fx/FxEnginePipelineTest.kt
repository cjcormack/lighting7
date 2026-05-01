package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.ParkSource
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.dmx.packChannelKey
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.effects.SineWave
import uk.me.cormack.lighting7.fx.effects.StaticColour
import uk.me.cormack.lighting7.fx.effects.StaticValue
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end Layer 2 → 3 → 4 → 5 pipeline test. Each test case matches a Worked Example from
 * [docs/lighting-composition-model.md](../../../../../../../../docs/lighting-composition-model.md),
 * plus a regression test for the Phase 0 smoke-check items that the 2026-04-19d review flagged
 * as missing automated coverage.
 *
 * Per Phase 5 of the cue-authoring unification plan: driving synthetic beat ticks through the
 * engine pays off the Phase 0 deferral that was blocked on an accessible `DmxController` stub.
 * [MockDmxController] already serves as the stub (main source), and [FxEngine.processBeatTick]
 * was relaxed to `internal` so this suite can pump ticks without the real-time loop.
 *
 * These tests are deliberately deterministic: they use [StaticValue] / [StaticColour] for
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
        val directWriteStore: DirectWriteStore,
        val masterClock: MasterClock,
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
        val directWriteStore = DirectWriteStore()
        val masterClock = MasterClock()
        val engine = FxEngine(
            fixtures = fixtures,
            masterClock = masterClock,
            directWriteStore = directWriteStore,
            layerResolver = LayerResolver(Layer3Resolver(), directWriteStore),
        )
        return Rig(controller, fixtures, engine, directWriteStore, masterClock, parkSource)
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
    ) = Layer3Resolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = 1.0,
        targetKey = targetKey,
        targetIsGroup = false,
        propertyName = "dimmer",
        category = PropertyCategory.DIMMER,
        compositionOverride = compositionOverride,
        value = Layer3Resolver.PropertyValue.Slider(value),
    )

    private fun colourAssignment(
        cueId: Int,
        priority: Int = 1,
        targetKey: String = "hex-a",
        color: Color,
    ) = Layer3Resolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = 1.0,
        targetKey = targetKey,
        targetIsGroup = false,
        propertyName = "rgbColour",
        category = PropertyCategory.COLOUR,
        value = Layer3Resolver.PropertyValue.Colour(ExtendedColour(color, 0u, 0u, 0u)),
    )

    private fun makeStaticDimmer(value: UByte, blendMode: BlendMode, priority: Int = 0): FxInstance =
        FxInstance(
            effect = StaticValue(value),
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

        // SineWave on dimmer, OVERRIDE blend. The engine has no ParkManager wired in, so the
        // effect still writes to the controller's raw `values` map; parking wins at transmit
        // time via `getEffectiveValue`.
        val effect = FxInstance(
            effect = SineWave(),
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
    fun `Worked Example 2 — sticky direct write persists with ADDITIVE effect on top`() {
        val rig = newRig(firstChannel = 1)
        // Layer 4 sticky write: operator dragged dimmer to 180.
        rig.directWriteStore.put(universe = 0, channel = 1, value = 180u)

        // Additive effect range 0..30 so composition is deterministic: 180 + 30 = 210 whenever
        // StaticValue emits its constant.
        val effect = makeStaticDimmer(value = 30u, blendMode = BlendMode.ADDITIVE)
        rig.engine.addEffect(effect)

        rig.engine.processBeatTick(tick(0))

        assertEquals(
            210u.toUByte(), rig.controller.currentValues[1],
            "direct write must persist under effect reset; ADDITIVE composes 180 + 30 = 210",
        )
    }

    @Test
    fun `Worked Example 2 regression — effect reset falls through to Layer 4 not zero`() {
        val rig = newRig(firstChannel = 1)
        rig.directWriteStore.put(universe = 0, channel = 1, value = 180u)

        // First tick paints 180 + 30 = 210 (see above). Removing the effect should leave the
        // sticky 180 on the stage, NOT drop to zero — this is the pre-Phase-0 regression.
        val effect = makeStaticDimmer(value = 30u, blendMode = BlendMode.ADDITIVE)
        val id = rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(210u.toUByte(), rig.controller.currentValues[1])

        rig.engine.removeEffect(id)
        assertEquals(
            180u.toUByte(), rig.controller.currentValues[1],
            "effect removal must reset to Layer 4 sticky value, not to zero",
        )
    }

    // ─── Worked Example 3: two cues contributing HTP dimmer ─────────────────

    @Test
    fun `Worked Example 3 — HTP dimmer composition takes max across cues`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(cueId = 10, priority = 1, value = 100u)))
        rig.engine.setCueAssignments(20, listOf(dimmerAssignment(cueId = 20, priority = 2, value = 180u)))

        // With no effect running, Layer 3 publish is enough. Pump a tick to confirm the effect
        // reset path (when it runs) doesn't stomp Layer 3.
        val harmless = makeStaticDimmer(value = 50u, blendMode = BlendMode.MAX)
        rig.engine.addEffect(harmless)
        rig.engine.processBeatTick(tick(0))

        // Layer 3 HTP: max(100, 180) = 180. MAX-blend effect at 50 keeps 180.
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
    fun `SineWave plus updateChannel at 180 — direct write remains visible as effect baseline`() {
        val rig = newRig(firstChannel = 1)
        // updateChannel equivalent: direct write onto Layer 4.
        rig.directWriteStore.put(universe = 0, channel = 1, value = 180u)
        // Immediately paint the sticky value onto the controller so any tick that runs
        // without a cue reset reads 180 as the fallback baseline (matches the real
        // updateChannel socket handler which writes through the controller).
        rig.controller.setValue(1, 180u, 0L)

        val effect = FxInstance(
            effect = SineWave(),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        )
        rig.engine.addEffect(effect)

        // Drive several ticks. Under OVERRIDE the effect replaces the baseline each tick —
        // the guarantee here is that removing the effect falls back to 180 (Layer 4 sticky),
        // i.e. the effect reset path observes the direct write, not zero.
        for (n in 0L..4L) rig.engine.processBeatTick(tick(n))

        rig.engine.removeEffect(effect.id)
        assertEquals(
            180u.toUByte(), rig.controller.currentValues[1],
            "post-removal reset must fall through Layer 3 (empty) → Layer 4 (180)",
        )
    }

    @Test
    fun `two OVERRIDE effects on one property — higher priority wins`() {
        val rig = newRig(firstChannel = 1)

        val low = FxInstance(
            effect = StaticValue(value = 80u),
            target = SliderTarget("hex-a", "dimmer"),
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            blendMode = BlendMode.OVERRIDE,
        ).also { it.priority = 1 }

        val high = FxInstance(
            effect = StaticValue(value = 220u),
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
            effect = StaticValue(value = 111u),
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
    fun `cue assignments composed under a MAX-blend effect — Layer 3 baseline wins when higher`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(cueId = 10, value = 200u)))

        // MAX-blend effect at 50 should NOT lower the Layer 3 baseline of 200.
        val effect = makeStaticDimmer(value = 50u, blendMode = BlendMode.MAX)
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(200u.toUByte(), rig.controller.currentValues[1], "max(200, 50) = 200")

        // MAX-blend effect at 220 should lift above Layer 3.
        val lifter = makeStaticDimmer(value = 220u, blendMode = BlendMode.MAX)
        rig.engine.addEffect(lifter)
        rig.engine.processBeatTick(tick(1))
        assertEquals(220u.toUByte(), rig.controller.currentValues[1], "max(200, 220) = 220")
    }

    @Test
    fun `removing the only effect covering a property resets to Layer 3 composed value`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(cueId = 10, value = 120u)))

        val effect = makeStaticDimmer(value = 255u, blendMode = BlendMode.OVERRIDE)
        val id = rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(255u.toUByte(), rig.controller.currentValues[1], "effect OVERRIDES Layer 3")

        rig.engine.removeEffect(id)
        assertEquals(
            120u.toUByte(), rig.controller.currentValues[1],
            "removal falls back to Layer 3 composed value (120)",
        )
    }

    @Test
    fun `clearing all cue assignments with an effect running — reset tick paints fallback`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(cueId = 10, value = 180u)))

        // Effect running, Layer 3 asserted: max(180, 40) = 180.
        val effect = makeStaticDimmer(value = 40u, blendMode = BlendMode.MAX)
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])

        rig.engine.clearAllCueAssignments()
        // Layer 3 cleared. Next beat tick should reset to Layer 4 (empty) → Layer 5 (0),
        // then compose max(0, 40) = 40.
        rig.engine.processBeatTick(tick(1))
        assertEquals(40u.toUByte(), rig.controller.currentValues[1])
    }

    // ─── Worked Example 6: Layer 4 property-level writes ────────────────────

    @Test
    fun `Worked Example 6 — writeLayer4Property paints RGB and UV channels`() {
        val rig = newRig(firstChannel = 1)
        val hex = rig.fixtures.fixture<HexFixture>("hex-a")

        val red = ExtendedColour(Color(255, 0, 0), uv = 128u)
        rig.engine.writeLayer4Property(hex, "rgbColour", Layer3Resolver.PropertyValue.Colour(red))

        // Hex R/G/B at channels 2/3/4, UV at channel 7.
        assertEquals(255u.toUByte(), rig.controller.currentValues[2], "red painted")
        assertEquals(0u.toUByte(), rig.controller.currentValues[3], "green painted")
        assertEquals(0u.toUByte(), rig.controller.currentValues[4], "blue painted")
        assertEquals(128u.toUByte(), rig.controller.currentValues[7], "uv painted via WithUv")
    }

    @Test
    fun `Worked Example 6 — updateChannel after Layer 4 write wins (channel-keyed last-write-wins)`() {
        val rig = newRig(firstChannel = 1)
        val hex = rig.fixtures.fixture<HexFixture>("hex-a")

        val red = ExtendedColour(Color(255, 0, 0))
        rig.engine.writeLayer4Property(hex, "rgbColour", Layer3Resolver.PropertyValue.Colour(red))
        assertEquals(255u.toUByte(), rig.controller.currentValues[2])

        // Simulate updateChannel on the green channel — controller write + directWriteStore put.
        rig.directWriteStore.put(0, 3, 128u)
        rig.controller.setValue(3, 128u, 0L)
        assertEquals(128u.toUByte(), rig.controller.currentValues[3], "manual channel write wins")

        // Writing the same colour again overwrites the green channel back to 0.
        rig.engine.writeLayer4Property(hex, "rgbColour", Layer3Resolver.PropertyValue.Colour(red))
        assertEquals(0u.toUByte(), rig.controller.currentValues[3], "Layer 4 re-write stomps previous")
    }

    @Test
    fun `Worked Example 6 — Layer 3 cue overrides Layer 4 preset on same property`() {
        val rig = newRig(firstChannel = 1)
        val hex = rig.fixtures.fixture<HexFixture>("hex-a")

        rig.engine.writeLayer4Property(
            hex, "rgbColour",
            Layer3Resolver.PropertyValue.Colour(ExtendedColour(Color(255, 0, 0))),
        )
        assertEquals(255u.toUByte(), rig.controller.currentValues[2], "Layer 4 red on stage")

        // Layer 3 cue with blue should override.
        rig.engine.setCueAssignments(
            10,
            listOf(colourAssignment(cueId = 10, priority = 1, color = Color(0, 0, 255))),
        )
        assertEquals(0u.toUByte(), rig.controller.currentValues[2], "cue overrides Layer 4 red")
        assertEquals(255u.toUByte(), rig.controller.currentValues[4], "cue blue on stage")

        // Clear the cue: Layer 4 red re-emerges.
        rig.engine.removeCueAssignments(10)
        assertEquals(255u.toUByte(), rig.controller.currentValues[2], "Layer 4 red re-emerges")
        assertEquals(0u.toUByte(), rig.controller.currentValues[4], "cue blue cleared")
    }

    @Test
    fun `Worked Example 6 — clearLayer4Property cascades back to Layer 5 baseline`() {
        val rig = newRig(firstChannel = 1)
        val hex = rig.fixtures.fixture<HexFixture>("hex-a")

        rig.engine.writeLayer4Property(
            hex, "rgbColour",
            Layer3Resolver.PropertyValue.Colour(ExtendedColour(Color(200, 100, 50), uv = 128u)),
        )
        assertEquals(200u.toUByte(), rig.controller.currentValues[2])

        rig.engine.clearLayer4Property(hex, "rgbColour")
        assertEquals(0u.toUByte(), rig.controller.currentValues[2], "red cleared")
        assertEquals(0u.toUByte(), rig.controller.currentValues[3], "green cleared")
        assertEquals(0u.toUByte(), rig.controller.currentValues[4], "blue cleared")
        assertEquals(0u.toUByte(), rig.controller.currentValues[7], "uv cleared")

        // DirectWriteStore no longer holds these channels.
        assertNull(rig.directWriteStore.get(0, 2))
        assertNull(rig.directWriteStore.get(0, 3))
        assertNull(rig.directWriteStore.get(0, 4))
        assertNull(rig.directWriteStore.get(0, 7))
    }

    @Test
    fun `Worked Example 6 — slider property write and clear`() {
        val rig = newRig(firstChannel = 1)
        val hex = rig.fixtures.fixture<HexFixture>("hex-a")

        rig.engine.writeLayer4Property(hex, "dimmer", Layer3Resolver.PropertyValue.Slider(180u))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])
        assertEquals(180u.toUByte(), rig.directWriteStore.get(0, 1))

        rig.engine.clearLayer4Property(hex, "dimmer")
        assertEquals(0u.toUByte(), rig.controller.currentValues[1])
        assertNull(rig.directWriteStore.get(0, 1))
    }

    @Test
    fun `pipeline is deterministic across many ticks for static effects`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(dimmerAssignment(cueId = 10, value = 100u)))

        val effect = makeStaticDimmer(value = 50u, blendMode = BlendMode.ADDITIVE)
        rig.engine.addEffect(effect)

        // Run 50 ticks; the output must be stable: 100 (layer 3) + 50 (additive) = 150.
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
}
