package uk.me.cormack.lighting7.ai

import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** `describe_rig` names what Wave drives and its presets, so a model can find a Zoom Wave (fx-settings plan D4). */
class FxLibraryBriefTest : RouteIntegrationTest() {

    @Test
    fun `the FX library names each FX's attributes and Wave's presets`() {
        val brief = RigBriefing(state).describeRig()
        assertTrue("**Wave** (family=intensity, output=SLIDER, on=dimmer/uv/zoom/iris/frost/focus)" in brief, brief)
        assertTrue("Zoom Wave = on zoom shape=SINE, low=64, high=192" in brief, brief)
        assertTrue("Focus Pull = on focus shape=RAMP_UP, low=64, high=192, curve=SINE_IN_OUT (cycle=2.0, direction=BOUNCE)" in brief, brief)
        assertTrue("store its family as intensity" in brief, brief)
    }

    @Test
    fun `the FX library names Path and its sweeps, and offers no legacy FX`() {
        val brief = RigBriefing(state).describeRig()
        assertTrue("**Path** (family=position, output=POSITION, on=position)" in brief, brief)
        assertTrue("Pan Sweep = on position shape=LINE" in brief, brief)
        assertTrue("Path is every movement" in brief, brief)
        assertTrue("**ColourWave**" !in brief && "**ColourFade**" !in brief, "a legacy FX is never offered: $brief")
    }
}
