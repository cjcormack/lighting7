package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [FxEngine]'s Layer 4 assignment tracking. End-to-end tick-loop integration is
 * still blocked by the sealed `DmxController` interface (Phase 5 lifts that); here we verify
 * that per-cue assignments compose through [LayerResolver.currentCueLayerState] correctly and
 * that cue teardown releases the contribution.
 */
class FxEngineCueAssignmentsTest {

    private fun newEngine(): FxEngine = FxEngine(Fixtures(), SpeedMasterBank())

    private fun slider(
        cueId: Int,
        priority: Int,
        targetKey: String = "fx-1",
        propertyName: String = "dimmer",
        value: UByte,
    ) = CueAssignmentResolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = 1.0,
        targetKey = targetKey,
        targetIsGroup = false,
        propertyName = propertyName,
        category = PropertyCategory.DIMMER,
        value = CueAssignmentResolver.PropertyValue.Slider(value),
    )

    @Test
    fun `setCueAssignments publishes Layer 4 state`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 180u)))

        val state = engine.layerResolver.currentCueLayerState
        val v = state[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")]
        assertIs<CueAssignmentResolver.PropertyValue.Slider>(v)
        assertEquals(180u.toUByte(), v.value)
    }

    @Test
    fun `two cues' HTP dimmer assignments compose via max`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        engine.setCueAssignments(20, listOf(slider(cueId = 20, priority = 2, value = 200u)))

        val v = engine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")]
        assertIs<CueAssignmentResolver.PropertyValue.Slider>(v)
        assertEquals(200u.toUByte(), v.value)
    }

    @Test
    fun `removeCueAssignments drops only that cue's contributions`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        engine.setCueAssignments(20, listOf(slider(cueId = 20, priority = 2, value = 200u)))

        engine.removeCueAssignments(20)

        val v = engine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")]
        assertIs<CueAssignmentResolver.PropertyValue.Slider>(v)
        assertEquals(100u.toUByte(), v.value, "only cue 10 remains")
    }

    @Test
    fun `setCueAssignments with empty list clears that cue's contribution`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        engine.setCueAssignments(10, emptyList())
        assertTrue(engine.layerResolver.currentCueLayerState.isEmpty())
    }

    @Test
    fun `clearAllCueAssignments empties everything`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        engine.setCueAssignments(20, listOf(slider(cueId = 20, priority = 2, value = 200u)))
        engine.clearAllCueAssignments()
        assertTrue(engine.layerResolver.currentCueLayerState.isEmpty())
    }

    @Test
    fun `group-expansion assignments honour the specificity rule`() {
        val engine = newEngine()
        // Caller emits: one group-level row plus two member rows, then a same-cue fixture-level
        // override on one of the members. The specificity rule in CueAssignmentResolver filters out
        // the group-flagged rows when any fixture-flagged row shares the (key, property), so
        // member-A sees the group value and member-B sees the override.
        val groupRow = CueAssignmentResolver.Assignment(
            cueId = 10, priority = 1, fadeWeight = 1.0,
            targetKey = "front-wash", targetIsGroup = true,
            propertyName = "dimmer", category = PropertyCategory.DIMMER,
            value = CueAssignmentResolver.PropertyValue.Slider(100u),
        )
        val memberA = groupRow.copy(targetKey = "hex-a", targetIsGroup = false)
        val memberB = groupRow.copy(targetKey = "hex-b", targetIsGroup = false)
        val overrideB = memberB.copy(value = CueAssignmentResolver.PropertyValue.Slider(220u))

        engine.setCueAssignments(10, listOf(groupRow, memberA, memberB, overrideB))

        val state = engine.layerResolver.currentCueLayerState
        val a = state[CueAssignmentResolver.Key.fixture("hex-a", "dimmer")] as CueAssignmentResolver.PropertyValue.Slider
        assertEquals(100u.toUByte(), a.value, "member-A follows the group value via HTP max")
        val b = state[CueAssignmentResolver.Key.fixture("hex-b", "dimmer")] as CueAssignmentResolver.PropertyValue.Slider
        // For HTP the override is max(100, 220) = 220 anyway; the critical invariant is that
        // the group-flagged duplicate row is not also added to the list for hex-b's key.
        assertEquals(220u.toUByte(), b.value)
    }

    @Test
    fun `removeEffectsForCue also drops that cue's Layer 4 contributions`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 180u)))
        engine.removeEffectsForCue(10)
        assertNull(engine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")])
    }

    @Test
    fun `appendCueAssignments adds rows without clobbering existing`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        // Append a second property for the same cue — must not remove the dimmer row.
        val uvRow = slider(cueId = 10, priority = 1, propertyName = "uv", value = 180u)
        engine.appendCueAssignments(10, listOf(uvRow))

        val state = engine.layerResolver.currentCueLayerState
        val dimmer = state[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.PropertyValue.Slider
        assertEquals(100u.toUByte(), dimmer.value, "existing dimmer row survives append")
        val uv = state[CueAssignmentResolver.Key.fixture("fx-1", "uv")] as CueAssignmentResolver.PropertyValue.Slider
        assertEquals(180u.toUByte(), uv.value, "appended uv row is composed")
    }

    @Test
    fun `appendCueAssignments creates entry for cue with no prior assignments`() {
        val engine = newEngine()
        // Timed preset fire is the first Layer 4 contribution for a cue whose own assignments
        // are empty.
        val uvRow = slider(cueId = 10, priority = 1, propertyName = "uv", value = 180u)
        engine.appendCueAssignments(10, listOf(uvRow))

        val uv = engine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("fx-1", "uv")]
        assertIs<CueAssignmentResolver.PropertyValue.Slider>(uv)
        assertEquals(180u.toUByte(), uv.value)
    }

    @Test
    fun `appendCueAssignments with empty list is a no-op`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        engine.appendCueAssignments(10, emptyList())
        val dimmer = engine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")]
        assertIs<CueAssignmentResolver.PropertyValue.Slider>(dimmer)
        assertEquals(100u.toUByte(), dimmer.value)
    }

    @Test
    fun `removeCueAssignmentSubset round-trips with appendCueAssignments`() {
        val engine = newEngine()
        val apply = slider(cueId = 10, priority = 1, value = 100u)
        engine.setCueAssignments(10, listOf(apply))
        val uvRow = slider(cueId = 10, priority = 1, propertyName = "uv", value = 180u)
        engine.appendCueAssignments(10, listOf(uvRow))
        engine.removeCueAssignmentSubset(10, listOf(uvRow))

        val state = engine.layerResolver.currentCueLayerState
        assertNull(state[CueAssignmentResolver.Key.fixture("fx-1", "uv")], "uv row retracted")
        val dimmer = state[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.PropertyValue.Slider
        assertEquals(100u.toUByte(), dimmer.value, "apply-time dimmer row survives subset removal")
    }

    @Test
    fun `removeCueAssignmentSubset only removes one matching occurrence per request`() {
        val engine = newEngine()
        // Two structurally-equal rows (e.g. apply-time preset + timed preset producing the
        // same row). Removing the "timed" instance should leave the "apply-time" one.
        val row = slider(cueId = 10, priority = 1, value = 100u)
        engine.setCueAssignments(10, listOf(row))
        engine.appendCueAssignments(10, listOf(row))
        engine.removeCueAssignmentSubset(10, listOf(row))

        val dimmer = engine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")]
        assertIs<CueAssignmentResolver.PropertyValue.Slider>(dimmer)
        assertEquals(100u.toUByte(), dimmer.value, "one occurrence of the row still contributes")
    }

    @Test
    fun `removeCueAssignmentSubset drops cue entry when list becomes empty`() {
        val engine = newEngine()
        val uvRow = slider(cueId = 10, priority = 1, propertyName = "uv", value = 180u)
        engine.appendCueAssignments(10, listOf(uvRow))
        engine.removeCueAssignmentSubset(10, listOf(uvRow))
        assertTrue(engine.layerResolver.currentCueLayerState.isEmpty())
    }

    @Test
    fun `removeCueAssignmentSubset on unknown cue is a no-op`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        // cue 99 has no assignments — removal must not touch cue 10.
        engine.removeCueAssignmentSubset(99, listOf(slider(cueId = 99, priority = 1, value = 200u)))

        val dimmer = engine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")]
        assertIs<CueAssignmentResolver.PropertyValue.Slider>(dimmer)
        assertEquals(100u.toUByte(), dimmer.value)
    }

    @Test
    fun `replaceCueAssignmentSubset atomically retracts and appends`() {
        val engine = newEngine()
        val applyRow = slider(cueId = 10, priority = 1, value = 100u)
        engine.setCueAssignments(10, listOf(applyRow))

        val firstFire = slider(cueId = 10, priority = 1, propertyName = "uv", value = 100u)
        val secondFire = slider(cueId = 10, priority = 1, propertyName = "uv", value = 200u)
        engine.appendCueAssignments(10, listOf(firstFire))
        // Simulates the recurring-fire path: retract prior + append new in one shot.
        engine.replaceCueAssignmentSubset(10, toRemove = listOf(firstFire), additions = listOf(secondFire))

        val state = engine.layerResolver.currentCueLayerState
        val dimmer = state[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.PropertyValue.Slider
        assertEquals(100u.toUByte(), dimmer.value, "apply-time row preserved")
        val uv = state[CueAssignmentResolver.Key.fixture("fx-1", "uv")] as CueAssignmentResolver.PropertyValue.Slider
        assertEquals(200u.toUByte(), uv.value, "second fire wins")
    }

    @Test
    fun `recurring timed-preset fire pattern does not accumulate rows`() {
        val engine = newEngine()
        val applyRow = slider(cueId = 10, priority = 1, value = 100u)
        engine.setCueAssignments(10, listOf(applyRow))

        val timedRow = slider(cueId = 10, priority = 1, propertyName = "uv", value = 180u)
        var prior: List<CueAssignmentResolver.Assignment> = emptyList()
        repeat(3) {
            engine.replaceCueAssignmentSubset(10, toRemove = prior, additions = listOf(timedRow))
            prior = listOf(timedRow)
        }

        val state = engine.layerResolver.currentCueLayerState
        // Dimmer still at 100 (apply-time row), uv at 180 (latest timed fire), no duplicates.
        val dimmer = state[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")] as CueAssignmentResolver.PropertyValue.Slider
        assertEquals(100u.toUByte(), dimmer.value)
        val uv = state[CueAssignmentResolver.Key.fixture("fx-1", "uv")] as CueAssignmentResolver.PropertyValue.Slider
        assertEquals(180u.toUByte(), uv.value)
    }

    // ─── replaceCueAssignments (palette-edit republish) ─────────────────────

    /** LTP, so the highest priority wins outright rather than values merging. */
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
    fun `replaceCueAssignments swaps rows for several cues in one pass`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(ltpSlider(cueId = 10, priority = 1_000, value = 100u)))
        engine.setCueAssignments(20, listOf(ltpSlider(cueId = 20, priority = 2_000, value = 200u)))

        val replaced = engine.replaceCueAssignments(
            mapOf(
                10 to listOf(ltpSlider(cueId = 10, priority = 1_000, value = 10u)),
                20 to listOf(ltpSlider(cueId = 20, priority = 2_000, value = 50u)),
            )
        )

        assertEquals(2, replaced)
        assertEquals(50u.toUByte(), engine.dimmer(), "the higher-priority cue's new value wins")
    }

    @Test
    fun `replaceCueAssignments leaves fade weights alone, unlike setCueAssignments`() {
        // The highest-severity hazard in the palette work. setCueAssignments defaults `weight` to
        // 1.0 and *clears* the cue's fade-weight entry, so routing a palette edit through it would
        // snap an in-flight crossfade to fully-in. A palette edit touches every referencing cue at
        // once, which makes that likely rather than theoretical. This test pins both halves: the
        // new function preserves the weight, and the old one demonstrably does not.
        val engine = newEngine()
        // A weight only means anything against something to fade *from*, so this is the real
        // crossfade shape: cue 20 coming in over cue 10, half way there.
        engine.setCueAssignments(10, listOf(ltpSlider(cueId = 10, priority = 1_000, value = 100u)))
        val incoming = listOf(ltpSlider(cueId = 20, priority = 2_000, value = 200u))
        engine.setCueAssignments(20, incoming)
        engine.updateCueFadeWeights(mapOf(20 to 0.5))

        val midFade = engine.dimmer()
        assertTrue(
            midFade in 100u.toUByte()..199u.toUByte(),
            "precondition: cue 20 half-faded should sit between the two values, got $midFade",
        )

        engine.replaceCueAssignments(mapOf(20 to incoming))
        assertEquals(midFade, engine.dimmer(), "replacing rows must not disturb the fade")

        // Contrast, and the reason replaceCueAssignments exists at all.
        engine.setCueAssignments(20, incoming)
        assertEquals(
            200u.toUByte(), engine.dimmer(),
            "setCueAssignments clears the weight and snaps the fade — that is the hazard",
        )
    }

    @Test
    fun `replaceCueAssignments skips cues that are no longer live`() {
        // A cue can stop being live between a caller's scan and the replace; that is a no-op, not
        // a resurrection.
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(ltpSlider(cueId = 10, priority = 1_000, value = 100u)))

        val replaced = engine.replaceCueAssignments(
            mapOf(99 to listOf(ltpSlider(cueId = 99, priority = 9_000, value = 250u)))
        )

        assertEquals(0, replaced)
        assertEquals(100u.toUByte(), engine.dimmer())
        assertNull(
            engine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("fx-1", "other")],
        )
    }

    @Test
    fun `replaceCueAssignments with empty rows drops the cue's contribution`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(ltpSlider(cueId = 10, priority = 1_000, value = 100u)))

        engine.replaceCueAssignments(mapOf(10 to emptyList()))

        assertNull(engine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("fx-1", "dimmer")])
    }
}
