package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the Layer 3 transmit path: [FxEngine.setCueAssignments] /
 * [FxEngine.removeCueAssignments] / [FxEngine.clearAllCueAssignments] must write the composed
 * Layer 3 → Layer 4 → Layer 5 fallback onto the [uk.me.cormack.lighting7.dmx.DmxController]
 * even when no effects are running.
 *
 * Regression target: the 2026-04-19d smoke-check found that pure-assignment cues (no effects)
 * never painted the stage because the tick loop early-returns and the effect-reset pass is
 * the only code path that currently composes Layer 3 onto controllers. These tests assert the
 * fix via real [MockDmxController] reads — no tick loop involvement.
 */
class FxEnginePublishLayer3Test {

    private val universe = Universe(0, 0)

    private data class Rig(
        val controller: MockDmxController,
        val fixtures: Fixtures,
        val engine: FxEngine,
        val directWriteStore: DirectWriteStore,
    )

    /**
     * Build a minimal test rig: one controller, one [HexFixture] at [firstChannel], wired
     * into an [FxEngine]. Dimmer lives at [firstChannel]; R/G/B at +1/+2/+3.
     */
    private fun newRig(firstChannel: Int = 1): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-a", "Hex A", firstChannel))
        }
        val directWriteStore = DirectWriteStore()
        val engine = FxEngine(
            fixtures = fixtures,
            masterClock = MasterClock(),
            directWriteStore = directWriteStore,
            layerResolver = LayerResolver(Layer3Resolver(), directWriteStore),
        )
        return Rig(controller, fixtures, engine, directWriteStore)
    }

    private fun slider(
        cueId: Int,
        priority: Int = 1,
        targetKey: String = "hex-a",
        propertyName: String = "dimmer",
        value: UByte,
        category: PropertyCategory = PropertyCategory.DIMMER,
    ) = Layer3Resolver.Assignment(
        cueId = cueId,
        priority = priority,
        fadeWeight = 1.0,
        targetKey = targetKey,
        targetIsGroup = false,
        propertyName = propertyName,
        category = category,
        value = Layer3Resolver.PropertyValue.Slider(value),
    )

    @Test
    fun `setCueAssignments writes dimmer value to controller with no effects running`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1], "dimmer channel must reflect Layer 3 publish")
    }

    @Test
    fun `removeCueAssignments releases the channel back to direct-write value`() {
        val rig = newRig(firstChannel = 1)
        // Sticky direct write at Layer 4.
        rig.directWriteStore.put(universe = 0, channel = 1, value = 55u)

        rig.engine.setCueAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])

        rig.engine.removeCueAssignments(10)
        assertEquals(
            55u.toUByte(), rig.controller.currentValues[1],
            "release should fall through to Layer 4 sticky write, not zero"
        )
    }

    @Test
    fun `removeCueAssignments with no layer 4 releases to baseline zero`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        rig.engine.removeCueAssignments(10)
        assertEquals(0u.toUByte(), rig.controller.currentValues[1])
    }

    @Test
    fun `setCueAssignments with empty list releases that cue's contribution`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])

        rig.engine.setCueAssignments(10, emptyList())
        assertEquals(0u.toUByte(), rig.controller.currentValues[1])
    }

    @Test
    fun `clearAllCueAssignments releases every previously-asserted channel`() {
        val rig = newRig(firstChannel = 1)
        rig.directWriteStore.put(universe = 0, channel = 1, value = 30u)

        rig.engine.setCueAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])

        rig.engine.clearAllCueAssignments()
        assertEquals(
            30u.toUByte(), rig.controller.currentValues[1],
            "clearAllCueAssignments should release to Layer 4"
        )
    }

    @Test
    fun `colour assignment writes R G B channels`() {
        val rig = newRig(firstChannel = 1) // R/G/B at channels 2/3/4
        val assignment = Layer3Resolver.Assignment(
            cueId = 10,
            priority = 1,
            fadeWeight = 1.0,
            targetKey = "hex-a",
            targetIsGroup = false,
            propertyName = "rgbColour",
            category = PropertyCategory.COLOUR,
            compositionOverride = CompositionRule.UNSET,
            value = Layer3Resolver.PropertyValue.Colour(
                ExtendedColour(java.awt.Color(128, 64, 200), 0u, 0u, 0u)
            ),
        )
        rig.engine.setCueAssignments(10, listOf(assignment))

        assertEquals(128u.toUByte(), rig.controller.currentValues[2], "red")
        assertEquals(64u.toUByte(), rig.controller.currentValues[3], "green")
        assertEquals(200u.toUByte(), rig.controller.currentValues[4], "blue")
    }

    @Test
    fun `two cues HTP dimmer composition writes max onto channel`() {
        val rig = newRig(firstChannel = 1)
        rig.engine.setCueAssignments(10, listOf(slider(cueId = 10, priority = 1, value = 100u)))
        assertEquals(100u.toUByte(), rig.controller.currentValues[1])

        rig.engine.setCueAssignments(20, listOf(slider(cueId = 20, priority = 2, value = 200u)))
        assertEquals(200u.toUByte(), rig.controller.currentValues[1], "HTP takes max across cues")

        rig.engine.removeCueAssignments(20)
        assertEquals(100u.toUByte(), rig.controller.currentValues[1], "cue 10 still asserts 100")
    }

    @Test
    fun `publish does not touch unrelated channels`() {
        val rig = newRig(firstChannel = 1)
        // Touch an unrelated channel via direct write. Layer 3 publish walks only affected
        // keys, so the unrelated channel must remain untouched.
        rig.controller.setValue(200, 77u, 0L)

        rig.engine.setCueAssignments(10, listOf(slider(cueId = 10, value = 180u)))
        assertEquals(180u.toUByte(), rig.controller.currentValues[1])
        assertEquals(77u.toUByte(), rig.controller.currentValues[200], "unrelated channel untouched")
    }

    @Test
    fun `colour release falls through to Layer 4 when direct writes exist`() {
        val rig = newRig(firstChannel = 1)
        // Layer 4 holds a dim red across the RGB channels.
        rig.directWriteStore.put(0, 2, 40u)
        rig.directWriteStore.put(0, 3, 10u)
        rig.directWriteStore.put(0, 4, 10u)

        val assignment = Layer3Resolver.Assignment(
            cueId = 10, priority = 1, fadeWeight = 1.0,
            targetKey = "hex-a", targetIsGroup = false,
            propertyName = "rgbColour", category = PropertyCategory.COLOUR,
            value = Layer3Resolver.PropertyValue.Colour(
                ExtendedColour(java.awt.Color(255, 255, 255), 0u, 0u, 0u)
            ),
        )
        rig.engine.setCueAssignments(10, listOf(assignment))
        assertEquals(255u.toUByte(), rig.controller.currentValues[2])

        rig.engine.removeCueAssignments(10)
        assertEquals(40u.toUByte(), rig.controller.currentValues[2], "red falls back to Layer 4")
        assertEquals(10u.toUByte(), rig.controller.currentValues[3])
        assertEquals(10u.toUByte(), rig.controller.currentValues[4])
    }
}
