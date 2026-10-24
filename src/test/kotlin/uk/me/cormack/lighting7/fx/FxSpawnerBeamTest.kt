package uk.me.cormack.lighting7.fx

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
 * over a selection resolved through a head that has it, whichever item comes first.
 */
class FxSpawnerBeamTest : RouteIntegrationTest() {

    private val zoomWave = LookFxSpec(
        fxType = "ZoomWave", family = "beam", cycle = 4.0, blendMode = "OVERRIDE", order = "NORMAL",
    )

    private fun seed() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedFixture(state, projectId, "etc-source4-revolution-base-frame", "rev-1", 100)
        LocateTestSupport.seedGroup(state, projectId, "mixed", "hex-1", "rev-1")
    }

    @Test
    fun `a selection whose first item lacks the property still drives the head that has it`() {
        seed()
        val selection = FxTargetsRef.of(listOf(TargetRef.Fixture("hex-1"), TargetRef.Fixture("rev-1")))
        val target = FxSpawner.resolveTargetForCue(state, selection, zoomWave)
        assertEquals(SliderTarget(FxTargetRef.SelectionRef(selection as FxTargetsRef.Selection), "zoom"), target)
    }

    @Test
    fun `a group where a member lacks the property is skipped, as a dimmer effect on a dimmerless group is`() {
        seed()
        assertNull(FxSpawner.resolveTargetForCue(state, FxTargetsRef.Single(TargetRef.Group("mixed")), zoomWave))
    }

    @Test
    fun `a group whose members have the property takes it`() {
        seed()
        LocateTestSupport.seedGroup(state, projectId, "revs", "rev-1")
        val target = FxSpawner.resolveTargetForCue(state, FxTargetsRef.Single(TargetRef.Group("revs")), zoomWave)
        assertEquals(SliderTarget(FxTargetRef.group("revs"), "zoom"), target)
    }
}
