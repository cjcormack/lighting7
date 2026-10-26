package uk.me.cormack.lighting7.scripts

import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A script naming a level wave Wave replaced still runs it, as Wave (fx-settings plan D3). */
class LegacyEffectNameTest : RouteIntegrationTest() {

    @Test
    fun `a script naming SineWave with min 40 runs a Wave on Sine at its old levels`() {
        val script = object : FxApplicationScript(state.show, state.show.fxEngine, "probe", 0) {}
        val fx = script.effect("SineWave", "min" to "40")
        assertEquals("Wave", fx.registrationId)
        assertEquals(mapOf("shape" to "SINE", "low" to "40", "high" to "255"), fx.parameters)

        val focus = script.effect("FocusPull")
        assertEquals("Wave", focus.registrationId)
        assertEquals(mapOf("shape" to "RAMP_UP", "low" to "64", "high" to "192", "curve" to "SINE_IN_OUT"), focus.parameters)
    }
}
