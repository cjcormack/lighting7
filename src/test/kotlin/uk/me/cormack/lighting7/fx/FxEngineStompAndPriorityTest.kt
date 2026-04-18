package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.effects.SineWave
import uk.me.cormack.lighting7.fx.effects.StaticValue
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Correctness tests for FxEngine's stomp behaviour and sorted-snapshot ordering. The tests
 * don't require a real DMX controller — [stompForCue] filters [activeEffects] by metadata,
 * and sorted-snapshot ordering is observable via [FxEngine.getActiveEffects] indirectly via
 * the internal snapshots used by the tick loops.
 */
class FxEngineStompAndPriorityTest {

    private fun newEngine(): FxEngine = FxEngine(Fixtures(), MasterClock())

    private fun makeEffect(
        propertyName: String = "dimmer",
        targetKey: String = "fx-1",
        cueId: Int? = null,
        priority: Int = 0,
    ): FxInstance {
        val target = SliderTarget(FxTargetRef.fixture(targetKey), propertyName)
        return FxInstance(
            effect = SineWave(),
            target = target,
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
        ).apply {
            this.cueId = cueId
            this.priority = priority
        }
    }

    @Test
    fun `stompForCue removes other cues' effects in the overlap set`() {
        val engine = newEngine()
        val cueAId = engine.addEffect(makeEffect(cueId = 10))
        val cueBId = engine.addEffect(makeEffect(cueId = 20, targetKey = "fx-1"))
        val manualId = engine.addEffect(makeEffect(cueId = null, targetKey = "fx-1"))

        // Cue 30 stomps — overlap is (fx-1, dimmer).
        val removed = engine.stompForCue(
            stompingCueId = 30,
            overlap = setOf(FxEngine.PropertyKey("fx-1", "dimmer")),
        )

        assertEquals(2, removed, "both cue-owned effects on fx-1/dimmer stomped")
        val remaining = engine.getActiveEffects().map { it.id }.toSet()
        assertFalse(cueAId in remaining)
        assertFalse(cueBId in remaining)
        assertTrue(manualId in remaining, "manual (uncued) effects are not stomped")
    }

    @Test
    fun `stompForCue leaves the stomping cue's own effects alone`() {
        val engine = newEngine()
        val myEffectId = engine.addEffect(makeEffect(cueId = 30))
        val otherId = engine.addEffect(makeEffect(cueId = 10))

        engine.stompForCue(
            stompingCueId = 30,
            overlap = setOf(FxEngine.PropertyKey("fx-1", "dimmer")),
        )

        val remaining = engine.getActiveEffects().map { it.id }.toSet()
        assertTrue(myEffectId in remaining, "stomping cue keeps its own effects")
        assertFalse(otherId in remaining)
    }

    @Test
    fun `stompForCue only removes effects inside the overlap set`() {
        val engine = newEngine()
        val onOverlap = engine.addEffect(makeEffect(cueId = 10, targetKey = "fx-1", propertyName = "dimmer"))
        val offOverlap = engine.addEffect(makeEffect(cueId = 10, targetKey = "fx-2", propertyName = "dimmer"))

        engine.stompForCue(
            stompingCueId = 30,
            overlap = setOf(FxEngine.PropertyKey("fx-1", "dimmer")),
        )

        val remaining = engine.getActiveEffects().map { it.id }.toSet()
        assertFalse(onOverlap in remaining)
        assertTrue(offOverlap in remaining)
    }

    @Test
    fun `empty overlap is a no-op`() {
        val engine = newEngine()
        val id = engine.addEffect(makeEffect(cueId = 10))
        val removed = engine.stompForCue(stompingCueId = 30, overlap = emptySet())
        assertEquals(0, removed)
        assertTrue(id in engine.getActiveEffects().map { it.id })
    }

    @Test
    fun `added effects have a monotonically increasing id for stable tie-break`() {
        val engine = newEngine()
        val id1 = engine.addEffect(makeEffect())
        val id2 = engine.addEffect(makeEffect())
        val id3 = engine.addEffect(makeEffect())
        assertTrue(id1 < id2)
        assertTrue(id2 < id3)
    }

    @Test
    fun `priority field survives FxInstance mutation`() {
        val engine = newEngine()
        val effect = makeEffect(priority = 42)
        val id = engine.addEffect(effect)
        assertEquals(42, engine.getEffect(id)?.priority)
    }
}
