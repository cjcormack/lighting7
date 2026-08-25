package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.testsupport.SineSlider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for wall-clock timing integration.
 */
class WallClockTimingTest {

    // ─── TimingSource enum ──────────────────────────────────────────────

    @Test
    fun `TimingSource has BEAT and WALL_CLOCK values`() {
        assertEquals(2, TimingSource.entries.size)
        assertEquals(TimingSource.BEAT, TimingSource.valueOf("BEAT"))
        assertEquals(TimingSource.WALL_CLOCK, TimingSource.valueOf("WALL_CLOCK"))
    }

    // Note: FxInstance phase calculation tests require a real FxTarget (sealed class),
    // which requires the fixture system. Those are tested via integration tests.

    // ─── EffectRegistration timingSource ────────────────────────────────

    @Test
    fun `EffectRegistration defaults to BEAT timing source`() {
        val reg = EffectRegistration(
            id = "TestEffect",
            name = "Test Effect",
            category = "dimmer",
            outputType = FxOutputType.SLIDER,
            compatibleProperties = listOf("dimmer"),
            factory = { _, _, _ -> SineSlider() },
        )
        assertEquals(TimingSource.BEAT, reg.timingSource)
    }

    @Test
    fun `EffectRegistration can specify WALL_CLOCK timing source`() {
        val reg = EffectRegistration(
            id = "CandleFlicker",
            name = "Candle Flicker",
            category = "dimmer",
            outputType = FxOutputType.SLIDER,
            timingSource = TimingSource.WALL_CLOCK,
            compatibleProperties = listOf("dimmer"),
            factory = { _, _, _ -> SineSlider() },
        )
        assertEquals(TimingSource.WALL_CLOCK, reg.timingSource)
    }

    // ─── EffectTypeInfo timingSource ────────────────────────────────────

    @Test
    fun `EffectTypeInfo defaults to BEAT`() {
        val info = EffectTypeInfo(
            name = "TestEffect",
            category = "dimmer",
            outputType = "SLIDER",
            parameters = emptyList(),
            compatibleProperties = listOf("dimmer"),
        )
        assertEquals("BEAT", info.timingSource)
    }

    @Test
    fun `EffectTypeInfo can be WALL_CLOCK`() {
        val info = EffectTypeInfo(
            name = "CandleFlicker",
            category = "dimmer",
            outputType = "SLIDER",
            parameters = emptyList(),
            compatibleProperties = listOf("dimmer"),
            timingSource = "WALL_CLOCK",
        )
        assertEquals("WALL_CLOCK", info.timingSource)
    }

    // ─── FxFileMetadata timingSource ────────────────────────────────────

    @Test
    fun `FxFileMetadata defaults to BEAT`() {
        val meta = FxFileMetadata(id = "test", name = "Test", category = "dimmer")
        assertEquals("BEAT", meta.timingSource)
    }

    @Test
    fun `FxFileMetadata can be WALL_CLOCK`() {
        val meta = FxFileMetadata(id = "test", name = "Test", category = "dimmer", timingSource = "WALL_CLOCK")
        assertEquals("WALL_CLOCK", meta.timingSource)
    }

    // ─── FxFileLoader parsing ───────────────────────────────────────────

    @Test
    fun `parseFxFile extracts timingSource from frontmatter`() {
        val content = """
            |/*---
            |id: TestFlicker
            |name: Test Flicker
            |category: dimmer
            |outputType: SLIDER
            |effectMode: STATEFUL
            |timingSource: WALL_CLOCK
            |compatibleProperties: [dimmer]
            |parameters: []
            |---*/
            |
            |FxOutput.Slider(128u)
        """.trimMargin()

        val (metadata, _) = FxFileLoader.parseFxFile(content)
        assertEquals("WALL_CLOCK", metadata.timingSource)
        assertEquals("TestFlicker", metadata.id)
        assertEquals("STATEFUL", metadata.effectMode)
    }

    @Test
    fun `parseFxFile defaults timingSource to BEAT when not specified`() {
        val content = """
            |/*---
            |id: TestEffect
            |name: Test Effect
            |category: dimmer
            |outputType: SLIDER
            |effectMode: STANDARD
            |compatibleProperties: [dimmer]
            |parameters: []
            |---*/
            |
            |FxOutput.Slider(128u)
        """.trimMargin()

        val (metadata, _) = FxFileLoader.parseFxFile(content)
        assertEquals("BEAT", metadata.timingSource)
    }

