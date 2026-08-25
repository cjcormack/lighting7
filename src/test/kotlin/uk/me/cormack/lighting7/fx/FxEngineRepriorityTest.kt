package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.testsupport.SineSlider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Cue priority is derived from stack position and stamped when the cue is applied, so reordering
 * a stack that has cues on stage has to restamp the rows already in the engine — otherwise the
 * cues keep composing in their old relative order until each is re-applied. See
 * [FxEngine.repriorityCues].
 */
class FxEngineRepriorityTest {

    private fun newEngine(): FxEngine = FxEngine(Fixtures(), SpeedMasterBank())

    /** LTP, so the highest priority wins outright rather than the values being merged. */
    private fun ltpSlider(cueId: Int, priority: Int, value: UByte) = CueAssignmentResolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = 1.0,
        targetKey = "fx-1",
        targetIsGroup = false,
        propertyName = "dimmer",
        category = PropertyCategory.DIMMER,
        compositionOverride = CompositionRule.LTP,
        value = CueAssignmentResolver.PropertyValue.Slider(value),
    )

    private fun FxEngine.dimmer(): UByte {
        val value = layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")]
        assertIs<CueAssignmentResolver.PropertyValue.Slider>(value)
        return value.value
    }

    @Test
    fun `swapping two live cues' priorities flips the Layer 4 winner`() {
        val engine = newEngine()
        // Cue 20 sits later in the stack, so it wins.
        engine.setCueAssignments(10, listOf(ltpSlider(cueId = 10, priority = 1_000, value = 100u)))
        engine.setCueAssignments(20, listOf(ltpSlider(cueId = 20, priority = 2_000, value = 200u)))
        assertEquals(200u.toUByte(), engine.dimmer())

        // Drag cue 10 below cue 20 — the reorder route hands the new derived priorities here.
        val changed = engine.repriorityCues(mapOf(10 to 2_000, 20 to 1_000))

        assertEquals(2, changed)
        assertEquals(100u.toUByte(), engine.dimmer())
    }

    @Test
    fun `repriority leaves fade weights alone`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(ltpSlider(cueId = 10, priority = 1_000, value = 100u)))
        engine.setCueAssignments(20, listOf(ltpSlider(cueId = 20, priority = 2_000, value = 200u)))
        // Cue 20 mid-fade at a quarter in.
        engine.updateCueFadeWeights(mapOf(20 to 0.25))

        engine.repriorityCues(mapOf(10 to 3_000))

        // Cue 10 now outranks cue 20, and cue 20 is still fading rather than snapped to full.
        assertEquals(100u.toUByte(), engine.dimmer())
        engine.updateCueFadeWeights(mapOf(20 to 1.0))
        engine.repriorityCues(mapOf(10 to 1_000))
        assertEquals(200u.toUByte(), engine.dimmer())
    }

    @Test
    fun `unchanged and unknown priorities are no-ops`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(ltpSlider(cueId = 10, priority = 1_000, value = 100u)))

        // Same priority for a live cue, plus a cue that has nothing on stage.
        assertEquals(0, engine.repriorityCues(mapOf(10 to 1_000, 99 to 5_000)))
        assertEquals(0, engine.repriorityCues(emptyMap()))
        assertEquals(100u.toUByte(), engine.dimmer())
    }

    @Test
    fun `repriority restamps Layer 3 effect instances and skips manual ones`() {
        val engine = newEngine()
        val cueEffect = makeEffect(cueId = 10, priority = 1_000)
        val manualEffect = makeEffect(cueId = null, priority = 0)
        engine.addEffect(cueEffect)
        engine.addEffect(manualEffect)

        assertEquals(1, engine.repriorityCues(mapOf(10 to 4_000)))
        assertEquals(4_000, cueEffect.priority)
        // Manual effects aren't cue-owned, so they stay at 0 and keep composing underneath.
        assertEquals(0, manualEffect.priority)
    }

    private fun makeEffect(cueId: Int?, priority: Int): FxInstance = FxInstance(
        effect = SineSlider(),
        target = SliderTarget(FxTargetRef.fixture("fx-1"), "dimmer"),
        timing = FxTiming(beatDivision = BeatDivision.QUARTER),
    ).apply {
        this.cueId = cueId
        this.priority = priority
    }
}
