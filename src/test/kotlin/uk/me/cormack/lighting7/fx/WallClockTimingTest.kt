package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.effects.SineWave
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
            factory = { _, _, _ -> SineWave() },
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
            factory = { _, _, _ -> SineWave() },
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

    private fun wallClockInstance(cycleSeconds: Double, startedAgoMs: Long): FxInstance =
        FxInstance(
            effect = SineWave(),
            target = SliderTarget(FxTargetRef.FixtureRef("test"), "dimmer"),
            timing = FxTiming(beatDivision = cycleSeconds),
        ).apply {
            timingSource = TimingSource.WALL_CLOCK
            startedAtMs = System.currentTimeMillis() - startedAgoMs
        }

    @Test
    fun `rateScale divides the wall-clock cycle`() {
        // 4 s cycle, 1 s in → phase 0.25 unscaled.
        val instance = wallClockInstance(cycleSeconds = 4.0, startedAgoMs = 1_000)

        assertEquals(0.25, instance.calculateWallClockPhase(), 0.02)
        // Master at 240 BPM → scale 2.0 → effective 2 s cycle → 1 s in is phase 0.5.
        assertEquals(0.5, instance.calculateWallClockPhase(2.0), 0.02)
        // Master at 60 BPM → scale 0.5 → effective 8 s cycle → 1 s in is phase 0.125.
        assertEquals(0.125, instance.calculateWallClockPhase(0.5), 0.02)
    }

    @Test
    fun `unassigned and degenerate rate scales leave the cycle unchanged`() {
        val instance = wallClockInstance(cycleSeconds = 4.0, startedAgoMs = 1_000)
        val unscaled = instance.calculateWallClockPhase()

        assertEquals(unscaled, instance.calculateWallClockPhase(1.0), 0.02, "scale 1.0 is identity")
        assertEquals(unscaled, instance.calculateWallClockPhase(0.0), 0.02, "zero degrades to unscaled")
        assertEquals(unscaled, instance.calculateWallClockPhase(-2.0), 0.02, "negative degrades to unscaled")
    }

    @Test
    fun `rateScale applies to the member form too`() {
        val instance = wallClockInstance(cycleSeconds = 4.0, startedAgoMs = 1_000)
        val member = object : uk.me.cormack.lighting7.fx.group.DistributionMemberInfo {
            override val index = 0
            override val normalizedPosition = 0.0
        }

        assertEquals(0.25, instance.calculateWallClockPhaseForMember(member, 1), 0.02)
        assertEquals(0.5, instance.calculateWallClockPhaseForMember(member, 1, 2.0), 0.02)
    }

    /**
     * The documented (and deliberate) discontinuity: phase derives from `elapsed % cycle`,
     * so changing the rate mid-cycle jumps the phase rather than continuing smoothly. This
     * test pins the behaviour as *intended* — if it starts failing because someone made the
     * wall-clock path accumulate scaled elapsed time, delete it and celebrate.
     */
    @Test
    fun `changing the rate mid-cycle jumps the phase — accepted, not a bug`() {
        val instance = wallClockInstance(cycleSeconds = 4.0, startedAgoMs = 3_000)

        val before = instance.calculateWallClockPhase(1.0)   // 3s into 4s → 0.75
        val after = instance.calculateWallClockPhase(2.0)    // 3s into 2s → 0.5, a jump
        assertEquals(0.75, before, 0.02)
        assertEquals(0.5, after, 0.02)
    }
}

