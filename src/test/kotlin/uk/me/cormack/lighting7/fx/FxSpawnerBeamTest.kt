package uk.me.cormack.lighting7.fx

import org.junit.After
import org.junit.Test
import uk.me.cormack.lighting7.models.FxTargetsRef
import uk.me.cormack.lighting7.models.LookFxSpec
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A beam effect stored with no property (an effect palette's, a Look's deferred effect) lands on the
 * one its registration declares (effects plan D6) — on a group only where every member has it, and
 * over a selection resolved through a head that has it, whichever item comes first. No built-in is
 * filed under Beam since Wave took Zoom Wave's place, so a script FX declaring `zoom` stands in.
 */
class FxSpawnerBeamTest : RouteIntegrationTest() {

    private val zoomWave = LookFxSpec(
        fxType = ZOOM_PROBE, family = "beam", cycle = 4.0, blendMode = "OVERRIDE", order = "NORMAL",
    )

    private fun seed() {
        val wave = state.show.fxRegistry.getRegistration("Wave")!!
        state.show.fxRegistry.register(
            wave.copy(id = ZOOM_PROBE, aliases = emptySet(), name = ZOOM_PROBE, family = "beam", compatibleAttributes = listOf("zoom")),
        )
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedFixture(state, projectId, "etc-source4-revolution-base-frame", "rev-1", 100)
        LocateTestSupport.seedGroup(state, projectId, "mixed", "hex-1", "rev-1")
    }

    @After
    fun unregisterProbe() {
        state.show.fxRegistry.unregister(ZOOM_PROBE)
    }

    @Test
    fun `a selection whose first item lacks the attribute still drives the head that has it`() {
        seed()
        val selection = FxTargetsRef.of(listOf(TargetRef.Fixture("hex-1"), TargetRef.Fixture("rev-1")))
        val target = FxSpawner.resolveTargetForCue(state, selection, zoomWave)
        assertEquals(SliderTarget(FxTargetRef.SelectionRef(selection as FxTargetsRef.Selection), "zoom"), target)
    }

    @Test
    fun `a group where a member lacks the attribute is skipped, as a dimmer effect on a dimmerless group is`() {
        seed()
        assertNull(FxSpawner.resolveTargetForCue(state, FxTargetsRef.Single(TargetRef.Group("mixed")), zoomWave))
    }

    @Test
    fun `a group whose members have the attribute takes it`() {
        seed()
        LocateTestSupport.seedGroup(state, projectId, "revs", "rev-1")
        val target = FxSpawner.resolveTargetForCue(state, FxTargetsRef.Single(TargetRef.Group("revs")), zoomWave)
        assertEquals(SliderTarget(FxTargetRef.group("revs"), "zoom"), target)
    }

    @Test
    fun `a Wave naming a beam attribute lands on it`() {
        seed()
        LocateTestSupport.seedGroup(state, projectId, "revs", "rev-1")
        val wave = zoomWave.copy(fxType = "Wave", family = "intensity", attribute = "zoom")
        assertEquals(
            SliderTarget(FxTargetRef.group("revs"), "zoom"),
            FxSpawner.resolveTargetForCue(state, FxTargetsRef.Single(TargetRef.Group("revs")), wave),
        )
    }

    @Test
    fun `a migrated Wave naming its beam attribute lands on a mixed group, where its old FX did not (the recorded exception to D3)`() {
        seed()
        val wave = zoomWave.copy(fxType = "Wave", family = "intensity", attribute = "zoom")
        assertEquals(
            SliderTarget(FxTargetRef.group("mixed"), "zoom"),
            FxSpawner.resolveTargetForCue(state, FxTargetsRef.Single(TargetRef.Group("mixed")), wave),
            "the heads with zoom wave; before step 7 named the attribute, the group was skipped (the test above)",
        )
    }

    private companion object {
        const val ZOOM_PROBE = "ZoomProbe"
    }
}
