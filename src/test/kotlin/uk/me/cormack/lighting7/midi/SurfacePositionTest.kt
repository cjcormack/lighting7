package uk.me.cormack.lighting7.midi

import org.junit.Test
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.FxSuppression
import uk.me.cormack.lighting7.fx.KeyStackLayer
import uk.me.cormack.lighting7.testsupport.FxTestSupport
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A surface's pan or tilt writes the head's `position` (surface-setup plan D5), the other axis kept,
 * as `programmer.setPosition` writes it: the key a Circle paints, so the nudge holds a running Circle
 * back where a `pan` entry would leave the stage circling.
 */
class SurfacePositionTest : RouteIntegrationTest() {

    @Test
    fun `a pan move writes position with tilt kept, which holds back a running Circle`() {
        LocateTestSupport.seedFixture(state, projectId, "martin-mac-250-mode-4", "mac-1", 1)
        val actions = DefaultSurfaceActions(state)
        val circle = FxTestSupport.start(state, "Path", "mac-1", "position")

        actions.writeLevel(Subject.Fixture("mac-1"), "tilt", 100u)
        actions.writeLevel(Subject.Fixture("mac-1"), "pan", 64u)

        val store = state.show.programmerStore
        val keys = store.activeAttributesByFixture().getValue("mac-1")
        assertTrue("position" in keys, "$keys")
        assertFalse("pan" in keys || "tilt" in keys, "the axes are not keys of their own: $keys")
        assertEquals(CueAssignmentResolver.AttributeValue.Position(pan = 129u, tilt = 201u), store.get("mac-1", "position")!!.value.resolved)

        val engine = state.show.fxEngine
        val effect = engine.provenance.keyStack("mac-1", "position")!!.layers.single { it.kind == KeyStackLayer.Kind.FX }
        assertEquals(circle.id, effect.effect?.id)
        assertTrue(effect.heldBack, "the Circle is running and held back")
        assertEquals(KeyStackLayer.HeldBackReason.PROGRAMMER, effect.heldBackReason)
        assertTrue(FxSuppression.isSuppressed(engine.programmerSuppression(), "mac-1", "position", circle, engine.cueLayer::isLayerStomped))
    }

    @Test
    fun `in Blind a pan move keeps the staged tilt, not the stage's`() {
        LocateTestSupport.seedFixture(state, projectId, "martin-mac-250-mode-4", "mac-1", 1)
        val actions = DefaultSurfaceActions(state)
        state.show.fxEngine.programmer.setBlind(true)

        actions.writeLevel(Subject.Fixture("mac-1"), "tilt", 100u)
        actions.writeLevel(Subject.Fixture("mac-1"), "pan", 64u)

        val staged = state.show.programmerStore.get("mac-1", "position")!!.value.resolved
        assertEquals(CueAssignmentResolver.AttributeValue.Position(pan = 129u, tilt = 201u), staged)
    }
}
