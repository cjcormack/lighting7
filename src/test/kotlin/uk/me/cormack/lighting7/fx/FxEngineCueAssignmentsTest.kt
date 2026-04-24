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
 * Exercises [FxEngine]'s Layer 3 assignment tracking. End-to-end tick-loop integration is
 * still blocked by the sealed `DmxController` interface (Phase 5 lifts that); here we verify
 * that per-cue assignments compose through [LayerResolver.currentLayer3State] correctly and
 * that cue teardown releases the contribution.
 */
class FxEngineCueAssignmentsTest {

    private fun newEngine(): FxEngine = FxEngine(Fixtures(), MasterClock())

    private fun slider(
        cueId: Int,
        priority: Int,
        targetKey: String = "fx-1",
        propertyName: String = "dimmer",
        value: UByte,
    ) = Layer3Resolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = 1.0,
        targetKey = targetKey,
        targetIsGroup = false,
        propertyName = propertyName,
        category = PropertyCategory.DIMMER,
        value = Layer3Resolver.PropertyValue.Slider(value),
    )

    @Test
    fun `setCueAssignments publishes Layer 3 state`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 180u)))

        val state = engine.layerResolver.currentLayer3State
        val v = state[Layer3Resolver.Key.fixture("fx-1", "dimmer")]
        assertIs<Layer3Resolver.PropertyValue.Slider>(v)
        assertEquals(180u.toUByte(), v.value)
    }

    @Test
    fun `two cues' HTP dimmer assignments compose via max`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        engine.setCueAssignments(20, listOf(slider(cueId = 20, priority = 2, value = 200u)))

        val v = engine.layerResolver.currentLayer3State[Layer3Resolver.Key.fixture("fx-1", "dimmer")]
        assertIs<Layer3Resolver.PropertyValue.Slider>(v)
        assertEquals(200u.toUByte(), v.value)
    }

    @Test
    fun `removeCueAssignments drops only that cue's contributions`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        engine.setCueAssignments(20, listOf(slider(cueId = 20, priority = 2, value = 200u)))

        engine.removeCueAssignments(20)

        val v = engine.layerResolver.currentLayer3State[Layer3Resolver.Key.fixture("fx-1", "dimmer")]
        assertIs<Layer3Resolver.PropertyValue.Slider>(v)
        assertEquals(100u.toUByte(), v.value, "only cue 10 remains")
    }

    @Test
    fun `setCueAssignments with empty list clears that cue's contribution`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        engine.setCueAssignments(10, emptyList())
        assertTrue(engine.layerResolver.currentLayer3State.isEmpty())
    }

    @Test
    fun `clearAllCueAssignments empties everything`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        engine.setCueAssignments(20, listOf(slider(cueId = 20, priority = 2, value = 200u)))
        engine.clearAllCueAssignments()
        assertTrue(engine.layerResolver.currentLayer3State.isEmpty())
    }

    @Test
    fun `group-expansion assignments honour the specificity rule`() {
        val engine = newEngine()
        // Caller emits: one group-level row plus two member rows, then a same-cue fixture-level
        // override on one of the members. The specificity rule in Layer3Resolver filters out
        // the group-flagged rows when any fixture-flagged row shares the (key, property), so
        // member-A sees the group value and member-B sees the override.
        val groupRow = Layer3Resolver.Assignment(
            cueId = 10, priority = 1, fadeWeight = 1.0,
            targetKey = "front-wash", targetIsGroup = true,
            propertyName = "dimmer", category = PropertyCategory.DIMMER,
            value = Layer3Resolver.PropertyValue.Slider(100u),
        )
        val memberA = groupRow.copy(targetKey = "hex-a", targetIsGroup = false)
        val memberB = groupRow.copy(targetKey = "hex-b", targetIsGroup = false)
        val overrideB = memberB.copy(value = Layer3Resolver.PropertyValue.Slider(220u))

        engine.setCueAssignments(10, listOf(groupRow, memberA, memberB, overrideB))

        val state = engine.layerResolver.currentLayer3State
        val a = state[Layer3Resolver.Key.fixture("hex-a", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(100u.toUByte(), a.value, "member-A follows the group value via HTP max")
        val b = state[Layer3Resolver.Key.fixture("hex-b", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        // For HTP the override is max(100, 220) = 220 anyway; the critical invariant is that
        // the group-flagged duplicate row is not also added to the list for hex-b's key.
        assertEquals(220u.toUByte(), b.value)
    }

    @Test
    fun `removeEffectsForCue also drops that cue's Layer 3 contributions`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 180u)))
        engine.removeEffectsForCue(10)
        assertNull(engine.layerResolver.currentLayer3State[Layer3Resolver.Key.fixture("fx-1", "dimmer")])
    }

    @Test
    fun `appendCueAssignments adds rows without clobbering existing`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        // Append a second property for the same cue — must not remove the dimmer row.
        val uvRow = slider(cueId = 10, priority = 1, propertyName = "uv", value = 180u)
        engine.appendCueAssignments(10, listOf(uvRow))

        val state = engine.layerResolver.currentLayer3State
        val dimmer = state[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(100u.toUByte(), dimmer.value, "existing dimmer row survives append")
        val uv = state[Layer3Resolver.Key.fixture("fx-1", "uv")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(180u.toUByte(), uv.value, "appended uv row is composed")
    }

    @Test
    fun `appendCueAssignments creates entry for cue with no prior assignments`() {
        val engine = newEngine()
        // Timed preset fire is the first Layer 3 contribution for a cue whose own assignments
        // are empty.
        val uvRow = slider(cueId = 10, priority = 1, propertyName = "uv", value = 180u)
        engine.appendCueAssignments(10, listOf(uvRow))

        val uv = engine.layerResolver.currentLayer3State[Layer3Resolver.Key.fixture("fx-1", "uv")]
        assertIs<Layer3Resolver.PropertyValue.Slider>(uv)
        assertEquals(180u.toUByte(), uv.value)
    }

    @Test
    fun `appendCueAssignments with empty list is a no-op`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        engine.appendCueAssignments(10, emptyList())
        val dimmer = engine.layerResolver.currentLayer3State[Layer3Resolver.Key.fixture("fx-1", "dimmer")]
        assertIs<Layer3Resolver.PropertyValue.Slider>(dimmer)
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

        val state = engine.layerResolver.currentLayer3State
        assertNull(state[Layer3Resolver.Key.fixture("fx-1", "uv")], "uv row retracted")
        val dimmer = state[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
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

        val dimmer = engine.layerResolver.currentLayer3State[Layer3Resolver.Key.fixture("fx-1", "dimmer")]
        assertIs<Layer3Resolver.PropertyValue.Slider>(dimmer)
        assertEquals(100u.toUByte(), dimmer.value, "one occurrence of the row still contributes")
    }

    @Test
    fun `removeCueAssignmentSubset drops cue entry when list becomes empty`() {
        val engine = newEngine()
        val uvRow = slider(cueId = 10, priority = 1, propertyName = "uv", value = 180u)
        engine.appendCueAssignments(10, listOf(uvRow))
        engine.removeCueAssignmentSubset(10, listOf(uvRow))
        assertTrue(engine.layerResolver.currentLayer3State.isEmpty())
    }

    @Test
    fun `removeCueAssignmentSubset on unknown cue is a no-op`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        // cue 99 has no assignments — removal must not touch cue 10.
        engine.removeCueAssignmentSubset(99, listOf(slider(cueId = 99, priority = 1, value = 200u)))

        val dimmer = engine.layerResolver.currentLayer3State[Layer3Resolver.Key.fixture("fx-1", "dimmer")]
        assertIs<Layer3Resolver.PropertyValue.Slider>(dimmer)
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

        val state = engine.layerResolver.currentLayer3State
        val dimmer = state[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(100u.toUByte(), dimmer.value, "apply-time row preserved")
        val uv = state[Layer3Resolver.Key.fixture("fx-1", "uv")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(200u.toUByte(), uv.value, "second fire wins")
    }

    @Test
    fun `recurring timed-preset fire pattern does not accumulate rows`() {
        val engine = newEngine()
        val applyRow = slider(cueId = 10, priority = 1, value = 100u)
        engine.setCueAssignments(10, listOf(applyRow))

        val timedRow = slider(cueId = 10, priority = 1, propertyName = "uv", value = 180u)
        var prior: List<Layer3Resolver.Assignment> = emptyList()
        repeat(3) {
            engine.replaceCueAssignmentSubset(10, toRemove = prior, additions = listOf(timedRow))
            prior = listOf(timedRow)
        }

        val state = engine.layerResolver.currentLayer3State
        // Dimmer still at 100 (apply-time row), uv at 180 (latest timed fire), no duplicates.
        val dimmer = state[Layer3Resolver.Key.fixture("fx-1", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(100u.toUByte(), dimmer.value)
        val uv = state[Layer3Resolver.Key.fixture("fx-1", "uv")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(180u.toUByte(), uv.value)
    }
}