    // ─── Rate-master scaling of the wall-clock cycle ─────────────────────
    // The old "requires the fixture system" note above predates SliderTarget taking a plain
    // key (see FxInstanceTest.stubTarget) — direct phase tests are possible after all.

    /**
     * Phase now derives from accumulated scaled time rather than `now - startedAtMs`, so
     * these tests advance the clock explicitly instead of back-dating a start time. That
     * also makes them exact: no wall-clock reading is involved, so the old 0.02 tolerances
     * for scheduler jitter are gone.
     */
    private fun wallClockInstance(cycleSeconds: Double): FxInstance =
        FxInstance(
            effect = SineSlider(),
            target = SliderTarget(FxTargetRef.FixtureRef("test"), "dimmer"),
            timing = FxTiming(beatDivision = cycleSeconds),
        ).apply {
            timingSource = TimingSource.WALL_CLOCK
        }

    @Test
    fun `rateScale scales how fast the cycle is consumed`() {
        // 4 s cycle, 1 s of unscaled time in → a quarter of the way round.
        val unscaled = wallClockInstance(cycleSeconds = 4.0)
        unscaled.advanceWallClock(1_000, 1.0)
        assertEquals(0.25, unscaled.calculateWallClockPhase(), 1e-9)

        // Master at 240 BPM → scale 2.0 → the same second covers half the cycle.
        val fast = wallClockInstance(cycleSeconds = 4.0)
        fast.advanceWallClock(1_000, 2.0)
        assertEquals(0.5, fast.calculateWallClockPhase(), 1e-9)

        // Master at 60 BPM → scale 0.5 → an eighth.
        val slow = wallClockInstance(cycleSeconds = 4.0)
        slow.advanceWallClock(1_000, 0.5)
        assertEquals(0.125, slow.calculateWallClockPhase(), 1e-9)
    }

    @Test
    fun `unassigned and degenerate rate scales leave the cycle unchanged`() {
        fun phaseAfter(scale: Double): Double =
            wallClockInstance(cycleSeconds = 4.0).apply { advanceWallClock(1_000, scale) }
                .calculateWallClockPhase()

        val unscaled = phaseAfter(1.0)
        assertEquals(0.25, unscaled, 1e-9)
        assertEquals(unscaled, phaseAfter(0.0), 1e-9, "zero degrades to unscaled")
        assertEquals(unscaled, phaseAfter(-2.0), 1e-9, "negative degrades to unscaled")
    }

    @Test
    fun `rateScale applies to the member form too`() {
        val member = object : uk.me.cormack.lighting7.fx.group.DistributionMemberInfo {
            override val index = 0
            override val normalizedPosition = 0.0
        }

        val unscaled = wallClockInstance(cycleSeconds = 4.0)
        unscaled.advanceWallClock(1_000, 1.0)
        assertEquals(0.25, unscaled.calculateWallClockPhaseForMember(member, 1), 1e-9)

        val fast = wallClockInstance(cycleSeconds = 4.0)
        fast.advanceWallClock(1_000, 2.0)
        assertEquals(0.5, fast.calculateWallClockPhaseForMember(member, 1), 1e-9)
    }

    /**
     * The replacement for the old "changing the rate mid-cycle jumps the phase — accepted,
     * not a bug" pin, which existed because phase was `elapsed % cycle` with a cycle that
     * moved under a fixed elapsed time. Accumulating scaled time instead means a rate change
     * only alters how fast the phase advances *from here*, never where it currently is —
     * which is what stops a rate-master tap snapping a live look.
     */
    @Test
    fun `changing the rate mid-cycle is continuous`() {
        val instance = wallClockInstance(cycleSeconds = 4.0)
        instance.advanceWallClock(3_000, 1.0)
        val before = instance.calculateWallClockPhase()
        assertEquals(0.75, before, 1e-9)

        // The rate doubles. The old behaviour snapped straight to 0.5 (3 s into a now-2 s
        // cycle); the phase must instead carry on forward from 0.75.
        instance.advanceWallClock(100, 2.0)
        val after = instance.calculateWallClockPhase()
        assertEquals(0.8, after, 1e-9, "0.75 + (100ms * 2) / 4000ms")
        assertTrue(after > before, "a rate change must never move the phase backwards")
    }

    /** Paused or not, a wall-clock effect keeps its place in real time — unchanged. */
    @Test
    fun `phase wraps rather than growing without bound`() {
        val instance = wallClockInstance(cycleSeconds = 2.0)
        instance.advanceWallClock(9_000, 1.0)   // 4.5 cycles
        assertEquals(0.5, instance.calculateWallClockPhase(), 1e-9)
    }
}
