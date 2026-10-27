package uk.me.cormack.lighting7.scripts

import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A script naming an old FX or parameter still runs it, as today's (fx-settings plan D3). */
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

    @Test
    fun `a script naming a movement runs a Path, and an old parameter name its new one`() {
        val script = object : FxApplicationScript(state.show, state.show.fxEngine, "probe", 0) {}
        val circle = script.effect("Circle", "panRadius" to "40")
        assertEquals("Path", circle.registrationId)
        assertEquals(
            mapOf("shape" to "CIRCLE", "panCentre" to "128", "tiltCentre" to "128", "panSize" to "40", "tiltSize" to "64"),
            circle.parameters,
        )
        val flicker = script.effect("Flicker", "min" to "40")
        assertEquals("Flicker", flicker.registrationId)
        assertEquals(mapOf("low" to "40"), flicker.parameters)
        assertEquals(mapOf("colours" to "red,blue", "between" to "CUT"), script.effect("ColourCycle", "colours" to "red,blue", "fadeRatio" to "0").parameters)
    }
}
