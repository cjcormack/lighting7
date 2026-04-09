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
}

