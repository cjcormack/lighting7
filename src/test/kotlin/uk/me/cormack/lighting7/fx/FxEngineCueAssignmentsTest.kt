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
        val v = state[Layer3Resolver.Key("fx-1", "dimmer")]
        assertIs<Layer3Resolver.PropertyValue.Slider>(v)
        assertEquals(180u.toUByte(), v.value)
    }

    @Test
    fun `two cues' HTP dimmer assignments compose via max`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        engine.setCueAssignments(20, listOf(slider(cueId = 20, priority = 2, value = 200u)))

        val v = engine.layerResolver.currentLayer3State[Layer3Resolver.Key("fx-1", "dimmer")]
        assertIs<Layer3Resolver.PropertyValue.Slider>(v)
        assertEquals(200u.toUByte(), v.value)
    }

    @Test
    fun `removeCueAssignments drops only that cue's contributions`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        engine.setCueAssignments(20, listOf(slider(cueId = 20, priority = 2, value = 200u)))

        engine.removeCueAssignments(20)

        val v = engine.layerResolver.currentLayer3State[Layer3Resolver.Key("fx-1", "dimmer")]
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
        val a = state[Layer3Resolver.Key("hex-a", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        assertEquals(100u.toUByte(), a.value, "member-A follows the group value via HTP max")
        val b = state[Layer3Resolver.Key("hex-b", "dimmer")] as Layer3Resolver.PropertyValue.Slider
        // For HTP the override is max(100, 220) = 220 anyway; the critical invariant is that
        // the group-flagged duplicate row is not also added to the list for hex-b's key.
        assertEquals(220u.toUByte(), b.value)
    }

    @Test
    fun `removeEffectsForCue also drops that cue's Layer 3 contributions`() {
        val engine = newEngine()
        engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 180u)))
        engine.removeEffectsForCue(10)
        assertNull(engine.layerResolver.currentLayer3State[Layer3Resolver.Key("fx-1", "dimmer")])
    }
}
